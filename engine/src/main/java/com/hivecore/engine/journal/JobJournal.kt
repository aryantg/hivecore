package com.hivecore.engine.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// v2 plan §11 / §13.5: job state has one writer (the orchestrator), is persisted before it is acted on, and is
// reclaimable from nodes. These are the seams: the split engine (WorkQueue / SplitCommander) writes rows through
// [JobJournal] before it sends a message or runs the merge; the SQLite implementation and the resume path live in
// this package; every phone keeps a [ChunkMemo] so a TASK whose chunkId is memoized answers without running.
//
// Column names follow §11.1 exactly so the ledger replay and the Jobs screen can read either.

/** Job states as persisted (§11.1). */
object JobState {
    const val PLACING = "placing"; const val RUNNING = "running"; const val MERGING = "merging"
    const val DONE = "done"; const val PARTIAL = "partial"; const val FAILED = "failed"; const val CANCELLED = "cancelled"
    /** States offered for resume on app start. */
    val RESUMABLE = setOf(PLACING, RUNNING, MERGING)
}

/** `jobs`: written when the plan is compiled and on every state change. `capsSnapshot` = the catalog capabilities seen at placement. */
@Serializable data class JobRow(
    val jobId: String,
    val planId: String,
    val planHash: String,
    /** "T0" | "T1" | "T2" (§6.1), or "recipe" for a split job started from a recipe */
    val plannerTier: String,
    val state: String,
    val createdTs: Long,
    val updatedTs: Long,
    /** "public" | "private" | "mixed" (strictest input privacy) */
    val privacy: String,
    val capsSnapshot: JsonElement? = null,
    /** The SplitJob (or composite plan) as JSON so resume can rebuild the queue without the UI. */
    val spec: JsonElement? = null,
)

/** `chunks`: written at cut time and on every state change. `recutOf` names the parent chunk after an adaptive re-cut (§9.3). */
@Serializable data class ChunkRow(
    val jobId: String,
    val chunkId: String,
    val index: Int,
    val params: JsonElement,
    val owner: String? = null,
    /** "queued" | "running" | "done" | "failed" */
    val state: String,
    val attempts: Int,
    val recutOf: String? = null,
    val units: Int = 1,
)

/** `attempts`: written before the TASK is sent (outcome = "sent") and again on result or fault. */
@Serializable data class AttemptRow(
    val jobId: String,
    val chunkId: String,
    val attempt: Int,
    val nodeId: String,
    val dispatchedTs: Long,
    val deadlineTs: Long,
    /** "sent" | "ok" | "mismatch" | "timeout" | "yield" | "error" | "cancelled" | "duplicate" */
    val outcome: String,
    val outputHash: String? = null,
    val errorClass: String? = null,
)

/** `outputs`: on an accepted result. `source` = `node:<nodeId>` or `cloud:<slug>` (C5). */
@Serializable data class OutputRow(val jobId: String, val chunkId: String, val outputHash: String, val outputJson: JsonElement, val source: String)

/** `placements`: on MODEL_REQ (`state = requested`), MODEL_READY (`ready`), MODEL_FAIL (`failed`). */
@Serializable data class PlacementRow(
    val jobId: String,
    val nodeId: String,
    val capability: String,
    val modelId: String,
    val sha256: String,
    val state: String,
    val loadMs: Long? = null,
)

/** `escalations`: on every gate decision, including refusals (`slug` = "" and `costUsd` = 0 then). */
@Serializable data class EscalationRow(
    val jobId: String,
    val nodeId: String,
    val trigger: String,
    val slug: String,
    val bytesSent: Long,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val ts: Long,
    /** "ran" | "refused:<reason>" | "failed:<reason>" */
    val outcome: String = "ran",
)

/** Everything the journal holds for one job, for resume (§11.3) and the ledger replay. */
data class JobSnapshot(
    val job: JobRow,
    val chunks: List<ChunkRow>,
    val attempts: List<AttemptRow>,
    val outputs: List<OutputRow>,
    val placements: List<PlacementRow>,
    val escalations: List<EscalationRow>,
)

/**
 * The orchestrator's write-ahead journal (§11.1). Implementations commit each row before returning; the caller sends
 * the message or runs the merge only after the call returns. Rows for the same key replace the earlier row
 * (`jobs` by jobId, `chunks` by (jobId, chunkId), `attempts` by (jobId, chunkId, attempt), `placements` by
 * (jobId, nodeId, capability)); `outputs` and `escalations` are append-only.
 */
interface JobJournal {
    fun begin(job: JobRow)
    fun job(row: JobRow)
    fun chunk(row: ChunkRow)
    fun attempt(row: AttemptRow)
    fun output(row: OutputRow)
    fun placement(row: PlacementRow)
    fun escalation(row: EscalationRow)
    /** Jobs in a [JobState.RESUMABLE] state, oldest first. */
    fun resumable(): List<JobRow>
    fun load(jobId: String): JobSnapshot?
    /**
     * Every row of [jobId] newer than [afterRow] as JSON for the deputy stream (§11.4), with the new high-water
     * mark; the row sequence is implementation-defined but monotonic.
     */
    fun rowsAfter(jobId: String, afterRow: Long): Pair<Long, List<JsonElement>> = afterRow to emptyList()

    /** A journal that keeps nothing (pre-v2 behaviour and tests that do not care). */
    object None : JobJournal {
        override fun begin(job: JobRow) {}
        override fun job(row: JobRow) {}
        override fun chunk(row: ChunkRow) {}
        override fun attempt(row: AttemptRow) {}
        override fun output(row: OutputRow) {}
        override fun placement(row: PlacementRow) {}
        override fun escalation(row: EscalationRow) {}
        override fun resumable(): List<JobRow> = emptyList()
        override fun load(jobId: String): JobSnapshot? = null
    }
}

/** A memoized chunk output (§11.2). */
data class MemoHit(val chunkId: String, val outputHash: String, val outputJson: JsonElement, val ts: Long)

/**
 * Persistent chunk memo on every phone (§11.2): `chunkId -> (outputHash, outputJson, ts)`, 24-hour retention, 2 GB cap
 * with LRU eviction. Covers outputs a phone computed for its own jobs and for peers' jobs; a TASK whose chunkId is
 * memoized is answered from here without running. Cloud outputs are stored under `cloud:<slug>:<chunkId>`, never
 * under the bare chunkId (§7.3 step 5).
 */
interface ChunkMemo {
    fun get(chunkId: String): MemoHit?
    fun put(chunkId: String, hash: String, json: JsonElement)
    /** The entries among [chunkIds] this phone holds, for RECLAIM_RES. */
    fun holding(chunkIds: Collection<String>): List<MemoHit> = chunkIds.mapNotNull { get(it) }

    object None : ChunkMemo {
        override fun get(chunkId: String): MemoHit? = null
        override fun put(chunkId: String, hash: String, json: JsonElement) {}
    }
}
