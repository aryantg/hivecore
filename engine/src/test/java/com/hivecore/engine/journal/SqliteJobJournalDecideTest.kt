package com.hivecore.engine.journal

import com.hivecore.core.JdbcSqlDb
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `decisions` table (docs/plans/decision-chunking.md §7): round trip, streaming, import, forward migration. */
class SqliteJobJournalDecideTest {
    private val t0 = 1_700_000_000_000L
    private fun job(id: String) = JobRow(id, "$id#1", "ph", "recipe", JobState.RUNNING, t0, t0, "public")
    private fun row(jobId: String, q: String, p: Double) = DecisionRow(jobId, "A.boundary", q, "r".repeat(64),
        buildJsonObject { put("type", "noul"); put("probability", p) }, buildJsonArray { add(JsonPrimitive(p)) }, "s".repeat(64), "cal-1+t1", 3, t0)

    @Test fun decisionsRoundTripStreamAndImport() {
        val db = JdbcSqlDb.inMemory()
        val j = SqliteJobJournal(db)
        j.begin(job("j1"))
        j.decisions(listOf(row("j1", "b0", 0.1), row("j1", "b1", 0.9)))
        j.chunks(listOf(ChunkRow("j1", "c0", 0, buildJsonObject { put("units", 3) }, null, ChunkStates.QUEUED, 0, units = 3, replicas = 2, build = "large")))
        val s = j.load("j1")!!
        assertEquals(listOf(row("j1", "b0", 0.1), row("j1", "b1", 0.9)), s.decisions)
        assertEquals(2, s.chunks.single().replicas); assertEquals("large", s.chunks.single().build)
        // the deputy stream carries the rows in sequence order, under their table name
        val (hw, rows) = j.rowsAfter("j1", 0)
        assertEquals(listOf("jobs", "decisions", "decisions", "chunks"), rows.map { JournalRows.tableOf(it) })
        assertEquals(4L, hw)
        assertEquals(hw, j.highWater("j1"))
        // a second copy imports them once
        val copy = SqliteJobJournal(JdbcSqlDb.inMemory())
        assertEquals(4, copy.importRows(rows))
        assertEquals(0, copy.importRows(rows))
        assertEquals(s.decisions, copy.load("j1")!!.decisions)
        assertEquals(s.chunks, copy.load("j1")!!.chunks)
    }

    @Test fun anOlderDatabaseGainsTheTableAndColumns() {
        val db = JdbcSqlDb.inMemory()
        db.exec("CREATE TABLE chunks(jobId TEXT NOT NULL, chunkId TEXT NOT NULL, \"index\" INTEGER NOT NULL, params TEXT, owner TEXT, state TEXT NOT NULL, attempts INTEGER NOT NULL, recutOf TEXT, units INTEGER NOT NULL DEFAULT 1, seq INTEGER NOT NULL, PRIMARY KEY (jobId, chunkId))")
        db.exec("INSERT INTO chunks(jobId, chunkId, \"index\", params, state, attempts, seq) VALUES ('old', 'c0', 0, '{}', 'done', 1, 1)")
        val j = SqliteJobJournal(db)
        val cols = db.query("PRAGMA table_info(chunks)").map { it["name"] as String }
        assertTrue(cols.containsAll(listOf("replicas", "build")))
        assertTrue(db.query("PRAGMA table_info(decisions)").isNotEmpty())
        j.begin(job("old"))
        val s = j.load("old")!!
        assertEquals(null, s.chunks.single().replicas)
        assertTrue(s.decisions.isEmpty())
    }
}
