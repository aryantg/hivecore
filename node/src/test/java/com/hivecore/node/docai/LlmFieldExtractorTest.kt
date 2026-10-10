package com.hivecore.node.docai

import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmFieldExtractorTest {
    private val rows = listOf("Vikram Hardware", "TAX INVOICE", "Invoice No: 1150", "Date: 11/10/2026", "Amount payable: ₹ 1,12,000.00", "Status: PAID")

    @Test fun fillsOnlyTheMissingFieldsAndMarksThemLlm() = runTest {
        val llm = FakeLlmEngine().apply {
            replies["vikram"] = """Sure! {"vendor":"Vikram Hardware Ltd","date":"2026-01-01","total":"₹ 1,12,000.00","status":"Settled"} done"""
        }
        val regex = InvoiceRegex.extract(rows)
        assertEquals(listOf("total"), regex.missing)
        val filled = LlmFieldExtractor(llm).fill(regex, rows).getOrThrow().partial
        assertEquals("Vikram Hardware", filled.vendor)     // regex value kept
        assertEquals("2026-10-11", filled.date)            // regex value kept
        assertEquals("PAID", filled.status)
        assertEquals(112000L, filled.total)                // from the LLM, string amount parsed
        assertEquals(mapOf("vendor" to "regex", "date" to "regex", "status" to "regex", "total" to "llm"), filled.by)
        assertEquals(listOf("total"), filled.llmFields)
        assertTrue(filled.complete)
        // Prompt: maxTokens 96, stopOnBalancedJson, OCR text as data, the JSON shape named, only the missing fields asked for.
        val (messages, params) = llm.calls.single()
        assertEquals(96, params.maxTokens)
        assertTrue(params.stopOnBalancedJson)
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("Return only JSON {\"vendor\":\"name\",\"date\":\"YYYY-MM-DD\",\"total\":number"))
        assertTrue(messages[0].content.contains("data"))
        assertTrue(messages[1].content.startsWith("Fields needed: total\n"))
        assertTrue(messages[1].content.contains("Amount payable: ₹ 1,12,000.00"))
    }

    @Test fun nothingMissingMeansNoCall() = runTest {
        val llm = FakeLlmEngine()
        val p = Partial("A B", "2026-10-01", 1, "PAID", mapOf("vendor" to "regex"))
        assertEquals(p, LlmFieldExtractor(llm).fill(p, rows).getOrThrow().partial)
        assertTrue(llm.calls.isEmpty())
    }

    @Test fun statusNormalisation() {
        assertEquals("PAID", LlmFieldExtractor.normaliseStatus("Settled"))
        assertEquals("PAID", LlmFieldExtractor.normaliseStatus("Paid in full"))
        assertEquals("PAID", LlmFieldExtractor.normaliseStatus("PAID"))
        assertEquals("UNPAID", LlmFieldExtractor.normaliseStatus("Outstanding"))
        assertEquals("UNPAID", LlmFieldExtractor.normaliseStatus("Pending"))
        assertEquals("UNPAID", LlmFieldExtractor.normaliseStatus("unpaid"))
        assertEquals("UNPAID", LlmFieldExtractor.normaliseStatus("Not paid"))
        assertEquals("DUE", LlmFieldExtractor.normaliseStatus("Due"))
        assertEquals("DUE", LlmFieldExtractor.normaliseStatus("Overdue"))
        assertNull(LlmFieldExtractor.normaliseStatus("whatever"))
        assertNull(LlmFieldExtractor.normaliseStatus(null))
        assertNull(LlmFieldExtractor.normaliseStatus("  "))
    }

    @Test fun lenientParse() {
        val f = LlmFieldExtractor.parse("""```json
            {"vendor": "Nilgiri Tea Co", "date": "3 October 2026", "total": 27300.0, "status": null, "extra": {"x": "}"}}
            ```""").getOrThrow()
        assertEquals("Nilgiri Tea Co", f.vendor)
        assertEquals("2026-10-03", f.date)
        assertEquals(27300L, f.total)
        assertNull(f.status)
        val nulls = LlmFieldExtractor.parse("""{"vendor":null,"date":"null","total":"n/a","status":"Settled"}""").getOrThrow()
        assertNull(nulls.vendor); assertNull(nulls.date); assertNull(nulls.total); assertEquals("PAID", nulls.status)
        assertEquals("""{"a":"}"}""", LlmFieldExtractor.balancedJson("""x {"a":"}"} y"""))
        assertNull(LlmFieldExtractor.balancedJson("""{"a": 1"""))
    }

    @Test fun badJsonIsAFailure() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "I cannot find the total." }
        val regex = InvoiceRegex.extract(rows)
        assertTrue(LlmFieldExtractor(llm).fill(regex, rows).isFailure)
        llm.fallback = """{"vendor": "x", "total": """      // truncated
        assertTrue(LlmFieldExtractor(llm).fill(regex, rows).isFailure)
        llm.fallback = "[1, 2]"
        assertTrue(LlmFieldExtractor(llm).fill(regex, rows).isFailure)
        llm.failNext = IllegalStateException("engine down")
        assertTrue(LlmFieldExtractor(llm).fill(regex, rows).isFailure)
        // A reply that parses but does not contain the missing field leaves it missing (no failure, nothing filled).
        llm.fallback = """{"vendor":"Vikram Hardware"}"""
        val still = LlmFieldExtractor(llm).fill(regex, rows).getOrThrow().partial
        assertEquals(listOf("total"), still.missing)
        assertTrue(still.llmFields.isEmpty())
    }
}
