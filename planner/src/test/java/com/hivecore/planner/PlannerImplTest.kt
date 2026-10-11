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
import kotlinx.serialization.json.jsonObject
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
        // the one compatible pattern is the cue's own (per → RATIO → per_unit), so no model call chose it, and every
        // slot had exactly one grounded candidate: the plan is deterministic
        assertEquals(0, llm.calls.size)
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
        // the cue chose compare_two; only the measure slot asked the model (k = 3 runs), with "none" offered
        assertEquals(3, llm.calls.size)
        assertTrue(llm.calls[0].first.last().content.contains("spend (invoices.total on Accounts)"))
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

    @Test fun makeImageBindsTheSubjectIntoThePromptAndThePictureStep() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "make_image" }
        val pq = planner(llm).plan("Please create an image of my team", route("an image" to Op.GEN_IMAGE, owners = emptyList()), catalogs).getOrThrow()
        assertEquals("make_image", pq.dag.patternId)
        assertEquals(1.0, pq.confidence, 1e-9)
        val (i, p) = pq.dag.tasks
        assertEquals(Op.GEN_IMAGE, i.op)
        assertTrue(i.params["prompt"]!!.jsonPrimitive.content, i.params["prompt"]!!.jsonPrimitive.content.startsWith("my team, "))
        assertEquals(Op.RENDER, p.op)
        assertEquals("picture", p.params["kind"]!!.jsonPrimitive.content)
        assertEquals("my team", p.params["subject"]!!.jsonPrimitive.content)
        assertEquals(listOf("i"), p.inputs)
        // the subject keeps the question's own words after the asking phrase
        val poster = planner(llm).plan("Draw a poster for the sales kickoff", route("draw" to Op.GEN_IMAGE, owners = emptyList()), catalogs).getOrThrow()
        assertEquals("poster for the sales kickoff", poster.dag.tasks.last().params["subject"]!!.jsonPrimitive.content)
    }

    @Test fun aPictureWithNoSubjectOrNoDrawingPhoneFailsInPlainWords() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "make_image" }
        val none = planner(llm).plan("draw", route("draw" to Op.GEN_IMAGE, owners = emptyList()), catalogs)
        assertTrue(none.exceptionOrNull()!!.message!!.startsWith("I can't tell what to draw"))
        val noPhone = PlannerImpl(ClosedChooserImpl(llm), PlannerTestCatalogs.patterns(), PlannerTestCatalogs.CLOCK, ZoneOffset.UTC,
            availableOps = { setOf(Op.AGG, Op.LLM_MAP) })
        val r = noPhone.plan("Create an image of my team", route("an image" to Op.GEN_IMAGE, owners = emptyList()), catalogs)
        assertTrue(r.exceptionOrNull()!!.message, r.exceptionOrNull()!!.message!!.startsWith("No phone of the swarm can draw right now"))
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
        // the model answering "none" among several compatible patterns is the same refusal (one cue-chosen pattern is never vetoed)
        val none = FakeLlmEngine().apply { replies["which plan fits"] = "none" }
        val r2 = planner(none).plan("Which region has the highest revenue per rep?", route("per" to Op.RATIO, "highest" to Op.RANK), catalogs)
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

/** The deck pattern (patterns v2): cues, year spans, the optional-op nodes and the grounded slots. */
class DeckPlanningTest {
    private val catalogs = PlannerTestCatalogs.all()
    private val q = "Generate a pitch deck showing the increase in sales FY2024-26"

    private fun planner(llm: FakeLlmEngine, ops: Set<Op>? = null) =
        PlannerImpl(ClosedChooserImpl(llm), PlannerTestCatalogs.patterns(), PlannerTestCatalogs.CLOCK, ZoneOffset.UTC, availableOps = { ops })

    @Test fun yearSpans() {
        assertEquals(PeriodSlot("FY2024-26", "2023-04", "2026-03"), Periods.resolve("FY2024-26", TODAY))
        assertEquals(PeriodSlot("FY24-26", "2023-04", "2026-03"), Periods.resolve("FY24-26", TODAY))
        assertEquals(PeriodSlot("2024 to 2026", "2024-01", "2026-12"), Periods.resolve("2024 to 2026", TODAY))
        assertEquals(PeriodSlot("2024-2026", "2024-01", "2026-12"), Periods.resolve("2024-2026", TODAY))
        assertEquals(PeriodSlot("FY2025", "2024-04", "2025-03"), Periods.resolve("FY2025", TODAY))
        assertEquals(PeriodSlot("2025", "2025-01", "2025-12"), Periods.resolve("2025", TODAY))
        assertEquals(null, Periods.resolve("26", TODAY))
        val found = Periods.find(QText(q), TODAY)
        assertEquals(listOf("FY2024 26"), found.map { it.second.phrase })
        assertEquals("2023-04" to "2026-03", found[0].second.let { it.gte to it.lte })
    }

    @Test fun theDeckQuestionRoutesAndBinds() = runTest {
        val route = SwarmRouterImpl().route(q, "hr", catalogs)
        assertTrue(route.reason, route.composite)
        assertEquals(listOf(Op.RENDER, Op.TREND), route.cues.map { it.op })   // in order of appearance
        assertEquals(listOf(SALES), route.owners)
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "deck_growth" }
        val pq = planner(llm, setOf(Op.AGG, Op.LLM_MAP, Op.GEN_IMAGE)).plan(q, route, catalogs).getOrThrow()
        assertEquals("deck_growth", pq.dag.patternId)
        assertTrue(pq.clarification.isEmpty())
        assertEquals(listOf("a", "w", "i", "d"), pq.dag.tasks.map { it.id })
        val a = pq.dag.tasks[0]
        assertEquals(SALES, a.owner)
        assertEquals("sales by month FY2024 26", a.params["q"]!!.jsonPrimitive.content)
        val expect = a.params["expect"]!!.jsonObject
        assertEquals("revenue", expect["measure"]!!.jsonPrimitive.content)
        assertEquals("2023-04", expect["period"]!!.jsonObject["gte"]!!.jsonPrimitive.content)
        val w = pq.dag.tasks[1]
        assertEquals(Op.LLM_MAP, w.op); assertEquals("none", w.params["replicate"]!!.jsonPrimitive.content)
        assertTrue(w.params["template"]!!.jsonPrimitive.content.contains("about revenue FY2024 26"))
        val d = pq.dag.tasks[3]
        assertEquals(Op.RENDER, d.op); assertEquals(listOf("a", "w", "i"), d.inputs)
        assertEquals("deck", d.params["kind"]!!.jsonPrimitive.content)
        assertEquals("FY2024 26", d.params["period"]!!.jsonPrimitive.content)
        // the plan compiles with the swarm's ops
        val c = PlanCompilerImpl().compile(pq.dag, catalogs, "", setOf("llm.json", "image.gen"), setOf(Op.AGG, Op.LLM_MAP, Op.GEN_IMAGE), "hr")
        assertEquals(emptyList<String>(), c.check.errors)
    }

    @Test fun optionalOpNodesAreDroppedWhenNoPhoneRunsThem() = runTest {
        val route = SwarmRouterImpl().route(q, "hr", catalogs)
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "deck_growth" }
        val pq = planner(llm, setOf(Op.AGG)).plan(q, route, catalogs).getOrThrow()
        assertEquals(listOf("a", "d"), pq.dag.tasks.map { it.id })
        assertEquals(listOf("a"), pq.dag.tasks[1].inputs)
        // unknown ops (null): every node stays
        assertEquals(4, planner(llm).plan(q, route, catalogs).getOrThrow().dag.tasks.size)
    }
}
