package com.hivecore.node.textai

import com.hivecore.core.Canon
import com.hivecore.core.LlmBackend
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 7.5 with [FakeLlmEngine]. */
class LlmMapExecutorTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"label":{"type":"string","enum":["positive","negative"]},"score":{"type":"number","minimum":0,"maximum":1}},"required":["label","score"]}""") as JsonObject
    private val texts = Json.parseToJsonElement("""{"texts":[{"id":"r1","text":"Loved the service, will return."},{"id":"r2","text":"Cold food and a long wait."}]}""")

    private fun task(template: String = "Classify this review: {item}", schema: JsonObject? = this.schema, maxTokens: Int? = 64) =
        TaskSpec("map", Op.LLM_MAP, buildJsonObject {
            put("template", template); if (schema != null) put("schema", schema); if (maxTokens != null) put("maxTokens", maxTokens)
        })

    @Test fun mapsEveryItemInOrderWithAGrammarOnLlamaCpp() = runTest {
        val llm = FakeLlmEngine(LlmBackend.CPU).apply {
            replies["loved"] = """{"label":"positive","score":0.9}"""
            replies["cold"] = """{"label":"negative","score":0.8}"""
        }
        val out = LlmMapExecutor(llm).execute(task(), listOf(texts), emptyList()).getOrThrow().jsonObject
        val items = out["items"]!!.jsonArray
        assertEquals(listOf("r1", "r2"), items.map { it.jsonObject["item"]!!.jsonPrimitive.content })
        assertEquals("positive", items[0].jsonObject["output"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("negative", items[1].jsonObject["output"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(2, llm.calls.size)
        val (msgs, params) = llm.calls[0]
        assertEquals(64, params.maxTokens)
        assertTrue(params.stopOnBalancedJson)
        assertNotNull("a GBNF grammar constrains llama.cpp", params.grammar)
        assertTrue(params.grammar!!.contains("positive") && params.grammar!!.contains("negative"))
        assertTrue(msgs[0].content.contains(Canon.canonical(schema)))
        assertEquals("Classify this review: Loved the service, will return.", msgs[1].content)
        // the output is canonical-hashable and stable
        assertEquals(Canon.hash(out), Canon.hash(LlmMapExecutor(llm).execute(task(), listOf(texts), emptyList()).getOrThrow()))
    }

    @Test fun noGrammarOnTheNpuOrForRicherSchemas() = runTest {
        val llm = FakeLlmEngine(LlmBackend.NPU).apply { fallback = """{"label":"positive","score":0.5}""" }
        LlmMapExecutor(llm).execute(task(), listOf(texts), emptyList()).getOrThrow()
        assertNull(llm.calls[0].second.grammar)
        val nested = Json.parseToJsonElement("""{"type":"object","properties":{"a":{"type":"object","properties":{"b":{"type":"string"}}}}}""") as JsonObject
        assertNull(LlmMapExecutor.grammarFor(nested, LlmBackend.CPU))
        assertNull(LlmMapExecutor.grammarFor(schema, null))
        assertNotNull(LlmMapExecutor.grammarFor(schema, LlmBackend.GPU))
    }

    @Test fun repliesOutsideTheSchemaAreRejectedNeverRepaired() = runTest {
        val bad = FakeLlmEngine(LlmBackend.NPU).apply { fallback = """{"label":"meh","score":0.5}""" }
        val e = LlmMapExecutor(bad).execute(task(), listOf(texts), emptyList()).exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.startsWith("rejected: item r1:"))
        val prose = FakeLlmEngine(LlmBackend.NPU).apply { fallback = """Sure: {"label":"positive","score":0.5}""" }
        assertTrue(LlmMapExecutor(prose).execute(task(), listOf(texts), emptyList()).exceptionOrNull()!!.message!!.contains("not exactly one JSON value"))
        val two = FakeLlmEngine(LlmBackend.NPU).apply { fallback = """{"label":"positive","score":0.5} {"label":"positive","score":0.5}""" }
        assertTrue(LlmMapExecutor(two).execute(task(), listOf(texts), emptyList()).isFailure)
    }

    @Test fun paramsAreChecked() = runTest {
        val llm = FakeLlmEngine(LlmBackend.CPU).apply { fallback = """{"label":"positive","score":0.5}""" }
        assertTrue(LlmMapExecutor(llm).execute(task(template = " "), listOf(texts), emptyList()).exceptionOrNull()!!.message!!.contains("needs a template"))
        assertTrue(LlmMapExecutor(llm).execute(task(schema = null), listOf(texts), emptyList()).exceptionOrNull()!!.message!!.contains("needs a schema"))
        val ref = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"${'$'}ref":"#/x"}}}""") as JsonObject
        assertTrue(LlmMapExecutor(llm).execute(task(schema = ref), listOf(texts), emptyList()).exceptionOrNull()!!.message!!.contains("references are not allowed"))
        assertTrue(LlmMapExecutor(llm).execute(task(), emptyList(), emptyList()).exceptionOrNull()!!.message!!.contains("no items"))
        assertTrue(llm.calls.isEmpty())
        // the token cap and the default
        LlmMapExecutor(llm).execute(task(maxTokens = 99_999), listOf(texts), emptyList()).getOrThrow()
        assertEquals(LlmMapExecutor.MAX_TOKENS_CAP, llm.calls.last().second.maxTokens)
        LlmMapExecutor(llm).execute(task(maxTokens = null), listOf(texts), emptyList()).getOrThrow()
        assertEquals(LlmMapExecutor.DEFAULT_MAX_TOKENS, llm.calls.last().second.maxTokens)
    }

    @Test fun templateWithoutPlaceholderAppendsTheItemAndPlainStringInputsWork() = runTest {
        val llm = FakeLlmEngine(LlmBackend.CPU).apply { fallback = """{"label":"positive","score":1}""" }
        val out = LlmMapExecutor(llm).execute(task(template = "Classify the review."), listOf(Json.parseToJsonElement("\"Great!\"")), emptyList()).getOrThrow().jsonObject
        assertEquals(1, (out["items"] as JsonArray).size)
        assertEquals("Classify the review.\n\nItem:\nGreat!", llm.calls.single().first[1].content)
        assertEquals("in0", out["items"]!!.jsonArray[0].jsonObject["item"]!!.jsonPrimitive.content)
    }
}
