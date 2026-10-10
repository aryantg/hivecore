package com.hivecore.planner

import com.hivecore.core.Confidence
import com.hivecore.core.Cue
import com.hivecore.core.Op
import com.hivecore.core.RouteDecision
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.runtime.llm.ClosedChooserImpl
import com.hivecore.planner.PlannerTestCatalogs.ACCOUNTS
import com.hivecore.planner.PlannerTestCatalogs.HR
import com.hivecore.planner.PlannerTestCatalogs.SALES
import com.hivecore.planner.PlannerTestCatalogs.TODAY
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class PlannerImplTest {
    private val catalogs = PlannerTestCatalogs.all()
    private fun planner(llm: FakeLlmEngine) =
        PlannerImpl(ClosedChooserImpl(llm), PlannerTestCatalogs.patterns(), PlannerTestCatalogs.CLOCK, ZoneOffset.UTC)
    private fun route(vararg cues: Pair<String, Op>, owners: List<String> = listOf(SALES, HR)) =
        RouteDecision(true, owners, cues.map { Cue(it.first, it.second) }, "test")

    @Test fun revenuePerRepByRegionThisYearIsPerUnit() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "per_unit" }
        val r = planner(llm).plan("Revenue per rep by region this year", route("per" to Op.RATIO), catalogs)
        val pq = r.getOrThrow()
        assertEquals("per_unit", pq.dag.patternId)
        assertEquals(1.0, pq.confidence, 1e-9)
        assertTrue(pq.clarification.isEmpty())
        val (a, b, ratio) = pq.dag.tasks
        assertEquals(SALES, a.owner); assertEquals("Revenue by region this year", a.params["q"]!!.jsonPrimitive.content)
        assertEquals(HR, b.owner); assertEquals("number of rep by region", b.params["q"]!!.jsonPrimitive.content)
        assertEquals(Op.RATIO, ratio.op)
        // only the pattern choice used the model: k = 3 runs; every slot had exactly one grounded candidate
        assertEquals(3, llm.calls.size)
        assertTrue(llm.calls[0].first.last().content.contains("per_unit: "))
        assertTrue(llm.calls[0].first.last().content.contains("none: "))
    }

    @Test fun slotChoiceGoesThroughTheChooser() = runTest {
        val llm = FakeLlmEngine().apply {
            replies["first amount compared"] = "spend (invoices.total on Accounts)"
            replies["which plan fits"] = "compare_two"
        }
        val r = planner(llm).plan("Compare October invoice spend with October revenue",
            route("compare" to Op.DIFF, owners = listOf(ACCOUNTS, SALES)), catalogs).getOrThrow()
        assertEquals("compare_two", r.dag.patternId)
        val (a, b) = r.dag.tasks
        assertEquals(ACCOUNTS, a.owner); assertEquals("total spend October", a.params["q"]!!.jsonPrimitive.content)
        assertEquals(SALES, b.owner); assertEquals("total revenue October", b.params["q"]!!.jsonPrimitive.content)
        assertEquals(6, llm.calls.size)
    }

    @Test fun splitVotesReturnAClarification() = runTest {
        // a position-biased model (always "1") splits the vote across the permuted orders
        val llm = FakeLlmEngine().apply { fallback = "1" }
        val r = planner(llm).plan("Which region has the highest revenue per rep this year?",
            route("per" to Op.RATIO, "highest" to Op.RANK), catalogs)
        val pq = r.getOrThrow()
        assertTrue(pq.confidence < Confidence.LOW)
        assertEquals(2, pq.clarification.size)
        assertTrue(pq.clarification.toString(), pq.clarification.distinct().size == 2)
    }

    @Test fun ungroundedSlotsAreRejected() {
        val q = "Revenue per rep by region this year"
        val ok = mapOf("numerator" to MeasureSlot("revenue", "sales", "revenue", SALES), "denominator" to CountSlot("rep", "employees", HR),
            "by" to DimensionSlot("region", "region"), "period" to PeriodSlot("this year", "2026-01", "2026-12"))
        assertEquals(emptyList<String>(), PlanGrounding.check(q, ok, catalogs, TODAY))
        val errs = PlanGrounding.check(q, mapOf(
            "numerator" to MeasureSlot("commission", "employees", "commission", HR),   // not in the question
            "x" to MeasureSlot("revenue", "sales", "salary", SALES),                    // column not in that catalog
            "by" to DimensionSlot("rep", "rep_id"),                                     // not groupable
            "period" to PeriodSlot("this year", "2026-01", "2026-06"),                  // wrong range
            "k" to IntSlot("3", 3)), catalogs, TODAY)
        assertEquals(errs.toString(), 5, errs.size)
    }

    @Test fun nothingInTheCatalogsMeansAFailureNotAGuess() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "per_unit" }
        val r = planner(llm).plan("Profit per rep by region this year", route("per" to Op.RATIO), catalogs)
        assertTrue(r.exceptionOrNull() is PlanFailed)
        assertTrue(r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message!!.contains("amount"))
    }

    @Test fun rankRepsByCommissionIsRefused() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "top_k_by" }
        val r = planner(llm).plan("Rank reps by commission", route("rank" to Op.RANK, owners = listOf(HR)), catalogs)
        assertTrue(r.exceptionOrNull() is PlanFailed)
    }

    @Test fun outOfAlgebraListsWhatTheSwarmCanDo() = runTest {
        val llm = FakeLlmEngine()
        val r = planner(llm).plan("Forecast next year's revenue", route(owners = listOf(SALES)), catalogs)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("It can answer"))
        assertEquals(0, llm.calls.size)
        // the model answering "none" is the same refusal
        val none = FakeLlmEngine().apply { replies["which plan fits"] = "none" }
        val r2 = planner(none).plan("Revenue per rep by region", route("per" to Op.RATIO), catalogs)
        assertTrue(r2.exceptionOrNull()!!.message!!.contains("It can answer"))
    }

    @Test fun periodsResolveAgainstToday() {
        assertEquals(PeriodSlot("Q3", "2026-07", "2026-09"), Periods.resolve("Q3", TODAY))
        assertEquals(PeriodSlot("last quarter", "2026-07", "2026-09"), Periods.resolve("last quarter", TODAY))
        assertEquals(PeriodSlot("last month", "2026-09", "2026-09"), Periods.resolve("last month", TODAY))
        assertEquals(PeriodSlot("October", "2026-10", "2026-10"), Periods.resolve("October", TODAY))
        assertEquals(PeriodSlot("2025", "2025-01", "2025-12"), Periods.resolve("2025", TODAY))
    }
}
