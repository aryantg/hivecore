package com.hivecore.cloud

import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.Lineage
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.MemoHit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 15.1-15.3 over MockWebServer (phase 8 gate scenarios). */
class EscalationGateTest {
    private val server = MockWebServer()
    private val config = CloudTestSupport.config()
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"vendor":{"type":"string"},"total":{"type":"number"}},"required":["vendor","total"],"additionalProperties":false}""") as JsonObject
    private val ledger = RecordingLedgerSink()
    private val spend = MemorySpendStore()
    private val consentStore = MemoryConsentStore()
    private val asked = ArrayList<ConsentRequest>()
    private var decision = ConsentDecision.ALLOW_ONCE
    private val memo = object : ChunkMemo {
        val map = HashMap<String, Pair<String, JsonElement>>()
        override fun get(chunkId: String): MemoHit? = map[chunkId]?.let { MemoHit(chunkId, it.first, it.second, 0) }
        override fun put(chunkId: String, hash: String, json: JsonElement) { map[chunkId] = hash to json }
    }

    private fun gate(selfId: String = "A", caps: RoleCaps = RoleCaps(25.0, 500.0), triggers: TriggerSettings = TriggerSettings()): EscalationGate {
        val client = OpenRouterClient.create(CloudTestSupport.keys(), config, server.url("/api/v1").toString(), attemptTimeoutMs = 2_000)
        return EscalationGate(selfId, client, config, SpendCaps(spend, { caps }, { 1_791_590_400_000L }), { asked += it; decision }, consentStore, ledger, memo, { triggers })
    }

    /** FIELDS over OCR text with the catalog-ish param `expect` that must never leave. */
    private val fields = TaskSpec("fields", Op.FIELDS, buildJsonObject {
        put("cap", "llm.json"); put("q", "vendor and total"); put("schema", schema); put("maxTokens", 200)
        put("expect", buildJsonObject { put("table", "invoices"); put("measure", "total") })
    }, listOf("ocr"))
    private val inputs = mapOf<String, JsonElement>("ocr" to Json.parseToJsonElement("""{"items":[{"item":"inv1","text":"ACME Ltd  Total 12.50"}]}"""))

    private fun request(step: TaskSpec = fields, privacy: String = Lineage.RELEASED, commander: String = "A",
                        trigger: EscalationTrigger = EscalationTrigger.VerificationFailed("3-way replica disagreement", 3), chunkId: String? = null) =
        EscalationRequest("job1", "plan1", commander, step, inputs, privacy, trigger, 3, chunkId)

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun threeWayDisagreementOnAPublicStepEscalatesAndIsLabelled() = runTest {
        server.enqueue(CloudTestSupport.completion("""{"vendor":"ACME Ltd","total":12.5}"""))
        val out = gate().tryEscalate(request(chunkId = "c42")) as GateOutcome.Ran
        assertEquals("anthropic/claude-sonnet-5", out.slug)
        assertEquals("cloud:anthropic/claude-sonnet-5", out.source)
        assertEquals(Canon.hash(out.output), out.outputHash)
        assertEquals(12.5, ((out.output as JsonObject)["total"] as JsonPrimitive).content.toDouble(), 0.0)
        // the ledger task entry
        val e = ledger.tasks.single()
        assertEquals("cloud:anthropic/claude-sonnet-5", e.source)
        assertEquals("verification_failed", e.trigger)
        assertEquals(3, e.attemptsReplaced)
        assertEquals(120, e.tokensIn); assertEquals(20, e.tokensOut)
        assertEquals(0.00044 * ModelsConfig.INR_PER_USD, e.costInr, 1e-9)
        assertTrue(e.bytesSent > 0)
        assertEquals("c42", e.chunkId)
        assertEquals("cloud:anthropic/claude-sonnet-5", ledger.lastJson()["source"])
        assertEquals(listOf("ran"), ledger.decisions.map { it.outcome })
        // memo under the cloud key only
        assertEquals(setOf("cloud:anthropic/claude-sonnet-5:c42"), memo.map.keys)
        // spend booked
        assertEquals(e.costInr, spend.spent("job:job1"), 1e-9)
        // consent was asked once and remembered for the job
        assertEquals(1, asked.size)
        assertEquals("Send", asked[0].sentence.substring(0, 4))
        assertTrue(asked[0].sentence.contains("Nothing private is included"))
        assertTrue(consentStore.allowed("job1", "2026-10"))
        // the request carried the prompt, the inputs and the schema, and nothing from the catalog
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("ACME Ltd  Total 12.50"))
        assertTrue(body.contains("vendor and total"))
        assertTrue(body.contains("\\\"required\\\":[\\\"vendor\\\",\\\"total\\\"]"))
        assertFalse("catalog values never leave", body.contains("invoices"))
        assertFalse(body.contains("expect"))
        assertTrue(body.contains("\"temperature\":0"))
    }

    @Test fun privateLineageIsRefusedBeforeAnyHttpCall() = runTest {
        val out = gate().tryEscalate(request(privacy = Lineage.PRIVATE))
        assertTrue(out is GateOutcome.Refused)
        assertTrue((out as GateOutcome.Refused).reason.contains("private"))
        assertEquals(0, server.requestCount)
        assertTrue(asked.isEmpty())
        assertEquals("refused", ledger.decisions.single().outcome)
        assertTrue(ledger.tasks.isEmpty())
    }

    @Test fun ownerOpIsRefused() = runTest {
        val agg = TaskSpec("a", Op.AGG, buildJsonObject { put("q", "revenue by region"); put("schema", schema) }, owner = "B")
        val out = gate().tryEscalate(request(step = agg, privacy = Lineage.PUBLIC)) as GateOutcome.Refused
        assertTrue(out.reason, out.reason.contains("owner data"))
        assertEquals(0, server.requestCount)
    }

    @Test fun onlyTheCommanderMayEscalate() = runTest {
        val out = gate(selfId = "A").tryEscalate(request(commander = "B")) as GateOutcome.Refused
        assertTrue(out.reason.contains("only the phone that made the request"))
        assertEquals(0, server.requestCount)
    }

    @Test fun capStopsSpend() = runTest {
        spend.add("job:job1", 24.9)
        val out = gate(caps = RoleCaps(25.0, 500.0)).tryEscalate(request()) as GateOutcome.Refused
        assertTrue(out.reason, out.reason.contains("job's cloud cap"))
        assertEquals(0, server.requestCount)
        assertTrue(asked.isEmpty())
        // the month cap too
        spend.map.clear(); spend.add("month:2026-10", 499.99)
        val m = gate(caps = RoleCaps(25.0, 500.0)).tryEscalate(request()) as GateOutcome.Refused
        assertTrue(m.reason.contains("month's cloud cap"))
    }

    @Test fun consentDeclinedIsRefusedAndAllowMonthIsRemembered() = runTest {
        decision = ConsentDecision.KEEP_LOCAL
        val out = gate().tryEscalate(request()) as GateOutcome.Refused
        assertTrue(out.reason.contains("keep"))
        assertEquals(0, server.requestCount)
        decision = ConsentDecision.ALLOW_MONTH
        server.enqueue(CloudTestSupport.completion("""{"vendor":"ACME Ltd","total":12.5}"""))
        server.enqueue(CloudTestSupport.completion("""{"vendor":"ACME Ltd","total":12.5}"""))
        assertTrue(gate().tryEscalate(request()) is GateOutcome.Ran)
        assertTrue(consentStore.allowed("another job", "2026-10"))
        assertTrue(gate().tryEscalate(request().copy(jobId = "job2")) is GateOutcome.Ran)
        assertEquals(2, asked.size)   // declined once, allowed once; the third call asked nobody
    }

    @Test fun serverErrorRetriesThenFallsBackToTheNextModel() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(CloudTestSupport.completion("""{"vendor":"ACME Ltd","total":12.5}""", slug = "openai/gpt-5.4"))
        val out = gate().tryEscalate(request()) as GateOutcome.Ran
        assertEquals("openai/gpt-5.4", out.slug)
        assertEquals(3, server.requestCount)
        assertEquals("cloud:openai/gpt-5.4", ledger.tasks.single().source)
    }

    @Test fun noCompliantEndpointIsAFailureNotARetry() = runTest {
        server.enqueue(CloudTestSupport.noEndpoints)
        val out = gate().tryEscalate(request()) as GateOutcome.Failed
        assertTrue(out.reason, out.reason.startsWith("no compliant endpoint"))
        assertEquals(1, server.requestCount)
        assertTrue(ledger.tasks.isEmpty())
        assertEquals("failed", ledger.decisions.single().outcome)
        assertTrue(memo.map.isEmpty())
    }

    @Test fun triggersOffByDefaultAreRefused() = runTest {
        val out = gate().tryEscalate(request(trigger = EscalationTrigger.TimeBudget(10_000, 3_000))) as GateOutcome.Refused
        assertTrue(out.reason.contains("time budget"))
        assertEquals(0, server.requestCount)
        val off = gate(triggers = TriggerSettings(verificationFailed = false))
        assertTrue(off.tryEscalate(request()) is GateOutcome.Refused)
        server.enqueue(CloudTestSupport.completion("""{"vendor":"ACME Ltd","total":12.5}"""))
        val verdict = gate().tryEscalate(request(trigger = EscalationTrigger.UserVerdict))
        assertTrue(verdict.toString(), verdict is GateOutcome.Ran)
    }

    @Test fun stepWithoutSchemaIsRefused() = runTest {
        val bare = TaskSpec("s", Op.SUMMARIZE, buildJsonObject { put("q", "summarise") }, listOf("ocr"))
        val out = gate().tryEscalate(request(step = bare)) as GateOutcome.Refused
        assertTrue(out.reason.contains("no output schema"))
        assertNull(server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test fun multimodalMediaGoesToTheMultimodalRole() = runTest {
        server.enqueue(CloudTestSupport.completion("""{"vendor":"ACME Ltd","total":12.5}""", slug = "google/gemini-3.1-pro"))
        val req = request(privacy = Lineage.RELEASED, trigger = EscalationTrigger.CapabilityGap("vlm")).copy(media = listOf(byteArrayOf(9, 9)))
        val out = gate().tryEscalate(req) as GateOutcome.Ran
        assertEquals("google/gemini-3.1-pro", out.slug)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"models\":[\"google/gemini-3.1-pro\",\"google/gemini-3.8-flash\"]"))
        assertTrue(body.contains("image_url"))
    }
}
