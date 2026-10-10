package com.hivecore.runtime.llm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    private val clean = """{"intents":[{"table":"employees","metric":"avg","column":"commission","filters":[{"column":"region","op":"eq","value":"West"}]}]}"""

    @Test fun cleanJson() {
        val set = IntentParser.parse(clean).getOrThrow()
        assertFalse(set.notRelevant)
        assertEquals(1, set.intents.size)
        val i = set.intents[0]
        assertEquals("employees", i.table); assertEquals("avg", i.metric); assertEquals("commission", i.column)
        assertEquals(listOf("region"), i.filters.map { it.column })
        assertEquals("eq", i.filters[0].op)
        assertEquals(JsonPrimitive("West"), i.filters[0].value)
        assertTrue(i.groupBy.isEmpty())
    }

    @Test fun junkPrefixAndSuffix() {
        val text = "Sure! Here is the JSON you asked for:\n```json\n$clean\n```\nLet me know if you need anything else."
        val set = IntentParser.parse(text).getOrThrow()
        assertEquals("commission", set.intents[0].column)
    }

    @Test fun nestedBracesInsideStringsAreIgnored() {
        val text = """{"intents":[{"table":"employees","metric":"count","filters":[{"column":"name","op":"eq","value":"a{b}c \"q\" }"}]}]} trailing {"""
        val set = IntentParser.parse(text).getOrThrow()
        assertEquals("a{b}c \"q\" }", set.intents[0].filters[0].value.jsonPrimitive.content)
    }

    @Test fun truncatedIsAnError() {
        val r = IntentParser.parse("""{"intents":[{"table":"employees","metric":"count","group_by":["region"""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("truncated"))
    }

    @Test fun noObjectIsAnError() {
        val r = IntentParser.parse("I cannot answer that.")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("no JSON object"))
    }

    @Test fun notRelevant() {
        val set = IntentParser.parse("""{"not_relevant": true}""").getOrThrow()
        assertTrue(set.notRelevant); assertTrue(set.intents.isEmpty())
        // a not_relevant false with intents behaves like a normal answer
        val set2 = IntentParser.parse("""{"not_relevant": false, "intents":[{"table":"sales","metric":"sum","column":"revenue"}]}""").getOrThrow()
        assertFalse(set2.notRelevant); assertEquals("revenue", set2.intents[0].column)
    }

    @Test fun emptyIntentsIsAnErrorWithAdvice() {
        val r = IntentParser.parse("""{"intents":[]}""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not_relevant"))
    }

    @Test fun skipKeyIsIgnoredAndAllSkippedMeansNotRelevant() {
        // Wave 4 prompt: the model names another department's measures in "skip" before its own intents.
        val set = IntentParser.parse("""{"skip":["revenue"],"intents":[{"table":"employees","metric":"count","group_by":["region"]}]}""").getOrThrow()
        assertFalse(set.notRelevant)
        assertEquals(1, set.intents.size); assertEquals("count", set.intents[0].metric); assertEquals(listOf("region"), set.intents[0].groupBy)
        assertTrue(IntentParser.parse("""{"skip":["units"],"intents":[]}""").getOrThrow().notRelevant)
        // an empty skip with no intents is still the advice error
        assertTrue(IntentParser.parse("""{"skip":[],"intents":[]}""").isFailure)
    }

    @Test fun anExtraAggregateWithoutAColumnIsDroppedButAloneIsAnError() {
        // Measured on HR (Wave 4 baseline): count by region plus a column-less "sum" standing in for revenue.
        val text = """{"intents":[{"table":"employees","metric":"count","group_by":["region"]},{"table":"employees","metric":"sum","group_by":["region"]}]}"""
        val set = IntentParser.parse(text).getOrThrow()
        assertEquals(1, set.intents.size); assertEquals("count", set.intents[0].metric)
        // order does not matter
        val swapped = IntentParser.parse("""{"intents":[{"table":"employees","metric":"avg"},{"table":"employees","metric":"count"}]}""").getOrThrow()
        assertEquals(listOf("count"), swapped.intents.map { it.metric })
        // all column-less: nothing complete to keep, so the error stands
        assertTrue(IntentParser.parse("""{"intents":[{"table":"sales","metric":"sum"},{"table":"sales","metric":"avg"}]}""").exceptionOrNull()!!.message!!.contains("needs a numeric column"))
    }

    @Test fun lenientShapes() {
        // bare intent, group_by as a string, operator symbol, metric alias, mixed case
        val set = IntentParser.parse("""{"table":"Employees","metric":"Average","column":"Salary","group_by":"Region","filters":{"column":"region","op":">=","value":5}}""").getOrThrow()
        val i = set.intents[0]
        assertEquals("employees", i.table); assertEquals("avg", i.metric); assertEquals("salary", i.column)
        assertEquals(listOf("region"), i.groupBy)
        assertEquals("gte", i.filters[0].op)
        assertEquals(JsonPrimitive(5), i.filters[0].value)
    }

    @Test fun countDropsColumnAndMoreThanThreeAreTrimmed() {
        val four = (1..4).joinToString(",") { i -> """{"table":"sales","metric":"count","column":null,"group_by":["region"],"filters":[{"column":"units","op":"gt","value":$i}]}""" }
        val set = IntentParser.parse("""{"intents":[$four]}""").getOrThrow()
        assertEquals(3, set.intents.size)
        assertNull(set.intents[0].column)
    }

    @Test fun exactDuplicatesAreDropped() {
        // Gate 1 sample: the NPU model repeated the first intent as the third one
        val text = """{"intents":[{"table":"employees","metric":"count","group_by":["region"]},{"table":"employees","metric":"count","group_by":["region"],"filters":[{"column":"region","op":"eq","value":"South"}]},{"table":"employees","metric":"count","group_by":["region"]}]}"""
        val set = IntentParser.parse(text).getOrThrow()
        assertEquals(2, set.intents.size)
        assertEquals(1, set.intents[1].filters.size)
    }

    @Test fun specificErrors() {
        fun err(s: String) = IntentParser.parse(s).exceptionOrNull()!!.message!!
        assertTrue(err("""{"intents":[{"metric":"count"}]}""").contains("no table"))
        assertTrue(err("""{"intents":[{"table":"sales"}]}""").contains("no metric"))
        assertTrue(err("""{"intents":[{"table":"sales","metric":"median","column":"revenue"}]}""").contains("unknown metric"))
        assertTrue(err("""{"intents":[{"table":"sales","metric":"sum"}]}""").contains("needs a numeric column"))
        assertTrue(err("""{"intents":[{"table":"sales","metric":"count","filters":[{"column":"region","op":"like","value":"W"}]}]}""").contains("unknown op"))
        assertTrue(err("""{"intents":[{"table":"sales","metric":"count","filters":[{"column":"region","op":"eq"}]}]}""").contains("no value"))
        assertTrue(err("""{"intents":[{"table":"sales","metric":"count","filters":[{"column":"region","op":"in","value":"West"}]}]}""").contains("needs an array"))
        assertTrue(err("""{"intents": 5}""").contains("array"))
        assertTrue(err("""{"intents":[{"table":"sales","metric":"count",}]}""").startsWith("invalid JSON") || err("""[1,2""").isNotEmpty())
    }

    @Test fun inOperatorKeepsArrays() {
        val set = IntentParser.parse("""{"intents":[{"table":"sales","metric":"sum","column":"revenue","filters":[{"column":"region","op":"in","value":["West","South"]}]}]}""").getOrThrow()
        assertEquals(2, set.intents[0].filters[0].value.jsonArray.size)
    }

    @Test fun extractJsonFindsFirstBalancedObject() {
        assertEquals("""{"a":{"b":1}}""", IntentParser.extractJson("""xx {"a":{"b":1}} {"c":2}""").getOrThrow())
        assertTrue(IntentParser.extractJson("""{"a":"}""").isFailure)
    }

    @Test fun encodeRoundTrip() {
        val set = IntentParser.parse(clean).getOrThrow()
        val again = IntentParser.parse(IntentParser.encode(set)).getOrThrow()
        assertEquals(set, again)
    }
}
