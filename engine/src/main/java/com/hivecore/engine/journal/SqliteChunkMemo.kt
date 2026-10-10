package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The persistent chunk memo on every phone (§13.2, constants §20.2):
 * `chunk_memo(chunkId PK, outputHash, outputJson, ts, bytes, used)`.
 *
 * - **Retention**: an entry older than [retentionMs] (24 h, counted from when it was written) is gone: [get] drops it
 *   and every [put] purges expired rows.
 * - **Cap**: the sum of `bytes` (UTF-8 length of the canonical output) stays under [capBytes] (2 GB); [put] evicts the
 *   least recently used entries first. [get] and [holding] count as a use (`used` is a monotonic use counter, so the
 *   order is exact even when the clock does not move).
 * - Covers own and peers' chunk outputs. Cloud outputs are stored by their callers under [cloudKey]
 *   (`cloud:<slug>:<chunkId>`); to the memo that is just another key, so a bare chunkId never answers from a cloud result.
 */
class SqliteChunkMemo(
    private val db: SqlDb,
    private val clock: () -> Long = System::currentTimeMillis,
    val retentionMs: Long = RETENTION_MS,
    val capBytes: Long = CAP_BYTES,
) : ChunkMemo {

    init {
        db.exec("""CREATE TABLE IF NOT EXISTS chunk_memo(
            chunkId TEXT PRIMARY KEY, outputHash TEXT NOT NULL, outputJson TEXT NOT NULL,
            ts INTEGER NOT NULL, bytes INTEGER NOT NULL, used INTEGER NOT NULL)""")
        db.exec("CREATE INDEX IF NOT EXISTS chunk_memo_used ON chunk_memo(used)")
    }

    @Synchronized
    override fun get(chunkId: String): MemoHit? {
        val r = db.query("SELECT * FROM chunk_memo WHERE chunkId = ?", listOf(chunkId)).firstOrNull() ?: return null
        val ts = (r["ts"] as Number).toLong()
        if (expired(ts)) { db.exec("DELETE FROM chunk_memo WHERE chunkId = ?", listOf(chunkId)); return null }
        val json = try { JSON.parseToJsonElement(r["outputJson"] as String) } catch (e: Exception) {
            SLog.w(TAG, "memo ${chunkId.take(8)} unreadable; dropped"); db.exec("DELETE FROM chunk_memo WHERE chunkId = ?", listOf(chunkId)); return null
        }
        db.exec("UPDATE chunk_memo SET used = ? WHERE chunkId = ?", listOf(nextUse(), chunkId))
        return MemoHit(chunkId, r["outputHash"] as String, json, ts)
    }

    @Synchronized
    override fun put(chunkId: String, hash: String, json: JsonElement) {
        val text = json.toString()
        val bytes = Canon.canonical(json).toByteArray(Charsets.UTF_8).size.toLong()
        db.tx {
            db.exec("INSERT OR REPLACE INTO chunk_memo(chunkId, outputHash, outputJson, ts, bytes, used) VALUES (?,?,?,?,?,?)",
                listOf(chunkId, hash, text, clock(), bytes, nextUse()))
            db.exec("DELETE FROM chunk_memo WHERE ts < ?", listOf(clock() - retentionMs))
            evict()
        }
    }

    @Synchronized
    override fun holding(chunkIds: Collection<String>): List<MemoHit> = chunkIds.distinct().mapNotNull { get(it) }

    /** Number of entries and their total bytes (for the Jobs screen and tests). */
    @Synchronized
    fun stats(): Pair<Int, Long> {
        val r = db.query("SELECT COUNT(*) AS n, COALESCE(SUM(bytes), 0) AS b FROM chunk_memo").first()
        return (r["n"] as Number).toInt() to (r["b"] as Number).toLong()
    }

    /** Drops every entry older than the retention (also done on every [put]). */
    @Synchronized
    fun purgeExpired() { db.exec("DELETE FROM chunk_memo WHERE ts < ?", listOf(clock() - retentionMs)) }

    private fun expired(ts: Long) = ts < clock() - retentionMs

    private fun evict() {
        var total = (db.query("SELECT COALESCE(SUM(bytes), 0) AS b FROM chunk_memo").first()["b"] as Number).toLong()
        if (total <= capBytes) return
        for (r in db.query("SELECT chunkId, bytes FROM chunk_memo ORDER BY used")) {
            if (total <= capBytes) break
            db.exec("DELETE FROM chunk_memo WHERE chunkId = ?", listOf(r["chunkId"]))
            total -= (r["bytes"] as Number).toLong()
        }
    }

    private fun nextUse(): Long =
        ((db.query("SELECT COALESCE(MAX(used), 0) AS u FROM chunk_memo").first()["u"] as Number).toLong()) + 1

    companion object {
        private const val TAG = "ChunkMemo"
        /** §20.2: chunk memo retention. */
        const val RETENTION_MS = 24L * 60 * 60 * 1000
        /** §20.2: chunk memo cap (LRU beyond it). */
        const val CAP_BYTES = 2L * 1024 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The memo key of a cloud output (§13.2): never the bare chunkId. */
        fun cloudKey(slug: String, chunkId: String): String = "cloud:$slug:$chunkId"
    }
}
