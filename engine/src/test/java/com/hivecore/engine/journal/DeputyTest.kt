package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.Profile
import com.hivecore.mesh.JournalBody
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeputyTest {
    private fun out(s: String): JsonElement = buildJsonObject { put("text", s) }
    private fun h(s: String) = Canon.hash(out(s))

    @Test fun selectorPrefersLanThenUptime() {
        val lanOld = Profile("lan-old", 0, link = Link(LinkKind.LAN, 100_000, 5))
        val lanNew = Profile("lan-new", 0, link = Link(LinkKind.LAN, 100_000, 5))
        val unknown = Profile("unknown", 0)                                   // no measured link counts as LAN
        val wanOldest = Profile("wan", 0, link = Link(LinkKind.WAN_RELAY, 2_000, 120, metered = true))
        val p2p = Profile("p2p", 0, link = Link(LinkKind.P2P, 50_000, 10))
        val up = mapOf("lan-old" to 3_600_000L, "lan-new" to 60_000L, "unknown" to 10_000L, "wan" to 86_400_000L, "p2p" to 7_200_000L)
        assertEquals("lan-old", DeputySelector.pick(listOf(wanOldest, p2p, lanNew, lanOld, unknown), up, "self"))
        assertEquals("lan-new", DeputySelector.pick(listOf(wanOldest, p2p, lanNew, lanOld, unknown), up, "self", exclude = setOf("lan-old")))
        assertEquals("p2p", DeputySelector.pick(listOf(wanOldest, p2p), up, "self"))
        assertEquals("wan", DeputySelector.pick(listOf(wanOldest), up, "self"))
        assertNull(DeputySelector.pick(listOf(lanOld), up, "lan-old"))
        assertNull(DeputySelector.pick(emptyList(), up, "self"))
        // Equal uptime: nodeId breaks the tie so every phone picks the same deputy.
        assertEquals("a", DeputySelector.pick(listOf(Profile("b", 0), Profile("a", 0)), emptyMap(), "self"))
    }

    @Test fun batchesStayUnderTheEnvelope() {
        val rows = (0 until 20).map { JsonPrimitive("x".repeat(100)) as JsonElement }
        val batches = StreamingJournal.batches(rows, 350)
        assertTrue(batches.all { it.size == 3 || it == batches.last() })
        assertEquals(rows, batches.flatten())
        assertEquals(listOf(rows.take(1)), StreamingJournal.batches(rows.take(1), 10))   // a single large row travels alone
        assertEquals(emptyList<List<JsonElement>>(), StreamingJournal.batches(emptyList(), 10))
    }

    @Test fun theStreamReachesTheDeputyAndAReturningOrchestratorPullsWhatItLost() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b"); val c = m.node("c")
        val store = DeputyStore(b.db, b.port, b.memo, m.now)
        b.responder.extra = { commander, ids -> store.holding(commander, ids) }
        b.runner.fallback = { store.lookup(it) }
        store.start(b.scope)
        val pull = JournalPull(a.port, a.journal)
        pull.start(a.scope)
        m.start()
        var deputy: String? = b.nodeId
        val streaming = StreamingJournal(a.journal, a.nodeId, { deputy }, a.port::send, a.scope, maxBatchBytes = 600)

        val t0 = m.now()
        streaming.begin(JobRow("j1", "j1#1", "ph", "T1", JobState.RUNNING, t0, t0, "public",
            capsSnapshot = buildJsonObject { put("deputy", b.nodeId) }, spec = buildJsonObject { put("op", "OCR") }))
        for (i in 0 until 4) streaming.chunk(ChunkRow("j1", "c$i", i, buildJsonObject { put("i", i) }, null, ChunkStates.QUEUED, 0))
        streaming.attempt(AttemptRow("j1", "c0", 1, a.nodeId, t0, t0 + 4000, AttemptOutcome.OK, h("zero")))
        streaming.output(OutputRow("j1", "c0", h("zero"), out("zero"), OutputRow.nodeSource(a.nodeId)))
        streaming.chunk(ChunkRow("j1", "c0", 0, buildJsonObject { put("i", 0) }, null, ChunkStates.DONE, 1))
        advanceUntilIdle()

        // Every committed row reached the deputy (in several batches), newest sequence included; outputs are memoized there.
        assertTrue(streaming.batchesSent >= 2)
        assertEquals(a.journal.highWater("j1"), store.highWater(a.nodeId, "j1"))
        assertEquals(a.journal.rowsAfter("j1", 0).second, store.rowsFor(a.nodeId, "j1", 0))
        assertEquals(h("zero"), b.memo.get("c0")!!.outputHash)
        assertEquals(listOf("c0"), store.holding(a.nodeId, listOf("c0", "c1")).map { it.chunkId })
        assertEquals(out("zero"), store.lookup("c0")!!.outputJson)
        // A stale re-send does not roll a row back; a stream from a stranger naming A is ignored.
        val first = a.journal.rowsAfter("j1", 0).second.first()
        assertEquals(0, store.store(a.nodeId, JournalBody("j1", a.nodeId, 1, listOf(first))))
        store.onJournal(JournalBody("j1", a.nodeId, 1, listOf(first)), c.nodeId)

        // A loses its last rows (the write after the stream reached the deputy never hit the disk): its new journal
        // has only the first five rows (the jobs row, c1..c3 and the attempt; c0's row was replaced by its `done`
        // write, which took a later sequence). The pull brings back the rest, sequence numbers included.
        val lost = SqliteJobJournal(JdbcSqlDb.inMemory())
        lost.importRows(a.journal.rowsAfter("j1", 0).second.take(5))
        assertEquals(listOf("c1", "c2", "c3"), lost.load("j1")!!.chunks.map { it.chunkId })
        assertEquals(0, lost.load("j1")!!.outputs.size)
        val pull2 = JournalPull(a.port, lost)
        pull2.start(a.scope)
        val n = pull2.pull(b.nodeId, "j1")
        advanceUntilIdle()
        assertEquals(2, n)                                       // the output and c0 done
        assertEquals(a.journal.load("j1"), lost.load("j1"))
        assertEquals(a.journal.highWater("j1"), pull2.lastHighWater)
        // Nothing newer: the deputy answers with the end marker alone.
        assertEquals(0, pull2.pull(b.nodeId, "j1"))
        assertEquals(0, pull.pull(b.nodeId))                     // every job of A
        // A deputy that cannot be reached: null, and the resume goes on from the local journal.
        m.bus.setOffline("b", true)
        assertNull(pull2.pull(b.nodeId, "j1"))
        m.bus.setOffline("b", false)

        // Resume through the deputy: the JobResume facade pulls first, then RECLAIM reaches B as an extra holder
        // (the eligible set is empty in this journal), and B serves A's own output from the stream.
        val lost2 = SqliteJobJournal(JdbcSqlDb.inMemory())
        lost2.importRows(a.journal.rowsAfter("j1", 0).second.take(5))
        val coordinator = ResumeCoordinator(a.port, ChunkMemo.None, lost2, m.now)
        coordinator.start(a.scope)
        val pull3 = JournalPull(a.port, lost2).also { it.start(a.scope) }
        val plan = JobResume(lost2, coordinator, pull3).resume("j1")!!
        advanceUntilIdle()
        assertEquals(listOf("c0"), plan.prefilled.keys.toList())
        assertEquals(listOf("c1", "c2", "c3"), plan.missing)
        assertEquals(setOf(b.nodeId), plan.respondents)
        assertEquals(0, plan.reclaimed)                          // c0 came back with the pulled rows, not a fetch
        assertNull(JobResume(lost2, coordinator, pull3).resume("unknown"))

        // The deputy only stores and answers: no TASK, no merge, no escalation ever left B.
        assertEquals(0, b.executor.runs)
        assertTrue(a.received.none { it.type == MsgType.TASK })
        // Switching the deputy off stops the stream; switching to C streams the job from row 0.
        deputy = null
        streaming.chunk(ChunkRow("j1", "c1", 1, buildJsonObject { put("i", 1) }, null, ChunkStates.RUNNING, 1))
        advanceUntilIdle()
        assertTrue(c.received.none { it.type == MsgType.JOURNAL })
        val storeC = DeputyStore(c.db, c.port, c.memo, m.now).also { it.start(c.scope) }
        deputy = c.nodeId
        streaming.chunk(ChunkRow("j1", "c1", 1, buildJsonObject { put("i", 1) }, null, ChunkStates.DONE, 1))
        advanceUntilIdle()
        assertEquals(a.journal.highWater("j1"), storeC.highWater(a.nodeId, "j1"))
        assertEquals(out("zero"), c.memo.get("c0")!!.outputJson)
        streaming.close()
        m.close()
    }

    @Test fun aRowOverTheEnvelopeIsSkippedAndTheRowsAfterItStillStream() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b")
        val store = DeputyStore(b.db, b.port, b.memo, m.now)
        store.start(b.scope)
        m.start()
        val streaming = StreamingJournal(a.journal, a.nodeId, { b.nodeId }, a.port::send, a.scope, maxBatchBytes = 600)
        val t0 = m.now()
        streaming.begin(JobRow("j2", "j2#1", "ph", "T1", JobState.RUNNING, t0, t0, "public", spec = buildJsonObject { put("op", "OCR") }))
        streaming.chunk(ChunkRow("j2", "c0", 0, buildJsonObject { put("i", 0) }, null, ChunkStates.QUEUED, 0))
        // c0's output is far over the batch limit (a 30-photo OCR chunk on a phone): it can never travel
        streaming.output(OutputRow("j2", "c0", h("x".repeat(2_000)), out("x".repeat(2_000)), OutputRow.nodeSource(a.nodeId)))
        streaming.chunk(ChunkRow("j2", "c0", 0, buildJsonObject { put("i", 0) }, null, ChunkStates.DONE, 1))
        streaming.chunk(ChunkRow("j2", "c1", 1, buildJsonObject { put("i", 1) }, null, ChunkStates.QUEUED, 0))
        advanceUntilIdle()
        // the rows after it reached the deputy; only the big output row is missing there
        assertEquals(a.journal.highWater("j2"), store.highWater(a.nodeId, "j2"))
        assertEquals(a.journal.rowsAfter("j2", 0).second.size - 1, store.rowsFor(a.nodeId, "j2", 0).size)
        streaming.close()
        m.close()
    }

    @Test fun deputyRowsAreTheWireRowsOfTheJournal() = runTest {
        val m = JournalMesh(this)
        val a = m.node("a"); val b = m.node("b")
        val store = DeputyStore(b.db, b.port, null, m.now)
        m.start()
        val t0 = m.now()
        a.journal.begin(JobRow("j1", "j1#1", "ph", "T1", JobState.RUNNING, t0, t0, "public"))
        a.journal.escalation(EscalationRow("j1", "c0", "verification", "slug", 10, 2, 3, 0.5, t0))
        val (hw, rows) = a.journal.rowsAfter("j1", 0)
        assertEquals(2, store.store(a.nodeId, JournalBody("j1", a.nodeId, 1, rows)))
        assertEquals(hw, store.highWater(a.nodeId))
        assertEquals(rows, store.rowsFor(a.nodeId, null, 0))
        assertEquals(emptyList<JsonElement>(), store.rowsFor(a.nodeId, "j1", hw))
        assertNotNull(MeshJson.decode<JournalBody>(MeshJson.encode(JournalBody("j1", a.nodeId, 1, rows))))
        // Old streams are pruned by their last write.
        advanceTimeBy(1)
        store.prune(0)
        assertEquals(0L, store.highWater(a.nodeId))
        m.close()
    }
}
