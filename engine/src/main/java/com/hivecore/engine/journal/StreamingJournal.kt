package com.hivecore.engine.journal

import com.hivecore.core.SLog
import com.hivecore.mesh.JournalBody
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * A [JobJournal] decorator that streams every committed row to the deputy (§13.4) as
 * `JOURNAL {jobId, commander = self, fromRow, rows}`.
 *
 * Each write commits in [inner] first (write-ahead is unchanged), then signals a single sender coroutine, which reads
 * `inner.rowsAfter(jobId, sentHighWater)` and ships the rows in sequence order, in batches of at most
 * [maxBatchBytes] of JSON (`fromRow` = the first row's sequence). The high-water mark per (deputy, job) advances only
 * on a successful send, so a failed send is retried after the next commit; a new deputy gets the job from row 0.
 * Rows are ordered and replace-by-key on the deputy, so a re-sent row is harmless.
 */
class StreamingJournal(
    private val inner: ReplicatedJournal,
    private val selfId: String,
    private val deputy: () -> String?,
    private val send: suspend (String, MsgType, String) -> Result<Unit>,
    scope: CoroutineScope,
    val maxBatchBytes: Int = MAX_BATCH_BYTES,
) : ReplicatedJournal by inner {

    private val signals = Channel<String>(Channel.UNLIMITED)
    private val sent = HashMap<String, Long>()      // "deputy|jobId" -> highest row sequence delivered
    private val sender: Job = scope.launch { for (jobId in signals) flush(jobId) }

    /** Batches sent so far (for tests). */
    @Volatile var batchesSent: Int = 0
        private set

    override fun begin(job: JobRow) { inner.begin(job); signal(job.jobId) }
    override fun job(row: JobRow) { inner.job(row); signal(row.jobId) }
    override fun chunk(row: ChunkRow) { inner.chunk(row); signal(row.jobId) }
    override fun attempt(row: AttemptRow) { inner.attempt(row); signal(row.jobId) }
    override fun output(row: OutputRow) { inner.output(row); signal(row.jobId) }
    override fun placement(row: PlacementRow) { inner.placement(row); signal(row.jobId) }
    override fun escalation(row: EscalationRow) { inner.escalation(row); signal(row.jobId) }

    /** After a pull from the deputy: it already holds [jobId] (or every job when null) up to [highWater]. */
    fun markSent(deputyId: String, jobId: String?, highWater: Long) = synchronized(sent) {
        if (jobId != null) sent["$deputyId|$jobId"] = maxOf(sent["$deputyId|$jobId"] ?: 0L, highWater)
        else for (j in inner.resumable()) sent["$deputyId|${j.jobId}"] = maxOf(sent["$deputyId|${j.jobId}"] ?: 0L, highWater)
    }

    fun close() { signals.close(); sender.cancel() }

    private fun signal(jobId: String) { signals.trySend(jobId) }

    private suspend fun flush(jobId: String) {
        val to = try { deputy() } catch (e: Exception) { null } ?: return
        if (to == selfId) return
        val key = "$to|$jobId"
        val from = synchronized(sent) { sent[key] ?: 0L }
        val (_, rows) = try { inner.rowsAfter(jobId, from) } catch (e: Exception) { SLog.w(TAG, "rowsAfter: ${e.message}"); return }
        if (rows.isEmpty()) return
        for (batch in batches(rows, maxBatchBytes)) {
            val first = JournalRows.seqOf(batch.first()) ?: return
            val last = JournalRows.seqOf(batch.last()) ?: return
            val r = send(to, MsgType.JOURNAL, MeshJson.encode(JournalBody(jobId, selfId, first, batch)))
            if (r.isFailure) { SLog.w(TAG, "JOURNAL to ${to.take(8)}: ${r.exceptionOrNull()?.message}"); return }
            batchesSent++
            synchronized(sent) { sent[key] = maxOf(sent[key] ?: 0L, last) }
        }
    }

    companion object {
        private const val TAG = "JournalStream"
        /** JSON bytes per JOURNAL body, under the 64 KB router envelope with room for the signature and framing. */
        const val MAX_BATCH_BYTES = 48 * 1024

        /** Consecutive batches of at most [max] JSON bytes (a single larger row travels alone). */
        fun batches(rows: List<JsonElement>, max: Int): List<List<JsonElement>> {
            val out = ArrayList<List<JsonElement>>()
            var cur = ArrayList<JsonElement>()
            var size = 0
            for (r in rows) {
                val n = r.toString().toByteArray(Charsets.UTF_8).size + 1
                if (cur.isNotEmpty() && size + n > max) { out += cur; cur = ArrayList(); size = 0 }
                cur += r; size += n
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
    }
}
