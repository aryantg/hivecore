package com.hivecore.cloud

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 15.6 over MockWebServer: no real network, a placeholder key. */
class OpenRouterClientTest {
    private val server = MockWebServer()
    private val config = CloudTestSupport.config()
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"vendor":{"type":"string"},"total":{"type":"number"}},"required":["vendor","total"],"additionalProperties":false}""") as JsonObject
    private val messages = listOf(Msg("system", "Reply with JSON."), Msg("user", "Vendor and total of: ACME 12.5"))
    private var now = 1_000_000L

    private fun client(attemptMs: Long = 2_000, budgetMs: Long = 90_000) =
        OpenRouterClient.create(CloudTestSupport.keys(), config, server.url("/api/v1").toString(), clock = { now }, attemptTimeoutMs = attemptMs, totalBudgetMs = budgetMs)

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun requestIsExactlyTheSpecConfiguration() = runTest {
        server.enqueue(CloudTestSupport.completion("""{"vendor":"Acme","total":12.5}"""))
        val r = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 300).getOrThrow()
        assertEquals("Acme", (r.output as JsonObject)["vendor"]!!.jsonPrimitive.content)
        assertEquals("anthropic/claude-sonnet-5", r.slug)
        assertEquals(120, r.tokensIn); assertEquals(20, r.tokensOut); assertEquals(0.00044, r.costUsd!!, 1e-9); assertEquals(1, r.attempts)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/chat/completions", req.path)
        assertEquals("Bearer sk-or-test-placeholder", req.getHeader("Authorization"))
        assertEquals(OpenRouterClient.DEFAULT_REFERER, req.getHeader("HTTP-Referer"))
        assertEquals(OpenRouterClient.DEFAULT_TITLE, req.getHeader("X-OpenRouter-Title"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("models", "temperature", "max_tokens", "response_format", "provider", "messages"), body.keys)
        assertEquals(listOf("anthropic/claude-sonnet-5", "openai/gpt-5.4"), body["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("0", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("300", body["max_tokens"]!!.jsonPrimitive.content)
        val rf = body["response_format"]!!.jsonObject
        assertEquals("json_schema", rf["type"]!!.jsonPrimitive.content)
        val js = rf["json_schema"]!!.jsonObject
        assertEquals("step_output", js["name"]!!.jsonPrimitive.content)
        assertEquals("true", js["strict"]!!.jsonPrimitive.content)
        assertEquals(schema, js["schema"])
        val provider = body["provider"]!!.jsonObject
        assertEquals("true", provider["zdr"]!!.jsonPrimitive.content)
        assertEquals("true", provider["require_parameters"]!!.jsonPrimitive.content)
        assertEquals("deny", provider["data_collection"]!!.jsonPrimitive.content)
        assertEquals("latency", provider["sort"]!!.jsonPrimitive.content)
        assertEquals(4, provider.size)
        val msgs = body["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Vendor and total of: ACME 12.5", msgs[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(req.bodySize, r.bytesSent)
    }

    @Test fun maxTokensIsCappedByTheRole() = runTest {
        server.enqueue(CloudTestSupport.completion("""{"vendor":"Acme","total":1}"""))
        client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 99_999).getOrThrow()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("1500", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test fun serverErrorIsRetriedOnceThenTheNextModelIsTried() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream"))
        server.enqueue(MockResponse().setResponseCode(502).setBody("upstream"))
        server.enqueue(CloudTestSupport.completion("""{"vendor":"Acme","total":1}""", slug = "openai/gpt-5.4"))
        val r = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).getOrThrow()
        assertEquals("openai/gpt-5.4", r.slug)
        assertEquals(3, r.attempts)
        val chains = (1..3).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["models"]!!.jsonArray.map { m -> m.jsonPrimitive.content } }
        assertEquals(listOf("anthropic/claude-sonnet-5", "openai/gpt-5.4"), chains[0])
        assertEquals(chains[0], chains[1])                       // the one retry on 5xx
        assertEquals(listOf("openai/gpt-5.4"), chains[2])        // then the chain advances
    }

    @Test fun everyModelFailingIsAnHttpFailure() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        val e = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.HTTP, e.kind)
        assertEquals(4, e.attempts)
        assertEquals(4, server.requestCount)
    }

    @Test fun noCompliantEndpointFailsWithoutAnyFallback() = runTest {
        server.enqueue(CloudTestSupport.noEndpoints)
        val e = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.NO_COMPLIANT_ENDPOINT, e.kind)
        assertTrue(e.message!!, e.message!!.startsWith("no compliant endpoint"))
        assertEquals("one request, no non-ZDR fallback", 1, server.requestCount)
    }

    @Test fun badKeyIsAnAuthFailureAndMissingKeyNeverCallsOut() = runTest {
        server.enqueue(CloudTestSupport.error(401, "User not found."))
        val e = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.AUTH, e.kind)
        val noKey = OpenRouterClient.create(MemoryCloudKeyStore(null), config, server.url("/api/v1").toString())
        val e2 = noKey.complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.AUTH, e2.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun replyMustSatisfyTheSchemaAndIsNeverRepaired() = runTest {
        server.enqueue(CloudTestSupport.completion("""{"vendor":"Acme"}"""))   // missing total
        val e = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.INVALID_REPLY, e.kind)
        server.enqueue(CloudTestSupport.completion("""Sure! {"vendor":"Acme","total":1}"""))   // prose around the JSON
        val e2 = client().complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.INVALID_REPLY, e2.kind)
        assertEquals(2, server.requestCount)
    }

    @Test fun totalBudgetStopsTheChain() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val c = OpenRouterClient.create(CloudTestSupport.keys(), config, server.url("/api/v1").toString(), clock = { now.also { now += 60_000 } },
            attemptTimeoutMs = 2_000, totalBudgetMs = 90_000)
        val e = c.complete(CloudRole.ESCALATE_TEXT, messages, schema, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.BUDGET, e.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun multimodalMessagesCarryImagesAsDataUrls() = runTest {
        server.enqueue(CloudTestSupport.completion("""{"vendor":"Acme","total":1}""", slug = "google/gemini-3.1-pro"))
        val img = byteArrayOf(1, 2, 3)
        client().complete(CloudRole.ESCALATE_MULTIMODAL, listOf(Msg("user", "what is this", listOf(img))), schema, 100).getOrThrow()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(listOf("google/gemini-3.1-pro", "google/gemini-3.8-flash"), body["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun invalidSchemaNeverLeavesThePhone() = runTest {
        val bad = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"${'$'}ref":"#/x"}}}""") as JsonObject
        val e = client().complete(CloudRole.ESCALATE_TEXT, messages, bad, 100).exceptionOrNull() as CloudException
        assertEquals(CloudException.Kind.INVALID_REPLY, e.kind)
        assertEquals(0, server.requestCount)
        assertNull(server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }
}
