package com.hivecore.engine.journal

import com.hivecore.core.JdbcSqlDb
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SqliteJobJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L
    private fun job(id: String, state: String = JobState.RUNNING, created: Long = t0, mode: String = JobMode.LOCAL) = JobRow(
        id, "$id#1", "ph-$id", "T1", state, created, created, "public",
        capsSnapshot = buildJsonObject { put("eligible", buildJsonArray { add(JsonPrimitive("b")); add(JsonPrimitive("c")) }) },
        spec = buildJsonObject { put("op", "OCR"); put("modelId", "ocr-1") }, mode = mode)
    private fun chunk(jobId: String, i: Int, state: String = ChunkStates.QUEUED, attempts: Int = 0) =
        ChunkRow(jobId, "c$i", i, buildJsonObject { put("from", i * 10); put("to", i * 10 + 10) }, null, state, attempts, units = 10)

    @Test fun roundTripsEveryTable() {
        val db = JdbcSqlDb.inMemory()
        val j = SqliteJobJournal(db)
        j.begin(job("j1"))
        j.chunks(listOf(chunk("j1", 0), chunk("j1", 1)))
        j.attempt(AttemptRow("j1", "c0", 1, "b", t0, t0 + 4000, AttemptOutcome.SENT))
        j.attempt(AttemptRow("j1", "c0", 1, "b", t0, t0 + 4000, AttemptOutcome.OK, "h0"))
        j.output(OutputRow("j1", "c0", "h0", buildJsonObject { put("text", "a") }, OutputRow.nodeSource("b")))
        j.chunk(chunk("j1", 0, ChunkStates.DONE, 1))
        j.placement(PlacementRow("j1", "b", "ocr", "ocr-1", "sha-1", PlacementState.REQUESTED))
        j.placement(PlacementRow("j1", "b", "ocr", "ocr-1", "sha-1", PlacementState.READY, 1200))
        j.escalation(EscalationRow("j1", "c1", "verification", "", 0, 0, 0, 0.0, t0 + 1, "refused:private lineage"))
        j.escalation(EscalationRow("j1", "c1", "verification", "openai/gpt-5-mini", 2048, 500, 120, 0.42, t0 + 2))

        val s = j.load("j1")!!
        assertEquals(job("j1"), s.job)
        assertEquals(listOf(chunk("j1", 0, ChunkStates.DONE, 1), chunk("j1", 1)), s.chunks)
        // Replace-by-key: the second attempt row replaced the "sent" row; placements likewise.
        assertEquals(listOf(AttemptRow("j1", "c0", 1, "b", t0, t0 + 4000, AttemptOutcome.OK, "h0")), s.attempts)
        assertEquals(listOf(PlacementRow("j1", "b", "ocr", "ocr-1", "sha-1", PlacementState.READY, 1200)), s.placements)
        // Append-only tables keep every row in write order.
        assertEquals(listOf("h0"), s.outputs.map { it.outputHash })
        assertEquals("node:b", s.outputs.single().source)
        assertEquals("a", s.outputs.single().outputJson.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("refused:private lineage", "ran"), s.escalations.map { it.outcome })
        assertEquals(0.42, s.escalations[1].costInr, 1e-9)
        assertEquals("c1", s.escalations[1].stepId)
        assertNull(j.load("nope"))

        // §13.1 columns exactly, under the spec's table names.
        fun cols(t: String) = db.query("PRAGMA table_info($t)").map { it["name"] as String }
        assertTrue(cols("jobs").containsAll(listOf("jobId", "planId", "planHash", "plannerTier", "mode", "state", "createdTs", "updatedTs", "privacy", "capsSnapshot")))
        assertTrue(cols("chunks").containsAll(listOf("jobId", "chunkId", "index", "params", "owner", "state", "attempts", "recutOf")))
        assertTrue(cols("attempts").containsAll(listOf("jobId", "chunkId", "attempt", "nodeId", "dispatchedTs", "deadlineTs", "outcome", "outputHash", "errorClass")))
        assertTrue(cols("outputs").containsAll(listOf("chunkId", "outputHash", "outputJson", "source")))
        assertTrue(cols("placements").containsAll(listOf("jobId", "nodeId", "capability", "modelId", "sha256", "state", "loadMs")))
        assertTrue(cols("escalations").containsAll(listOf("jobId", "stepId", "trigger", "slug", "bytesSent", "tokensIn", "tokensOut", "costInr", "ts")))
        assertEquals("local", db.query("SELECT mode FROM jobs").single()["mode"])
        db.close()
    }

    @Test fun resumableListsOnlyOpenJobsOldestFirst() {
        val j = SqliteJobJournal(JdbcSqlDb.inMemory())
        j.begin(job("running", JobState.RUNNING, t0 + 30))
        j.begin(job("placing", JobState.PLACING, t0 + 10))
        j.begin(job("merging", JobState.MERGING, t0 + 20, JobMode.CLOUD))
        j.begin(job("done", JobState.DONE, t0))
        j.begin(job("failed", JobState.FAILED, t0 + 1))
        j.begin(job("cancelled", JobState.CANCELLED, t0 + 2))
        j.begin(job("partial", JobState.PARTIAL, t0 + 3))
        assertEquals(listOf("placing", "merging", "running"), j.resumable().map { it.jobId })
        assertEquals(JobMode.CLOUD, j.resumable()[1].mode)
        // A state change replaces the row and drops it from the resume list.
        j.job(job("running", JobState.DONE, t0 + 30))
        assertEquals(listOf("placing", "merging"), j.resumable().map { it.jobId })
        assertEquals(7, j.jobs().size)
        assertEquals("running", j.jobs().first().jobId)   // newest first
    }

    @Test fun eachRowIsCommittedBeforeTheCallReturns() {
        // Two connections to one file: the second sees only committed rows, so a crash right after a call loses nothing.
        val file = File(tmp.newFolder("db"), "journal.db")
        val writer = JdbcSqlDb.open(file)
        val reader = JdbcSqlDb.open(file)
        val j = SqliteJobJournal(writer)
        j.begin(job("j1"))
        assertEquals(1L, reader.query("SELECT COUNT(*) AS n FROM jobs WHERE jobId = 'j1'").single()["n"])
        j.chunks(listOf(chunk("j1", 0), chunk("j1", 1), chunk("j1", 2)))
        assertEquals(3L, reader.query("SELECT COUNT(*) AS n FROM chunks").single()["n"])
        j.attempt(AttemptRow("j1", "c0", 1, "b", t0, t0 + 4000, AttemptOutcome.SENT))
        assertEquals("sent", reader.query("SELECT outcome FROM attempts").single()["outcome"])
        j.output(OutputRow("j1", "c0", "h0", JsonPrimitive(1), "node:b"))
        assertEquals(1L, reader.query("SELECT COUNT(*) AS n FROM outputs").single()["n"])
        writer.close(); reader.close()
    }

    @Test fun resumeStateSurvivesReopeningTheDatabaseFile() {
        val file = File(tmp.newFolder("db"), "journal.db")
        run {
            val db = JdbcSqlDb.open(file)
            val j = SqliteJobJournal(db)
            j.begin(job("j1", JobState.PLACING))
            j.placement(PlacementRow("j1", "b", "ocr", "ocr-1", "sha-1", PlacementState.READY, 900))
            j.job(job("j1", JobState.RUNNING))
            j.chunks(listOf(chunk("j1", 0), chunk("j1", 1), chunk("j1", 2)))
            j.attempt(AttemptRow("j1", "c0", 1, "b", t0, t0 + 4000, AttemptOutcome.SENT))
            j.attempt(AttemptRow("j1", "c0", 1, "b", t0, t0 + 4000, AttemptOutcome.OK, "h0"))
            j.output(OutputRow("j1", "c0", "h0", buildJsonObject { put("text", "a") }, "node:b"))
            j.chunk(chunk("j1", 0, ChunkStates.DONE, 1))
            j.chunk(chunk("j1", 1, ChunkStates.RUNNING, 1))
            j.attempt(AttemptRow("j1", "c1", 1, "c", t0 + 5, t0 + 4005, AttemptOutcome.SENT))
            // The job that finished is not offered for resume.
            j.begin(job("j0", JobState.DONE, t0 - 1))
            db.close()   // the app is force-stopped here
        }
        val db = JdbcSqlDb.open(file)
        val j = SqliteJobJournal(db)
        assertEquals(listOf("j1"), j.resumable().map { it.jobId })
        val s = j.load("j1")!!
        assertEquals(JobState.RUNNING, s.job.state)
        assertEquals(listOf(ChunkStates.DONE, ChunkStates.RUNNING, ChunkStates.QUEUED), s.chunks.map { it.state })
        assertEquals(listOf("ok", "sent"), s.attempts.map { it.outcome })
        assertEquals("h0", s.outputs.single().outputHash)
        assertEquals(PlacementState.READY, s.placements.single().state)
        assertEquals(listOf("b", "c"), ResumeCoordinator.eligibleOf(s))
        // Writes continue with the sequence where it left off.
        val before = j.highWater()
        j.chunk(chunk("j1", 1, ChunkStates.DONE, 1))
        assertEquals(before + 1, j.highWater())
        db.close()
    }

    @Test fun rowsAfterIsMonotonicAndImportKeepsSequences() {
        val a = SqliteJobJournal(JdbcSqlDb.inMemory())
        a.begin(job("j1"))
        a.chunks(listOf(chunk("j1", 0), chunk("j1", 1)))
        a.begin(job("j2"))
        val (hw1, rows1) = a.rowsAfter("j1", 0)
        assertEquals(3L, hw1)
        assertEquals(listOf(1L, 2L, 3L), rows1.map { JournalRows.seqOf(it) })
        assertEquals(listOf("jobs", "chunks", "chunks"), rows1.map { JournalRows.tableOf(it) })
        assertEquals("chunks|j1|c0", JournalRows.keyOf(rows1[1]))
        assertEquals(4L, a.highWater()); assertEquals(3L, a.highWater("j1")); assertEquals(4L, a.highWater("j2"))

        a.chunk(chunk("j1", 0, ChunkStates.DONE, 1))        // replace takes a new seq
        a.output(OutputRow("j1", "c0", "h0", JsonPrimitive(1), "node:b"))
        val (hw2, rows2) = a.rowsAfter("j1", hw1)
        assertEquals(6L, hw2)
        assertEquals(listOf(5L, 6L), rows2.map { JournalRows.seqOf(it) })
        assertEquals(listOf("chunks", "outputs"), rows2.map { JournalRows.tableOf(it) })
        assertEquals("j1", JournalRows.jobIdOf(rows2[1]))
        assertEquals(hw2 to emptyList<Any>(), a.rowsAfter("j1", hw2))

        // A second copy imports the rows and ends up identical, sequence numbers included.
        val b = SqliteJobJournal(JdbcSqlDb.inMemory())
        assertEquals(3, b.importRows(rows1))
        assertEquals(2, b.importRows(rows2))
        assertEquals(0, b.importRows(rows1))                 // older rows never overwrite newer ones
        assertEquals(a.load("j1"), b.load("j1"))
        assertEquals(6L, b.highWater("j1"))
        assertEquals(a.rowsAfterAll(0).second.filter { JournalRows.jobIdOf(it) == "j1" }, b.rowsAfterAll(0).second)
        assertEquals(0, b.importRows(listOf(JournalRows.endMarker(9), JsonPrimitive("junk"))))
    }

    @Test fun anOlderDatabaseGainsTheNewColumns() {
        val db = JdbcSqlDb.inMemory()
        db.exec("CREATE TABLE jobs(jobId TEXT NOT NULL, planId TEXT NOT NULL, planHash TEXT NOT NULL, plannerTier TEXT NOT NULL, state TEXT NOT NULL, createdTs INTEGER NOT NULL, updatedTs INTEGER NOT NULL, privacy TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (jobId))")
        db.exec("INSERT INTO jobs VALUES ('old', 'old#1', 'h', 'T0', 'running', 1, 1, 'public', 1)")
        val j = SqliteJobJournal(db)
        val old = j.load("old")
        assertNotNull(old)
        assertEquals(JobMode.LOCAL, old!!.job.mode)
        assertNull(old.job.spec)
        j.job(old.job.copy(mode = JobMode.CLOUD, state = JobState.DONE))
        assertEquals(JobMode.CLOUD, j.load("old")!!.job.mode)
    }
}
