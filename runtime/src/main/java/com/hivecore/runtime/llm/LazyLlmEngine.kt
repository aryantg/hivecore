package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.SLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An [LlmEngine] that loads its model on the first generate when nothing is loaded (spec amendment "open paths").
 * After a low-memory exit the wiring skips the eager 4 GB load so the app is not killed again in a loop; before this
 * wrapper an owner question arriving on such a phone failed with "no model loaded" instead of loading. The first
 * available backend in [order] is loaded under a lock, once; a load that fails leaves the engine's own error to the
 * caller.
 */
class LazyLlmEngine(
    private val engine: LlmEngine,
    private val order: List<LlmBackend> = listOf(LlmBackend.NPU, LlmBackend.GPU, LlmBackend.CPU),
    /** whether the phone can hold the model right now (free memory); false = stay unloaded and let the caller fail plainly */
    private val mayLoad: () -> Boolean = { true },
) : LlmEngine {
    override val backend: StateFlow<LlmBackend?> get() = engine.backend
    override val lastStats: StateFlow<GenStats?> get() = engine.lastStats
    override val status: StateFlow<String> get() = engine.status
    private val loading = Mutex()

    override fun available(): Set<LlmBackend> = engine.available()
    override suspend fun load(backend: LlmBackend): Result<Unit> = engine.load(backend)
    override suspend fun cancel() = engine.cancel()

    override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> {
        if (engine.backend.value == null) loading.withLock {
            if (engine.backend.value == null) {
                val pick = order.firstOrNull { it in runCatching { engine.available() }.getOrDefault(emptySet()) }
                if (pick == null) SLog.w(TAG, "no model loaded and no bundle available")
                else if (!mayLoad()) SLog.w(TAG, "no model loaded and not enough free memory to load one now")
                else {
                    SLog.d(TAG, "loading $pick on demand")
                    engine.load(pick).onFailure { SLog.w(TAG, "on-demand load failed: ${it.message}") }
                }
            }
        }
        return engine.generate(messages, params, onToken)
    }

    companion object { private const val TAG = "LazyLlm" }
}
