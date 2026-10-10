package com.hivecore.runtime.models

import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.SLog
import com.hivecore.runtime.catalog.BigKind
import com.hivecore.runtime.catalog.ErrorClasses
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.catalog.ModelLoadException
import com.hivecore.runtime.llm.GenieXEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Residency (spec 4.3, 8.3, 8.4).
 *
 * **Big slot** — exactly one of: the GenieX LLM ([llm]), the VLM (the installed `geniex_vlm` model), or a HEAVY
 * generation bundle (a catalog entry with `big = true` of kind `gen`). The LLM and the VLM cannot be resident
 * together (NPU map error 8003), so claiming `vlm` first unloads the LLM ([unloadLlm]) and remembers its backend;
 * releasing the VLM ([releaseBig], or `claimBig("llm")`) closes the VLM and reloads the LLM on that backend. A failed
 * VLM load restores the LLM before returning the failure. `with(<vlm id>)` claims the slot when needed and keeps
 * the VLM resident afterwards. The caller must not swap the slot while the owner is asking.
 *
 * **Small slots** — everything else, LRU within [budgetBytes] (default 6 GB) using manifest `sizeMb` (or the
 * files' size when 0). Loading a model evicts least-recently-used idle models until it fits; a model in use is
 * never evicted (the budget may then be exceeded until it is released); a model larger than the whole budget is
 * refused. Each session is used by one caller at a time (LiteRT interpreters and ORT sessions are not reentrant).
 *
 * **Placement** — [ensure] loads one catalog entry for a `MODEL_REQ`: the `qairt` build first, on failure the
 * `llama_cpp` build of the same capability once (when [catalog] has one), then a [ModelLoadException] whose class is
 * one of {oom, model_load, thermal, unsupported, timeout}. Models stay loaded after the job (8.4); [big] and
 * [bigModel] feed the profile so the next solver prefers phones that need no swap.
 */
class ModelPoolImpl(
    private val registry: ModelRegistry,
    private val runtimes: List<ModelRuntime>,
    private val llm: LlmEngine? = null,
    private val unloadLlm: suspend () -> Unit = { (llm as? GenieXEngine)?.unload() },
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    /** sha256 check before the first load of a model (ModelRegistryImpl.verify); default: none. */
    private val verify: suspend (ModelManifest) -> Result<Unit> = { m -> (registry as? ModelRegistryImpl)?.verify(m.id) ?: Result.success(Unit) },
    /** The catalog [ensure] consults for the llama_cpp fallback (null = no fallback). */
    private val catalog: () -> ModelCatalog? = { null },
    /** Catalog model id of the GenieX LLM per backend (spec 4.3): the qairt bundle on the NPU, the GGUF otherwise. */
    private val llmModelId: (LlmBackend) -> String = { if (it == LlmBackend.NPU) LLM_QAIRT_ID else LLM_GGUF_ID },
    /** The LLM backend a catalog entry loads on: `qairt` → NPU, `llama_cpp` → GPU (GGUF Q4_0). */
    private val llmBackendFor: (ModelEntry) -> LlmBackend = { if (it.runtime == ModelCatalog.RUNTIME_QAIRT) LlmBackend.NPU else LlmBackend.GPU },
    private val nanoTime: () -> Long = System::nanoTime,
) : CapabilityPool {

    private val bigState = MutableStateFlow(initialBig())
    override val big: StateFlow<String> = bigState
    private val warmState = MutableStateFlow<List<String>>(emptyList())
    override val warm: StateFlow<List<String>> = warmState

    // ---- small slots -------------------------------------------------------------------------------------------

    private class Entry(val manifest: ModelManifest, val runtime: ModelRuntime, val session: ModelSession, val bytes: Long) {
        val use = Mutex()
        var users = 0
    }

    private val poolLock = Mutex()
    private val small = LinkedHashMap<String, Entry>(16, 0.75f, true)   // access order = LRU first

    /** Bytes held by resident small models. */
    fun usedBytes(): Long = synchronized(small) { small.values.sumOf { it.bytes } }

    override suspend fun <T> with(modelId: String, block: suspend (ModelSession) -> T): Result<T> {
        val m = registry.manifest(modelId) ?: return Result.failure(IllegalArgumentException("model $modelId is not installed"))
        return when (m.runtime) {
            GenieXVlmRuntime.ID -> withVlm(m, block)
            "geniex_llm" -> Result.failure(IllegalArgumentException("$modelId is the LLM: use LlmEngine"))
            else -> withSmall(m, block)
        }
    }

    /** Runs [inputs] through the model's runtime (the common case of [with]). */
    suspend fun run(modelId: String, inputs: Map<String, Any>): Result<Map<String, Any>> =
        with(modelId) { s -> runtimeFor(s.manifest)!!.run(s, inputs).getOrThrow() }

    fun runtimeFor(m: ModelManifest): ModelRuntime? = runtimes.firstOrNull { it.supports(m) }

    private suspend fun <T> withSmall(m: ModelManifest, block: suspend (ModelSession) -> T): Result<T> {
        val e = try { acquire(m) } catch (t: Throwable) { return Result.failure(t) }
        return try {
            e.use.withLock { runCatching { block(e.session) } }
        } finally {
            poolLock.withLock { e.users-- }
        }
    }

    private suspend fun acquire(m: ModelManifest): Entry = poolLock.withLock {
        synchronized(small) { small[m.id] }?.let { it.users++; publishWarm(); return@withLock it }
        val runtime = runtimeFor(m) ?: throw IllegalStateException("no runtime for ${m.id} (${m.runtime})")
        val dir = registry.dir(m.id) ?: throw IllegalStateException("model ${m.id} has no folder")
        val bytes = sizeOf(m, dir)
        if (bytes > budgetBytes) throw IllegalStateException("${m.id} needs ${bytes / MB} MB, over the ${budgetBytes / MB} MB budget")
        evictFor(bytes)
        verify(m).getOrThrow()
        val session = runtime.load(m, dir).getOrThrow()
        val e = Entry(m, runtime, session, bytes).also { it.users = 1 }
        synchronized(small) { small[m.id] = e }
        publishWarm()
        e
    }

    /** Evicts idle LRU entries until [need] more bytes fit (caller holds [poolLock]). */
    private fun evictFor(need: Long) {
        val victims = ArrayList<Entry>()
        synchronized(small) {
            var used = small.values.sumOf { it.bytes }
            val it = small.entries.iterator()
            while (used + need > budgetBytes && it.hasNext()) {
                val e = it.next().value
                if (e.users > 0) continue
                it.remove(); victims += e; used -= e.bytes
            }
        }
        victims.forEach { e ->
            SLog.d(TAG, "evict ${e.manifest.id} (${e.bytes / MB} MB, LRU)")
            runCatching { e.session.close() }
            if (genBig == e.manifest.id) { genBig = null; refreshBig() }
        }
    }

    /** Unloads one small model if idle; returns whether it was resident. */
    suspend fun evict(modelId: String): Boolean = poolLock.withLock {
        val e = synchronized(small) { small[modelId]?.takeIf { it.users == 0 }?.also { small.remove(modelId) } } ?: return@withLock false
        runCatching { e.session.close() }
        if (genBig == modelId) { genBig = null; refreshBig() }
        publishWarm()
        true
    }

    /** Closes every idle small model (low memory, thermal). */
    suspend fun evictAll() = poolLock.withLock {
        val victims = synchronized(small) { small.values.filter { it.users == 0 }.also { v -> v.forEach { small.remove(it.manifest.id) } } }
        victims.forEach { runCatching { it.session.close() } }
        if (genBig != null && synchronized(small) { !small.containsKey(genBig) }) { genBig = null; refreshBig() }
        publishWarm()
    }

    private fun publishWarm() { warmState.value = synchronized(small) { small.keys.toList() } }

    private fun sizeOf(m: ModelManifest, dir: File): Long =
        if (m.sizeMb > 0) m.sizeMb * MB else m.files.sumOf { File(dir, it).length() }.coerceAtLeast(1)

    // ---- big slot ----------------------------------------------------------------------------------------------

    private val bigLock = Mutex()
    private var vlm: Pair<ModelRuntime, ModelSession>? = null
    /** The LLM backend parked while the VLM holds the slot (reloaded on release). */
    private var parkedLlm: LlmBackend? = null
    /** A HEAVY generation bundle (catalog `big`, kind `gen`) holding the slot; it lives in the small-slot map. */
    @Volatile private var genBig: String? = null

    private fun initialBig(): String = if (llm?.backend?.value != null) BigKind.LLM else BigKind.NONE

    private fun refreshBig() {
        bigState.value = when {
            vlm != null -> BigKind.VLM
            genBig != null -> BigKind.GEN
            parkedLlm == null && llm?.backend?.value != null -> BigKind.LLM
            else -> BigKind.NONE
        }
    }

    override val bigModel: String?
        get() = vlm?.second?.manifest?.id ?: genBig ?: llm?.backend?.value?.takeIf { parkedLlm == null }?.let(llmModelId)

    override suspend fun claimBig(kind: String): Result<Unit> = bigLock.withLock {
        when (kind) {
            BigKind.VLM -> claimVlm()
            BigKind.LLM -> releaseVlmLocked(reloadLlm = true, forceLlm = true)
            BigKind.NONE -> runCatching {
                closeVlm()
                if (parkedLlm == null && llm?.backend?.value != null) unloadLlm()
                parkedLlm = null
                refreshBig()
            }
            else -> Result.failure(IllegalArgumentException("big slot kind must be llm|vlm|none, got $kind"))
        }
    }

    /** Gives the slot back: closes the VLM and reloads the LLM if it was loaded before the VLM claimed the slot. */
    suspend fun releaseBig(): Result<Unit> = bigLock.withLock { releaseVlmLocked(reloadLlm = true, forceLlm = false) }

    private suspend fun claimVlm(want: ModelManifest? = null): Result<Unit> = runCatching {
        if (vlm != null) return@runCatching
        val m = want ?: registry.installed().firstOrNull { it.runtime == GenieXVlmRuntime.ID } ?: throw IllegalStateException("no VLM installed")
        val runtime = runtimeFor(m) ?: throw IllegalStateException("no runtime for ${m.id}")
        val dir = registry.dir(m.id) ?: throw IllegalStateException("model ${m.id} has no folder")
        verify(m).getOrThrow()
        val before = llm?.backend?.value
        if (before != null && parkedLlm == null) {
            SLog.d(TAG, "big slot: unloading the LLM ($before) for the VLM")
            unloadLlm()
            parkedLlm = before
        }
        val loaded = runtime.load(m, dir)
        if (loaded.isFailure) {
            SLog.w(TAG, "VLM load failed (${loaded.exceptionOrNull()?.message}); restoring the LLM")
            restoreLlm()
            refreshBig()
            throw loaded.exceptionOrNull()!!
        }
        vlm = runtime to loaded.getOrThrow()
        refreshBig()
    }

    private suspend fun releaseVlmLocked(reloadLlm: Boolean, forceLlm: Boolean): Result<Unit> = runCatching {
        closeVlm()
        if (reloadLlm) {
            if (parkedLlm != null) restoreLlm()
            else if (forceLlm && llm != null && llm.backend.value == null) {
                llm.load(LlmBackend.NPU).getOrThrow()
            }
        }
        refreshBig()
    }

    private fun closeVlm() {
        val v = vlm ?: return
        vlm = null
        SLog.d(TAG, "big slot: closing the VLM")
        runCatching { v.second.close() }
    }

    private suspend fun restoreLlm() {
        val b = parkedLlm ?: return
        parkedLlm = null
        SLog.d(TAG, "big slot: reloading the LLM ($b)")
        llm?.load(b)?.onFailure { SLog.w(TAG, "LLM reload failed: ${it.message}") }
    }

    private suspend fun <T> withVlm(m: ModelManifest, block: suspend (ModelSession) -> T): Result<T> = bigLock.withLock {
        if (vlm == null) claimVlm().onFailure { return@withLock Result.failure(it) }
        val s = vlm!!.second
        if (s.manifest.id != m.id) return@withLock Result.failure(IllegalStateException("big slot holds ${s.manifest.id}, not ${m.id}"))
        runCatching { block(s) }
    }

    // ---- placement (spec 8.3): ensure one catalog entry is loaded -----------------------------------------------

    override suspend fun ensure(entry: ModelEntry): Result<LoadInfo> {
        val first = loadEntry(entry)
        if (first.isSuccess) return first
        val fb = catalog()?.fallback(entry.capability, entry) ?: return first
        SLog.w(TAG, "${entry.modelId} (${entry.runtime}) failed: ${first.exceptionOrNull()?.message}; trying ${fb.modelId} (${fb.runtime}) once")
        val second = loadEntry(fb)
        return if (second.isSuccess) second else first
    }

    private suspend fun loadEntry(entry: ModelEntry): Result<LoadInfo> {
        val t0 = nanoTime()
        fun info(warm: Boolean) = LoadInfo(entry.modelId, entry.runtime, if (warm) 0L else (nanoTime() - t0) / 1_000_000, warm)
        return try {
            Result.success(when (BigKind.of(entry)) {
                BigKind.LLM -> info(ensureLlm(entry))
                BigKind.VLM -> info(ensureVlm(entry))
                BigKind.GEN -> info(ensureGen(entry))
                else -> info(ensureSmall(entry))
            })
        } catch (e: ModelLoadException) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val cls = ErrorClasses.of(t).takeIf { it in ErrorClasses.MODEL_FAIL } ?: ErrorClasses.MODEL_LOAD
            Result.failure(ModelLoadException(cls, t.message ?: t.javaClass.simpleName, t))
        }
    }

    /** Returns whether the model was already warm. */
    private suspend fun ensureLlm(entry: ModelEntry): Boolean {
        val engine = llm ?: throw ModelLoadException(ErrorClasses.UNSUPPORTED, "no LLM engine on this phone for ${entry.modelId}")
        val want = llmBackendFor(entry)
        return bigLock.withLock {
            if (vlm == null && genBig == null && parkedLlm == null && engine.backend.value == want) return@withLock true
            closeVlm()
            evictGenLocked()
            if (parkedLlm == null && engine.backend.value != null) unloadLlm()   // parked = already unloaded for the VLM
            parkedLlm = null
            try { engine.load(want).getOrThrow() } finally { refreshBig() }
            false
        }
    }

    private suspend fun ensureVlm(entry: ModelEntry): Boolean {
        val m = registry.manifest(entry.modelId)?.takeIf { it.runtime == GenieXVlmRuntime.ID }
            ?: throw ModelLoadException(ErrorClasses.UNSUPPORTED, "model ${entry.modelId} is not installed as a GenieX VLM")
        checkSha(m, entry)
        return bigLock.withLock {
            if (vlm?.second?.manifest?.id == m.id) return@withLock true
            closeVlm()
            evictGenLocked()
            claimVlm(m).getOrThrow()
            false
        }
    }

    private suspend fun ensureGen(entry: ModelEntry): Boolean {
        val m = registry.manifest(entry.modelId) ?: throw ModelLoadException(ErrorClasses.UNSUPPORTED, "model ${entry.modelId} is not installed")
        checkSha(m, entry)
        if (m.runtime.startsWith("geniex")) throw ModelLoadException(ErrorClasses.UNSUPPORTED, "${m.id} is a GenieX model, not a generation bundle")
        bigLock.withLock {
            if (genBig == m.id && synchronized(small) { small.containsKey(m.id) }) return true
            closeVlm()
            val before = llm?.backend?.value
            if (parkedLlm == null && before != null) { SLog.d(TAG, "big slot: unloading the LLM for ${m.id}"); unloadLlm(); parkedLlm = before }
            evictGenLocked()
            withSmall(m) { }.getOrThrow()
            genBig = m.id
            refreshBig()
        }
        return false
    }

    private suspend fun ensureSmall(entry: ModelEntry): Boolean {
        if (ModelCatalog.isBuiltIn(entry.runtime)) return true
        val m = registry.manifest(entry.modelId) ?: throw ModelLoadException(ErrorClasses.UNSUPPORTED, "model ${entry.modelId} is not installed")
        checkSha(m, entry)
        if (m.runtime == GenieXVlmRuntime.ID || m.runtime == "geniex_llm") throw ModelLoadException(ErrorClasses.UNSUPPORTED, "${m.id} needs the big slot")
        val warm = synchronized(small) { small.containsKey(m.id) }
        withSmall(m) { }.getOrThrow()
        return warm
    }

    /** Drops a generation bundle from the big slot (caller holds [bigLock]). */
    private suspend fun evictGenLocked() {
        val g = genBig ?: return
        genBig = null
        evict(g)
    }

    private fun checkSha(m: ModelManifest, e: ModelEntry) {
        if (ModelCatalog.isHex64(m.sha256) && ModelCatalog.isHex64(e.sha256) && !m.sha256.equals(e.sha256, ignoreCase = true))
            throw ModelLoadException(ErrorClasses.MODEL_LOAD, "sha256 of ${m.id} differs from the catalog (P12)")
    }

    companion object {
        private const val TAG = "ModelPool"
        const val MB = 1024L * 1024L
        const val DEFAULT_BUDGET_BYTES = 6L * 1024L * MB
        /** Catalog ids of the GenieX LLM builds (assets/models/catalog.json; GenieXEngine.NPU_DIR and the GGUF name). */
        const val LLM_QAIRT_ID = GenieXEngine.NPU_DIR
        const val LLM_GGUF_ID = "qwen2.5-coder-1.5b-instruct-q4_0"
    }
}
