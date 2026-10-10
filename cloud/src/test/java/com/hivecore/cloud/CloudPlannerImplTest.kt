package com.hivecore.cloud

import com.hivecore.core.Catalog
import com.hivecore.core.CatalogColumn
import com.hivecore.core.CatalogTable
import com.hivecore.core.ColType
import com.hivecore.core.Op
import com.hivecore.core.OpRegistry
import com.hivecore.planner.PlanCompilerImpl
import com.hivecore.planner.T2Params
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 7.2-7.4 over MockWebServer with canned graphs. */
class CloudPlannerImplTest {
    private val server = MockWebServer()
    private val config = CloudTestSupport.config()
    private val catalogs = listOf(Catalog("node-acc", "Accounts", listOf(CatalogTable("invoices", listOf("bills"), listOf(
        CatalogColumn("vendor", ColType.TEXT, groupable = true), CatalogColumn("total", ColType.INTEGER), CatalogColumn("salary", ColType.INTEGER, confidential = true)))), listOf("policy.pdf")))
    private val caps = setOf("ocr", "llm.json", "llm.text")
    private val ops = OpRegistry.nodeOps + setOf(Op.AGG, Op.DOC)
    private var sharing = false
    private val spend = MemorySpendStore()

    private fun planner(): CloudPlannerImpl {
        val client = OpenRouterClient.create(CloudTestSupport.keys(), config, server.url("/api/v1").toString(), attemptTimeoutMs = 2_000)
        return CloudPlannerImpl(client, config, PlanCompilerImpl(), { sharing }, "self", { "m1" }, SpendCaps(spend, config, { 1_791_590_400_000L }))
    }

    /** The strict-form graph of the spec 7.3 example (schema and params as JSON strings, privacy as pairs). */
    private fun graph(fieldsExtra: String = "", confidence: String = "0.86", ocrOp: String = "OCR") = """
        {"goal":"extract vendor and total from invoice photos",
         "nodes":[
           {"id":"ocr","op":"$ocrOp","cap":"ocr","input":"items","axis":"shard","replicas":null,"schema":null,"needs":[],"owner":null,"params":null},
           {"id":"fields","op":"FIELDS","cap":"llm.json","input":null,"axis":"shard","replicas":2,
            "schema":"{\"type\":\"object\",\"properties\":{\"vendor\":{\"type\":\"string\"},\"total\":{\"type\":\"number\"}},\"required\":[\"vendor\",\"total\"]}",
            "needs":["ocr"],"owner":null,"params":${if (fieldsExtra.isEmpty()) "null" else "\"$fieldsExtra\""}},
           {"id":"out","op":"RENDER","cap":null,"input":null,"axis":null,"replicas":null,"schema":null,"needs":["fields"],"owner":null,"params":null}],
         "edges":[{"from":"fields","to":"out","when":"always"}],
         "privacy":[{"input":"items","privacy":"released"}],
         "confidence":$confidence,"clarification":null}
    """.trimIndent().replace("\n", "")

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun cannedGraphBecomesACompiledT2Plan() = runTest {
        server.enqueue(CloudTestSupport.completion(graph(), slug = "openai/gpt-5.6-luna", cost = 0.0002))
        val pq = planner().plan("extract vendor and total from these invoice photos", catalogs, caps, ops).getOrThrow()
        assertEquals(T2Params.PATTERN_ID, pq.dag.patternId)
        assertEquals(listOf("ocr", "fields", "out"), pq.dag.tasks.map { it.id })
        assertEquals(0.86, pq.confidence, 1e-9)
        assertTrue(pq.clarification.isEmpty())
        assertEquals("released", pq.dag.tasks[0].params[T2Params.PRIVACY]!!.jsonPrimitive.content)
        // the request: planner chain, strict graph schema, question + capabilities + ops, no catalog (sharing off)
        val raw = server.takeRequest().body.readUtf8()
        val body = Json.parseToJsonElement(raw).jsonObject
        assertEquals(listOf("openai/gpt-5.6-luna", "google/gemini-3.5-flash-lite", "anthropic/claude-haiku-4.5"), body["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(CloudPlannerImpl.GRAPH_SCHEMA, body["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["schema"])
        val user = Json.parseToJsonElement(body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals("extract vendor and total from these invoice photos", user["question"]!!.jsonPrimitive.content)
        assertEquals(listOf("llm.json", "llm.text", "ocr"), user["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(user["ops"]!!.jsonArray.any { it.jsonObject["op"]!!.jsonPrimitive.content == "LLM_MAP" })
        assertFalse("catalog is not sent unless schema sharing is on", user.containsKey("catalog"))
        assertFalse(raw.contains("invoices"))
        // the planner role's spend was booked
        assertEquals(0.0002 * ModelsConfig.INR_PER_USD, spend.map.values.first(), 1e-9)
    }

    @Test fun schemaSharingSendsNamesAndFlagsOnly() = runTest {
        sharing = true
        server.enqueue(CloudTestSupport.completion(graph(), slug = "openai/gpt-5.6-luna"))
        planner().plan("vendors by total", catalogs, caps, ops).getOrThrow()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val user = Json.parseToJsonElement(body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        val cat = user["catalog"]!!.jsonArray.single().jsonObject
        assertEquals("node-acc", cat["node"]!!.jsonPrimitive.content)
        val table = cat["tables"]!!.jsonArray.single().jsonObject
        assertEquals("invoices", table["name"]!!.jsonPrimitive.content)
        val salary = table["columns"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "salary" }
        assertEquals("true", salary["confidential"]!!.jsonPrimitive.content)
        assertEquals(setOf("name", "type", "groupable", "confidential", "identifier"), salary.keys)
        assertEquals(setOf("name", "columns"), table.keys)   // no row counts, no values
        assertEquals(listOf("policy.pdf"), cat["docs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun rejectedGraphGetsOneRepairRoundWithTheErrorList() = runTest {
        server.enqueue(CloudTestSupport.completion(graph(fieldsExtra = """{\"exec\":\"cloud\"}"""), slug = "openai/gpt-5.6-luna"))
        server.enqueue(CloudTestSupport.completion(graph(), slug = "openai/gpt-5.6-luna"))
        val pq = planner().plan("extract vendor and total", catalogs, caps, ops).getOrThrow()
        assertEquals(3, pq.dag.tasks.size)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val repair = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(4, repair.size)
        assertEquals("assistant", repair[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(repair[2].jsonObject["content"]!!.jsonPrimitive.content.contains("exec"))
        val ask = repair[3].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(ask, ask.contains("The compiler rejected that graph"))
        assertTrue(ask, ask.contains("declares exec"))
    }

    @Test fun secondFailureIsAT2PlanFailedForTheFacade() = runTest {
        server.enqueue(CloudTestSupport.completion(graph(ocrOp = "SCAN"), slug = "openai/gpt-5.6-luna"))
        server.enqueue(CloudTestSupport.completion(graph(ocrOp = "SCAN"), slug = "openai/gpt-5.6-luna"))
        val e = planner().plan("extract vendor and total", catalogs, caps, ops).exceptionOrNull()
        assertTrue(e is T2PlanFailed)
        assertTrue(e!!.message!!.contains("rejected twice"))
        assertTrue((e as T2PlanFailed).errors.any { it.contains("unknown op 'SCAN'") })
        assertEquals(2, server.requestCount)
    }

    @Test fun cloudFailureIsAFailure() = runTest {
        server.enqueue(CloudTestSupport.noEndpoints)
        val e = planner().plan("extract vendor and total", catalogs, caps, ops).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.NO_COMPLIANT_ENDPOINT, e.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun plannerCapStopsSpendBeforeTheCall() = runTest {
        spend.add("month:2026-10", config.role(CloudRole.PLANNER).monthCapInr)
        val e = planner().plan("extract vendor and total", catalogs, caps, ops).exceptionOrNull() as T2PlanFailed
        assertTrue(e.message!!.contains("month's cloud cap"))
        assertEquals(0, server.requestCount)
    }
}
