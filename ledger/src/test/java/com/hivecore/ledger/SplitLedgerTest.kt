package com.hivecore.ledger

import com.hivecore.core.Canon
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.MergeKind
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.engine.journal.AttemptOutcome
import com.hivecore.engine.journal.AttemptRow
import com.hivecore.engine.journal.ChunkRow
import com.hivecore.engine.journal.ChunkStates
import com.hivecore.engine.journal.EscalationRow
import com.hivecore.engine.journal.JobMode
import com.hivecore.engine.journal.JobRow
import com.hivecore.engine.journal.JobSnapshot
import com.hivecore.engine.journal.JobState
import com.hivecore.engine.journal.OutputRow
import com.hivecore.engine.journal.PlacementRow
import com.hivecore.engine.journal.PlacementState
import com.hivecore.engine.journal.SqliteJobJournal
import com.hivecore.engine.split.Mergers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLedgerTest {
    private var clock = 1_700_000_000_000L
    private val db = JdbcSqlDb.inMemory()
    private val signer = FakeSigner()
    private val ledger = Ledger(db, signer) { clock }
    private val self = "orch"

    @After fun tearDown() = db.close()

    private fun out(vararg words: String): JsonElement = JsonArray(words.map { JsonPrimitive(it) })
    private fun h(e: JsonElement) = Canon.hash(e)

    /**
     * An OCR job over four chunks: c0 ran on B; c1 ran on B and was duplicated on C (both agreed); c2 was a memo hit;
     * c3 failed twice locally and ran in the cloud (an escalation with cost). Chunks are journaled through the real
     * SQLite journal so the entries are built from a loaded snapshot.
     */
    private fun journaled(): Pair<JobSnapshot, Map<String, JsonElement>> {
        val j = SqliteJobJournal(db)
        val outs = mapOf("c0" to out("a", "b"), "c1" to out("c"), "c2" to out("d", "e"), "c3" to out("f"))
        j.begin(JobRow("j1", "j1#1", "ph-1", "recipe", JobState.RUNNING, clock, clock, "public",
            capsSnapshot = buildJsonObject { put("eligible", buildJsonArray { add(JsonPrimitive("b")); add(JsonPrimitive("c")) }) },
            spec = buildJsonObject { put("op", "OCR"); put("merge", "CONCAT"); put("params", buildJsonObject { put("lang", "en") }) }))
        j.chunks((0 until 4).map { i -> ChunkRow("j1", "c$i", i, buildJsonObject { put("i", i); put("bytes", 100L * (i + 1)) }, null, ChunkStates.QUEUED, 0, units = 5) })
        j.placement(PlacementRow("j1", "b", "ocr", "ocr-1", "sha-1", PlacementState.READY, 400))
        j.placement(PlacementRow("j1", "c", "ocr", "ocr-1", "sha-1", PlacementState.READY, 700))
        j.attempt(AttemptRow("j1", "c0", 1, "b", clock, clock + 4000, AttemptOutcome.OK, h(outs["c0"]!!)))
        j.output(OutputRow("j1", "c0", h(outs["c0"]!!), outs["c0"]!!, OutputRow.nodeSource("b")))
        j.attempt(AttemptRow("j1", "c1", 1, "b", clock, clock + 4000, AttemptOutcome.OK, h(outs["c1"]!!)))
        j.attempt(AttemptRow("j1", "c1", 2, "c", clock + 10, clock + 4010, AttemptOutcome.DUPLICATE, h(outs["c1"]!!)))
        j.output(OutputRow("j1", "c1", h(outs["c1"]!!), outs["c1"]!!, OutputRow.nodeSource("b")))
        j.attempt(AttemptRow("j1", "c2", 1, self, clock, clock, AttemptOutcome.MEMO, h(outs["c2"]!!)))
        j.output(OutputRow("j1", "c2", h(outs["c2"]!!), outs["c2"]!!, OutputRow.nodeSource(self)))
        j.attempt(AttemptRow("j1", "c3", 1, "b", clock, clock + 4000, AttemptOutcome.ERROR, errorClass = "schema"))
        j.attempt(AttemptRow("j1", "c3", 2, "c", clock, clock + 4000, AttemptOutcome.ERROR, errorClass = "schema"))
        j.escalation(EscalationRow("j1", "c3", "verification", "", 0, 0, 0, 0.0, clock, "refused:spend cap"))
        j.escalation(EscalationRow("j1", "c3", "verification", "openai/gpt-5-mini", 2048, 500, 120, 0.42, clock + 1))
        j.output(OutputRow("j1", "c3", h(outs["c3"]!!), outs["c3"]!!, OutputRow.cloudSource("openai/gpt-5-mini")))
        for (i in 0 until 4) j.chunk(ChunkRow("j1", "c$i", i, buildJsonObject { put("i", i); put("bytes", 100L * (i + 1)) }, null, ChunkStates.DONE, if (i == 1 || i == 3) 2 else 1, units = 5))
        j.job(j.load("j1")!!.job.copy(state = JobState.DONE, updatedTs = clock + 5000))
        return j.load("j1")!! to outs
    }

    @Test fun planAndTaskEntriesCarryWhatTheSpecLists() = runTest {
        val (snap, outs) = journaled()
        val merged = Mergers.merge(MergeKind.CONCAT, snap.chunks.map { outs.getValue(it.chunkId) }, emptyList(), buildJsonObject { put("lang", "en") })
        val merge = MergeOutcome(MergeKind.CONCAT, h(merged))

        val plan = SplitLedger.planEntry(snap, merge, self)
        assertEquals("ph-1", plan.planHash); assertEquals("recipe", plan.plannerTier); assertEquals(JobMode.LOCAL, plan.mode)
        assertEquals(mapOf("b" to listOf(AssignedModel("ocr", "ocr-1", "sha-1", "ready", 400)), "c" to listOf(AssignedModel("ocr", "ocr-1", "sha-1", "ready", 700))), plan.assignment)
        assertEquals(snap.job.capsSnapshot, plan.profileSnapshot); assertEquals(snap.job.spec, plan.graph)
        assertEquals("CONCAT", plan.mergeKind); assertEquals(4, plan.chunks)
        assertEquals(listOf("c0", "c1", "c2", "c3"), plan.chunkOrder.map { it.chunkId })
        assertEquals(outs.values.map { h(it) }, plan.chunkOrder.map { it.outputHash })
        assertEquals(listOf("node:b", "node:b", "node:orch", "cloud:openai/gpt-5-mini"), plan.chunkOrder.map { it.source })
        assertEquals(h(merged), plan.resultHash)
        assertEquals(listOf("b", "orch", "c"), plan.executors)
        assertEquals(listOf("b", "c"), SplitLedger.executors(snap, self))
        assertEquals(2, plan.escalations); assertEquals(0.42, plan.costInr, 1e-9)
        assertEquals(JobState.DONE, plan.state); assertEquals(clock + 5000, plan.finishedTs)

        val tasks = SplitLedger.taskEntries(snap, self)
        assertEquals(listOf(0, 1, 2, 3), tasks.map { it.index })
        val (t0, t1, t2, t3) = tasks
        assertEquals(listOf(Executor("b", 1, "ok", h(outs["c0"]!!))), t0.executors)
        assertEquals(listOf(self), t0.verifiedBy); assertFalse(t0.memoHit); assertEquals(0, t0.duplicates); assertEquals(100L, t0.bytes); assertEquals(5, t0.units)
        assertEquals(listOf("b", "c"), t1.executors.map { it.node })
        assertEquals(listOf(self, "c"), t1.verifiedBy); assertEquals(1, t1.duplicates); assertEquals("node:b", t1.source)
        assertTrue(t2.memoHit); assertEquals(listOf(Executor(self, 1, "memo", h(outs["c2"]!!))), t2.executors)
        assertEquals("openai/gpt-5-mini", t3.cloudSlug); assertEquals("cloud:openai/gpt-5-mini", t3.source)
        assertEquals(500, t3.tokensIn); assertEquals(120, t3.tokensOut); assertEquals(0.42, t3.costInr, 1e-9)
        assertEquals(listOf("schema", "schema"), t3.executors.map { it.errorClass })
        assertEquals(listOf(self), t3.verifiedBy); assertEquals(400L, t3.bytes)
        assertNull(t0.cloudSlug); assertEquals(0, t0.tokensIn)
        // What B records for itself: every chunk it attempted, the failed c3 included.
        assertEquals(listOf("c0", "c1", "c3"), SplitLedger.taskEntriesFor(snap, "b").map { it.chunkId })
        assertEquals(listOf("c1", "c3"), SplitLedger.taskEntriesFor(snap, "c").map { it.chunkId })
        assertEquals(listOf("c2"), SplitLedger.taskEntriesFor(snap, self).map { it.chunkId })

        // Entries are real JSON on the chain and come back typed.
        val (planEntry, taskEntries) = SplitLedger.record(ledger, snap, merge, self)
        assertEquals(SplitLedger.KIND_PLAN, planEntry.kind); assertEquals(4, taskEntries.size)
        assertEquals(plan, SplitLedger.planOf(planEntry)); assertEquals(tasks, taskEntries.map { SplitLedger.taskOf(it) })
        assertEquals(listOf("task", "task", "task", "task", "plan"), ledger.entriesFor("j1").map { it.kind })
        assertEquals(1, ledger.entriesOfKind("plan").size)
        assertTrue(ledger.verifyChain())
    }

    @Test fun theAnswerReplaysFromTheEntriesAndTamperingShows() = runTest {
        val (snap, outs) = journaled()
        val params = buildJsonObject { put("lang", "en") }
        val merged = Mergers.merge(MergeKind.CONCAT, snap.chunks.map { outs.getValue(it.chunkId) }, emptyList(), params)
        assertEquals(out("a", "b", "c", "d", "e", "f"), merged)
        val (planEntry, taskEntries) = SplitLedger.record(ledger, snap, MergeOutcome(MergeKind.CONCAT, h(merged)), self)
        // Round-trip through the chain: a reader with only the ledger and the stored outputs rebuilds the answer hash.
        val plan = SplitLedger.planOf(ledger.entriesFor("j1").last())
        val tasks = ledger.entriesFor("j1").filter { it.kind == "task" }.map { SplitLedger.taskOf(it) }
        val stored = snap.outputs.associate { it.chunkId to it.outputJson }
        assertEquals(h(merged), SplitLedger.replay(plan, tasks, stored::get).getOrThrow())
        assertEquals(plan.resultHash, SplitLedger.replay(plan, emptyList(), stored::get).getOrThrow())   // the plan's chunk refs suffice
        // An altered output, a missing output, a reordered plan: each is caught.
        val altered = stored + ("c1" to out("x"))
        assertTrue(SplitLedger.replay(plan, tasks, altered::get).exceptionOrNull()!!.message!!.contains("chunk 1"))
        assertTrue(SplitLedger.replay(plan, tasks) { if (it == "c2") null else stored[it] }.exceptionOrNull()!!.message!!.contains("missing"))
        val reordered = plan.copy(chunkOrder = plan.chunkOrder.reversed())
        assertTrue(SplitLedger.replay(reordered, tasks, stored::get).getOrThrow() != plan.resultHash)
        assertTrue(SplitLedger.replay(plan.copy(mergeKind = null), tasks, stored::get).isFailure)
        // The same job on one phone or three gives the same bytes: the replay does not depend on who ran what.
        val oneNode = snap.copy(attempts = snap.attempts.map { it.copy(nodeId = self) }, outputs = snap.outputs.map { it.copy(source = OutputRow.nodeSource(self)) })
        assertEquals(plan.resultHash, SplitLedger.replay(SplitLedger.planEntry(oneNode, MergeOutcome(MergeKind.CONCAT, h(merged)), self), emptyList(), stored::get).getOrThrow())
        assertEquals(4, taskEntries.size); assertEquals("plan", planEntry.kind)
    }

    @Test fun exportIsTheWholeChainAsOneDocumentAndVerifies() = runTest {
        val (snap, outs) = journaled()
        val merged = Mergers.merge(MergeKind.CONCAT, snap.chunks.map { outs.getValue(it.chunkId) }, emptyList(), buildJsonObject {})
        SplitLedger.record(ledger, snap, MergeOutcome(MergeKind.CONCAT, h(merged)), self)
        ledger.append("job-ask", "ask", """{"q":"Q1"}""")
        val peer = FakeSigner()
        ledger.peerKey = { if (it == "peer") peer.publicKeyB64 else null }
        val peerHash = "ab".repeat(32)
        ledger.countersign("job-ask", "peer", peerHash, java.util.Base64.getEncoder().encodeToString(peer.sign(peerHash.toByteArray())))

        val export = ledger.exportJson()
        assertEquals(signer.publicKeyB64, export["node"]!!.jsonPrimitive.content)
        assertEquals("true", export["verified"]!!.jsonPrimitive.content)
        assertEquals(6, export["count"]!!.jsonPrimitive.content.toInt())
        val entries = export["entries"]!!.jsonArray
        assertEquals(listOf("task", "task", "task", "task", "plan", "ask"), entries.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
        assertEquals("j1#1", entries[4].jsonObject["entry"]!!.jsonObject["planId"]!!.jsonPrimitive.content)   // parsed, not a string
        assertEquals("peer", entries[5].jsonObject["peerNode"]!!.jsonPrimitive.content)
        assertNull(entries[0].jsonObject["peerNode"])
        // A reader re-checks every link and hash from the export alone.
        var prev: String? = null
        for (e in entries.map { it.jsonObject }) {
            val entryHash = e["entryHash"]!!.jsonPrimitive.content
            val prevHash = e["prevHash"]!!.jsonPrimitive.content
            if (prev != null) assertEquals(prev, prevHash)
            val raw = ledger.entriesFor(e["jobId"]!!.jsonPrimitive.content).first { it.seq == e["seq"]!!.jsonPrimitive.content.toLong() }.entry
            assertEquals(entryHash, Ledger.hashOf(prevHash, e["jobId"]!!.jsonPrimitive.content, e["kind"]!!.jsonPrimitive.content, e["ts"]!!.jsonPrimitive.content.toLong(), raw))
            assertTrue(com.hivecore.core.Verify.verify(signer.publicKeyB64, entryHash.toByteArray(), java.util.Base64.getDecoder().decode(e["sig"]!!.jsonPrimitive.content)))
            prev = entryHash
        }
        assertEquals(2, ledger.exportJson(2)["count"]!!.jsonPrimitive.content.toInt())
        // Tampering with a task entry shows in the export.
        db.exec("UPDATE _receipts SET entry = entry || ' ' WHERE seq = 2")
        assertEquals("false", ledger.exportJson()["verified"]!!.jsonPrimitive.content)
    }
}
