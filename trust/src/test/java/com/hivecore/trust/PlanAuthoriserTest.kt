package com.hivecore.trust

import com.hivecore.core.Dag
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.TaskBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 6.5 (P10): a TASK runs only against a held PLAN from the same orchestrator carrying the task's spec hash. */
class PlanAuthoriserTest {
    private var clock = 1_700_000_000_000L
    private val orch = "o".repeat(64)
    private val other = "x".repeat(64)
    private val owner = "w".repeat(64)

    private val agg = TaskSpec("a", Op.AGG, buildJsonObject { put("q", "revenue by region") }, owner = owner)
    private val ratio = TaskSpec("r", Op.RATIO, buildJsonObject { put("key", "region") }, inputs = listOf("a"))
    private fun plan(id: String = "plan-1", tasks: List<TaskSpec> = listOf(agg, ratio), commander: String = orch, chunkIds: List<String> = emptyList()) =
        PlanBody(id, "ph", commander, "q", Dag("per_unit", "p1", tasks, "r"), emptyMap(), clock, "n-$id", chunkIds)
    private fun task(spec: TaskSpec, planId: String = "plan-1", commander: String = orch) =
        TaskBody(planId, spec, emptyMap(), clock + 4_000, "m1", commander, clock, "nonce")

    @Test fun aTaskRunsOnlyAgainstAHeldPlanFromTheSameOrchestratorWithTheSameSpec() {
        val auth = PlanAuthoriser(clock = { clock })
        val warnings = ArrayList<Pair<String, Int>>()
        auth.onWarning = { p, n -> warnings += p to n }
        assertNotNull(auth.authorise(task(agg), orch))                       // no plan held yet
        auth.onPlanSeen(plan(), orch)
        assertEquals(orch, auth.held("plan-1")!!.commander)
        assertNull(auth.authorise(task(agg), orch))
        assertNull(auth.authorise(task(ratio), orch))
        assertTrue(auth.authorise(task(agg, commander = other), other)!!.contains("another orchestrator"))
        assertTrue(auth.authorise(task(agg.copy(params = buildJsonObject { put("q", "salaries by region") })), orch)!!.contains("not in PLAN"))
        assertTrue(auth.authorise(task(agg.copy(owner = other)), orch)!!.contains("not in PLAN"))
        assertTrue(auth.authorise(task(agg, planId = "plan-9"), orch)!!.contains("no PLAN"))
        // A PLAN whose body names another commander than its signer is not recorded.
        auth.onPlanSeen(plan("plan-2", commander = other), orch)
        assertNull(auth.held("plan-2"))
        assertEquals(PlanAuthoriser.keyOf(agg), PlanAuthoriser.keyOf(agg.copy()))
    }

    @Test fun splitPlansAuthoriseChunkTasksByChunkIdAndTemplate() {
        val auth = PlanAuthoriser(clock = { clock })
        val template = TaskSpec("ocr", Op.OCR, buildJsonObject { put("lang", "en") }, modelId = "ocr-small")
        auth.onPlanSeen(plan("split-1", tasks = listOf(template), chunkIds = listOf("c0", "c1", "c2")), orch)
        val c1 = TaskSpec("c1", Op.OCR, buildJsonObject { put("lang", "en"); put("from", 10); put("to", 20) }, modelId = "ocr-small", blobs = listOf("b1"))
        assertNull(auth.authorise(task(c1, "split-1"), orch))
        assertNull(auth.authorise(task(template, "split-1"), orch))                                   // the template itself
        assertTrue(auth.authorise(task(c1.copy(id = "c7"), "split-1"), orch)!!.contains("not in PLAN"))      // unknown chunk id
        assertTrue(auth.authorise(task(c1.copy(op = Op.DETECT), "split-1"), orch)!!.contains("template"))   // other op
        assertTrue(auth.authorise(task(c1.copy(modelId = "ocr-big"), "split-1"), orch)!!.contains("template"))
        assertTrue(auth.authorise(task(c1.copy(params = buildJsonObject { put("lang", "fr"); put("from", 10) }), "split-1"), orch)!!.contains("template"))
        assertTrue(auth.authorise(task(c1, "split-1", commander = other), other)!!.contains("another orchestrator"))
    }

    @Test fun threeUnauthorisedTasksInAMinuteFromOnePeerRaiseAWarning() {
        val auth = PlanAuthoriser(clock = { clock })
        val warnings = ArrayList<Pair<String, Int>>()
        auth.onWarning = { p, n -> warnings += p to n }
        auth.authorise(task(agg), orch); clock += 10_000
        auth.authorise(task(agg), orch); clock += 10_000
        assertTrue(warnings.isEmpty())
        assertEquals(2, auth.unauthorisedInLastMinute(orch))
        auth.authorise(task(agg), other)                      // another peer: its own count
        assertTrue(warnings.isEmpty())
        auth.authorise(task(agg), orch)
        assertEquals(listOf(orch to 3), warnings)
        auth.authorise(task(agg), orch)                       // a fourth does not warn again
        assertEquals(1, warnings.size)
        clock += 61_000                                        // the window moves on
        assertEquals(0, auth.unauthorisedInLastMinute(orch))
        auth.authorise(task(agg), orch); auth.authorise(task(agg), orch); auth.authorise(task(agg), orch)
        assertEquals(2, warnings.size)
    }

    @Test fun heldPlansSurviveARestartThroughTheDb() {
        val db = JdbcSqlDb.inMemory()
        PlanAuthoriser(db, { clock }).onPlanSeen(plan(), orch)
        val again = PlanAuthoriser(db, { clock })
        assertNull(again.authorise(task(agg), orch))
        assertNotNull(again.authorise(task(agg), other))
    }
}
