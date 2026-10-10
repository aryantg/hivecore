package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import com.hivecore.mesh.JournalBody
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouterPort
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ReclaimFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * The deputy (§13.4): keeps the journal stream of other phones' jobs and serves it back. It never dispatches, merges
 * or escalates; it only stores rows and answers.
 *
 * JOURNAL forms (all share `JournalBody`; the router requires `commander == from`, so `commander` always names the
 * sender):
 * - **stream**: orchestrator -> deputy, `rows` non-empty, no end marker: stored per (commander, row key), a row
 *   replacing the held one only when its sequence is newer. `outputs` rows also go into this phone's chunk memo
 *   (after their hash checks), so the deputy answers RECLAIM and the memo-only fetch TASK for the orchestrator's own
 *   chunks through the ordinary [ReclaimResponder] / [MemoizingTaskRunner] path.
 * - **pull request**: returning orchestrator -> deputy, `rows` empty, `fromRow` = the orchestrator's high-water mark,
 *   `jobId` = one job or `*` for every job of that commander.
 * - **pull answer**: deputy -> orchestrator, `commander` = the deputy, rows newer than `fromRow` in sequence order,
 *   split into batches; the last batch ends with [JournalRows.endMarker] (also sent alone when nothing is newer).
 *   Answers are consumed by [JournalPull] on the orchestrator; this store ignores them.
 *
 * Table `deputy_rows(commander, jobId, key, seq, tbl, chunkId, outputHash, row, ts)`, primary key (commander, key).
 */
class DeputyStore(
    private val db: SqlDb,
    private val port: MeshRouterPort,
    private val memo: ChunkMemo? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxBatchBytes: Int = StreamingJournal.MAX_BATCH_BYTES,
) {
    private var job: Job? = null
    /** The scope pull answers are sent from (set by [start]). */
    @Volatile private var answerScope: CoroutineScope? = null

    init {
        db.exec("""CREATE TABLE IF NOT EXISTS deputy_rows(
            commander TEXT NOT NULL, jobId TEXT NOT NULL, "key" TEXT NOT NULL, seq INTEGER NOT NULL, tbl TEXT NOT NULL,
            chunkId TEXT, outputHash TEXT, row TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (commander, "key"))""")
        db.exec("CREATE INDEX IF NOT EXISTS deputy_rows_seq ON deputy_rows(commander, jobId, seq)")
        db.exec("CREATE INDEX IF NOT EXISTS deputy_rows_chunk ON deputy_rows(chunkId)")
    }

    /** Starts storing streams and answering pulls from the verified stream. Subscribe before the router starts; call once. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        answerScope = scope
        job = scope.launch {
            port.verified.collect { v ->
                if (v.type != MsgType.JOURNAL) return@collect
                val body = try { MeshJson.decode<JournalBody>(v.body) } catch (e: Exception) {
                    SLog.w(TAG, "JOURNAL from ${v.from.take(8)} does not parse: ${e.message}"); return@collect
                }
                onJournal(body, v.from)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; answerScope = null }

    /** One JOURNAL (also `SwarmNode.onJournal`): a stream batch is stored, a pull request is answered, an answer is ignored. */
    fun onJournal(body: JournalBody, from: String) {
        if (body.commander != from) { SLog.w(TAG, "JOURNAL from ${from.take(8)} names ${body.commander.take(8)}; ignored"); return }
        when {
            body.rows.isEmpty() -> answerScope?.launch { answer(from, body.jobId, body.fromRow) }
                ?: SLog.w(TAG, "pull from ${from.take(8)} before start(); ignored")
            body.rows.any { JournalRows.isEnd(it) } -> Unit
            else -> store(from, body)
        }
    }

    /** Stores one streamed batch; returns the rows applied. */
    fun store(commander: String, body: JournalBody): Int {
        val outs = ArrayList<Triple<String, String, JsonElement>>()
        val n = synchronized(this) { db.tx { storeRows(commander, body, outs) } }
        // Outside the transaction: the memo commits on its own.
        for ((chunkId, hash, out) in outs) if (Canon.hash(out) == hash) try { memo?.put(chunkId, hash, out) } catch (ex: Exception) { SLog.w(TAG, "memo: ${ex.message}") }
        return n
    }

    private fun storeRows(commander: String, body: JournalBody, outs: MutableList<Triple<String, String, JsonElement>>): Int {
        var n = 0
        for (e in body.rows) {
            val seq = JournalRows.seqOf(e) ?: continue
            val key = JournalRows.keyOf(e) ?: continue
            val table = JournalRows.tableOf(e) ?: continue
            val row = JournalRows.rowOf(e) as? JsonObject ?: continue
            val jobId = (row["jobId"]?.jsonPrimitive?.content) ?: body.jobId
            val held = (db.query("SELECT seq FROM deputy_rows WHERE commander = ? AND \"key\" = ?", listOf(commander, key))
                .firstOrNull()?.get("seq") as Number?)?.toLong()
            if (held != null && held >= seq) continue
            val chunkId = if (table == JournalRows.OUTPUTS) row["chunkId"]?.jsonPrimitive?.content else null
            val hash = if (table == JournalRows.OUTPUTS) row["outputHash"]?.jsonPrimitive?.content else null
            db.exec("INSERT OR REPLACE INTO deputy_rows(commander, jobId, \"key\", seq, tbl, chunkId, outputHash, row, ts) VALUES (?,?,?,?,?,?,?,?,?)",
                listOf(commander, jobId, key, seq, table, chunkId, hash, e.toString(), clock()))
            n++
            if (chunkId != null && hash != null) row["outputJson"]?.let { outs += Triple(chunkId, hash, it) }
        }
        return n
    }

    /** Rows held for [commander] newer than [afterRow] (one job, or every job when [jobId] is null or `*`), in sequence order. */
    @Synchronized
    fun rowsFor(commander: String, jobId: String?, afterRow: Long): List<JsonElement> {
        val all = jobId == null || jobId == ALL
        val rows = if (all) db.query("SELECT row FROM deputy_rows WHERE commander = ? AND seq > ? ORDER BY seq", listOf(commander, afterRow))
                   else db.query("SELECT row FROM deputy_rows WHERE commander = ? AND jobId = ? AND seq > ? ORDER BY seq", listOf(commander, jobId, afterRow))
        return rows.map { JournalRows.json.parseToJsonElement(it["row"] as String) }
    }

    /** Highest sequence held for [commander] (and [jobId] unless null / `*`). */
    @Synchronized
    fun highWater(commander: String, jobId: String? = null): Long {
        val all = jobId == null || jobId == ALL
        val r = if (all) db.query("SELECT MAX(seq) AS s FROM deputy_rows WHERE commander = ?", listOf(commander))
                else db.query("SELECT MAX(seq) AS s FROM deputy_rows WHERE commander = ? AND jobId = ?", listOf(commander, jobId))
        return (r.first()["s"] as Number?)?.toLong() ?: 0L
    }

    /** RECLAIM for the orchestrator's own chunks: which of [chunkIds] the stream of [commander] holds outputs for ([ReclaimResponder.extra]). */
    @Synchronized
    fun holding(commander: String, chunkIds: List<String>): List<ReclaimFound> {
        if (chunkIds.isEmpty()) return emptyList()
        val wanted = chunkIds.toSet()
        return db.query("SELECT chunkId, outputHash FROM deputy_rows WHERE commander = ? AND tbl = ? ORDER BY seq", listOf(commander, JournalRows.OUTPUTS))
            .mapNotNull { r -> val c = r["chunkId"] as String?; val h = r["outputHash"] as String?
                if (c != null && h != null && c in wanted) ReclaimFound(c, h) else null }
            .distinctBy { it.chunkId }
    }

    /** A streamed output by chunkId (any commander): the memo-only fetch's fallback when the memo evicted it ([MemoizingTaskRunner.fallback]). */
    @Synchronized
    fun lookup(chunkId: String): MemoHit? {
        val r = db.query("SELECT row, ts FROM deputy_rows WHERE chunkId = ? AND tbl = ? ORDER BY seq DESC LIMIT 1", listOf(chunkId, JournalRows.OUTPUTS))
            .firstOrNull() ?: return null
        val row = JournalRows.rowOf(JournalRows.json.parseToJsonElement(r["row"] as String)) as? JsonObject ?: return null
        val hash = row["outputHash"]?.jsonPrimitive?.content ?: return null
        val out = row["outputJson"] ?: return null
        return MemoHit(chunkId, hash, out, (r["ts"] as Number).toLong())
    }

    /** Drops streams not written for [olderThanMs] (default: the memo retention). */
    @Synchronized
    fun prune(olderThanMs: Long = SqliteChunkMemo.RETENTION_MS) {
        db.exec("DELETE FROM deputy_rows WHERE commander || '|' || jobId IN (SELECT commander || '|' || jobId FROM deputy_rows GROUP BY commander, jobId HAVING MAX(ts) < ?)", listOf(clock() - olderThanMs))
    }

    /** Answers a pull request from [to]: rows of [jobId] newer than [afterRow], batched, with the end marker. */
    suspend fun answer(to: String, jobId: String, afterRow: Long) {
        val rows = rowsFor(to, jobId, afterRow)
        val hw = maxOf(afterRow, highWater(to, jobId))
        val batches = StreamingJournal.batches(rows, maxBatchBytes).toMutableList()
        if (batches.isEmpty()) batches += listOf(JournalRows.endMarker(hw)) else batches[batches.lastIndex] = batches.last() + JournalRows.endMarker(hw)
        for (b in batches) {
            val r = port.send(to, MsgType.JOURNAL, MeshJson.encode(JournalBody(jobId, port.nodeId, afterRow, b)))
            if (r.isFailure) { SLog.w(TAG, "JOURNAL answer to ${to.take(8)}: ${r.exceptionOrNull()?.message}"); return }
        }
        SLog.d(TAG, "answered ${to.take(8)}'s pull of $jobId after $afterRow with ${rows.size} rows")
    }

    companion object {
        private const val TAG = "Deputy"
        /** `jobId` of a pull request for every job of the commander. */
        const val ALL = "*"
    }
}

/**
 * The returning orchestrator's side of the deputy pull (§13.4): "ask the deputy for rows newer than its own journal
 * before resuming". Sends the pull request, collects the answer batches until the end marker, imports them into the
 * local journal keeping their sequence numbers. Answers arrive through [start]'s subscription (or [onAnswer]).
 */
class JournalPull(
    private val port: MeshRouterPort,
    private val journal: ReplicatedJournal,
    val waitMs: Long = PULL_WAIT_MS,
) {
    private val pending = ConcurrentHashMap<String, Channel<JournalBody>>()
    private var job: Job? = null

    /** The deputy's high-water mark from the last completed pull (for [StreamingJournal.markSent]). */
    @Volatile var lastHighWater: Long = 0L
        private set

    /** Subscribes to JOURNAL answers (bodies carrying the end marker). Subscribe before the router starts; call once. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            port.verified.collect { v ->
                if (v.type != MsgType.JOURNAL) return@collect
                val body = try { MeshJson.decode<JournalBody>(v.body) } catch (e: Exception) { return@collect }
                if (body.commander == v.from && body.rows.any { JournalRows.isEnd(it) }) onAnswer(body, v.from)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun onAnswer(body: JournalBody, from: String) { pending[from]?.trySend(body) }

    /**
     * Pulls rows of [jobId] (every job when null) newer than this journal's high-water mark from [deputy] and imports
     * them. Returns the rows applied, or null when the deputy did not answer in time or could not be reached.
     */
    suspend fun pull(deputy: String, jobId: String? = null): Int? {
        val ch = Channel<JournalBody>(Channel.UNLIMITED)
        pending[deputy] = ch
        try {
            val from = journal.highWater(jobId)
            val req = JournalBody(jobId ?: DeputyStore.ALL, port.nodeId, from, emptyList())
            if (port.send(deputy, MsgType.JOURNAL, MeshJson.encode(req)).isFailure) return null
            val rows = ArrayList<JsonElement>()
            withTimeoutOrNull(waitMs) {
                var end = false
                while (!end) {
                    val b = ch.receive()
                    if (b.jobId != (jobId ?: DeputyStore.ALL)) continue
                    rows += b.rows.filter { !JournalRows.isEnd(it) }
                    b.rows.firstOrNull { JournalRows.isEnd(it) }?.let { lastHighWater = JournalRows.endHighWater(it); end = true }
                }
                true
            } ?: return null
            return journal.importRows(rows)
        } finally { pending.remove(deputy, ch) }
    }

    companion object {
        /** How long a returning orchestrator waits for its deputy's answer before resuming from its own journal. */
        const val PULL_WAIT_MS = 5_000L
    }
}
