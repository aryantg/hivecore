package com.hivecore.node.data

import com.hivecore.core.AggTable
import com.hivecore.core.DocAnswer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerRendererTest {
    private val r = TextAnswerRenderer()

    private fun row(vararg v: Any?): List<JsonElement> = v.map {
        when (it) { null -> JsonNull; is Int -> JsonPrimitive(it.toLong()); is Long -> JsonPrimitive(it); is Double -> JsonPrimitive(it); else -> JsonPrimitive(it.toString()) }
    }

    @Test fun indianDigitGrouping() {
        assertEquals("0", TextAnswerRenderer.indian(0L))
        assertEquals("999", TextAnswerRenderer.indian(999L))
        assertEquals("1,000", TextAnswerRenderer.indian(1000L))
        assertEquals("42,300", TextAnswerRenderer.indian(42300L))
        assertEquals("1,00,000", TextAnswerRenderer.indian(100000L))
        assertEquals("12,02,900", TextAnswerRenderer.indian(1202900L))
        assertEquals("1,23,45,000", TextAnswerRenderer.indian(12345000L))
        assertEquals("1,23,45,678", TextAnswerRenderer.indian(12345678L))
        assertEquals("-1,23,456", TextAnswerRenderer.indian(-123456L))
        assertEquals("85,760", TextAnswerRenderer.indian(85760.0))
        assertEquals("11,79,933.33", TextAnswerRenderer.indian(1179933.3333333333))
        assertEquals("2,05,955.13", TextAnswerRenderer.indian(205955.12820512822))
        assertEquals("0.5", TextAnswerRenderer.indian(0.5))
        assertEquals("-2.5", TextAnswerRenderer.indian(-2.5))
    }

    @Test fun tourSentences() {
        val q2 = AggTable("HR", "Average commission in West", listOf("n", "avg_commission"), listOf(row(15, 85760.0)), 15, 61, "sql")
        assertEquals("Average commission in West: 85,760 (15 reps)", r.render(listOf(q2), null, emptyList()))

        val q1 = AggTable("Sales", "Revenue by region, 2026", listOf("region", "n", "sum_revenue"),
            listOf(row("East", 246, 50797500), row("North", 238, 51033000), row("South", 278, 60607500), row("West", 238, 51460000)), 1000, 1204, "sql")
        assertEquals("Revenue by region, 2026: East 5,07,97,500 (246 rows) · North 5,10,33,000 (238 rows) · South 6,06,07,500 (278 rows) · West 5,14,60,000 (238 rows)",
            r.render(listOf(q1), null, emptyList()))

        val hr = AggTable("HR", "Employees by region", listOf("region", "n"), listOf(row("East", 15), row("North", 15), row("South", 16), row("West", 15)), 61, 61, "sql")
        assertEquals("Employees by region: East 15 · North 15 · South 16 · West 15", r.render(listOf(hr), null, emptyList()))

        val q5 = AggTable("Accounts", "Total, October 2026", listOf("n", "sum_total"), listOf(row(10, 1202900)), 10, 40, "sql")
        assertEquals("Total, October 2026: 12,02,900 (10 invoices)", r.render(listOf(q5), null, emptyList()))

        val one = AggTable("Accounts", "Total with vendor Deccan Caterers", listOf("n", "sum_total"), listOf(row(1, 5000)), 1, 40, "sql")
        assertEquals("Total with vendor Deccan Caterers: 5,000 (1 invoice)", r.render(listOf(one), null, emptyList()))

        val count = AggTable("HR", "Employees in West", listOf("n"), listOf(row(15)), 15, 61, "sql")
        assertEquals("Employees in West: 15", r.render(listOf(count), null, emptyList()))

        val empty = AggTable("Sales", "Revenue by region in Mars", listOf("region", "n", "sum_revenue"), emptyList(), 0, 1204, "sql")
        assertEquals("Revenue by region in Mars: no rows match", r.render(listOf(empty), null, emptyList()))

        val two = AggTable("Sales", "Units by region and month", listOf("region", "month", "n", "sum_units"), listOf(row("West", "2026-01", 24, 480)), 24, 1204, "sql")
        assertEquals("Units by region and month: West / 2026-01 480 (24 rows)", r.render(listOf(two), null, emptyList()))
    }

    @Test fun joinedRowsAndCoveredTablesAreNotRepeated() {
        val sales = AggTable("Sales", "Revenue by region, 2026", listOf("region", "n", "sum_revenue"), listOf(row("West", 238, 51460000)), 238, 1204, "sql")
        val hr = AggTable("HR", "Employees by region", listOf("region", "n"), listOf(row("West", 15)), 15, 61, "sql")
        val joined = AggTable("joined", "Sales + HR", listOf("region", "sales_n", "sales_sum_revenue", "hr_n"),
            listOf(row("West", 238, 51460000, 15), row("South", 278, 60607500, 16)), 1061, 1265, "sql")
        assertEquals("West: revenue 5,14,60,000, 15 reps · South: revenue 6,06,07,500, 16 reps", r.render(listOf(sales, hr), joined, emptyList()))
        // A node outside the join is still rendered, on its own line.
        val acc = AggTable("Accounts", "Total, October 2026", listOf("n", "sum_total"), listOf(row(10, 1202900)), 10, 40, "sql")
        assertEquals("West: revenue 5,14,60,000, 15 reps · South: revenue 6,06,07,500, 16 reps\nTotal, October 2026: 12,02,900 (10 invoices)",
            r.render(listOf(sales, hr, acc), joined, emptyList()))
        val avgJoin = AggTable("joined", "HR + Sales", listOf("region", "hr_n", "hr_avg_commission", "sales_n", "sales_sum_units"), listOf(row("West", 15, 85760.0, 238, 4927)), 0, 0, "sql")
        assertEquals("West: average commission 85,760, units 4,927", r.render(emptyList(), avgJoin, emptyList()))
    }

    @Test fun docsRefusalAndNoNode() {
        val d = DocAnswer("A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly", "leave_policy.pdf", "p.2", 3)
        assertEquals("A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly. (leave_policy.pdf, p.2)", r.render(emptyList(), null, listOf(d)))
        val hr = AggTable("HR", "Employees in West", listOf("n"), listOf(row(15)), 15, 61, "sql")
        assertEquals("Employees in West: 15\nA new joiner is entitled to 18 days of paid leave in the first year, accrued monthly. (leave_policy.pdf, p.2)", r.render(listOf(hr), null, listOf(d)))

        assertEquals("HR refused: filters on name are not allowed on confidential data.", r.refusal(listOf("HR" to "filters on name are not allowed on confidential data")))
        assertEquals("Declined by HR.", r.refusal(listOf("HR" to "declined by HR")))
        assertEquals("No approval from HR in time.", r.refusal(listOf("HR" to "no approval from HR in time")))
        assertEquals("HR refused: every group has fewer than 5 rows. Sales refused: unknown metric median.",
            r.refusal(listOf("HR" to "every group has fewer than 5 rows", "Sales" to "unknown metric median.")))
        assertEquals("No node holds data about that. HR holds employees, leave_policy.pdf, onboarding_checklist.docx · Sales holds sales, q3_review.pptx · Accounts holds invoices.",
            r.noNode(listOf("HR" to listOf("employees", "leave_policy.pdf", "onboarding_checklist.docx"), "Sales" to listOf("sales", "q3_review.pptx"), "Accounts" to listOf("invoices"))))
        assertEquals("No node holds data about that.", r.noNode(emptyList()))
    }
}
