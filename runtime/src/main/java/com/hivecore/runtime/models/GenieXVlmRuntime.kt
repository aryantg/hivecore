package com.hivecore.runtime.models

import android.content.Context
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.SLog
import com.hivecore.runtime.llm.GenieXEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Qwen3-VL-4B on the NPU through GenieX 0.8.0 `VlmWrapper` (swarm plan §4A.2, VLM_ASK §4A.3). Verified on the
 * iQOO 15: `model_path` = `<bundle>/part1_of_4.bin` (the folder form is the fallback), runtime `qairt`,
 * `ModelConfig(nCtx = 0, nGpuLayers = 0)`; loads in about 8 s; cannot be resident with the Qwen3-4B LLM (NPU map
 * error 8003), which is why [ModelPoolImpl] owns the big slot.
 *
 * Only closed questions (never free captions into tables): run inputs
 * `{"image": <path>, "question": String, "options": [..] | "a,b,c", "maxTokens"?: Int}` → outputs
 * `{"text": raw answer, "choice": matched option or null, "ms": Long}`. Greedy sampling (topK 1), `reset()` first.
 */
class GenieXVlmRuntime(private val context: Context) : ModelRuntime {
    override val id: String = ID

    override fun supports(m: ModelManifest): Boolean = m.runtime == ID

    override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> = withContext(Dispatchers.IO) {
        runCatching {
            GenieXEngine.ensureSdk(context).getOrThrow()
            val t0 = System.currentTimeMillis()
            val first = File(dir, m.files.first())
            val attempts = listOf(first.takeIf { it.isFile }?.absolutePath ?: File(dir, "part1_of_4.bin").absolutePath, dir.absolutePath).distinct()
            val errors = mutableListOf<String>()
            for (path in attempts) {
                val r = VlmWrapper.builder().vlmCreateInput(VlmCreateInput(
                    model_path = path, mmproj_path = null, config = ModelConfig(nCtx = 0, nGpuLayers = 0),
                    runtime_id = "qairt", compute_unit = null)).build()
                if (r.isSuccess) {
                    val ms = System.currentTimeMillis() - t0
                    SLog.d(TAG, "${m.id} loaded via ${File(path).name} in $ms ms")
                    return@runCatching Session(m, r.getOrThrow(), ms)
                }
                errors += "${File(path).name}: ${r.exceptionOrNull()?.message}"
            }
            throw IllegalStateException("VLM load failed: ${errors.joinToString(" | ")}")
        }
    }

    override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = runCatching {
        val s = session as? Session ?: throw IllegalArgumentException("not a VLM session")
        val image = (inputs["image"] as? String) ?: throw IllegalArgumentException("VLM needs an image path")
        require(File(image).isFile) { "no such image: $image" }
        val question = inputs["question"] as? String ?: throw IllegalArgumentException("VLM needs a question")
        val options = ClosedAnswer.options(inputs["options"])
        val maxTokens = (inputs["maxTokens"] as? Number)?.toInt() ?: 24
        val t0 = System.currentTimeMillis()
        val text = s.ask(image, ClosedAnswer.prompt(question, options), maxTokens)
        mapOf("text" to text, "choice" to (ClosedAnswer.match(text, options) ?: ""), "ms" to (System.currentTimeMillis() - t0))
    }

    class Session internal constructor(override val manifest: ModelManifest, private val vlm: VlmWrapper, val loadMs: Long) : ModelSession {
        private val lock = Mutex()

        suspend fun ask(image: String, q: String, maxTokens: Int): String = lock.withLock {
            vlm.reset()
            val msgs = arrayOf(VlmChatMessage(role = "user", contents = listOf(VlmContent("image", image), VlmContent("text", q))))
            val prompt = vlm.applyChatTemplate(msgs, null, false).getOrThrow().formattedText
            val cfg = vlm.injectMediaPathsToConfig(msgs, GenerationConfig(maxTokens = maxTokens, stopWords = arrayOf("<|im_end|>"),
                stopCount = 1, samplerConfig = SamplerConfig(topK = 1, temperature = 0.01f)))   // temperature 0 = bundle default
            val sb = StringBuilder()
            vlm.generateStreamFlow(prompt, cfg).collect { r ->
                when (r) {
                    is LlmStreamResult.Token -> sb.append(r.text)
                    is LlmStreamResult.Error -> throw r.throwable
                    else -> {}
                }
            }
            sb.toString().substringBefore("<|im_end|>").trim()
        }

        override fun close() { runCatching { vlm.close() }.onFailure { SLog.w(TAG, "close failed: ${it.message}") } }
    }

    companion object {
        const val ID = "geniex_vlm"
        private const val TAG = "GenieXVlm"
    }
}

/** Closed-question prompt and answer matching for VLM_ASK (pure; the Decision is built by the caller). */
object ClosedAnswer {
    fun options(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        is JsonArray -> v.map { it.jsonPrimitive.content }
        is List<*> -> v.mapNotNull { it?.toString() }
        is Array<*> -> v.mapNotNull { it?.toString() }
        else -> listOf(v.toString())
    }

    fun prompt(question: String, options: List<String>): String =
        if (options.isEmpty()) question else "$question Answer with exactly one of: ${options.joinToString(", ")}."

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** The single option the answer names: an exact match, else the only option it contains as whole words; else null. */
    fun match(answer: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        val a = norm(answer)
        if (a.isEmpty()) return null
        options.firstOrNull { norm(it) == a }?.let { return it }
        val padded = " $a "
        val contained = options.filter { o -> norm(o).let { it.isNotEmpty() && padded.contains(" $it ") } }
        return contained.singleOrNull()
    }
}
