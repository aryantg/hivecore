package com.hivecore.runtime.llm

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The only file that knows GenieX (plan 3.4, 8). Verified against geniex-android 0.8.0 `classes.jar`:
 *  - `GenieXSdk.getInstance().init(context, InitCallback?)` is synchronous; it registers the `llama_cpp` and
 *    `qairt` plugins from `nativeLibraryDir` and inits the model manager in `filesDir/geniex`.
 *  - `LlmWrapper.builder().llmCreateInput(LlmCreateInput(model_path, tokenizer_path, config, runtime_id, compute_unit)).build()`
 *    → `Result<LlmWrapper>` (suspend). `ModelConfig` defaults: nCtx 2048, nGpuLayers -1; qairt wants 0/0.
 *  - `applyChatTemplate(messages, tools, enableThinking, addGenerationPrompt = true)` → `Result<LlmApplyChatTemplateOutput>`;
 *    the SDK does not apply a template on generate, and `formattedText` may be empty (bug #1432) → [ChatTemplate.qwen].
 *  - `generateStreamFlow(prompt, GenerationConfig)` → `LlmStreamResult.Token | Completed(ProfilingData) | Error`.
 *    Tokens come from a callbackFlow; `stopStream()` is a suspend call on the wrapper's dispatcher (IO).
 *  - `ModelManagerWrapper.pullFlow(ModelPullInput(model_name, hub = LOCALFS, local_path))` copies a bundle into the
 *    SDK cache; `getPaths(name)` returns `ModelPaths(model_path, …)`. Used only if a direct folder load fails.
 *
 * Backends: NPU = the AI Hub qairt bundle in `<root>/qwen3-4b-npu/` (falls back to the GGUF on `llama_cpp`
 * with `compute_unit = "npu"` when qairt cannot load; [runtimeLabel] says which); GPU/CPU = `<root>/gguf/<name>.gguf`
 * on `llama_cpp`. Roots are tried in order per model. One [Mutex] serialises `load` and `generate` because every
 * caller (intent, RAG, merge, phrasing, evening close) shares the single model.
 */
class GenieXEngine(
    private val context: Context,
    private val modelRoots: List<File> = defaultRoots(context)
) : LlmEngine {

    private val backendState = MutableStateFlow<LlmBackend?>(null)
    override val backend: StateFlow<LlmBackend?> = backendState
    private val statsState = MutableStateFlow<GenStats?>(null)
    override val lastStats: StateFlow<GenStats?> = statsState
    private val statusState = MutableStateFlow(STATUS_NONE)
    override val status: StateFlow<String> = statusState

    private val mutex = Mutex()
    @Volatile private var wrapper: LlmWrapper? = null
    @Volatile private var streaming = false
    @Volatile private var readyLine = STATUS_NONE

    /** "qairt" | "llama_cpp:npu" | "llama_cpp:gpu" | "llama_cpp:cpu" | "" — which runtime the loaded model runs on. */
    @Volatile var runtimeLabel: String = ""
        private set
    /** Model file or folder currently loaded (for STATE and the Settings screen). */
    @Volatile var modelPath: String = ""
        private set
    /** "sdk" or "fallback" — which chat template rendered the last prompt; plus its length. */
    @Volatile var lastPromptInfo: String = ""
        private set
    /** Set to true to skip the SDK template and always use [ChatTemplate.qwen] (Gate 1 diagnostics). */
    @Volatile var forceOwnTemplate: Boolean = false

    // ---- discovery -------------------------------------------------------------------------------------------

    /** The extracted qairt bundle folder (must contain genie_config.json), searched through the roots in order. */
    fun npuBundle(): File? = modelRoots.map { File(it, NPU_DIR) }.firstOrNull { File(it, "genie_config.json").isFile }

    /** The first `.gguf` under `<root>/gguf/` (alphabetical), searched through the roots in order. */
    fun ggufFile(): File? = modelRoots.asSequence()
        .map { File(it, GGUF_DIR) }
        .filter { it.isDirectory }
        .mapNotNull { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }?.minByOrNull { it.name } }
        .firstOrNull()

    override fun available(): Set<LlmBackend> {
        val out = HashSet<LlmBackend>()
        val gguf = ggufFile()
        if (npuBundle() != null || gguf != null) out += LlmBackend.NPU
        if (gguf != null) { out += LlmBackend.GPU; out += LlmBackend.CPU }
        return out
    }

    // ---- load ------------------------------------------------------------------------------------------------

    override suspend fun load(backend: LlmBackend): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            runCatching {
                statusState.value = "initialising GenieX…"
                ensureSdk(context).getOrThrow()
                closeWrapper()
                backendState.value = null
                statsState.value = null
                val loaded = when (backend) {
                    LlmBackend.NPU -> loadNpu()
                    LlmBackend.GPU -> loadGguf("gpu")
                    LlmBackend.CPU -> loadGguf("cpu")
                }
                wrapper = loaded.wrapper
                runtimeLabel = loaded.runtime
                modelPath = loaded.path
                backendState.value = backend
                val secs = String.format(Locale.US, "%.1f", (System.currentTimeMillis() - t0) / 1000.0)
                readyLine = "${backend.name} ready: ${loaded.label} (${loaded.runtime}) in $secs s"
                statusState.value = readyLine
                SLog.d(TAG, readyLine + " from " + loaded.path)
            }.onFailure { t ->
                closeWrapper()
                backendState.value = null
                runtimeLabel = ""
                readyLine = STATUS_NONE
                statusState.value = "load failed (${backend.name}): ${t.message ?: t.javaClass.simpleName}"
                SLog.e(TAG, "load ${backend.name} failed", t)
            }
        }
    }

    private class Loaded(val wrapper: LlmWrapper, val label: String, val runtime: String, val path: String)

    private suspend fun loadNpu(): Loaded {
        val bundle = npuBundle()
        var qairtError: Throwable? = null
        if (bundle != null) {
            val label = "Qwen3-4B w4a16"
            statusState.value = "loading NPU model $label (qairt) from ${bundle.absolutePath}…"
            SLog.d(TAG, statusState.value)
            // Gate 1 (0.8.0): qairt wants model_path = the first context binary (…/part1_of_4.bin), not the folder;
            // loading it in place from /sdcard works (8.8 s) and needs no 3 GB copy. The folder form fails with
            // "File not found or inaccessible"; it is kept as a second attempt in case a later SDK accepts it.
            val firstBin = firstCtxBin(bundle)
            if (firstBin != null) {
                val viaBin = build(LlmCreateInput(
                    model_path = firstBin.absolutePath, tokenizer_path = null,
                    config = ModelConfig(nCtx = 0, nGpuLayers = 0), runtime_id = RUNTIME_QAIRT, compute_unit = null))
                if (viaBin.isSuccess) return Loaded(viaBin.getOrThrow(), label, RUNTIME_QAIRT, firstBin.absolutePath)
                qairtError = viaBin.exceptionOrNull()
                SLog.w(TAG, "direct qairt load (${firstBin.name}) failed: ${qairtError?.message}; trying the folder")
            }
            val direct = build(LlmCreateInput(
                model_path = bundle.absolutePath, tokenizer_path = null,
                config = ModelConfig(nCtx = 0, nGpuLayers = 0), runtime_id = RUNTIME_QAIRT, compute_unit = null))
            if (direct.isSuccess) return Loaded(direct.getOrThrow(), label, RUNTIME_QAIRT, bundle.absolutePath)
            qairtError = direct.exceptionOrNull()
            SLog.w(TAG, "direct qairt load (folder) failed: ${qairtError?.message}; trying the SDK model cache")

            val cached = runCatching { pullLocal(bundle) }
            if (cached.isSuccess) {
                val paths = cached.getOrThrow()
                statusState.value = "loading NPU model $label (qairt) from the SDK cache…"
                val viaCache = build(LlmCreateInput(
                    model_path = paths.model_path, tokenizer_path = paths.tokenizer_path,
                    config = ModelConfig(nCtx = 0, nGpuLayers = 0), runtime_id = paths.runtime_id ?: RUNTIME_QAIRT, compute_unit = null))
                if (viaCache.isSuccess) return Loaded(viaCache.getOrThrow(), label, RUNTIME_QAIRT, paths.model_path)
                qairtError = viaCache.exceptionOrNull()
                SLog.w(TAG, "qairt load from the SDK cache failed: ${qairtError?.message}")
            } else {
                SLog.w(TAG, "pull into the SDK cache failed: ${cached.exceptionOrNull()?.message}")
            }
        }
        // Fallback: the GGUF on llama.cpp's Hexagon backend.
        val gguf = ggufFile()
        if (gguf != null) {
            val label = gguf.nameWithoutExtension
            statusState.value = "qairt unavailable (${qairtError?.message ?: "no bundle"}); loading $label on llama_cpp/npu…"
            SLog.w(TAG, statusState.value)
            val w = build(LlmCreateInput(
                model_path = gguf.absolutePath, tokenizer_path = null,
                config = ModelConfig(nCtx = GGUF_CTX), runtime_id = RUNTIME_LLAMA, compute_unit = "npu")).getOrThrow()
            return Loaded(w, label, "$RUNTIME_LLAMA:npu", gguf.absolutePath)
        }
        throw IllegalStateException(
            if (bundle == null) "no NPU model: expected <root>/$NPU_DIR/genie_config.json under ${modelRoots.joinToString { it.absolutePath }}"
            else "qairt could not load ${bundle.absolutePath}: ${qairtError?.message}"
        )
    }

    private suspend fun loadGguf(computeUnit: String): Loaded {
        val gguf = ggufFile() ?: throw IllegalStateException(
            "no GGUF model: expected <root>/$GGUF_DIR/*.gguf under ${modelRoots.joinToString { it.absolutePath }}")
        val label = gguf.nameWithoutExtension
        statusState.value = "loading ${computeUnit.uppercase()} model $label (llama_cpp) from ${gguf.absolutePath}…"
        SLog.d(TAG, statusState.value)
        val cfg = if (computeUnit == "cpu") ModelConfig(nCtx = GGUF_CTX, nGpuLayers = 0) else ModelConfig(nCtx = GGUF_CTX)
        val w = build(LlmCreateInput(
            model_path = gguf.absolutePath, tokenizer_path = null, config = cfg,
            runtime_id = RUNTIME_LLAMA, compute_unit = computeUnit)).getOrThrow()
        return Loaded(w, label, "$RUNTIME_LLAMA:$computeUnit", gguf.absolutePath)
    }

    /** `ctx-bins[0]` from genie_config.json, else the alphabetically first `*.bin` in the bundle. */
    private fun firstCtxBin(bundle: File): File? {
        val fromConfig = runCatching {
            Regex("\"ctx-bins\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(File(bundle, "genie_config.json").readText())?.groupValues?.get(1)
        }.getOrNull()?.let { File(bundle, it) }?.takeIf { it.isFile }
        return fromConfig ?: bundle.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.minByOrNull { it.name }
    }

    private suspend fun build(input: LlmCreateInput): Result<LlmWrapper> =
        runCatching { LlmWrapper.builder().llmCreateInput(input).build().getOrThrow() }

    /** Copies the bundle into the SDK cache once (3 GB) and returns its paths; reuses an earlier copy. */
    private suspend fun pullLocal(bundle: File): ModelPaths {
        val known = runCatching { ModelManagerWrapper.list() }.getOrDefault(emptyList())
        if (LOCAL_MODEL_NAME !in known) {
            val totalBytes = bundle.listFiles()?.sumOf { it.length() } ?: 0L
            SLog.d(TAG, "pulling ${bundle.absolutePath} (${totalBytes / MB} MB) into the SDK cache as $LOCAL_MODEL_NAME")
            var error: String? = null
            ModelManagerWrapper.pullFlow(ModelPullInput(model_name = LOCAL_MODEL_NAME, hub = HubSource.LOCALFS, local_path = bundle.absolutePath))
                .collect { ev ->
                    when (ev) {
                        is ModelManagerWrapper.PullEvent.Progress -> {
                            val done = ev.files.sumOf { it.downloaded_bytes }
                            val total = ev.files.sumOf { it.total_bytes }.takeIf { it > 0 } ?: totalBytes
                            statusState.value = "copying NPU model into the SDK cache: ${done / MB} / ${total / MB} MB"
                        }
                        is ModelManagerWrapper.PullEvent.Error -> error = "pull error ${ev.code}: ${ev.message}"
                        is ModelManagerWrapper.PullEvent.Completed -> {}
                        else -> {}
                    }
                }
            error?.let { throw IllegalStateException(it) }
        }
        return ModelManagerWrapper.getPaths(LOCAL_MODEL_NAME)
            ?: throw IllegalStateException("SDK cache has no paths for $LOCAL_MODEL_NAME after the pull")
    }

    /**
     * Frees the NPU for the other big-slot model (swarm plan §4A.2: the LLM and the VLM cannot be resident together).
     * Waits for any running load/generate; [load] brings the model back.
     */
    suspend fun unload() = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeWrapper()
            backendState.value = null
            runtimeLabel = ""
            readyLine = STATUS_NONE
            statusState.value = "unloaded (NPU released)"
            SLog.d(TAG, "model unloaded")
        }
    }

    private fun closeWrapper() {
        val w = wrapper ?: return
        wrapper = null
        runCatching { w.destroy() }.onFailure { SLog.w(TAG, "destroy failed: ${it.message}") }
    }

    // ---- generate --------------------------------------------------------------------------------------------

    override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val w = wrapper ?: throw IllegalStateException("no model loaded (${statusState.value})")
                    // The qairt dialog keeps its KV state between calls (Gate 1: the second applyChatTemplate rendered
                    // only the new user turn). Every call here is stateless, so clear it first.
                    val rc = runCatching { w.reset() }.getOrElse { SLog.w(TAG, "reset threw: ${it.message}"); -1 }
                    if (rc != 0) SLog.w(TAG, "reset returned $rc")
                    val prompt = formatPrompt(w, messages)
                    val cfg = GenerationConfig(
                        maxTokens = params.maxTokens,
                        stopWords = params.stopWords.toTypedArray(),
                        stopCount = params.stopWords.size,
                        // temperature 0 = "bundle default", not greedy. A GBNF grammar only off the NPU: on qairt it throws
                        // "generate() unknown exception" and the stream never completes (measured, swarm plan §5.3).
                        samplerConfig = if (params.grammar != null && backendState.value != LlmBackend.NPU)
                            SamplerConfig(topK = 1, temperature = 0.01f, grammarString = params.grammar)
                        else SamplerConfig(topK = 1, temperature = 0.01f)
                    )
                    statusState.value = "generating on ${backendState.value?.name ?: "?"}…"
                    val sb = StringBuilder()
                    var profile: ProfilingData? = null
                    val brace = BraceTracker()
                    var stopped = false
                    val t0 = System.nanoTime()
                    var firstTokenNs = 0L
                    streaming = true
                    try {
                        w.generateStreamFlow(prompt, cfg).collect { r ->
                            when (r) {
                                is LlmStreamResult.Token -> {
                                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                                    if (!stopped) {
                                        sb.append(r.text)
                                        onToken?.invoke(r.text)
                                        if (params.stopOnBalancedJson && brace.feed(r.text)) {
                                            stopped = true
                                            SLog.d(TAG, "balanced JSON after ${sb.length} chars → stopStream")
                                            w.stopStream()
                                        }
                                    }
                                }
                                is LlmStreamResult.Completed -> profile = r.profile
                                is LlmStreamResult.Error -> throw r.throwable
                                else -> {}
                            }
                        }
                    } finally {
                        streaming = false
                        statusState.value = readyLine
                    }
                    val endNs = System.nanoTime()
                    var text = sb.toString()
                    for (sw in params.stopWords) { val i = text.indexOf(sw); if (i >= 0) text = text.substring(0, i) }
                    val stats = statsFrom(profile, prompt, text, t0, firstTokenNs, endNs)
                    statsState.value = stats
                    SLog.d(TAG, "gen ${TokStats.format(backendState.value, stats)} stop=${profile?.stopReason ?: if (stopped) "balanced-json" else "?"} chars=${text.length}")
                    GenResult(text, stats)
                }.onFailure { SLog.e(TAG, "generate failed", it) }
            }
        }

    override suspend fun cancel() {
        val w = wrapper ?: return
        if (!streaming) return
        runCatching { w.stopStream() }.onFailure { SLog.w(TAG, "stopStream failed: ${it.message}") }
    }

    private suspend fun formatPrompt(w: LlmWrapper, messages: List<ChatMsg>): String {
        if (!forceOwnTemplate) {
            val sdk = runCatching {
                w.applyChatTemplate(messages.map { ChatMessage(role = it.role, content = it.content) }.toTypedArray(), null, false, true)
                    .getOrThrow().formattedText
            }
            val text = sdk.getOrNull()
            if (!text.isNullOrBlank()) { lastPromptInfo = "sdk template, ${text.length} chars"; return text }
            SLog.w(TAG, "SDK chat template ${if (sdk.isFailure) "failed: " + sdk.exceptionOrNull()?.message else "returned empty"}; using ChatTemplate.qwen")
        }
        val own = ChatTemplate.qwen(messages)
        lastPromptInfo = "fallback template, ${own.length} chars"
        return own
    }

    private fun statsFrom(p: ProfilingData?, prompt: String, text: String, t0: Long, firstNs: Long, endNs: Long): GenStats {
        val wallPrefillMs = if (firstNs > 0) (firstNs - t0) / 1_000_000 else (endNs - t0) / 1_000_000
        val wallDecodeMs = if (firstNs > 0) (endNs - firstNs) / 1_000_000 else 0
        if (p == null) {
            val gen = maxOf(1, text.length / 4)
            return GenStats(prompt.length / 4, gen, wallPrefillMs, wallDecodeMs, if (wallDecodeMs > 0) gen * 1000.0 / wallDecodeMs else 0.0)
        }
        val prefillMs = when { p.promptTimeMs > 0 -> p.promptTimeMs.toLong(); p.ttftMs > 0 -> p.ttftMs.toLong(); else -> wallPrefillMs }
        val decodeMs = if (p.decodeTimeMs > 0) p.decodeTimeMs.toLong() else wallDecodeMs
        val gen = p.generatedTokens.toInt().takeIf { it > 0 } ?: maxOf(1, text.length / 4)
        val tps = if (p.decodingSpeed > 0) p.decodingSpeed else if (decodeMs > 0) gen * 1000.0 / decodeMs else 0.0
        return GenStats(p.promptTokens.toInt(), gen, prefillMs, decodeMs, tps)
    }

    /** String-aware brace depth over a token stream; `feed` returns true once a top-level object has closed. */
    private class BraceTracker {
        private var depth = 0; private var inString = false; private var escaped = false; private var opened = false
        fun feed(s: String): Boolean {
            for (ch in s) {
                if (inString) {
                    when { escaped -> escaped = false; ch == '\\' -> escaped = true; ch == '"' -> inString = false }
                } else when (ch) {
                    '"' -> inString = true
                    '{' -> { depth++; opened = true }
                    '}' -> { depth--; if (opened && depth <= 0) return true }
                }
            }
            return false
        }
    }

    companion object {
        const val TAG = "GenieX"
        const val NPU_DIR = "qwen3-4b-npu"
        const val GGUF_DIR = "gguf"
        const val RUNTIME_QAIRT = "qairt"
        const val RUNTIME_LLAMA = "llama_cpp"
        const val LOCAL_MODEL_NAME = "local/qwen3-4b-npu"
        const val STATUS_NONE = "no model loaded"
        const val GGUF_CTX = 2048
        private const val MB = 1024L * 1024L
        const val SHARED_ROOT = "/storage/emulated/0/Download/HiveCoreModels"

        /** `getExternalFilesDir("models")` then the shared Download folder (plan 8); both may hold models. */
        fun defaultRoots(context: Context): List<File> =
            listOfNotNull(context.getExternalFilesDir("models"), File(SHARED_ROOT))

        @Volatile private var sdkReady = false
        @Volatile var sdkVersion: String = ""
            private set

        /** `GenieXSdk.init` once per process; synchronous in 0.8.0. Safe to call from any thread. */
        @Synchronized
        fun ensureSdk(context: Context): Result<Unit> {
            if (sdkReady) return Result.success(Unit)
            var failure: String? = null
            val r = runCatching {
                GenieXSdk.getInstance().init(context.applicationContext, object : GenieXSdk.InitCallback {
                    override fun onSuccess() { sdkReady = true }
                    override fun onFailure(message: String) { failure = message }
                })
            }
            if (r.isFailure) return Result.failure(IllegalStateException("GenieX init threw: ${r.exceptionOrNull()?.message}", r.exceptionOrNull()))
            if (!sdkReady) return Result.failure(IllegalStateException("GenieX init failed: ${failure ?: "unknown"}"))
            sdkVersion = runCatching { GenieXSdk.getInstance().getSdkVersion() }.getOrDefault("?")
            SLog.d(TAG, "GenieX SDK $sdkVersion initialised")
            return Result.success(Unit)
        }
    }
}
