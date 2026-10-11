package com.hivecore.planner

import com.hivecore.core.Op
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.runtime.llm.ClosedChooserImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The open paths: the direct plan, the knowledge plan and the on-device graph planner. */
class DirectPlannerTest {
    private val catalogs = PlannerTestCatalogs.all()
    private val router = SwarmRouterImpl()
    private val direct = DirectPlanner()

    private fun plan(q: String): PlannedQuestion? = direct.plan(q, router.route(q, "self", catalogs), catalogs)

    @Test fun aQuestionNamingOnePhonesTableIsOneAggOnThatPhone() {
        val pq = plan("Revenue per region this year")!!
        assertEquals(DirectPlanner.PATTERN_ID, pq.dag.patternId)
        val a = pq.dag.tasks.single()
        assertEquals(Op.AGG, a.op); assertEquals(PlannerTestCatalogs.SALES, a.owner)
        assertEquals("Revenue per region this year", a.params["q"]!!.jsonPrimitive.content)
        assertEquals(a.id, pq.dag.output)
        assertEquals(DirectPlanner.CONFIDENCE, pq.confidence, 1e-9)
    }

    @Test fun twoPhonesEachAnswerTheirPartUnderOneRender() {
        val pq = plan("Headcount per region and total units per region")!!
        val owners = pq.dag.tasks.filter { it.op == Op.AGG }.map { it.owner }.toSet()
        assertEquals(setOf(PlannerTestCatalogs.HR, PlannerTestCatalogs.SALES), owners)
        val r = pq.dag.tasks.last()
        assertEquals(Op.RENDER, r.op); assertEquals(r.id, pq.dag.output)
        assertEquals(2, r.inputs.size)
    }

    @Test fun aChartWordAddsTheChartStepAndIsStrippedFromTheOwnersQuestion() {
        val pq = plan("Plot revenue by region this year as a bar chart")!!
        val (a, c) = pq.dag.tasks
        assertEquals(Op.AGG, a.op)
        assertEquals("revenue by region this year", a.params["q"]!!.jsonPrimitive.content)
        assertEquals(Op.RENDER, c.op); assertEquals("chart", c.params["kind"]!!.jsonPrimitive.content); assertEquals("bar", c.params["style"]!!.jsonPrimitive.content)
        assertEquals(listOf("a1"), c.inputs)
        assertEquals("units sold per month", DirectPlanner.stripChartWords("graph the units sold per month"))
        assertEquals("revenue by month", DirectPlanner.stripChartWords("Show me revenue by month in a line chart"))
    }

    @Test fun aNamedDocumentGoesToItsOwnerAndADocumentQuestionToTheBestFit() {
        val named = plan("What does the onboarding checklist say about laptops?")!!
        val d = named.dag.tasks.single()
        assertEquals(Op.DOC, d.op); assertEquals(PlannerTestCatalogs.HR, d.owner)
        val policy = plan("What is the policy on leave according to the documents?")!!
        assertEquals(Op.DOC, policy.dag.tasks.single().op)
        assertNull("nothing on the phones: no direct plan", plan("What is the capital of France?"))
    }

    @Test fun aPolicyQuestionGoesToTheDocumentEvenWhenATableSharesAWord() {
        // the Kavira HR phone holds a devices table and the kavira_hr_access_policy.md document
        val hr = com.hivecore.core.Catalog("node-hr", "HR", listOf(com.hivecore.core.CatalogTable("devices", listOf("laptops"),
            listOf(com.hivecore.core.CatalogColumn("device_type", com.hivecore.core.ColType.TEXT, groupable = true)))), listOf("kavira_hr_access_policy.md", "onboarding_checklist.docx"))
        val cats = listOf(hr, PlannerTestCatalogs.sales())
        val q = "what does the HR access policy say about devices"
        val pq = direct.plan(q, router.route(q, "self", cats), cats)!!
        val d = pq.dag.tasks.single()
        assertEquals(Op.DOC, d.op); assertEquals("node-hr", d.owner)
        val count = "how many devices by device type"
        assertEquals(Op.AGG, direct.plan(count, router.route(count, "self", cats), cats)!!.dag.tasks.single().op)
    }

    @Test fun theKnowledgePlanIsOneUnreplicatedGenTextStep() {
        val pq = KnowledgePlan.of("What is the capital of France?")
        val g = pq.dag.tasks.single()
        assertEquals(Op.GEN_TEXT, g.op)
        assertTrue(g.params["instruction"]!!.jsonPrimitive.content.contains("What is the capital of France?"))
        assertEquals("none", g.params["replicate"]!!.jsonPrimitive.content)
        assertTrue(KnowledgePlan.isKnowledge(pq.dag))
        val compiled = PlanCompilerImpl().compile(pq.dag, catalogs, "m", null, null, "self")
        assertTrue(compiled.check.errors.toString(), compiled.ok)
    }

    @Test fun theLocalGraphPlannerCompilesTheModelsGraphAndRepairsOnce() = runTest {
        val good = """{"goal":"revenue per rep by region","nodes":[
            {"id":"a","op":"AGG","owner":"${PlannerTestCatalogs.SALES}","params":{"q":"revenue by region this year"}},
            {"id":"b","op":"AGG","owner":"${PlannerTestCatalogs.HR}","params":{"q":"number of reps by region"}},
            {"id":"r","op":"RATIO","needs":["a","b"],"params":{"key":"region","num":"sum_revenue","den":"n"}}],"output":"r","confidence":0.8}"""
        val llm = FakeLlmEngine().apply { fallback = "Here is the graph:\n$good" }
        val planner = LocalGraphPlanner(llm, PlanCompilerImpl(), "self")
        val pq = planner.plan("Revenue per rep by region this year", catalogs, setOf("llm.json"), Op.entries.toSet()).getOrThrow()
        assertEquals(listOf(Op.AGG, Op.AGG, Op.RATIO), pq.dag.tasks.map { it.op })
        assertEquals(0.8, pq.confidence, 1e-9)
        assertEquals(1, llm.calls.size)
        val sys = llm.calls[0].first.first().content
        assertTrue(sys.contains("AGG:") && sys.contains("RENDER:") && sys.contains("at most 6 steps"))
        assertTrue("the catalogs go in by name only", llm.calls[0].first.last().content.contains("\"name\":\"employees\""))

        // a bad graph first (unknown owner), the good one on repair
        val bad = good.replace(PlannerTestCatalogs.HR, "node-nowhere")
        val llm2 = FakeLlmEngine().apply { replies["revenue per rep"] = bad; replies["rejected that graph"] = good }
        val repaired = LocalGraphPlanner(llm2, PlanCompilerImpl(), "self").plan("Revenue per rep by region this year", catalogs, setOf("llm.json"), Op.entries.toSet())
        assertTrue(repaired.exceptionOrNull()?.message ?: "", repaired.isSuccess)
        assertEquals(2, llm2.calls.size)

        // no steps = not about the data
        val none = LocalGraphPlanner(FakeLlmEngine().apply { fallback = """{"nodes":[]}""" }, PlanCompilerImpl(), "self")
            .plan("What is the capital of France?", catalogs, emptySet(), Op.entries.toSet())
        assertTrue(none.isFailure)
        assertNotNull(LocalGraphPlanner.extractObject("```json\n{\"a\":{\"b\":\"}\"}}\n```"))
        assertEquals("""{"a":{"b":"}"}}""", LocalGraphPlanner.extractObject("```json\n{\"a\":{\"b\":\"}\"}}\n```"))
    }

    @Test fun theTieredPlannerFallsThroughPatternsGraphAndDirect() = runTest {
        // no cue: the direct plan answers from the owner without any model call
        val llm = FakeLlmEngine()
        val patterns = PlannerTestCatalogs.patterns()
        val t1 = PlannerImpl(ClosedChooserImpl(llm), patterns, PlannerTestCatalogs.CLOCK, ZoneOffset.UTC)
        val graphLlm = FakeLlmEngine().apply { fallback = """{"goal":"g","nodes":[{"id":"a","op":"AGG","owner":"${PlannerTestCatalogs.SALES}","params":{"q":"revenue by region"}},
            {"id":"s","op":"SHARE","needs":["a"],"params":{"num":"sum_revenue"}}],"output":"s","confidence":0.7}""" }
        val tiered = TieredPlannerImpl("self", router, t1, PlanCompilerImpl(), null, patterns = patterns,
            localGraph = LocalGraphPlanner(graphLlm, PlanCompilerImpl(), "self"), direct = direct)
        val caps = CapabilitySummary(setOf("llm.json", "llm.text"))
        val d = tiered.plan("Revenue per region this year", PlannerTestCatalogs.all(), caps)
        assertTrue(d.refusal ?: "", d.ok)
        assertEquals(DirectPlanner.PATTERN_ID, d.plan!!.dag.patternId)
        assertTrue(llm.calls.isEmpty() && graphLlm.calls.isEmpty())
        // a cued question the library has no pattern for goes to the on-device graph planner
        val g = tiered.plan("What is the share of revenue by region?", PlannerTestCatalogs.all(), caps)
        assertEquals(PlanTier.T1G, g.tier)
        assertTrue(g.refusal ?: "", g.ok)
        assertEquals(listOf(Op.AGG, Op.SHARE), g.plan!!.dag.tasks.map { it.op })
        // nothing on the phones: no plan at all, the port offers the model's knowledge
        val none = tiered.plan("What is the capital of France?", PlannerTestCatalogs.all(), caps)
        assertNull(none.plan)
        assertTrue(none.route.owners.isEmpty())
    }
}
