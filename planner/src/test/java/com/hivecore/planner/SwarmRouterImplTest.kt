package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.Op
import com.hivecore.core.Schema
import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeSchemaSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwarmRouterImplTest {
    private val router = SwarmRouterImpl()

    private val hrSchema = FakeSchemaSource.seed(NodeRole.HR).let { Schema(it.node, it.tables, it.docs + FakeSchemaSource.LEAVE_POLICY) }
    private val catalogs = listOf(
        Catalog.of("hr", "HR", hrSchema),
        Catalog.of("sales", "Sales", FakeSchemaSource.seed(NodeRole.SALES)),
        Catalog.of("accounts", "Accounts", FakeSchemaSource.seed(NodeRole.ACCOUNTS)),
    )

    private fun route(q: String, self: String = "sales") = router.route(q, self, catalogs)

    /** The 50-question Gate 2 set: none is composite except the ones that really carry an operator cue. */
    @Test fun gate2SetIsNotCompositeExceptRealCues() {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "tools/questions_50.json") }.first { it.isFile }
        val qs = Json.parseToJsonElement(file.readText()).jsonObject["questions"]!!.jsonArray.map { it.jsonObject["q"]!!.jsonPrimitive.content }
        assertEquals(50, qs.size)
        // "Revenue per rep" is a real ratio (Sales revenue over HR rep count), the per_unit pattern of §11 item 1.
        val cued = setOf("Revenue per rep this year")
        for (q in qs) {
            val d = route(q)
            assertEquals("$q -> ${d.reason}", q in cued, d.composite)
        }
        val ratio = route("Revenue per rep this year")
        assertEquals(listOf(Op.RATIO), ratio.cues.map { it.op })
        assertEquals(listOf("hr", "sales"), ratio.owners)
    }

    private data class Row(val q: String, val composite: Boolean, val ops: List<Op>, val owners: List<String>)

    @Test fun routingTable() {
        val rows = listOf(
            // §11 composites 1-7
            Row("Revenue per rep by region this year", true, listOf(Op.RATIO), listOf("hr", "sales")),
            Row("Which region has the highest revenue per rep, and is its average commission above the company average?", true,
                listOf(Op.RANK, Op.RATIO, Op.THRESHOLD), listOf("hr", "sales")),
            Row("How did revenue change month on month in Q3 by region?", true, listOf(Op.TREND, Op.TREND), listOf("sales")),
            Row("Top 2 regions by units sold in 2026", true, listOf(Op.RANK), listOf("sales")),
            Row("Compare October invoice spend with October revenue", true, listOf(Op.DIFF), listOf("sales", "accounts")),
            Row("Do Sales rep counts per region match HR headcount?", true, listOf(Op.RECONCILE), listOf("hr", "sales")),
            Row("Key points from the onboarding checklist and the Q3 review", true, listOf(Op.EXTRACT), listOf("hr", "sales")),
            // more cues
            Row("Revenue vs units by region", true, listOf(Op.DIFF), listOf("sales")),
            Row("What is the ratio of invoice spend to revenue?", true, listOf(Op.RATIO), listOf("sales", "accounts")),
            Row("Revenue growth by month", true, listOf(Op.TREND), listOf("sales")),
            Row("Flag regions whose revenue is more than the average", true, listOf(Op.THRESHOLD, Op.THRESHOLD), listOf("sales")),
            Row("Which vendors were billed above 50000?", true, listOf(Op.THRESHOLD), listOf("accounts")),
            Row("Rank regions by units sold", true, listOf(Op.RANK), listOf("sales")),
            Row("Summarise the leave policy", true, listOf(Op.EXTRACT), listOf("hr")),
            Row("What is the share of revenue by region?", true, listOf(Op.RATIO), listOf("sales")),
            Row("Is invoice spend consistent with revenue each month?", true, listOf(Op.RECONCILE), listOf("sales", "accounts")),
            Row("Which region had the worst units?", true, listOf(Op.RANK), listOf("sales")),
            // more than one aggregate on one node, with two owners
            Row("Units and revenue by region with headcount by region", true, emptyList(), listOf("hr", "sales")),
            Row("Average salary and commission by region, and revenue by region", true, emptyList(), listOf("hr", "sales")),
            // T0 / T1
            Row("Revenue per region this year", false, emptyList(), listOf("sales")),
            Row("Average revenue per sale in the East", false, emptyList(), listOf("sales")),
            Row("Headcount per region and total units per region", false, emptyList(), listOf("hr", "sales")),
            Row("What is the highest salary in the company?", false, emptyList(), listOf("hr")),
            Row("What risks did the sales review flag?", false, emptyList(), listOf("sales")),
            Row("Total invoiced per vendor", false, emptyList(), listOf("accounts")),
            Row("Revenue per month", false, emptyList(), listOf("sales")),
            // no owner: existing pipeline even with a cue
            Row("Compare the weather in Mumbai and Delhi", false, listOf(Op.DIFF), emptyList()),
            Row("What is the capital of France?", false, emptyList(), emptyList()),
        )
        assertTrue(rows.size >= 25)
        for (r in rows) {
            val d = route(r.q)
            assertEquals("${r.q} -> ${d.reason}", r.composite, d.composite)
            assertEquals("${r.q} cues", r.ops, d.cues.map { it.op })
            assertEquals("${r.q} owners", r.owners, d.owners)
        }
    }

    @Test fun reasonNamesTheTierAndLexiconVersion() {
        assertTrue(route("Revenue by region", self = "sales").reason.contains("T0"))
        assertTrue(route("Revenue by region", self = "hr").reason.contains("T1"))
        val c = route("Top 2 regions by units sold")
        assertTrue(c.reason, c.reason.contains("T2") && c.reason.contains("'top' → RANK"))
        assertTrue(c.reason.startsWith("[${CueLexicon.VERSION}]"))
    }

    @Test fun cuesComeInOrderOfAppearanceAndLongestPhraseWins() {
        val cues = router.cues("Is revenue above average and how did it change year over year?", catalogs)
        assertEquals(listOf("above average", "change", "year over year"), cues.map { it.word })
        assertEquals(listOf(Op.THRESHOLD, Op.TREND, Op.TREND), cues.map { it.op })
    }

    @Test fun confidentialDocumentsAreNotInTheCatalog() {
        val schema = Schema(NodeRole.HR, emptyList(), listOf(FakeSchemaSource.LEAVE_POLICY.copy(confidential = true)))
        val d = router.route("Summarise the leave policy", "sales", listOf(Catalog.of("hr", "HR", schema)))
        assertFalse(d.composite)
        assertTrue(d.owners.isEmpty())
    }
}
