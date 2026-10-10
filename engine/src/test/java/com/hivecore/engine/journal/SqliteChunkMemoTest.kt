package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.JdbcSqlDb
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SqliteChunkMemoTest {
    private var clock = 1_700_000_000_000L
    private val db = JdbcSqlDb.inMemory()

    private fun out(s: String) = buildJsonObject { put("text", s) }
    private fun put(memo: SqliteChunkMemo, id: String, s: String) = memo.put(id, Canon.hash(out(s)), out(s))

    @Test fun storesAndAnswersByChunkId() {
        val memo = SqliteChunkMemo(db, { clock })
        put(memo, "c1", "one")
        val hit = memo.get("c1")!!
        assertEquals("c1", hit.chunkId); assertEquals(Canon.hash(out("one")), hit.outputHash); assertEquals(out("one"), hit.outputJson); assertEquals(clock, hit.ts)
        assertNull(memo.get("c2"))
        put(memo, "c2", "two")
        assertEquals(listOf("c1", "c2"), memo.holding(listOf("c1", "c2", "c3", "c1")).map { it.chunkId })
        // A later put for the same id replaces the entry.
        clock += 10
        put(memo, "c1", "uno")
        assertEquals(out("uno"), memo.get("c1")!!.outputJson)
        assertEquals(2 to (Canon.canonical(out("uno")).length + Canon.canonical(out("two")).length).toLong(), memo.stats())
        // A second memo over the same database sees the rows (the memo is persistent).
        assertNotNull(SqliteChunkMemo(db, { clock }).get("c2"))
    }

    @Test fun entriesExpireAfterTwentyFourHours() {
        val memo = SqliteChunkMemo(db, { clock })
        put(memo, "c1", "one")
        clock += SqliteChunkMemo.RETENTION_MS - 1
        assertNotNull(memo.get("c1"))
        clock += 2
        assertNull(memo.get("c1"))                       // gone on read
        assertEquals(0 to 0L, memo.stats())
        put(memo, "c2", "two")
        clock += SqliteChunkMemo.RETENTION_MS + 1
        put(memo, "c3", "three")                         // a put purges expired rows too
        assertEquals(1, memo.stats().first)
        assertEquals(emptyList<MemoHit>(), memo.holding(listOf("c2")))
        clock += SqliteChunkMemo.RETENTION_MS + 1
        memo.purgeExpired()
        assertEquals(0, memo.stats().first)
    }

    @Test fun evictsLeastRecentlyUsedBeyondTheCap() {
        val one = Canon.canonical(out("aaaa")).toByteArray().size.toLong()
        val memo = SqliteChunkMemo(db, { clock }, capBytes = one * 3)
        put(memo, "c1", "aaaa"); put(memo, "c2", "bbbb"); put(memo, "c3", "cccc")
        assertEquals(3, memo.stats().first)
        memo.get("c1")                                   // c1 is now the most recently used; c2 the least
        put(memo, "c4", "dddd")
        assertNull(memo.get("c2"))
        assertNotNull(memo.get("c1")); assertNotNull(memo.get("c3")); assertNotNull(memo.get("c4"))
        assertEquals(3, memo.stats().first)
        // Reading counts as a use even when the clock does not move: c3 was read last, so c1 goes next.
        memo.get("c4"); memo.get("c3")
        put(memo, "c5", "eeee")
        assertNull(memo.get("c1"))
        // An entry larger than the cap is written and then evicted with everything else below it.
        memo.put("big", "h", JsonPrimitive("x".repeat(200)))
        assertNull(memo.get("big"))
        assertEquals(memo.stats().second <= memo.capBytes, true)
    }

    @Test fun cloudOutputsNeverAnswerTheBareChunkId() {
        val memo = SqliteChunkMemo(db, { clock })
        val key = SqliteChunkMemo.cloudKey("openai/gpt-5-mini", "c1")
        assertEquals("cloud:openai/gpt-5-mini:c1", key)
        memo.put(key, Canon.hash(out("cloud")), out("cloud"))
        assertNull(memo.get("c1"))
        assertEquals(out("cloud"), memo.get(key)!!.outputJson)
        assertEquals(emptyList<MemoHit>(), memo.holding(listOf("c1")))
    }

    @Test fun unreadableRowsAreDropped() {
        val memo = SqliteChunkMemo(db, { clock })
        db.exec("INSERT INTO chunk_memo(chunkId, outputHash, outputJson, ts, bytes, used) VALUES ('bad', 'h', '{not json', ?, 1, 1)", listOf(clock))
        assertNull(memo.get("bad"))
        assertEquals(0, memo.stats().first)
    }
}
