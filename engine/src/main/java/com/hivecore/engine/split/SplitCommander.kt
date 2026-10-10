package com.hivecore.engine.split

import com.hivecore.core.Comparator
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.TaskRunner
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import com.hivecore.mesh.TaskSignalBody
import com.hivecore.core.Canon
import com.hivecore.engine.ComparatorImpl
import com.hivecore.engine.TaskRunnerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs split jobs (§4B) from this phone: [ChunkPlanner] → [WorkQueue] → TASK / TASK_RESULT messages (the same ones
 * the [com.hivecore.engine.Commander] uses; this phone's own chunks are delivered in-process and run on its
 * TaskRunner) → [Mergers] in chunk-index order on this phone.
 *
 * Message ids: `planId` = "<jobId>#<run>", `taskId` = "c<chunk>.a<attempt>", so retries and duplicates are distinct
 * tasks for the workers' de-duplication, and a late result of an old attempt is recognised and compared, never
 * double-counted. Finished chunk outputs are memoized by chunkId (when [SplitJob] runs with `memo`).
 *
 * Faults: a phone that cannot be reached, or misses a deadline, leaves the job (its in-flight chunks go back to the
 * front of the queue); a yield or an error re-queues the chunk. This phone never leaves its own job.
 *
 * @param sendBlobs ships released items to a worker before its first TASK that reads them; returns bytes sent
 */
class SplitCommander(
    private val selfId: String,
    private val comparator: Comparator? = ComparatorImpl(),
    private val profiles: () -> List<Profile>,
    private val transmit: suspend (to: String, type: MsgType, body: String) -> Result<Unit>,
    private val sendBlobs: suspend (to: String, blobIds: List<String>, jobId: String) -> Result<Long> = { _, _, _ -> Result.success(0L) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val planner: ChunkPlanner = ChunkPlanner(),
    private val selector: TreeSelector = Mergers.extractiveSelector,
    private val modelHash: () -> String = { "" },
    private val slots: Int = 2,
) {
    private sealed interface Event {
        data class Result(val node: String, val body: TaskResultBody) : Event
        data class Yield(val node: String, val taskId: String, val reason: String) : Event
        data object Cancel : Event
    }

    private val lock = Any()
    private val runs = HashMap<String, Channel<Event>>()
    private val memo = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    private val seq = AtomicLong(0)

    val memoSize: Int get() = synchronized(lock) { memo.size }

    /** TASK_RESULT / TASK_YIELD for a live split job; false when no job of this phone matches. */
    /** Stops every live run of [jobId]: running chunks get TASK_CANCEL, the run ends with error "cancelled". */
    fun cancel(jobId: String): Boolean {
        val chs = synchronized(lock) { runs.filterKeys { it.substringBeforeLast('#') == jobId }.values.toList() }
        chs.forEach { it.trySend(Event.Cancel) }
        return chs.isNotEmpty()
    }

    fun onMessage(type: MsgType, from: String, body: String): Boolean {
        val (planId, ev) = try {
            when (type) {
                MsgType.TASK_RESULT -> MeshJson.decode<TaskResultBody>(body).let { it.planId to Event.Result(from, it) }
                MsgType.TASK_YIELD -> MeshJson.decode<TaskSignalBody>(body).let { it.planId to Event.Yield(from, it.taskId, it.reason) }
                else -> return false
            }
        } catch (e: Exception) { return false }
        val ch = synchronized(lock) { runs[planId] } ?: return false
        ch.trySend(ev)
        return true
    }

    /** Runs [job] to completion; emits the chunk grid as it changes; the last emission has `done` (or `error`). */
    fun run(job: SplitJob, useMemo: Boolean = true): Flow<SplitProgress> = channelFlow {
        val t0 = clock()
        val plan = planner.plan(job, profiles(), selfId).getOrElse { e ->
            send(failed(job, e.message ?: "cannot plan the split", t0)); return@channelFlow
        }
        val snapshot = profiles().associateBy { it.nodeId }
        val q = WorkQueue(plan, plan.eligible, clock, comparator, slots = slots,
            warm = { n -> job.modelId != null && snapshot[n]?.warm?.contains(job.modelId) == true },
            linkKbps = { n -> snapshot[n]?.linkKbps?.takeIf { it > 0 } ?: com.hivecore.engine.SchedulerImpl.DEFAULT_LINK_KBPS })
        if (useMemo) plan.chunks.forEach { c -> synchronized(lock) { memo[c.chunkId] }?.let { q.prefill(c.index, it) } }
        val planId = "${job.jobId}#${seq.incrementAndGet()}"
        val events = Channel<Event>(Channel.UNLIMITED)
        synchronized(lock) { runs[planId] = events }
        val blobsAt = HashSet<String>()     // "node|blob" already shipped
        try {
            suspend fun fault(a: WorkQueue.Attempt, why: String, drop: Boolean) {
                q.onFault(a.node, a.chunk, a.no, why)
                if (drop && a.node != selfId) q.removeNode(a.node, why)
            }
            suspend fun dispatch(a: WorkQueue.Assignment) {
                val remote = a.node != selfId
                if (remote && a.chunk.blobs.isNotEmpty()) {
                    val missing = a.chunk.blobs.filter { "${a.node}|$it" !in blobsAt }
                    if (missing.isNotEmpty()) {
                        val sent = sendBlobs(a.node, missing, job.jobId)
                        sent.onSuccess { b -> q.addBytes(b); missing.forEach { blobsAt += "${a.node}|$it" } }
                        if (sent.isFailure) {
                            q.onFault(a.node, a.chunk.index, a.attempt, "items could not be sent")
                            q.removeNode(a.node, "unreachable"); return
                        }
                    }
                }
                val body = MeshJson.encode(TaskBody(planId, plan.task(a.chunk, a.taskId), emptyMap(), a.deadlineMs, modelHash(), selfId, clock(), UUID.randomUUID().toString()))
                if (remote) q.addBytes(body.length.toLong())
                transmit(a.node, MsgType.TASK, body).onFailure {
                    SLog.w(TAG, "TASK ${a.taskId} to ${a.node.take(8)} not sent: ${it.message}")
                    q.onFault(a.node, a.chunk.index, a.attempt, "unreachable")
                    if (remote) q.removeNode(a.node, "unreachable")
                }
            }
            suspend fun cancel(a: WorkQueue.Attempt, why: String) {
                transmit(a.node, MsgType.TASK_CANCEL, MeshJson.encode(TaskSignalBody(planId, WorkQueue.taskId(a.chunk, a.no), selfId, why)))
            }

            while (!q.done) {
                q.error?.let { e -> q.running().forEach { cancel(it, "job failed") }; send(q.snapshot().copy(error = e, elapsedMs = clock() - t0)); return@channelFlow }
                val assigned = q.fill()
                for (a in assigned) dispatch(a)
                if (q.done) break
                send(q.progress.value)
                val wake = q.nextWake()
                if (wake == null) {
                    if (q.running().isEmpty()) {
                        if (q.fill().also { re -> re.forEach { dispatch(it) } }.isNotEmpty()) continue
                        send(q.snapshot().copy(error = q.error ?: "no phone can take the remaining chunks", elapsedMs = clock() - t0)); return@channelFlow
                    }
                }
                val ev = if (wake == null) events.receive() else withTimeoutOrNull(maxOf(0L, wake - clock())) { events.receive() }
                when (ev) {
                    null -> for (a in q.overdue()) { cancel(a, "deadline"); fault(a, "no reply", drop = true) }
                    is Event.Yield -> WorkQueue.parseTaskId(ev.taskId)?.let { (i, n) -> q.onFault(ev.node, i, n, "yielded") }
                    is Event.Cancel -> {
                        q.running().forEach { cancel(it, "cancelled") }
                        send(q.snapshot().copy(error = "cancelled", elapsedMs = clock() - t0)); return@channelFlow
                    }
                    is Event.Result -> {
                        val b = ev.body
                        val (i, n) = WorkQueue.parseTaskId(b.taskId) ?: continue
                        val out = b.output
                        when {
                            b.error != null -> q.onFault(ev.node, i, n, if (b.error == TaskRunnerImpl.YIELDED) "yielded" else "error: ${b.error}")
                            out == null || b.outputHash != Canon.hash(out) -> q.onFault(ev.node, i, n, "output hash does not match")
                            else -> {
                                if (ev.node != selfId) q.addBytes(Canon.canonical(out).length.toLong())
                                val r = q.onResult(ev.node, i, n, out, b.ms)
                                if (r is WorkQueue.Outcome.Accepted && useMemo) synchronized(lock) {
                                    memo[plan.chunks[i].chunkId] = out
                                    while (memo.size > MAX_MEMO) memo.remove(memo.keys.first())
                                }
                            }
                        }
                    }
                }
            }
            q.running().forEach { cancel(it, "job done") }
            val outputs = q.outputsInOrder()
            val result = try {
                Mergers.merge(plan.kind, outputs, plan.chunks, job.params, selector)
            } catch (e: Exception) {
                send(q.snapshot().copy(error = "merge failed: ${e.message}", elapsedMs = clock() - t0)); return@channelFlow
            }
            send(q.snapshot().copy(done = true, result = result, resultHash = Canon.hash(result), elapsedMs = clock() - t0))
        } finally {
            synchronized(lock) { runs.remove(planId) }
            events.close()
        }
    }

    /** [run] to the end; the final [SplitProgress]. */
    suspend fun runToEnd(job: SplitJob, useMemo: Boolean = true): SplitProgress = run(job, useMemo).last()

    private fun failed(job: SplitJob, why: String, t0: Long) = SplitProgress(job.jobId, job.op.name, job.merge?.name ?: "", "", emptyList(),
        emptyList(), 0, clock() - t0, 0.0, 0.0, 0.0, 0, 0, 0, done = false, error = why)

    companion object {
        private const val TAG = "SplitCommander"
        private const val MAX_MEMO = 5_000

        /**
         * A split commander with no mesh: every chunk runs on [runner] on this phone (the 1-phone baseline of gate X,
         * and the debug hook before the swarm node is wired). [blobFile] resolves released items to files.
         */
        fun standalone(selfId: String, runner: TaskRunner, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis,
                       blobFile: (String) -> File? = { null }, selector: TreeSelector = Mergers.extractiveSelector): SplitCommander {
            lateinit var sc: SplitCommander
            sc = SplitCommander(selfId, ComparatorImpl(), profiles = { emptyList() }, clock = clock, selector = selector, transmit = { _, type, body ->
                if (type == MsgType.TASK) {
                    val b = MeshJson.decode<TaskBody>(body)
                    scope.launch {
                        val t = clock()
                        val r = runner.run(b.task, emptyList(), b.task.blobs.mapNotNull(blobFile), selfId)
                        val res = r.fold({ TaskResultBody(b.planId, b.task.id, selfId, it, Canon.hash(it), ms = clock() - t) },
                            { TaskResultBody(b.planId, b.task.id, selfId, error = it.message ?: it.javaClass.simpleName) })
                        sc.onMessage(MsgType.TASK_RESULT, selfId, MeshJson.encode(res))
                    }
                }
                Result.success(Unit)
            })
            return sc
        }
    }
}
