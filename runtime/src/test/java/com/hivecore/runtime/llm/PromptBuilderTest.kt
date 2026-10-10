package com.hivecore.runtime.llm

import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeSchemaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val today = "2026-10-10"

    @Test fun schemaLineMatchesPlan() {
        assertEquals(
            "employees(region TEXT groupable, rep_id TEXT identifier, name TEXT identifier, salary INTEGER confidential, commission INTEGER confidential, joined TEXT) rows=61 aliases: employee, reps, rep, staff, headcount, people, salaries, commissions",
            PromptBuilder.schemaLine(FakeSchemaSource.EMPLOYEES)
        )
        assertTrue(PromptBuilder.schemaLine(FakeSchemaSource.INVOICES).startsWith("invoices(vendor TEXT groupable, date TEXT, month TEXT groupable, total INTEGER, status TEXT groupable, source TEXT) rows=40"))
    }

    @Test fun promptSnapshot() {
        val schema = FakeSchemaSource.seed(NodeRole.HR)
        val shots = TestAssets.fewShots(NodeRole.HR)
        val msgs = PromptBuilder.intent(schema, "Average commission in the West region", shots, today)

        assertEquals(1 + shots.size * 2 + 1, msgs.size)
        assertEquals("system", msgs.first().role)
        assertEquals("user", msgs.last().role)
        assertEquals("Average commission in the West region", msgs.last().content)

        val sys = msgs.first().content
        assertTrue(sys.contains(PromptBuilder.schemaLine(FakeSchemaSource.EMPLOYEES)))
        assertTrue(sys.contains(PromptBuilder.COVERAGE_RULE))
        assertTrue(sys.contains("Today is 2026-10-10"))
        assertTrue(sys.contains("\"this year\" = month gte \"2026-01\" and month lte \"2026-12\""))
        assertTrue(sys.contains("\"last quarter\" = month gte \"2026-07\" and month lte \"2026-09\""))
        assertTrue(sys.contains("\"in October\" = month eq \"2026-10\""))
        assertTrue(sys.contains("{\"not_relevant\":true}"))
        assertTrue(sys.contains("onboarding_checklist.docx"))
        assertTrue(sys.contains("count|sum|avg|min|max"))

        assertTrue(sys.contains(PromptBuilder.SKIP_RULE))

        // few-shots as user/assistant turns, in file order (key order of each answer kept: "skip" leads)
        assertEquals("user", msgs[1].role); assertEquals("Average salary in the South region", msgs[1].content)
        assertEquals("assistant", msgs[2].role)
        assertEquals("""{"skip":[],"intents":[{"table":"employees","metric":"avg","column":"salary","filters":[{"column":"region","op":"eq","value":"South"}]}]}""", msgs[2].content)
        assertEquals("Units sold per region last quarter, and how many staff per region?", msgs[3].content)
        assertEquals("""{"skip":["units"],"intents":[{"table":"employees","metric":"count","group_by":["region"]}]}""", msgs[4].content)
        assertEquals("assistant", msgs[6].role); assertEquals("""{"not_relevant":true}""", msgs[6].content)
        // every few-shot answer parses
        shots.forEach { assertTrue(it.question, IntentParser.parse(it.answerJson).isSuccess) }
    }

    @Test fun dateRulesRollOverYears() {
        val d = PromptBuilder.Dates.of("2026-01-15")
        assertEquals("2025-12", d.lastMonth)
        assertEquals("2025-10", d.lastQuarterStart)
        assertEquals("2025-12", d.lastQuarterEnd)
        val e = PromptBuilder.Dates.of("2026-05-01")
        assertEquals("2026-01", e.lastQuarterStart); assertEquals("2026-03", e.lastQuarterEnd); assertEquals("2026-04", e.lastMonth)
    }

    @Test fun chatTemplateRendersChatMl() {
        val msgs = PromptBuilder.intent(FakeSchemaSource.seed(NodeRole.ACCOUNTS), "Total invoiced in October?", emptyList(), today)
        val text = ChatTemplate.qwen(msgs)
        assertTrue(text.startsWith("<|im_start|>system\n"))
        assertTrue(text.endsWith("<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue(text.contains("<|im_end|>\n<|im_start|>user\nTotal invoiced in October?<|im_end|>\n"))
    }
}
