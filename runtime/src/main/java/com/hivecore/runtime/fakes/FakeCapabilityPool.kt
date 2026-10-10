package com.hivecore.runtime.fakes

import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelSession
import com.hivecore.runtime.catalog.BigKind
import com.hivecore.runtime.catalog.ErrorClasses
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.catalog.ModelLoadException
import com.hivecore.runtime.models.CapabilityPool
import com.hivecore.runtime.models.LoadInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [CapabilityPool] for JVM tests: one big slot, small models that stay warm, scripted failures per model id
 * ([fail]: modelId → error class), scripted load times ([loadMs]: modelId → ms, default [defaultLoadMs]), and a log
 * of every load. [catalog] (optional) enables the qairt → llama_cpp retry the real pool performs. [ensure] suspends
 * on [gate] when set, so a test can hold a load open past a deadline.
 */
class FakeCapabilityPool(private val catalog: ModelCatalog? = null, var defaultLoadMs: Long = 100L) : CapabilityPool {
    private val bigState = MutableStateFlow(BigKind.NONE)
    override val big: StateFlow<String> = bigState
    private val warmState = MutableStateFlow<List<String>>(emptyList())
    override val warm: StateFlow<List<String>> = warmState

    override var bigModel: String? = null
        private set
    private val small = LinkedHashSet<String>()

    val fail = HashMap<String, String>()
    val loadMs = HashMap<String, Long>()
    val log = ArrayList<String>()
    /** When set, every load awaits it before completing (tests of deadlines). */
    var gate: (suspend (ModelEntry) -> Unit)? = null

    val loads: List<String> get() = log.filter { it.startsWith("load ") }.map { it.removePrefix("load ") }

    /** Pre-warms [entry] without logging (a phone that already holds the model). */
    fun preload(entry: ModelEntry) { place(entry) }

    override suspend fun ensure(entry: ModelEntry): Result<LoadInfo> {
        val first = load(entry)
        if (first.isSuccess || catalog == null) return first
        val fb = catalog.fallback(entry.capability, entry) ?: return first
        log += "fallback ${entry.modelId} -> ${fb.modelId}"
        return load(fb)
    }

    private suspend fun load(entry: ModelEntry): Result<LoadInfo> {
        val kind = BigKind.of(entry)
        val resident = if (kind != BigKind.NONE) bigModel == entry.modelId else entry.modelId in small || ModelCatalog.isBuiltIn(entry.runtime)
        if (resident) { log += "warm ${entry.modelId}"; return Result.success(LoadInfo(entry.modelId, entry.runtime, 0, true)) }
        gate?.invoke(entry)
        fail[entry.modelId]?.let { cls ->
            log += "fail ${entry.modelId} $cls"
            return Result.failure(ModelLoadException(cls, "fake: ${entry.modelId} fails with $cls"))
        }
        log += "load ${entry.modelId}"
        place(entry)
        return Result.success(LoadInfo(entry.modelId, entry.runtime, loadMs[entry.modelId] ?: defaultLoadMs, false))
    }

    private fun place(entry: ModelEntry) {
        val kind = BigKind.of(entry)
        if (kind != BigKind.NONE) { bigModel = entry.modelId; bigState.value = kind } else small += entry.modelId
        warmState.value = small.toList()
    }

    /** Frees the big slot (the owner's own use evicted it). */
    fun releaseBig() { bigModel = null; bigState.value = BigKind.NONE }

    override suspend fun <T> with(modelId: String, block: suspend (ModelSession) -> T): Result<T> =
        Result.failure(UnsupportedOperationException("FakeCapabilityPool has no sessions"))

    override suspend fun claimBig(kind: String): Result<Unit> {
        if (kind !in setOf(BigKind.LLM, BigKind.VLM, BigKind.GEN, BigKind.NONE)) return Result.failure(IllegalArgumentException("big slot kind $kind"))
        bigState.value = kind
        if (kind == BigKind.NONE) bigModel = null
        return Result.success(Unit)
    }

    companion object {
        /** A failure class for tests that never hits the fallback (an `unsupported` entry has no llama_cpp twin). */
        const val FAIL_UNSUPPORTED = ErrorClasses.UNSUPPORTED
    }
}
