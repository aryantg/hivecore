package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.runtime.llm.ClosedChooserImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TieredPlannerTest {
    private val catalogs = PlannerTestCatalogs.all()
    private val caps = CapabilitySummary(setOf("ocr", "llm.json", "llm.text"))
    private var now = PlannerTestCatalogs.CLOCK()

    /** A scripted T2: returns [plan] (or a failure), records every call. */
    private class ScriptedT2(var plan: Result<PlannedQuestion>) : T2Planner {
        val calls = ArrayList<String>()
        override suspend fun plan(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>): Result<PlannedQuestion> {
            calls += question; return plan
        }
        override suspend fun repair(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>, previous: JsonElement, errors: List<String>) = plan
    }

    private val t2Dag = Dag("t2", "t2-graph-1", listOf(
        TaskSpec("ocr", Op.OCR, buildJsonObject { put("cap", "ocr"); put("input", "items"); put("axis", "shard"); put("privacy", "released") }),
        TaskSpec("out", Op.RENDER, inputs = listOf("ocr")),
    ), "out")

    private fun facade(llm: FakeLlmEngine, t2: T2Planner? = null, t2First: Boolean = false, cache: PlanCache? = PlanCache()): TieredPlannerImpl {
        val patterns = PlannerTestCatalogs.patterns()
        val t1 = PlannerImpl(ClosedChooserImpl(llm), patterns, { now }, ZoneOffset.UTC)
        return TieredPlannerImpl("self", SwarmRouterImpl(), t1, PlanCompilerImpl(), t2, { t2First }, { "m1" }, { now }, cache, patterns)
    }

    @Test fun t0ThenT1AnswersAConfidentCompositeQuestion() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "per_unit" }
        val t2 = ScriptedT2(Result.success(PlannedQuestion(t2Dag, 0.9)))
        val p = facade(llm, t2).plan("Revenue per rep by region this year", catalogs, caps)
        assertTrue(p.route.composite)
        assertEquals(PlanTier.T1, p.tier)
        assertTrue(p.ok)
        assertEquals("per_unit", p.plan!!.dag.patternId)
        assertNotNull(p.planHash)
        assertTrue("T2 must not be asked when T1 is confident", t2.calls.isEmpty())
    }

    @Test fun nonCompositeQuestionStopsAtT0() = runTest {
        val p = facade(FakeLlmEngine()).plan("What is the weather like?", catalogs, caps)
        assertEquals(PlanTier.T0, p.tier)
        assertFalse(p.route.composite)
        assertNull(p.plan)
        assertNotNull(p.refusal)
    }

    @Test fun uncoveredCapabilityGoesToT2EvenWithoutOwners() = runTest {
        val t2 = ScriptedT2(Result.success(PlannedQuestion(t2Dag, 0.9)))
        val p = facade(FakeLlmEngine(), t2).plan("Extract vendor and total from these scanned receipts", catalogs, caps)
        assertEquals(PlanTier.T2, p.tier)
        assertTrue(p.ok)
        assertEquals(listOf("Extract vendor and total from these scanned receipts"), t2.calls)
        assertTrue(p.note!!.contains("ocr"))
    }

    @Test fun noPatternGoesToT2AndItsFailureFallsBackToT1() = runTest {
        // the model answers "none of these fits" for every pattern: T1 has no pattern, so T2 is asked
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "none" }
        val t2 = ScriptedT2(Result.failure(IllegalStateException("the cloud planner's graph was rejected twice: 'x' reads missing input")))
        val p = facade(llm, t2).plan("Revenue per rep by region this year", catalogs, caps)
        assertEquals(PlanTier.T1, p.tier)
        assertNull(p.plan)
        assertNotNull(p.refusal)
        assertTrue(p.refusal, p.refusal!!.contains("cloud planner"))
        assertTrue(t2.calls.isNotEmpty())
    }

    @Test fun lowConfidenceAfterOneClarificationGoesToT2() = runTest {
        // a position-biased model always answers "1": split votes → confidence < 0.5 with a clarification
        val llm = FakeLlmEngine().apply { fallback = "1" }
        val t2 = ScriptedT2(Result.success(PlannedQuestion(t2Dag, 0.9)))
        val f = facade(llm, t2)
        val q = "Which region has the highest revenue per rep this year?"
        val first = f.plan(q, catalogs, caps)
        assertEquals(PlanTier.T1, first.tier)
        assertEquals(2, first.clarification.size)
        assertFalse(first.ok)
        assertTrue("the first unsure answer is a clarification, not a cloud call", t2.calls.isEmpty())
        now += 60_000
        val second = f.plan(q, catalogs, caps)
        assertEquals(PlanTier.T2, second.tier)
        assertTrue(second.ok)
        assertTrue(second.note!!.contains("after one clarification"))
    }

    @Test fun t2FailureKeepsT1BestCandidateWithClarification() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "1" }
        val t2 = ScriptedT2(Result.failure(IllegalStateException("no compliant endpoint")))
        val f = facade(llm, t2)
        val q = "Which region has the highest revenue per rep this year?"
        f.plan(q, catalogs, caps)
        now += 1_000
        val p = f.plan(q, catalogs, caps)
        assertEquals(PlanTier.T1, p.tier)
        assertEquals(2, p.clarification.size)
        assertTrue(p.note!!.contains("no compliant endpoint"))
    }

    @Test fun t2FirstSettingAsksTheCloudBeforeT1() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "per_unit" }
        val t2 = ScriptedT2(Result.success(PlannedQuestion(t2Dag, 0.9)))
        val p = facade(llm, t2, t2First = true).plan("Revenue per rep by region this year", catalogs, caps)
        assertEquals(PlanTier.T2, p.tier)
        assertEquals(1, t2.calls.size)
        assertTrue(llm.calls.isEmpty())
    }

    @Test fun t2GraphThatFailsTheCompilerIsNotUsed() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "per_unit" }
        val bad = Dag("t2", "t2-graph-1", listOf(TaskSpec("f", Op.FIELDS, buildJsonObject { put("exec", "cloud") })), "f")
        val t2 = ScriptedT2(Result.success(PlannedQuestion(bad, 0.9)))
        val p = facade(llm, t2, t2First = true).plan("Revenue per rep by region this year", catalogs, caps)
        assertEquals(PlanTier.T1, p.tier)
        assertTrue(p.ok)
    }

    @Test fun cacheRecordsTheTier() = runTest {
        val llm = FakeLlmEngine().apply { replies["which plan fits"] = "per_unit" }
        val cache = PlanCache()
        val f = facade(llm, cache = cache)
        val q = "Revenue per rep by region this year"
        val first = f.plan(q, catalogs, caps)
        assertFalse(first.fromCache)
        assertEquals(1, cache.size)
        assertEquals("T1", cache.get(PlanKeys.key(q, catalogs, caps.capabilities))!!.tier)
        val calls = llm.calls.size
        val second = f.plan(q, catalogs, caps)
        assertTrue(second.fromCache)
        assertEquals(PlanTier.T1, second.tier)
        assertEquals(calls, llm.calls.size)
        // a different capability set is a different key
        assertNull(cache.get(PlanKeys.key(q, catalogs, setOf("vlm"))))
    }
}
