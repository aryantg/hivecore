package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.OpExecutor
import com.hivecore.core.SLog
import com.hivecore.core.TaskRunner
import com.hivecore.core.TaskSpec
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * A [TaskRunner] decorator that makes the chunk memo (§13.2) answer tasks: a task whose `params.chunkId` is memoized
 * returns the memoized output without running; every successful result of a task carrying a chunkId is recorded
 * under it (hash = `Canon.hash`). This is how "re-running after adding ten invoices runs only the new chunks" survives
 * restarts without touching SwarmNode: build the node with this runner.
 *
 * `params.memoOnly = true` (the RECLAIM fetch, §13.3) never runs the task: a miss fails with [NOT_MEMOIZED], so a
 * resuming orchestrator re-queues the chunk instead of a peer computing it from a reduced parameter set.
 *
 * [fallback] is consulted after the memo (the deputy's streamed outputs, §13.4).
 */
class MemoizingTaskRunner(
    private val inner: TaskRunner,
    val memo: ChunkMemo,
) : TaskRunner {
    @Volatile var fallback: ((String) -> MemoHit?)? = null

    /** Tasks answered from the memo (for tests and the Jobs screen). */
    @Volatile var hits: Int = 0
        private set

    override val queueDepth: StateFlow<Int> get() = inner.queueDepth
    override fun register(executor: OpExecutor) = inner.register(executor)
    override fun yieldAll() = inner.yieldAll()

    override suspend fun run(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>, from: String): Result<JsonElement> {
        val chunkId = chunkIdOf(task)
        if (chunkId != null) {
            lookup(chunkId)?.let { hit ->
                hits++
                return Result.success(hit.outputJson)
            }
            if (memoOnly(task)) return Result.failure(IllegalStateException(NOT_MEMOIZED))
        }
        val r = inner.run(task, inputs, blobs, from)
        if (chunkId != null) r.onSuccess { out ->
            try { memo.put(chunkId, Canon.hash(out), out) } catch (e: Exception) { SLog.w(TAG, "memo put ${chunkId.take(8)}: ${e.message}") }
        }
        return r
    }

    private fun lookup(chunkId: String): MemoHit? {
        val hit = try { memo.get(chunkId) } catch (e: Exception) { SLog.w(TAG, "memo get ${chunkId.take(8)}: ${e.message}"); null }
            ?: try { fallback?.invoke(chunkId) } catch (e: Exception) { null }
            ?: return null
        // Never serve an entry whose bytes no longer match its hash.
        return hit.takeIf { Canon.hash(it.outputJson) == it.outputHash }
    }

    companion object {
        private const val TAG = "MemoRunner"
        const val PARAM_CHUNK_ID = "chunkId"
        const val PARAM_MEMO_ONLY = "memoOnly"
        const val NOT_MEMOIZED = "not memoized on this phone"

        fun chunkIdOf(task: TaskSpec): String? =
            (task.params[PARAM_CHUNK_ID] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        fun memoOnly(task: TaskSpec): Boolean = (task.params[PARAM_MEMO_ONLY] as? JsonPrimitive)?.content == "true"
    }
}
