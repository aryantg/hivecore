package com.hivecore.ui.vm

import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.core.WorkerEventKind.CHUNK_RECEIVED
import com.hivecore.core.WorkerEventKind.CHUNK_RUNNING
import com.hivecore.core.WorkerEventKind.MODEL_LOADING
import com.hivecore.core.WorkerEventKind.MODEL_READY
import com.hivecore.core.WorkerEventKind.MODEL_SELECTED
import com.hivecore.core.WorkerEventKind.PLAN_RECEIVED
import com.hivecore.core.WorkerEventKind.RECEIPT_COUNTERSIGNED
import com.hivecore.core.WorkerEventKind.RESULT_SENT
import com.hivecore.core.WorkerEventKind.YIELDED
import com.hivecore.ui.vm.WorkerTimeline.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerTimelineTest {
    private val names = mapOf("orch" to "Pixel 8", "me" to "Galaxy")
    private var t = 0L

    private fun ev(kind: WorkerEventKind, taskId: String? = null, planId: String = "job1#1", commander: String = "orch", modelId: String? = "qwen-1.5b",
                   warm: Boolean? = null, loadMs: Long? = null, ms: Long? = null, reason: String? = "preferred", error: String? = null) =
        WorkerEvent(planId, kind, "me", commander, ++t, taskId = taskId, op = "EXTRACT", capability = "llm.json", modelId = modelId, reason = reason,
            warm = warm, loadMs = loadMs, chunkIndex = taskId?.let { 2 }, chunkCount = 8, label = taskId?.let { "items 21–30" }, ms = ms, error = error)

    private fun build(vararg es: WorkerEvent) = WorkerTimeline.build(es.toList(), "me", { names[it] ?: it }).single()

    @Test fun placementBootThenChunkThenReturnThenCountersign() {
        val boot = listOf(ev(MODEL_SELECTED, warm = false), ev(MODEL_LOADING))
        val booting = build(*boot.toTypedArray())
        assertEquals("Working for", "Pixel 8", booting.commanderName)
        assertEquals(StepState.ACTIVE, booting.model.state)
        assertTrue(booting.model.detail!!, booting.model.detail!!.contains("llm.json → qwen-1.5b") && booting.model.detail!!.contains("booting"))
        assertEquals(StepState.ACTIVE, booting.chunk.state)
        assertEquals("Waiting for a chunk of 8", booting.chunk.detail)

        val loaded = boot + ev(MODEL_READY, warm = false, loadMs = 2300) + ev(PLAN_RECEIVED, modelId = null)
        val running = loaded + listOf(ev(CHUNK_RECEIVED, "c2.a1", warm = true), ev(MODEL_SELECTED, "c2.a1", warm = true), ev(CHUNK_RUNNING, "c2.a1", warm = true))
        val r = build(*running.toTypedArray())
        assertEquals(StepState.DONE, r.model.state)
        assertTrue(r.model.detail!!, r.model.detail!!.contains("booted in 2.3 s"))   // the cold load is kept over the warm chunk-time pick
        assertEquals("Chunk 3 of 8 · items 21–30", r.chunk.detail)
        assertEquals(StepState.ACTIVE, r.run.state)
        assertEquals(StepState.PENDING, r.back.state)
        assertFalse(r.settled)

        val sent = running + ev(RESULT_SENT, "c2.a1", ms = 1800)
        val s = build(*sent.toTypedArray())
        assertEquals(StepState.DONE, s.run.state)
        assertEquals("Sent to Pixel 8", s.back.detail)
        assertTrue(s.settled)

        val signed = build(*(sent + WorkerEvent("job1", RECEIPT_COUNTERSIGNED, "me", "orch", ++t)).toTypedArray())
        assertEquals("Sent 1 chunk to Pixel 8 · countersigned ✓", signed.back.detail)
    }

    @Test fun ownJobsAreLeftOutAndAYieldIsHandedBack() {
        val own = ev(CHUNK_RECEIVED, "c0.a1", planId = "mine#1", commander = "me")
        val y = listOf(ev(CHUNK_RECEIVED, "c2.a1"), ev(YIELDED, "c2.a1", error = "worker mode is off"))
        val cards = WorkerTimeline.build(y + own, "me", { names[it] ?: it })
        assertEquals(listOf("job1#1"), cards.map { it.planId })
        assertEquals(StepState.SKIPPED, cards.single().run.state)
        assertEquals(StepState.SKIPPED, cards.single().back.state)
    }

    @Test fun opsWithoutAModelSkipTheModelStep() {
        val e = WorkerEvent("job2#1", CHUNK_RECEIVED, "me", "orch", 1, taskId = "c0.a1", op = "RATIO", chunkIndex = 0)
        assertEquals(StepState.SKIPPED, build(e).model.state)
    }

    @Test fun laneLines() {
        assertEquals("booting qwen-1.5b…", WorkerTimeline.phrase(ev(MODEL_LOADING)))
        assertEquals("qwen-1.5b ready · booted in 2.3 s", WorkerTimeline.phrase(ev(MODEL_READY, warm = false, loadMs = 2300)))
        assertEquals("running chunk 3/8 · items 21–30", WorkerTimeline.phrase(ev(CHUNK_RUNNING, "c2.a1")))
        assertEquals("sent chunk 3/8 back · 1.8 s", WorkerTimeline.phrase(ev(RESULT_SENT, "c2.a1", ms = 1800)))
        assertTrue(WorkerTimeline.busy(ev(MODEL_LOADING)))
        assertFalse(WorkerTimeline.busy(ev(RESULT_SENT, "c2.a1")))
    }
}
