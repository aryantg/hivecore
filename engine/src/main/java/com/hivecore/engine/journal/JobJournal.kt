package com.hivecore.engine.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Spec §13: job state has one writer (the orchestrator), is persisted before it is acted on (P9), and is
// reclaimable from nodes. These are the seams: the split engine (WorkQueue / SplitCommander) writes rows through
// [JobJournal] before it sends a message or runs the merge; the SQLite implementation and the resume path live in
// this package; every phone keeps a [ChunkMemo] so a TASK whose chunkId is memoized answers without running.
//
// Column names follow §13.1 exactly so the ledger replay and the Jobs screen can read either.

/** Job states as persisted (§13.1). */
object JobState {
    const val PLACING = "placing"; const val RUNNING = "running"; const val MERGING = "merging"
    const val DONE = "done"; const val PARTIAL = "partial"; const val FAILED = "failed"; const val CANCELLED = "cancelled"
    /** States offered for resume on app start. */
    val RESUMABLE = setOf(PLACING, RUNNING, MERGING)
}

/** Execution mode of a job (§9): the user's choice from the plan's two options. */
object JobMode { const val LOCAL = "local"; const val CLOUD = "cloud" }

/** Chunk states as persisted (§13.1 `chunks.state`). */
object ChunkStates { const val QUEUED = "queued"; const val RUNNING = "running"; const val DONE = "done"; const val FAILED = "failed" }

/** Attempt outcomes as persisted (§13.1 `attempts.outcome`). */
object AttemptOutcome {
    const val SENT = "sent"; const val OK = "ok"; const val MISMATCH = "mismatch"; const val TIMEOUT = "timeout"
    const val YIELD = "yield"; const val ERROR = "error"; const val CANCELLED = "cancelled"; const val DUPLICATE = "duplicate"
    /** The chunk was answered from a memo without running (§13.2): `nodeId` is the phone whose memo held it. */
    const val MEMO = "memo"
    /** The output was fetched from a peer's memo during a resume (§13.3). */
    const val RECLAIMED = "reclaimed"
}

/** Placement states as persisted (§13.1 `placements.state`). */
object PlacementState { const val REQUESTED = "requested"; const val READY = "ready"; const val FAILED = "failed" }

/**
 * `jobs` (§13.1): written when the plan is compiled and on every state change. `mode` is `local` or `cloud` (§9);
 * `capsSnapshot` = the catalog capabilities seen at placement (with `eligible: [nodeId]` when known, for RECLAIM).
 */
@Serializable data class JobRow(
    val jobId: String,
    val planId: String,
    val planHash: String,
    /** "T0" | "T1" | "T2" (§7.2), or "recipe" for a split job started from a recipe */
    val plannerTier: String,
    val state: String,
    val createdTs: Long,
    val updatedTs: Long,
    /** "public" | "private" | "mixed" (strictest input privacy) */
    val privacy: String,
    val capsSnapshot: JsonElement? = null,
    /** The SplitJob (or composite plan) as JSON so resume can rebuild the queue without the UI. */
    val spec: JsonElement? = null,
    /** [JobMode.LOCAL] | [JobMode.CLOUD] */
    val mode: String = JobMode.LOCAL,
)

/** `chunks`: written at cut time and on every state change. `recutOf` names the parent chunk after an adaptive re-cut (§14). */
@Serializable data class ChunkRow(
    val jobId: String,
    val chunkId: String,
    val index: Int,
    val params: JsonElement,
    val owner: String? = null,
    /** "queued" | "running" | "done" | "failed" ([ChunkStates]) */
    val state: String,
    val attempts: Int,
    val recutOf: String? = null,
    val units: Int = 1,
    /** decision point B: the replicas the chunk was decided to run with (null = the plan's) and the build it named */
    val replicas: Int? = null,
    val build: String? = null,
)

/** `attempts`: written before the TASK is sent (outcome = "sent") and again on result or fault. */
@Serializable data class AttemptRow(
    val jobId: String,
    val chunkId: String,
    val attempt: Int,
    val nodeId: String,
    val dispatchedTs: Long,
    val deadlineTs: Long,
    /** One of [AttemptOutcome]. */
    val outcome: String,
    val outputHash: String? = null,
    val errorClass: String? = null,
)

/** `outputs`: on an accepted result. `source` = `node:<nodeId>` or `cloud:<slug>` (§13.1). */
@Serializable data class OutputRow(val jobId: String, val chunkId: String, val outputHash: String, val outputJson: JsonElement, val source: String) {
    companion object {
        fun nodeSource(nodeId: String) = "node:$nodeId"
        fun cloudSource(slug: String) = "cloud:$slug"
        /** The slug of a `cloud:<slug>` source, else null. */
        fun cloudSlug(source: String): String? = if (source.startsWith("cloud:")) source.removePrefix("cloud:") else null
        /** The node of a `node:<id>` source, else null. */
        fun nodeOf(source: String): String? = if (source.startsWith("node:")) source.removePrefix("node:") else null
    }
}

/** `placements`: on MODEL_REQ (`state = requested`), MODEL_READY (`ready`), MODEL_FAIL (`failed`) ([PlacementState]). */
@Serializable data class PlacementRow(
    val jobId: String,
    val nodeId: String,
    val capability: String,
    val modelId: String,
    val sha256: String,
    val state: String,
    val loadMs: Long? = null,
)

/** `escalations` (§13.1): on every gate decision, including refusals (`slug` = "" and `costInr` = 0 then). */
@Serializable data class EscalationRow(
    val jobId: String,
    /** The graph step (or the chunkId of a split chunk) the gate decided on. */
    val stepId: String,
    val trigger: String,
    val slug: String,
    val bytesSent: Long,
    val tokensIn: Int,
    val tokensOut: Int,
    val costInr: Double,
    val ts: Long,
    /** "ran" | "refused:<reason>" | "failed:<reason>" */
    val outcome: String = "ran",
)

/**
 * `decisions` (docs/plans/decision-chunking.md §7): one row per question the decision model answered for a job, written
 * before the decided cut, replicas or verdict are acted on. Append-only. [answerJson] is the typed answer, [probabilities]
 * the per-option distribution; [modelSha256] and [calibrationVersion] name the build that answered.
 */
@Serializable data class DecisionRow(
    val jobId: String,
    /** "A.boundary" | "B.difficulty" | "E.verdict" (Templates) */
    val point: String,
    val questionId: String,
    val requestHash: String,
    val answerJson: JsonElement,
    val probabilities: JsonElement,
    val modelSha256: String,
    val calibrationVersion: String,
    val llmCalls: Int,
    val ts: Long,
)

/** Everything the journal holds for one job, for resume (§13.3) and the ledger replay (§13.6). */
data class JobSnapshot(
    val job: JobRow,
    val chunks: List<ChunkRow>,
    val attempts: List<AttemptRow>,
    val outputs: List<OutputRow>,
    val placements: List<PlacementRow>,
    val escalations: List<EscalationRow>,
    val decisions: List<DecisionRow> = emptyList(),
)

/**
 * The orchestrator's write-ahead journal (§13.1). Implementations commit each row before returning; the caller sends
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
    /** The decision model's answers for a job, in one transaction, before they are acted on. Default: not kept. */
    fun decisions(rows: List<DecisionRow>) {}
    /** Jobs in a [JobState.RESUMABLE] state, oldest first. */
    fun resumable(): List<JobRow>
    fun load(jobId: String): JobSnapshot?
    /**
     * Every row of [jobId] newer than [afterRow] as JSON for the deputy stream (§13.4), with the new high-water
     * mark; the row sequence is implementation-defined but monotonic.
     */
    fun rowsAfter(jobId: String, afterRow: Long): Pair<Long, List<JsonElement>> = afterRow to emptyList()

    /** A journal that keeps nothing (tests that do not care). */
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

/** A memoized chunk output (§13.2). */
data class MemoHit(val chunkId: String, val outputHash: String, val outputJson: JsonElement, val ts: Long)

/**
 * Persistent chunk memo on every phone (§13.2): `chunkId -> (outputHash, outputJson, ts)`, 24-hour retention, 2 GB cap
 * with LRU eviction. Covers outputs a phone computed for its own jobs and for peers' jobs; a TASK whose chunkId is
 * memoized is answered from here without running. Cloud outputs are stored under `cloud:<slug>:<chunkId>`, never
 * under the bare chunkId, so a cloud answer is never mistaken for a local replica (P4, P12).
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
