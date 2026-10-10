package com.hivecore.ledger

import com.hivecore.core.Canon
import com.hivecore.core.LedgerEntry
import com.hivecore.core.LedgerStore
import com.hivecore.core.MergeKind
import com.hivecore.engine.journal.AttemptOutcome
import com.hivecore.engine.journal.AttemptRow
import com.hivecore.engine.journal.ChunkRow
import com.hivecore.engine.journal.JobSnapshot
import com.hivecore.engine.journal.OutputRow
import com.hivecore.engine.split.Mergers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** One model placed on one phone for the job (§8), as the `plan` entry records the assignment. */
@Serializable data class AssignedModel(val capability: String, val modelId: String, val sha256: String, val state: String, val loadMs: Long? = null)

/** One chunk in merge order with the hash of the output that went into the merge. */
@Serializable data class ChunkRef(val index: Int, val chunkId: String, val outputHash: String? = null, val source: String? = null)

/**
 * The `plan` ledger entry of a split job (§13.6): plan hash, planner tier, mode, assignment, profile snapshot and
 * graph, plus what the merge produced so the answer can be replayed ([SplitLedger.replay]): the merge kind, the
 * chunks in merge order with their output hashes, and the result hash.
 */
@Serializable data class PlanEntry(
    val jobId: String,
    val planId: String,
    val planHash: String,
    val plannerTier: String,
    val mode: String,
    val privacy: String,
    val state: String,
    /** nodeId -> models placed there */
    val assignment: Map<String, List<AssignedModel>>,
    val profileSnapshot: JsonElement? = null,
    /** The SplitJob (or composite plan) as journaled */
    val graph: JsonElement? = null,
    val mergeKind: String? = null,
    val chunks: Int,
    val chunkOrder: List<ChunkRef>,
    val resultHash: String? = null,
    /** Phones that executed at least one chunk (the orchestrator included when it did). */
    val executors: List<String>,
    val escalations: Int = 0,
    val costInr: Double = 0.0,
    val createdTs: Long,
    val finishedTs: Long,
)

/** One attempt of a chunk on one phone, as the `task` entry lists its executors. */
@Serializable data class Executor(val node: String, val attempt: Int, val outcome: String, val outputHash: String? = null, val errorClass: String? = null)

/**
 * The `task` ledger entry of one executed chunk (§13.6): executors, output hash, who verified, memo hit,
 * duplicates, re-cuts, cloud source with slug, bytes, tokens, cost.
 */
@Serializable data class TaskEntry(
    val jobId: String,
    val planId: String,
    val chunkId: String,
    val index: Int,
    val units: Int = 1,
    val recutOf: String? = null,
    /** Child chunks this chunk was re-cut into (§14). */
    val recuts: Int = 0,
    val executors: List<Executor>,
    val outputHash: String? = null,
    /** `node:<id>` or `cloud:<slug>` */
    val source: String? = null,
    /** The orchestrator (hash recomputed) plus every phone whose attempt returned the same hash. */
    val verifiedBy: List<String>,
    val memoHit: Boolean = false,
    val duplicates: Int = 0,
    val cloudSlug: String? = null,
    val bytes: Long = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costInr: Double = 0.0,
)

/** What the merge produced, as the split commander reports it. */
data class MergeOutcome(val kind: MergeKind, val resultHash: String)

/**
 * Turns a journal [JobSnapshot] plus the merge result into the §13.6 ledger entries: one `plan` entry per job and
 * one `task` entry per executed chunk. [record] appends them on the orchestrator; a node records its own
 * [TaskEntry]s through [ReceiptExchange.afterSplitExecutor]. [replay] rebuilds the answer hash from the entries and
 * the stored outputs, which is what makes a receipt worth holding.
 */
object SplitLedger {
    const val KIND_PLAN = "plan"
    const val KIND_TASK = "task"
    val json: Json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    /** Phones that executed at least one chunk of the job, [self] excluded: the receipt peers. */
    fun executors(snap: JobSnapshot, self: String): List<String> = allExecutors(snap).filter { it != self }

    private fun allExecutors(snap: JobSnapshot): List<String> {
        val fromAttempts = snap.attempts.filter { it.outcome == AttemptOutcome.OK || it.outcome == AttemptOutcome.DUPLICATE || it.outcome == AttemptOutcome.MEMO || it.outcome == AttemptOutcome.RECLAIMED }.map { it.nodeId }
        val fromOutputs = snap.outputs.mapNotNull { OutputRow.nodeOf(it.source) }
        return (fromOutputs + fromAttempts).distinct()
    }

    /** The accepted output of each chunk: the newest `outputs` row whose bytes still hash to its hash. */
    fun acceptedOutputs(snap: JobSnapshot): Map<String, OutputRow> =
        snap.outputs.filter { Canon.hash(it.outputJson) == it.outputHash }.associateBy { it.chunkId }

    fun planEntry(snap: JobSnapshot, merge: MergeOutcome?, self: String, finishedTs: Long = snap.job.updatedTs): PlanEntry {
        val outputs = acceptedOutputs(snap)
        val order = snap.chunks.filter { it.recutOf == null }.sortedBy { it.index }
            .map { c -> ChunkRef(c.index, c.chunkId, outputs[c.chunkId]?.outputHash, outputs[c.chunkId]?.source) }
        val assignment = snap.placements.groupBy { it.nodeId }.mapValues { (_, ps) -> ps.map { AssignedModel(it.capability, it.modelId, it.sha256, it.state, it.loadMs) } }
        val mergeKind = merge?.kind?.name ?: (snap.job.spec as? JsonObject)?.get("merge")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return PlanEntry(
            jobId = snap.job.jobId, planId = snap.job.planId, planHash = snap.job.planHash, plannerTier = snap.job.plannerTier,
            mode = snap.job.mode, privacy = snap.job.privacy, state = snap.job.state,
            assignment = assignment, profileSnapshot = snap.job.capsSnapshot, graph = snap.job.spec,
            mergeKind = mergeKind, chunks = order.size, chunkOrder = order, resultHash = merge?.resultHash,
            executors = allExecutors(snap), escalations = snap.escalations.size, costInr = snap.escalations.sumOf { it.costInr },
            createdTs = snap.job.createdTs, finishedTs = finishedTs,
        )
    }

    /** One `task` entry per chunk that was executed (an attempt or an output exists), in chunk-index order. */
    fun taskEntries(snap: JobSnapshot, self: String): List<TaskEntry> {
        val outputs = acceptedOutputs(snap)
        val attempts = snap.attempts.groupBy { it.chunkId }
        val children = snap.chunks.filter { it.recutOf != null }.groupingBy { it.recutOf!! }.eachCount()
        val escalations = snap.escalations.filter { it.outcome == "ran" }.groupBy { it.stepId }
        return snap.chunks.sortedBy { it.index }
            .filter { it.chunkId in outputs || attempts.containsKey(it.chunkId) }
            .map { c -> taskEntry(c, outputs[c.chunkId], attempts[c.chunkId].orEmpty(), children[c.chunkId] ?: 0, escalations[c.chunkId].orEmpty(), self, snap.job.planId) }
    }

    /** The `task` entries of the chunks [node] executed (its attempts, or outputs sourced from it). */
    fun taskEntriesFor(snap: JobSnapshot, node: String, self: String = node): List<TaskEntry> =
        taskEntries(snap, self).filter { t -> t.executors.any { it.node == node } || t.source == OutputRow.nodeSource(node) }

    private fun taskEntry(c: ChunkRow, out: OutputRow?, attempts: List<AttemptRow>, recuts: Int,
                          escalations: List<com.hivecore.engine.journal.EscalationRow>, self: String, planId: String): TaskEntry {
        val hash = out?.outputHash
        val executors = attempts.sortedBy { it.attempt }.map { Executor(it.nodeId, it.attempt, it.outcome, it.outputHash, it.errorClass) }
        val agreeing = attempts.filter { hash != null && it.outputHash == hash && it.nodeId != self }.map { it.nodeId }.distinct()
        val sourceNode = out?.let { OutputRow.nodeOf(it.source) }
        val verifiedBy = (listOf(self) + agreeing.filter { it != sourceNode }).distinct()
        val bytes = (c.params as? JsonObject)?.get("bytes")?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.long } ?: 0L
        return TaskEntry(
            jobId = c.jobId, planId = planId, chunkId = c.chunkId, index = c.index, units = c.units, recutOf = c.recutOf, recuts = recuts,
            executors = executors, outputHash = hash, source = out?.source, verifiedBy = if (hash != null) verifiedBy else emptyList(),
            memoHit = attempts.any { it.outcome == AttemptOutcome.MEMO } || (out != null && attempts.none { it.outcome == AttemptOutcome.OK || it.outcome == AttemptOutcome.RECLAIMED }),
            duplicates = attempts.count { it.outcome == AttemptOutcome.DUPLICATE },
            cloudSlug = out?.let { OutputRow.cloudSlug(it.source) },
            bytes = bytes,
            tokensIn = escalations.sumOf { it.tokensIn }, tokensOut = escalations.sumOf { it.tokensOut }, costInr = escalations.sumOf { it.costInr },
        )
    }

    /** Appends the `task` entries and then the `plan` entry on the orchestrator; returns (plan, tasks). */
    fun record(ledger: LedgerStore, snap: JobSnapshot, merge: MergeOutcome?, self: String, finishedTs: Long = snap.job.updatedTs): Pair<LedgerEntry, List<LedgerEntry>> {
        val tasks = taskEntries(snap, self).map { ledger.append(snap.job.jobId, KIND_TASK, json.encodeToString(TaskEntry.serializer(), it)) }
        val plan = ledger.append(snap.job.jobId, KIND_PLAN, json.encodeToString(PlanEntry.serializer(), planEntry(snap, merge, self, finishedTs)))
        return plan to tasks
    }

    fun planOf(e: LedgerEntry): PlanEntry = json.decodeFromString(PlanEntry.serializer(), e.entry)
    fun taskOf(e: LedgerEntry): TaskEntry = json.decodeFromString(TaskEntry.serializer(), e.entry)

    /**
     * Replays the answer from the ledger: every chunk of [plan]'s merge order is fetched through [outputs] (the
     * journal's `outputs` table or a memo), checked against the hash the `task` entry (else the plan's chunk ref)
     * recorded, merged with the recorded kind and the job's params, and hashed. The result equals
     * [PlanEntry.resultHash] when nothing was altered; a missing or altered output is a failure naming the chunk.
     */
    suspend fun replay(plan: PlanEntry, tasks: List<TaskEntry>, outputs: (String) -> JsonElement?): Result<String> {
        val kind = plan.mergeKind?.let { runCatching { MergeKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure(IllegalStateException("plan entry has no merge kind"))
        val byChunk = tasks.associateBy { it.chunkId }
        val outs = ArrayList<JsonElement>()
        for (ref in plan.chunkOrder) {
            val want = byChunk[ref.chunkId]?.outputHash ?: ref.outputHash ?: return Result.failure(IllegalStateException("no output hash for chunk ${ref.index}"))
            val out = outputs(ref.chunkId) ?: return Result.failure(IllegalStateException("output of chunk ${ref.index} is missing"))
            if (Canon.hash(out) != want) return Result.failure(IllegalStateException("output of chunk ${ref.index} does not match its recorded hash"))
            outs += out
        }
        if (outs.isEmpty()) return Result.failure(IllegalStateException("nothing to replay"))
        val params = (plan.graph as? JsonObject)?.get("params") as? JsonObject ?: JsonObject(emptyMap())
        val merged = runCatching { Mergers.merge(kind, outs, emptyList(), params) }.getOrElse { return Result.failure(it) }
        return Result.success(Canon.hash(merged))
    }
}
