package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.SLog
import com.hivecore.core.TaskSpec
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouterPort
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ReclaimBody
import com.hivecore.mesh.ReclaimResBody
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What a resumed split job starts from (§13.3 step 4): the split commander prefills [prefilled] into its work queue,
 * requeues [missing] and re-runs placement for [lostCapabilities].
 *
 * @property prefilled chunkId -> accepted output, in chunk-index order, for `WorkQueue.prefill`
 * @property sources   chunkId -> where the output came from (`node:<id>` or `cloud:<slug>`), for the ledger
 * @property missing   chunkIds to run again, in chunk-index order
 * @property lostCapabilities capabilities placed on phones that did not answer RECLAIM: re-run placement for them
 * @property respondents phones that answered RECLAIM (alive)
 * @property unreachable phones a RECLAIM could not be sent to, or that did not answer in time
 * @property reclaimed  how many outputs were fetched from peers' memos during this resume
 */
data class ResumePlan(
    val jobId: String,
    val planId: String,
    val prefilled: Map<String, JsonElement>,
    val sources: Map<String, String>,
    val missing: List<String>,
    val lostCapabilities: Set<String>,
    val respondents: Set<String>,
    val unreachable: Set<String>,
    val reclaimed: Int,
) {
    /** Nothing left to run: the commander can go straight to the merge. */
    val complete: Boolean get() = missing.isEmpty()
}

/**
 * Orchestrator side of RECLAIM (§13.3), the driver the split commander calls with a [JobSnapshot]:
 *
 * 1. chunks in `done` whose stored output still hashes to its `outputHash` are final;
 * 2. this phone's own memo is checked for the rest (it may have finished a chunk and crashed before journaling it);
 * 3. `RECLAIM {planId, chunkIds}` goes to every phone of the plan's eligible set (plus the deputy); `RECLAIM_RES`
 *    replies are collected until every phone answered or [reclaimWaitMs] passed;
 * 4. each found output is requested with a `TASK` carrying the same chunkId and `memoOnly` (served from the peer's
 *    memo by [MemoizingTaskRunner], never recomputed), verified (`sha256(canonical(output)) == outputHash`), journaled
 *    as an `outputs` row (`source = node:<holder>`) plus a `reclaimed` attempt, and the chunk marked `done`; a failed
 *    fetch tries the next holder;
 * 5. everything else is [ResumePlan.missing]; capabilities placed on silent phones are [ResumePlan.lostCapabilities].
 *
 * Messages go through [port]; replies arrive through [start]'s subscription to the verified stream (or through
 * [onReclaimRes] / [onTaskResult] from SwarmNode's hooks), so the whole exchange runs over FakeBus in tests.
 */
class ResumeCoordinator(
    private val port: MeshRouterPort,
    private val memo: ChunkMemo,
    private val journal: JobJournal = JobJournal.None,
    private val clock: () -> Long = System::currentTimeMillis,
    val reclaimWaitMs: Long = RECLAIM_WAIT_MS,
    val fetchWaitMs: Long = FETCH_WAIT_MS,
    val maxRounds: Int = 3,
) {
    val selfId: String get() = port.nodeId
    private val reclaims = ConcurrentHashMap<String, Channel<Pair<String, ReclaimResBody>>>()
    private val fetches = ConcurrentHashMap<String, Pending>()
    private val seq = AtomicLong(0)
    private var job: Job? = null

    private class Pending(val node: String, val chunkId: String, val hash: String) { val result = CompletableDeferred<TaskResultBody>() }

    /** Subscribes to RECLAIM_RES and TASK_RESULT on the verified stream. Subscribe before the router starts; call once. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            port.verified.collect { v ->
                try {
                    when (v.type) {
                        MsgType.RECLAIM_RES -> onReclaimRes(MeshJson.decode(v.body), v.from)
                        MsgType.TASK_RESULT -> onTaskResult(MeshJson.decode(v.body), v.from)
                        else -> Unit
                    }
                } catch (e: Exception) { SLog.w(TAG, "${v.type} from ${v.from.take(8)} ignored: ${e.message}") }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** `SwarmNode.onReclaimRes`. */
    fun onReclaimRes(body: ReclaimResBody, from: String) {
        if (body.node != from) return
        reclaims[body.planId]?.trySend(from to body)
    }

    /** A TASK_RESULT; true when it answered one of this coordinator's fetches. */
    fun onTaskResult(body: TaskResultBody, from: String): Boolean {
        val p = fetches["${body.planId}|${body.taskId}"] ?: return false
        if (p.node != from || body.node != from) return false
        p.result.complete(body)
        return true
    }

    /**
     * Rebuilds [snap]'s job state from the journal and the swarm's memos. [eligible] defaults to every phone the
     * journal saw in the job (`capsSnapshot.eligible`, attempts, placements); [extra] adds phones that may hold copies
     * (the deputy).
     */
    suspend fun resume(snap: JobSnapshot, eligible: Collection<String> = eligibleOf(snap), extra: Collection<String> = emptyList()): ResumePlan {
        val job = snap.job
        val planId = job.planId
        val prefilled = LinkedHashMap<String, JsonElement>()
        val sources = HashMap<String, String>()
        val outputs = snap.outputs.groupBy { it.chunkId }
        val attemptNo = HashMap<String, Int>()
        for (c in snap.chunks) attemptNo[c.chunkId] = c.attempts
        for (a in snap.attempts) attemptNo[a.chunkId] = maxOf(attemptNo[a.chunkId] ?: 0, a.attempt)

        // 1. final chunks
        for (c in snap.chunks) {
            if (c.state != ChunkStates.DONE) continue
            val o = outputs[c.chunkId]?.lastOrNull { Canon.hash(it.outputJson) == it.outputHash } ?: continue
            prefilled[c.chunkId] = o.outputJson; sources[c.chunkId] = o.source
        }
        val open = snap.chunks.filter { it.chunkId !in prefilled }
        val byId = snap.chunks.associateBy { it.chunkId }

        // 2. this phone's own memo
        for (c in open) {
            val hit = try { memo.get(c.chunkId) } catch (e: Exception) { null } ?: continue
            if (Canon.hash(hit.outputJson) != hit.outputHash) continue
            accept(job.jobId, c, hit.outputJson, hit.outputHash, selfId, AttemptOutcome.MEMO, attemptNo, prefilled, sources)
        }

        // 3. RECLAIM
        val wanted = open.map { it.chunkId }.filter { it !in prefilled }
        val targets = (eligible + extra).filter { it != selfId }.distinct()
        val respondents = LinkedHashSet<String>()
        val unreachable = LinkedHashSet<String>()
        val holders = LinkedHashMap<String, MutableList<Pair<String, String>>>()   // chunkId -> [(node, hash)] in answer order
        var reclaimed = 0
        if (targets.isNotEmpty()) {
            val ch = Channel<Pair<String, ReclaimResBody>>(Channel.UNLIMITED)
            reclaims[planId] = ch
            try {
                for (t in targets) {
                    val body = ReclaimBody(planId, wanted, selfId, clock(), UUID.randomUUID().toString())
                    port.send(t, MsgType.RECLAIM, MeshJson.encode(body)).onFailure { unreachable += t; SLog.w(TAG, "RECLAIM to ${t.take(8)}: ${it.message}") }
                }
                val waitFor = targets.filter { it !in unreachable }.toSet()
                if (waitFor.isNotEmpty()) withTimeoutOrNull(reclaimWaitMs) {
                    while (!respondents.containsAll(waitFor)) {
                        val (from, res) = ch.receive()
                        if (from !in waitFor || !respondents.add(from)) continue
                        val asked = wanted.toSet()
                        for (f in res.found) if (f.chunkId in asked) holders.getOrPut(f.chunkId) { ArrayList() } += from to f.outputHash
                    }
                }
                unreachable += waitFor.filter { it !in respondents }
            } finally { reclaims.remove(planId, ch) }

            // 4. fetch each found output with a memo-only TASK; a failure falls back to the next holder
            var round = 0
            var todo = holders.keys.filter { it !in prefilled }
            while (todo.isNotEmpty() && round < maxRounds) {
                val asked = LinkedHashMap<String, Pending>()
                for (cid in todo) {
                    val (node, hash) = holders.getValue(cid).getOrNull(round) ?: continue
                    val taskId = "reclaim.${byId[cid]?.index ?: 0}.${seq.incrementAndGet()}"
                    val p = Pending(node, cid, hash)
                    fetches["$planId|$taskId"] = p
                    val task = fetchTask(job, cid, taskId)
                    val body = TaskBody(planId, task, emptyMap(), fetchWaitMs, null, selfId, clock(), UUID.randomUUID().toString())
                    if (port.send(node, MsgType.TASK, MeshJson.encode(body)).isFailure) { fetches.remove("$planId|$taskId"); continue }
                    asked[taskId] = p
                }
                if (asked.isNotEmpty()) withTimeoutOrNull(fetchWaitMs) { for (p in asked.values) p.result.await() }
                for ((taskId, p) in asked) {
                    fetches.remove("$planId|$taskId")
                    if (!p.result.isCompleted) continue
                    val r = p.result.await()
                    val out = r.output
                    if (r.error != null || out == null || r.outputHash != p.hash || Canon.hash(out) != p.hash) {
                        SLog.w(TAG, "reclaim of ${p.chunkId.take(8)} from ${p.node.take(8)} rejected: ${r.error ?: "hash does not match"}")
                        continue
                    }
                    val row = byId[p.chunkId] ?: continue
                    accept(job.jobId, row, out, p.hash, p.node, AttemptOutcome.RECLAIMED, attemptNo, prefilled, sources)
                    reclaimed++
                }
                round++
                todo = holders.filterKeys { it !in prefilled }.filterValues { it.size > round }.keys.toList()
            }
        }

        // 5. what is left
        val missing = snap.chunks.sortedBy { it.index }.map { it.chunkId }.filter { it !in prefilled }
        val alive = respondents + selfId
        val lost = snap.placements.filter { it.state != PlacementState.FAILED && it.nodeId !in alive }.map { it.capability }.toSet() -
            snap.placements.filter { it.state == PlacementState.READY && it.nodeId in alive }.map { it.capability }.toSet()
        val ordered = LinkedHashMap<String, JsonElement>()
        for (c in snap.chunks.sortedBy { it.index }) prefilled[c.chunkId]?.let { ordered[c.chunkId] = it }
        SLog.d(TAG, "resume ${job.jobId}: ${ordered.size} done ($reclaimed reclaimed), ${missing.size} to run, lost ${lost.joinToString()}")
        return ResumePlan(job.jobId, planId, ordered, sources, missing, lost, respondents, unreachable, reclaimed)
    }

    private fun accept(jobId: String, row: ChunkRow, out: JsonElement, hash: String, node: String, outcome: String,
                       attemptNo: MutableMap<String, Int>, prefilled: MutableMap<String, JsonElement>, sources: MutableMap<String, String>) {
        val source = OutputRow.nodeSource(node)
        val n = (attemptNo[row.chunkId] ?: 0) + 1
        attemptNo[row.chunkId] = n
        // Write-ahead: the output is journaled before the caller acts on the resume plan.
        val now = clock()
        journal.attempt(AttemptRow(jobId, row.chunkId, n, node, now, now, outcome, hash))
        journal.output(OutputRow(jobId, row.chunkId, hash, out, source))
        journal.chunk(row.copy(state = ChunkStates.DONE))
        prefilled[row.chunkId] = out
        sources[row.chunkId] = source
    }

    /** The memo-only TASK that fetches [chunkId]: the job's op and model, params `{chunkId, memoOnly}` only (nothing is recomputed). */
    private fun fetchTask(job: JobRow, chunkId: String, taskId: String): TaskSpec {
        val spec = job.spec as? JsonObject
        val op = (spec?.get("op") as? JsonPrimitive)?.content?.let { runCatching { Op.valueOf(it) }.getOrNull() } ?: Op.INFER
        val modelId = (spec?.get("modelId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = JsonObject(mapOf(
            MemoizingTaskRunner.PARAM_CHUNK_ID to JsonPrimitive(chunkId),
            MemoizingTaskRunner.PARAM_MEMO_ONLY to JsonPrimitive(true),
        ))
        return TaskSpec(taskId, op, params, modelId = modelId)
    }

    companion object {
        private const val TAG = "Resume"
        /** How long a resume waits for RECLAIM_RES from the eligible set. */
        const val RECLAIM_WAIT_MS = 5_000L
        /** How long a resume waits for the memo-only TASK_RESULTs of one fetch round. */
        const val FETCH_WAIT_MS = 15_000L

        /** The plan's eligible set as the journal saw it: `capsSnapshot.eligible`, attempt nodes and placement nodes. */
        fun eligibleOf(snap: JobSnapshot): List<String> {
            val fromCaps = ((snap.job.capsSnapshot as? JsonObject)?.get("eligible") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            return (fromCaps + snap.attempts.map { it.nodeId } + snap.placements.map { it.nodeId }).distinct()
        }

        /** The deputy the orchestrator recorded for the job (`capsSnapshot.deputy`), or null. */
        fun deputyOf(job: JobRow): String? =
            ((job.capsSnapshot as? JsonObject)?.get("deputy") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }
}
