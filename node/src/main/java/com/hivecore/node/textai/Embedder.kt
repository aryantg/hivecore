package com.hivecore.node.textai

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.node.vision.TensorIo
import com.hivecore.node.vision.TensorSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One text's embedding: L2-normalised float vector + its int8 quantisation (round(v·127)), as KNN/CLUSTER use. */
data class Embedding(val id: String, val vec: FloatArray, val q8: ByteArray, val tokens: Int, val truncated: Boolean)

/**
 * EMBED (swarm plan §4A.3): WordPiece → the registry model through [ModelPool] / [runtime] → pooling → L2 norm →
 * int8. Runtime I/O follows vision/TensorIo: inputs keyed by tensor name as little-endian int32 ByteArrays, outputs
 * read by name (else position) and dequantised per the spec.
 *
 * Verified AI Hub assets (metadata.json): MiniLM-v2 w8a8 — inputs `input_ids`, `attention_mask` int32 [1,128],
 * output `embeddings` uint8 [1,384] (scale 0.00175, zp 134); Nomic-embed-text float — inputs `input_tokens`,
 * `attention_masks` int32 [1,128], output `embeddings` float32 [1,512]. Both outputs are already pooled, so a
 * [batch, dim] output is taken as is; a [batch, seq, dim] output is mean-pooled under the attention mask.
 *
 * manifest.preprocess keys (all optional): `vocab` (file in the model folder, default vocab.txt), `normalize`
 * (default true), `prefix` (prepended to every text; Nomic expects "search_document: " / "search_query: " /
 * "clustering: " / "classification: ").
 */
class Embedder(
    private val pool: ModelPool,
    private val runtime: ModelRuntime,
    private val registry: ModelRegistry?,
) {
    private val tokenizers = HashMap<String, WordPiece>()

    /** Embeds [texts] (id → text, order kept). [prefix] overrides the manifest's prefix. */
    suspend fun embed(modelId: String, texts: List<Pair<String, String>>, prefix: String? = null): List<Embedding> =
        pool.with(modelId) { s ->
            val m = s.manifest
            val wp = tokenizer(m)
            val ins = TensorIo.inputs(m.inputSpec)
            val idsSpec = ins.firstOrNull { n -> (n.name.contains("ids") || n.name.contains("token")) && !n.name.contains("type") && !n.name.contains("mask") }
                ?: ins.firstOrNull() ?: error("${m.id}: no inputSpec")
            val maskSpec = ins.firstOrNull { it !== idsSpec && it.name.contains("mask") }
            val typeSpec = ins.firstOrNull { it !== idsSpec && it.name.contains("type") }
            val seq = idsSpec.shape.lastOrNull()?.takeIf { it > 1 } ?: 128
            val batch = if (idsSpec.shape.size >= 2) idsSpec.shape[0].coerceAtLeast(1) else 1
            val outSpec = TensorIo.outputs(m.outputSpec).let { o -> o.firstOrNull { it.name.contains("embed") || it.name.contains("pool") } ?: o.firstOrNull() }
                ?: TensorSpec("0", 0, emptyList(), "float32", null, 0)
            val pre = m.preprocess
            val pfx = prefix ?: (pre["prefix"] as? JsonPrimitive)?.content ?: ""
            val normalize = (pre["normalize"] as? JsonPrimitive)?.booleanOrNull ?: true
            val result = ArrayList<Embedding>(texts.size)
            for (chunk in texts.chunked(batch)) {
                val enc = chunk.map { (_, t) -> wp.encode(pfx + t, seq) }
                val ids = IntArray(batch * seq) { wp.pad }
                val mask = IntArray(batch * seq)
                enc.forEachIndexed { b, e -> e.ids.copyInto(ids, b * seq); e.mask.copyInto(mask, b * seq) }
                val feed = LinkedHashMap<String, Any>()
                feed[idsSpec.name] = pack(ids, idsSpec)
                if (maskSpec != null) feed[maskSpec.name] = pack(mask, maskSpec)
                if (typeSpec != null) feed[typeSpec.name] = pack(IntArray(batch * seq), typeSpec)
                val out = runtime.run(s, feed).getOrThrow()
                val flat = TensorIo.read(out, outSpec)
                val vecs = pooled(flat, outSpec.shape, batch, seq, mask)
                chunk.forEachIndexed { b, (id, _) ->
                    val v = if (normalize) l2(vecs[b]) else vecs[b]
                    result += Embedding(id, v, quantize(if (normalize) v else l2(v)), enc[b].tokens, enc[b].truncated)
                }
            }
            result
        }.getOrThrow()

    private fun tokenizer(m: ModelManifest): WordPiece = tokenizers.getOrPut(m.id) {
        val name = (m.preprocess["vocab"] as? JsonPrimitive)?.content ?: WordPiece.VOCAB_FILE
        val dir = registry?.dir(m.id) ?: error("${m.id}: no model folder to load $name from")
        val f = File(dir, name)
        require(f.isFile) { "${m.id}: tokenizer vocabulary $name missing from the model folder (bert-base-uncased vocab.txt)" }
        WordPiece.load(f)
    }

    companion object {
        /** int32 inputs as little-endian bytes; int64 inputs as LongArray-equivalent bytes; float32 as FloatArray. */
        fun pack(v: IntArray, spec: TensorSpec): Any = when (spec.dtype) {
            "int64" -> java.nio.ByteBuffer.allocate(v.size * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putLong(it.toLong()) } }.array()
            "float32" -> FloatArray(v.size) { v[it].toFloat() }
            else -> TensorIo.int32Bytes(v)
        }

        /**
         * Per-row vectors from the model output: shape [batch, dim] (or [dim]) is taken as pooled; [batch, seq, dim]
         * is mean-pooled over positions whose [mask] is 1.
         */
        fun pooled(flat: FloatArray, shape: List<Int>, batch: Int, seq: Int, mask: IntArray): List<FloatArray> {
            if (shape.size == 3) {
                val (b, s, d) = Triple(shape[0], shape[1], shape[2])
                require(b * s * d == flat.size) { "output size ${flat.size} ≠ $shape" }
                return (0 until b).map { r -> meanPool(flat.copyOfRange(r * s * d, (r + 1) * s * d), s, d, mask.copyOfRange(r * seq, r * seq + s)) }
            }
            val d = flat.size / batch
            require(d * batch == flat.size && d > 0) { "output size ${flat.size} not divisible by batch $batch" }
            return (0 until batch).map { r -> flat.copyOfRange(r * d, (r + 1) * d) }
        }

        /** Mean of the [seq]×[dim] token vectors whose mask is 1 (all of them when the mask is empty). */
        fun meanPool(tokens: FloatArray, seq: Int, dim: Int, mask: IntArray): FloatArray {
            val acc = DoubleArray(dim)
            var n = 0
            for (t in 0 until seq) {
                if (mask.isNotEmpty() && mask[t] == 0) continue
                n++
                for (j in 0 until dim) acc[j] += tokens[t * dim + j].toDouble()
            }
            val den = n.coerceAtLeast(1).toDouble()
            return FloatArray(dim) { (acc[it] / den).toFloat() }
        }

        fun l2(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s)
            return if (n == 0.0) v.copyOf() else FloatArray(v.size) { (v[it] / n).toFloat() }
        }

        /** Unit vector → int8 with scale 1/127 (|v| ≤ 1 so no clipping in practice). */
        fun quantize(unit: FloatArray): ByteArray = ByteArray(unit.size) { (unit[it] * 127f).roundToInt().coerceIn(-127, 127).toByte() }

        fun dequantize(q: ByteArray): FloatArray = FloatArray(q.size) { q[it] / 127f }
    }
}
