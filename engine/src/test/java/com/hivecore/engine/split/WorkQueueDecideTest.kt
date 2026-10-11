package com.hivecore.engine.split

import com.hivecore.core.Op
import com.hivecore.core.Profile
import com.hivecore.engine.ComparatorImpl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision points B (per-chunk replicas) and E (verdict tie-break and annotation) in the queue; without a model nothing changes. */
class WorkQueueDecideTest {
    private var now = 1_000_000L
    private fun phone(id: String) = Profile(id, 0, eligible = true, idle = true, workerOptIn = true)
    private fun items(n: Int) = SplitInput.Items((0 until n).map { JsonPrimitive("t$it") })
    private fun out(v: String) = buildJsonObject { put("v", v) }

    private fun plan(op: Op, per: Int = 2, n: Int = 4, vararg nodes: String): SplitPlan =
        ChunkPlanner().plan(SplitJob("j", op, JsonObject(emptyMap()), "m", items(n), unitsPerChunk = per, unitMs = 10.0), nodes.map(::phone), "me").getOrThrow()

    private fun WorkQueue.assignments() = fill().associateBy { it.node to it.chunk.index }

    @Test fun aChunkDecidedToRunTwiceOnALightOpNeedsAnAgreeingPair() {
        val p = plan(Op.IQA, nodes = arrayOf("a", "b"))
        assertEquals(1, p.replicas)
        val decided = p.copy(chunks = p.chunks.map { if (it.index == 0) it.copy(replicas = 2) else it })
        val q = WorkQueue(decided, listOf("a", "b", "me"), { now }, ComparatorImpl())
        val a = q.assignments()
        val first = a.keys.first { it.second == 0 }
        val second = a.keys.filter { it.second == 0 }.first { it != first }
        assertEquals(WorkQueue.Outcome.AwaitingReplica, q.onResult(first.first, 0, a.getValue(first).attempt, out("x"), 5))
        val r = q.onResult(second.first, 0, a.getValue(second).attempt, out("x"), 5)
        assertTrue(r is WorkQueue.Outcome.Accepted && r.verified == true)
        // a chunk without an override on the same op settles on one run, as today
        val other = a.keys.first { it.second == 1 }
        val r1 = q.onResult(other.first, 1, a.getValue(other).attempt, out("y"), 5)
        assertTrue(r1 is WorkQueue.Outcome.Accepted && r1.verified == null)
    }

    @Test fun aChunkDecidedToRunOnceOnAnLlmOpIsAcceptedButNeverVerified() {
        val p = plan(Op.TEXT_CLASSIFY, nodes = arrayOf("a", "b"))
        assertEquals(2, p.replicas)
        val decided = p.copy(chunks = p.chunks.map { if (it.index == 0) it.copy(replicas = 1) else it })
        val q = WorkQueue(decided, listOf("a", "b", "me"), { now }, ComparatorImpl())
        val a = q.assignments()
        val k = a.keys.first { it.second == 0 }
        val r = q.onResult(k.first, 0, a.getValue(k).attempt, out("x"), 5)
        assertTrue(r is WorkQueue.Outcome.Accepted && r.verified == false)                  // not memoized by the commander
        assertTrue(q.isUnverified(0))
        assertEquals(1, q.snapshot().unverified)
    }

    @Test fun disagreeingReplicasWithNoPhoneLeftAskForAVerdictOnlyWhenEnabled() {
        val p = plan(Op.TEXT_CLASSIFY, per = 4, nodes = arrayOf("a", "b"))
        // without a model: today's verification failure
        val plain = WorkQueue(p, listOf("a", "b"), { now }, ComparatorImpl())
        val a0 = plain.assignments()
        plain.onResult("a", 0, a0.getValue("a" to 0).attempt, out("x"), 5)
        plain.onResult("b", 0, a0.getValue("b" to 0).attempt, out("y"), 5)
        assertEquals(listOf(0), plain.takeVerificationFailures().map { it.index })
        assertTrue(plain.takeVerdictRequests().isEmpty())
        // with a model: a verdict request first; the chunk waits
        val q = WorkQueue(p, listOf("a", "b"), { now }, ComparatorImpl(), verdictEnabled = true)
        val a = q.assignments()
        q.onResult("a", 0, a.getValue("a" to 0).attempt, out("x"), 5)
        q.onResult("b", 0, a.getValue("b" to 0).attempt, out("y"), 5)
        assertTrue(q.takeVerificationFailures().isEmpty())
        val reqs = q.takeVerdictRequests()
        assertEquals(1, reqs.size)
        assertEquals(listOf("a" to out("x"), "b" to out("y")), reqs[0].candidates)
        assertTrue(q.takeVerdictRequests().isEmpty())                                        // handed out once
        assertFalse(q.finished)
        // the model picks b: accepted, not verified, b's output
        q.verdict(0, "b")
        assertEquals(out("y"), q.output(0))
        assertTrue(q.isUnverified(0))
        assertTrue(q.finished)
        assertTrue(q.notes.any { it.contains("picked") })
        // a null verdict (model unavailable, below threshold, unknown node) keeps the verification-failure path
        val q2 = WorkQueue(p, listOf("a", "b"), { now }, ComparatorImpl(), verdictEnabled = true)
        val a2 = q2.assignments()
        q2.onResult("a", 0, a2.getValue("a" to 0).attempt, out("x"), 5)
        q2.onResult("b", 0, a2.getValue("b" to 0).attempt, out("y"), 5)
        q2.takeVerdictRequests()
        q2.verdict(0, "nobody")
        assertEquals(listOf(0), q2.takeVerificationFailures().map { it.index })
        assertNull(q2.output(0))
    }

    @Test fun agreeingReplicasNeverAskForAVerdictAndAnnotationOnlyAnnotates() {
        val p = plan(Op.TEXT_CLASSIFY, per = 4, nodes = arrayOf("a", "b"))
        val q = WorkQueue(p, listOf("a", "b"), { now }, ComparatorImpl(), verdictEnabled = true)
        val a = q.assignments()
        q.onResult("a", 0, a.getValue("a" to 0).attempt, out("x"), 5)
        val r = q.onResult("b", 0, a.getValue("b" to 0).attempt, out("x"), 5)
        assertTrue(r is WorkQueue.Outcome.Accepted && r.verified == true)
        assertTrue(q.takeVerdictRequests().isEmpty())
        q.annotateVerdict(0, 0.3)
        assertEquals(0.3, q.snapshot().chunks[0].verdict!!, 1e-9)
        assertEquals(out("x"), q.output(0))
        assertFalse(q.isUnverified(0))
        assertTrue(q.notes.any { it.contains("low-confidence") })
        q.annotateVerdict(0, 0.9)
        assertEquals(0.9, q.snapshot().chunks[0].verdict!!, 1e-9)
    }
}
