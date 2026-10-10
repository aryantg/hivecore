package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ReclaimBody
import com.hivecore.mesh.ReclaimFound
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13.3 over three phones on a FakeBus with real routers: the orchestrator A restarts with a journal that says c0 is
 * done, c1 is running on B, c2 and c3 are queued; B and C hold outputs in their memos. The resume must take c0 from
 * the journal, c1 and c2 from the peers' memos through RECLAIM / RECLAIM_RES / memo-only TASK, and leave c3 to run.
 */
class ReclaimTest {
    private fun out(s: String): JsonElement = buildJsonObject { put("text", s) }
    private fun h(s: String) = Canon.hash(out(s))

    private fun JournalMesh.Node.journalJob(t0: Long, eligible: List<String>, chunks: Int = 4) {
        journal.begin(JobRow("j1", "j1#1", "ph", "T1", JobState.RUNNING, t0, t0, "public",
            capsSnapshot = buildJsonObject { put("eligible", buildJsonArray { eligible.forEach { add(JsonPrimitive(it)) } }) },
            spec = buildJsonObject { put("op", "OCR"); put("modelId", "ocr-1") }))
        journal.chunks((0 until chunks).map { i -> ChunkRow("j1", "c$i", i, buildJsonObject { put("i", i) }, null, ChunkStates.QUEUED, 0) })
        journal.attempt(AttemptRow("j1", "c0", 1, mesh.nodes.getValue("b").nodeId, t0, t0 + 4000, AttemptOutcome.OK, h("zero")))
        journal.output(OutputRow("j1", "c0", h("zero"), out("zero"), OutputRow.nodeSource(mesh.nodes.getValue("b").nodeId)))
        journal.chunk(ChunkRow("j1", "c0", 0, buildJsonObject { put("i", 0) }, null, ChunkStates.DONE, 1))
        journal.attempt(AttemptRow("j1", "c1", 1, mesh.nodes.getValue("b").nodeId, t0 + 1, t0 + 4001, AttemptOutcome.SENT))
        journal.chunk(ChunkRow("j1", "c1", 1, buildJsonObject { put("i", 1) }, null, ChunkStates.RUNNING, 1))
        journal.placement(PlacementRow("j1", mesh.nodes.getValue("b").nodeId, "ocr", "ocr-1", "sha-1", PlacementState.READY, 500))
        journal.placement(PlacementRow("j1", mesh.nodes.getValue("c").nodeId, "llm", "llm-1", "sha-2", PlacementState.READY, 900))
    }

    @Test fun finishedChunksAreReclaimedFromPeersAndNothingReruns() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b"); val c = m.node("c")
        m.start()
        a.journalJob(m.now(), listOf(b.nodeId, c.nodeId))
        b.memo.put("c1", h("one"), out("one"))
        c.memo.put("c2", h("two"), out("two"))
        c.memo.put("c0", h("zero"), out("zero"))          // also held, but c0 is final from the journal and is not asked for

        val plan = a.coordinator.resume(a.journal.load("j1")!!)
        advanceUntilIdle()

        assertEquals(listOf("c0", "c1", "c2"), plan.prefilled.keys.toList())
        assertEquals(mapOf("c0" to out("zero"), "c1" to out("one"), "c2" to out("two")), plan.prefilled)
        assertEquals(listOf("c3"), plan.missing)
        assertEquals(setOf(b.nodeId, c.nodeId), plan.respondents)
        assertEquals(emptySet<String>(), plan.unreachable)
        assertEquals(emptySet<String>(), plan.lostCapabilities)
        assertEquals(2, plan.reclaimed)
        assertEquals(mapOf("c0" to "node:${b.nodeId}", "c1" to "node:${b.nodeId}", "c2" to "node:${c.nodeId}"), plan.sources)
        assertTrue(!plan.complete)
        // Nothing was computed: the fetches were answered from the memos.
        assertEquals(0, b.executor.runs); assertEquals(0, c.executor.runs)
        assertEquals(1, b.runner.hits); assertEquals(1, c.runner.hits)
        assertEquals(1, b.responder.answered); assertEquals(1, c.responder.answered)
        // The RECLAIM asked only for the open chunks; the fetch TASK carried the chunkId and memoOnly.
        val reclaim = b.received.single { it.type == MsgType.RECLAIM }
        assertEquals(listOf("c1", "c2", "c3"), com.hivecore.mesh.MeshJson.decode<ReclaimBody>(reclaim.body).chunkIds)
        val fetch = com.hivecore.mesh.MeshJson.decode<com.hivecore.mesh.TaskBody>(b.received.single { it.type == MsgType.TASK }.body)
        assertEquals("j1#1", fetch.planId); assertEquals(a.nodeId, fetch.commander)
        assertEquals("c1", MemoizingTaskRunner.chunkIdOf(fetch.task)); assertTrue(MemoizingTaskRunner.memoOnly(fetch.task))
        assertEquals(com.hivecore.core.Op.OCR, fetch.task.op); assertEquals("ocr-1", fetch.task.modelId)
        // Write-ahead: the journal holds the reclaimed outputs, a `reclaimed` attempt each, and the chunks are done.
        val s = a.journal.load("j1")!!
        assertEquals(listOf(ChunkStates.DONE, ChunkStates.DONE, ChunkStates.DONE, ChunkStates.QUEUED), s.chunks.map { it.state })
        assertEquals(listOf("c0", "c1", "c2"), s.outputs.map { it.chunkId })
        assertEquals(listOf("node:${b.nodeId}", "node:${c.nodeId}"), s.outputs.drop(1).map { it.source })
        val reclaimedAttempts = s.attempts.filter { it.outcome == AttemptOutcome.RECLAIMED }
        assertEquals(listOf("c1" to 2, "c2" to 1), reclaimedAttempts.map { it.chunkId to it.attempt })
        assertEquals(listOf(b.nodeId, c.nodeId), reclaimedAttempts.map { it.nodeId })
        m.close()
    }

    @Test fun aSilentPhoneLosesItsCapabilityAndItsChunksAreRequeued() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b"); val c = m.node("c")
        m.start()
        a.journalJob(m.now(), listOf(b.nodeId, c.nodeId))
        b.memo.put("c1", h("one"), out("one"))
        c.memo.put("c2", h("two"), out("two"))
        m.bus.setOffline("c", true)

        val plan = a.coordinator.resume(a.journal.load("j1")!!)
        advanceUntilIdle()

        assertEquals(listOf("c0", "c1"), plan.prefilled.keys.toList())
        assertEquals(listOf("c2", "c3"), plan.missing)
        assertEquals(setOf(b.nodeId), plan.respondents)
        assertEquals(setOf(c.nodeId), plan.unreachable)
        assertEquals(setOf("llm"), plan.lostCapabilities)     // ocr is still served by B, which answered
        assertEquals(1, plan.reclaimed)
        m.close()
    }

    @Test fun ownMemoAndCorruptOrMissingPeerCopiesAreHandled() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b"); val c = m.node("c")
        m.start()
        a.journalJob(m.now(), listOf(b.nodeId, c.nodeId))
        // A finished c3 itself and crashed before journaling it: its own memo prefills without any message.
        a.memo.put("c3", h("three"), out("three"))
        // B's memo entry for c1 no longer matches its hash: never offered, never served.
        b.memo.put("c1", "0".repeat(64), out("one"))
        // C claims c2 outside its memo with a hash it cannot serve (a deputy row gone stale): the fetch fails and the
        // chunk is requeued instead of being trusted.
        c.responder.extra = { _, ids -> ids.filter { it == "c2" }.map { ReclaimFound(it, h("two")) } }

        val plan = a.coordinator.resume(a.journal.load("j1")!!)
        advanceUntilIdle()

        assertEquals(listOf("c0", "c3"), plan.prefilled.keys.toList())
        assertEquals(listOf("c1", "c2"), plan.missing)
        assertEquals(setOf(b.nodeId, c.nodeId), plan.respondents)
        assertEquals(0, plan.reclaimed)
        assertEquals(0, a.executor.runs + b.executor.runs + c.executor.runs)
        val s = a.journal.load("j1")!!
        assertEquals(AttemptOutcome.MEMO, s.attempts.single { it.chunkId == "c3" }.outcome)
        assertEquals(a.nodeId, s.attempts.single { it.chunkId == "c3" }.nodeId)
        assertEquals("node:${a.nodeId}", s.outputs.single { it.chunkId == "c3" }.source)
        m.close()
    }

    @Test fun aWrongHashFromAPeerIsRejectedAndTheNextHolderIsTried() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b"); val c = m.node("c")
        m.start()
        a.journalJob(m.now(), listOf(b.nodeId, c.nodeId))
        // B serves bytes that do not hash to what it claimed (its memo is bypassed through a stale extra lookup);
        // C holds the real thing. The first holder fails verification, the second round takes C's copy.
        b.responder.extra = { _, ids -> ids.filter { it == "c2" }.map { ReclaimFound(it, h("two")) } }
        b.runner.fallback = { id -> if (id == "c2") MemoHit("c2", h("two"), out("not two"), 0) else null }
        c.memo.put("c2", h("two"), out("two"))

        val plan = a.coordinator.resume(a.journal.load("j1")!!)
        advanceUntilIdle()

        assertEquals(listOf("c0", "c2"), plan.prefilled.keys.toList())
        assertEquals(out("two"), plan.prefilled["c2"])
        assertEquals("node:${c.nodeId}", plan.sources["c2"])
        assertEquals(1, plan.reclaimed)
        assertEquals(2, b.received.count { it.type == MsgType.TASK } + c.received.count { it.type == MsgType.TASK })
        m.close()
    }

    @Test fun theResponderHoldsTheSenderRule() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b")
        m.start()
        b.memo.put("c1", h("one"), out("one"))
        assertNull(b.responder.answer(ReclaimBody("p", listOf("c1"), a.nodeId, m.now(), "n1"), "someone-else"))
        val res = b.responder.answer(ReclaimBody("p", listOf("c1", "c2", "c1"), a.nodeId, m.now(), "n2"), a.nodeId)!!
        assertEquals("p", res.planId); assertEquals(b.nodeId, res.node)
        assertEquals(listOf(ReclaimFound("c1", h("one"))), res.found)
        // An empty answer still goes back, so the orchestrator knows the phone is alive.
        assertEquals(emptyList<ReclaimFound>(), b.responder.answer(ReclaimBody("p", listOf("c9"), a.nodeId, m.now(), "n3"), a.nodeId)!!.found)
        m.close()
    }
}
