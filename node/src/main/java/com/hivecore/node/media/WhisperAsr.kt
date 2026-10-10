package com.hivecore.node.media

import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.runtime.models.TensorIo
import com.hivecore.runtime.models.TensorSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One transcription: [tokens] are the generated ids (prompt and EOT excluded). */
data class AsrResult(val text: String, val tokens: List<Int>, val encMs: Long, val decMs: Long, val melMs: Long, val steps: Int)

/**
 * Whisper-tiny on the NPU (AI Hub `whisper_tiny` precompiled_qnn_onnx, run by OrtQnnRuntime; swarm plan §4A.3 ASR).
 *
 * Graphs (from the asset's metadata.json, all float16 except ids):
 *  - `encoder`: `input_features` [1,80,3000] → `k_cache_cross_i` [6,1,64,1500], `v_cache_cross_i` [6,1,1500,64], i = 0..3.
 *  - `decoder`: `input_ids` [1,1] int32, `position_ids` [1] int32, `attention_mask` [1,1,1,200],
 *    `k_cache_self_i_in` [6,1,64,199], `v_cache_self_i_in` [6,1,199,64], the 8 cross caches
 *    → `logits` [1,51865,1,1], `k_cache_self_i_out`, `v_cache_self_i_out` (same shapes as the inputs).
 *
 * Decode loop, as AI Hub's `HfWhisperApp._transcribe_single_chunk` (qai_hub_models 0.64.0) drives the same graphs:
 * one token per call; self caches start at zero and each call's `_out` caches are fed back as the next `_in`
 * (the graph shifts the 199-slot window itself); at step n the mask slot `199 − n` is set from −100 ([MASK_NEG])
 * to 0, so the n + 1 most recent slots are visible; `position_ids` = n. Differences from AI Hub's app, both standard
 * openai/whisper decoding options: the prompt is forced to `<|startoftranscript|><|en|><|transcribe|><|notimestamps|>`
 * (fed one token per call, logits ignored until the last), and the generated logits suppress every special and
 * timestamp id except EOT, plus EOT and " " (220) on the first generated token (openai `SuppressBlank`).
 * Greedy argmax (ties → lowest id), stop at EOT or after [maxTokens]. Same audio ⇒ same input bytes ⇒ same tokens.
 */
class WhisperAsr(
    private val pool: ModelPool,
    private val runtime: ModelRuntime,
    private val tokenizer: WhisperTokenizer?,
    val modelId: String = MODEL_ID,
    private val maxTokens: Int = MAX_TOKENS,
) {
    /** Transcribes ≤ 30 s of 16 kHz mono PCM16 (longer input is trimmed to 30 s: split it with [Vad] first). */
    suspend fun transcribe(pcm: ShortArray): Result<AsrResult> {
        val t0 = System.currentTimeMillis()
        val mel = LogMel.halfBytes(pcm)
        val melMs = System.currentTimeMillis() - t0
        return pool.with(modelId) { s -> decode(s, mel).let { it.copy(melMs = melMs) } }
    }

    /** Runs the encoder on [melHalf] (float16 [1,80,3000]) and greedy-decodes. */
    suspend fun decode(session: ModelSession, melHalf: ByteArray): AsrResult {
        val t0 = System.currentTimeMillis()
        val enc = runtime.run(session, mapOf(TensorSession.GRAPH to "encoder", "input_features" to melHalf)).getOrThrow()
        val cross = LinkedHashMap<String, Any>()
        for (i in 0 until LAYERS) for (kv in listOf("k", "v")) {
            val n = "${kv}_cache_cross_$i"
            cross[n] = out(enc, n)
        }
        val t1 = System.currentTimeMillis()
        val k = Array(LAYERS) { ByteArray(SELF_BYTES) }
        val v = Array(LAYERS) { ByteArray(SELF_BYTES) }
        val mask = FloatArray(SLOTS) { MASK_NEG }
        val prompt = PROMPT
        val generated = ArrayList<Int>()
        var token = prompt[0]
        var steps = 0
        for (n in 0 until SLOTS - 1) {
            mask[SLOTS - 1 - n] = 0f
            val inputs = LinkedHashMap<String, Any>()
            inputs[TensorSession.GRAPH] = "decoder"
            inputs["input_ids"] = intArrayOf(token)
            inputs["position_ids"] = intArrayOf(n)
            inputs["attention_mask"] = halves(mask)
            for (i in 0 until LAYERS) { inputs["k_cache_self_${i}_in"] = k[i]; inputs["v_cache_self_${i}_in"] = v[i] }
            inputs.putAll(cross)
            val o = runtime.run(session, inputs).getOrThrow()
            steps++
            for (i in 0 until LAYERS) { k[i] = out(o, "k_cache_self_${i}_out"); v[i] = out(o, "v_cache_self_${i}_out") }
            if (n < prompt.size - 1) { token = prompt[n + 1]; continue }
            val next = argmax(out(o, "logits"), first = generated.isEmpty())
            if (next == WhisperTokenizer.EOT) break
            generated += next
            if (generated.size >= maxTokens) break
            token = next
        }
        val t2 = System.currentTimeMillis()
        val text = tokenizer?.decode(generated)?.trim() ?: ""
        return AsrResult(text, generated, t1 - t0, t2 - t1, 0, steps)
    }

    companion object {
        const val MODEL_ID = "whisper_tiny"
        const val MAX_TOKENS = 120
        const val LAYERS = 4
        const val SLOTS = 200
        const val MASK_NEG = -100f
        const val VOCAB = 51865
        /** [6,1,64,199] float16 = [6,1,199,64] float16 */
        const val SELF_BYTES = 6 * 64 * (SLOTS - 1) * 2
        const val BLANK = 220
        val PROMPT = intArrayOf(WhisperTokenizer.SOT, WhisperTokenizer.EN, WhisperTokenizer.TRANSCRIBE, WhisperTokenizer.NO_TIMESTAMPS)

        /** Output [name] as bytes (by exact name, else the output whose name ends with it). */
        internal fun out(o: Map<String, Any>, name: String): ByteArray {
            val v = o[name] ?: o.entries.firstOrNull { it.key.substringAfterLast('.') == name }?.value
                ?: throw IllegalStateException("whisper output '$name' missing (have ${o.keys.joinToString()})")
            return TensorIo.toBytes(v, "float16")
        }

        internal fun halves(f: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(f.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            f.forEach { b.putShort(TensorIo.floatToHalf(it)) }
            return b.array()
        }

        /**
         * Greedy choice over float16 logits: specials and timestamps (≥ EOT) are suppressed except EOT itself; on the
         * [first] generated token EOT and blank are suppressed too. Ties go to the lowest id.
         */
        internal fun argmax(logits: ByteArray, first: Boolean): Int {
            val b = ByteBuffer.wrap(logits).order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(VOCAB, logits.size / 2)
            var best = -1; var bestV = Float.NEGATIVE_INFINITY
            for (id in 0 until n) {
                if (id > WhisperTokenizer.EOT) continue
                if (first && (id == WhisperTokenizer.EOT || id == BLANK)) continue
                val x = TensorIo.halfToFloat(b.getShort(id * 2))
                if (x.isNaN()) continue
                if (best < 0 || x > bestV) { best = id; bestV = x }
            }
            return if (best < 0) WhisperTokenizer.EOT else best
        }
    }
}
