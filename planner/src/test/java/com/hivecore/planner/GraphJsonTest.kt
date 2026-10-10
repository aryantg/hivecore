package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Op
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphJsonTest {
    /** The spec 7.3 example, verbatim. */
    private val spec = """
    {
      "goal": "extract vendor and total from invoice photos",
      "nodes": [
        {"id": "ocr",    "op": "OCR",    "cap": "ocr",      "input": "items", "axis": "shard"},
        {"id": "fields", "op": "FIELDS", "cap": "llm.json", "needs": ["ocr"], "axis": "shard", "replicas": 2,
         "schema": {"type": "object", "properties": {"vendor": {"type": "string"}, "total": {"type": "number"}},
                    "required": ["vendor", "total"]}},
        {"id": "out",    "op": "RENDER", "needs": ["fields"]}
      ],
      "edges": [{"from": "fields", "to": "out", "when": "always"}],
      "privacy": {"items": "private"},
      "confidence": 0.86,
      "clarification": null
    }"""

    @Test fun specExampleParses() {
        val g = GraphJson.parse(Json.parseToJsonElement(spec)).getOrThrow()
        assertEquals("extract vendor and total from invoice photos", g.goal)
        assertEquals(0.86, g.confidence!!, 1e-9)
        assertTrue(g.clarification.isEmpty())
        assertEquals(T2Params.PATTERN_ID, g.dag.patternId)
        assertEquals(T2Params.VERSION, g.dag.patternsVersion)
        assertEquals(listOf("ocr", "fields", "out"), g.dag.tasks.map { it.id })
        assertEquals("out", g.dag.output)
        val ocr = g.dag.tasks[0]
        assertEquals(Op.OCR, ocr.op)
        assertEquals("ocr", ocr.params[T2Params.CAP]!!.jsonPrimitive.content)
        assertEquals("shard", ocr.params[T2Params.AXIS]!!.jsonPrimitive.content)
        assertEquals("items", ocr.params[T2Params.INPUT]!!.jsonPrimitive.content)
        assertEquals("private", ocr.params[T2Params.PRIVACY]!!.jsonPrimitive.content)   // from the privacy map
        val fields = g.dag.tasks[1]
        assertEquals(listOf("ocr"), fields.inputs)
        assertEquals(2, fields.params[T2Params.REPLICAS]!!.jsonPrimitive.content.toInt())
        assertTrue(fields.params[T2Params.SCHEMA] is JsonObject)
        assertNull(fields.params[T2Params.PRIVACY])   // inherited at compile time, not copied
        assertEquals(listOf("fields"), g.dag.tasks[2].inputs)
        assertNull(g.dag.tasks[2].params[T2Params.WHEN])   // an always edge leaves no condition
    }

    @Test fun roundTripIsStable() {
        val g = GraphJson.parse(Json.parseToJsonElement(spec)).getOrThrow()
        val json = GraphJson.encode(g.dag, g.goal, g.confidence, g.clarification)
        val again = GraphJson.parse(json).getOrThrow()
        assertEquals(Canon.canonical(g.dag), Canon.canonical(again.dag))
        assertEquals(g, again)
        assertEquals("private", (json["privacy"] as JsonObject)["items"]!!.jsonPrimitive.content)
        assertEquals(2, (json["edges"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun conditionalEdgesAndStrictFormsParse() {
        val strict = buildJsonObject {
            put("goal", JsonPrimitive("classify then escalate"))
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("review")); put("op", JsonPrimitive("LLM_MAP")); put("cap", JsonPrimitive("llm.json"))
                    put("input", JsonPrimitive("texts")); put("axis", JsonPrimitive("shard")); put("replicas", JsonPrimitive(2))
                    put("schema", JsonPrimitive("""{"type":"object","properties":{"label":{"type":"string"}},"required":["label"]}"""))
                    put("needs", buildJsonArray {}); put("owner", kotlinx.serialization.json.JsonNull)
                    put("params", JsonPrimitive("""{"template":"Label: {item}","maxTokens":64}"""))
                })
                add(buildJsonObject {
                    put("id", JsonPrimitive("cls")); put("op", JsonPrimitive("TEXT_CLASSIFY")); put("cap", JsonPrimitive("llm.json"))
                    put("input", JsonPrimitive("texts")); put("axis", JsonPrimitive("fanout")); put("replicas", JsonPrimitive(3))
                    put("schema", kotlinx.serialization.json.JsonNull); put("needs", buildJsonArray {}); put("owner", kotlinx.serialization.json.JsonNull)
                    put("params", kotlinx.serialization.json.JsonNull)
                })
                add(buildJsonObject {
                    put("id", JsonPrimitive("out")); put("op", JsonPrimitive("RENDER")); put("cap", kotlinx.serialization.json.JsonNull)
                    put("input", kotlinx.serialization.json.JsonNull); put("axis", kotlinx.serialization.json.JsonNull); put("replicas", kotlinx.serialization.json.JsonNull)
                    put("schema", kotlinx.serialization.json.JsonNull); put("needs", buildJsonArray { add(JsonPrimitive("cls")) }); put("owner", kotlinx.serialization.json.JsonNull)
                    put("params", kotlinx.serialization.json.JsonNull)
                })
            })
            put("edges", buildJsonArray {
                add(buildJsonObject { put("from", JsonPrimitive("cls")); put("to", JsonPrimitive("review")); put("when", JsonPrimitive("if_confidence_below:0.6")) })
                add(buildJsonObject { put("from", JsonPrimitive("review")); put("to", JsonPrimitive("out")); put("when", JsonPrimitive("if_count_above:0")) })
            })
            put("privacy", buildJsonArray { add(buildJsonObject { put("input", JsonPrimitive("texts")); put("privacy", JsonPrimitive("released")) }) })
            put("confidence", JsonPrimitive(0.7))
            put("clarification", JsonPrimitive("Do you mean every review or only this month's?"))
        }
        val g = GraphJson.parse(strict).getOrThrow()
        // the edge cls -> review made review a consumer of cls, and the nodes were re-ordered topologically
        assertEquals(listOf("cls", "review", "out"), g.dag.tasks.map { it.id })
        val review = g.dag.tasks[1]
        assertEquals(listOf("cls"), review.inputs)
        assertEquals("Label: {item}", review.params[T2Params.TEMPLATE]!!.jsonPrimitive.content)
        assertEquals(64, review.params[T2Params.MAX_TOKENS]!!.jsonPrimitive.content.toInt())
        assertTrue(review.params[T2Params.SCHEMA] is JsonObject)
        assertEquals("released", review.params[T2Params.PRIVACY]!!.jsonPrimitive.content)
        assertEquals(mapOf("cls" to EdgeCondition.IfConfidenceBelow(0.6)), GraphJson.conditions(review))
        assertEquals(mapOf("review" to EdgeCondition.IfCountAbove(0)), GraphJson.conditions(g.dag.tasks[2]))
        assertEquals(listOf("cls", "review"), g.dag.tasks[2].inputs)
        assertEquals(listOf("Do you mean every review or only this month's?"), g.clarification)
        // round trip through the spec form keeps the conditions
        val again = GraphJson.parse(GraphJson.encode(g.dag, g.goal, g.confidence, g.clarification)).getOrThrow()
        assertEquals(Canon.canonical(g.dag), Canon.canonical(again.dag))
    }

    @Test fun badInputIsReported() {
        fun err(s: String) = GraphJson.parse(Json.parseToJsonElement(s)).exceptionOrNull()!!.message!!
        assertTrue(err("""{"nodes":[]}""").contains("no nodes"))
        assertTrue(err("""{"nodes":[{"id":"a","op":"NOPE"}]}""").contains("unknown op"))
        assertTrue(err("""{"nodes":[{"op":"OCR"}]}""").contains("no id"))
        assertTrue(err("""{"nodes":[{"id":"a","op":"OCR"}],"edges":[{"from":"a","to":"a","when":"sometimes"}]}""").contains("unknown condition"))
        assertTrue(err("""{"nodes":[{"id":"a","op":"FIELDS","schema":"not json"}]}""").contains("not a JSON object"))
        assertTrue(err("""[1,2]""").contains("not a JSON object"))
    }

    @Test fun edgeConditionsParseAndHold() {
        assertEquals(EdgeCondition.Always, EdgeCondition.parse("always"))
        assertEquals(EdgeCondition.IfEmpty, EdgeCondition.parse("IF_EMPTY"))
        assertEquals(EdgeCondition.IfConfidenceBelow(0.5), EdgeCondition.parse("if_confidence_below:0.5"))
        assertEquals(EdgeCondition.IfCountAbove(3), EdgeCondition.parse("if_count_above:3"))
        assertNull(EdgeCondition.parse("if_confidence_below:2"))
        assertNull(EdgeCondition.parse("if_count_above:-1"))
        assertNull(EdgeCondition.parse("whenever"))
        assertEquals("if_confidence_below:0.5", EdgeCondition.IfConfidenceBelow(0.5).encode())
        assertTrue(EdgeCondition.IfEmpty.holds(Json.parseToJsonElement("""{"items":[]}""")))
        assertFalse(EdgeCondition.IfEmpty.holds(Json.parseToJsonElement("""{"items":[1]}""")))
        assertTrue(EdgeCondition.IfConfidenceBelow(0.6).holds(Json.parseToJsonElement("""{"value":"x","confidence":0.33}""")))
        assertFalse(EdgeCondition.IfConfidenceBelow(0.6).holds(Json.parseToJsonElement("""{"value":"x","confidence":0.67}""")))
        assertTrue(EdgeCondition.IfCountAbove(2).holds(Json.parseToJsonElement("""[1,2,3]""")))
        assertFalse(EdgeCondition.IfCountAbove(3).holds(Json.parseToJsonElement("""{"count":3}""")))
    }
}
