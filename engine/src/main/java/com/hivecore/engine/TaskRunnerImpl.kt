package com.hivecore.engine

import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.OpExecutor
import com.hivecore.core.SLog
import com.hivecore.core.TaskRunner
import com.hivecore.core.TaskSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Swarm plan §6.3 worker side: one task at a time (the phone has one LLM/NPU), picked by priority, highest first:
 *  1. [Priority.OWN]    a task of this phone's own question (`from` = this node)
 *  2. [Priority.OWNER]  owner tasks (AGG/DOC and other data-bound ops) for a peer's plan
 *  3. [Priority.WORKER] data-free work for a peer
 *  4. [Priority.CLOSE]  evening close (`from` = [FROM_CLOSE])
 * FIFO within a priority. [yieldAll] (the owner picked the phone up) lets the running task finish and fails every
 * queued worker task with "yielded"; until [resumeWorkers] new worker tasks fail the same way, so the commander
 * reassigns them (R8). [queueDepth] counts queued plus running tasks and feeds the PROFILE heartbeat.
 */
class TaskRunnerImpl(private val selfId: String) : TaskRunner {

    enum class Priority { OWN, OWNER, WORKER, CLOSE }

    private class Entry(val priority: Priority, val seq: Long, val task: TaskSpec) {
        val turn = CompletableDeferred<Boolean>()   // true = run now, false = yielded
    }

    private val lock = Any()
    private val executors = ArrayList<OpExecutor>()
    private val queue = ArrayList<Entry>()
    private var running: Entry? = null
    private var seq = 0L
    private var ownerBusy = false

    private val depth = MutableStateFlow(0)
    override val queueDepth: StateFlow<Int> = depth

    /** True between [yieldAll] and [resumeWorkers]. */
    val yielding: Boolean get() = synchronized(lock) { ownerBusy }

    override fun register(executor: OpExecutor) { synchronized(lock) { executors += executor } }

    /** Drops every registered executor (the swarm node is being rebuilt, e.g. after a role change). */
    fun clearExecutors() { synchronized(lock) { executors.clear() } }

    /** The ops this runner can execute now. */
    fun ops(): Set<com.hivecore.core.Op> = synchronized(lock) { executors.flatMap { it.ops }.toSet() }

    fun priorityOf(task: TaskSpec, from: String): Priority = when {
        from == FROM_CLOSE -> Priority.CLOSE
        from == selfId -> Priority.OWN
        OpClasses.of(task.op) == OpClass.OWNER -> Priority.OWNER
        else -> Priority.WORKER
    }

    override suspend fun run(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>, from: String): Result<JsonElement> {
        val priority = priorityOf(task, from)
        val executor = synchronized(lock) { executors.firstOrNull { task.op in it.ops } }
            ?: return Result.failure(IllegalStateException("no executor for ${task.op}"))
        val entry = synchronized(lock) {
            if (ownerBusy && priority == Priority.WORKER) return Result.failure(IllegalStateException(YIELDED))
            Entry(priority, seq++, task).also { queue += it; publish() }
        }
        grantNext()
        val go = try {
            entry.turn.await()
        } catch (e: CancellationException) {
            synchronized(lock) { queue.remove(entry); if (running === entry) running = null; publish() }
            grantNext()
            throw e
        }
        if (!go) return Result.failure(IllegalStateException(YIELDED))
        return try {
            executor.execute(task, inputs, blobs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            synchronized(lock) { if (running === entry) running = null; publish() }
            grantNext()
        }
    }

    override fun yieldAll() {
        val yielded = synchronized(lock) {
            ownerBusy = true
            val out = queue.filter { it.priority == Priority.WORKER }
            queue.removeAll(out.toSet())
            publish()
            out
        }
        yielded.forEach { it.turn.complete(false) }
        if (yielded.isNotEmpty()) SLog.d(TAG, "yielded ${yielded.size} queued worker task(s)")
    }

    /** The owner put the phone down: worker tasks are accepted again. */
    fun resumeWorkers() { synchronized(lock) { ownerBusy = false } }

    private fun grantNext() {
        val next = synchronized(lock) {
            if (running != null || queue.isEmpty()) return
            val e = queue.minWith(compareBy<Entry> { it.priority.ordinal }.thenBy { it.seq })
            queue.remove(e)
            running = e
            publish()
            e
        }
        next.turn.complete(true)
    }

    private fun publish() { depth.value = queue.size + (if (running != null) 1 else 0) }

    companion object {
        private const val TAG = "TaskRunner"
        const val YIELDED = "yielded"
        /** `from` for evening-close work, the lowest priority. */
        const val FROM_CLOSE = "close"
    }
}
