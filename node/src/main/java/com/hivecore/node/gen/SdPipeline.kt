package com.hivecore.node.gen

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.runtime.models.ManifestSpecs
import com.hivecore.runtime.models.TensorDesc
import com.hivecore.runtime.models.TensorIo
import com.hivecore.runtime.models.TensorSession
import com.hivecore.node.vision.ClipBpe
import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Png
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

// Swarm plan §4A.3 GEN_IMAGE: Stable Diffusion 1.5 w8a16 (AI Hub v0.64.0 precompiled_qnn_onnx) through the ORT QNN
// runtime. The registry model `sd15` is ONE session with three graphs (file stems): text_encoder, unet, vae.
//
// What the AI Hub export expects (from its metadata.json, see models/aihub/sd15/manifest.json):
//  - text_encoder: tokens int32 [1,77] → text_embedding uint16 [1,77,768] (scale 9.30e-4, zp 30063). CLIP ViT-L/14
//    BPE ids, <|startoftext|> … <|endoftext|>, padded with <|endoftext|> (49407) as SD's CLIPTokenizer does.
//  - unet: latent uint16 NHWC [1,64,64,4], timestep uint16 [1,1] (scale 0.01477, zp 0 → the raw training timestep
//    0…968 as a real number), text_emb uint16 [1,77,768] → output_latent uint16 [1,64,64,4] = predicted noise ε.
//    uint16 inputs are fed as real values; the runtime's CPU QuantizeLinear adapter (unet_q.onnx) quantizes them.
//  - vae: latent uint16 [1,64,64,4] → image uint16 NHWC [1,512,512,3], scale 1/65535, zp 0, i.e. RGB in [0,1].
//    The [0,1] output means the graph already contains (x/2 + 0.5).clamp(0,1). Assumption (checked on the phone):
//    the graph also contains the 1/0.18215 latent scaling, so the solver's x0 is passed unscaled
//    ([GenParams.vaeInputScale] = 1.0; set 1/0.18215 if a decode looks washed out / low contrast).
// Classifier-free guidance: two UNet calls per step (unconditional = empty prompt, conditional), ε = εu + g(εc − εu).
// All arithmetic between graphs is in double; tensors cross into the runtime as float32.

data class GenParams(
    val prompt: String,
    val negative: String = "",
    val seed: Long = 0,
    val steps: Int = 20,
    val guidance: Double = 7.5,
    val size: Int = 512,
    val vaeInputScale: Double = 1.0,
)

class GenImage(
    /** PNG with an embedded tEXt metadata chunk (AI-generated marker). */
    val png: ByteArray,
    /** packed RGB bytes, row-major, the replica comparison input ([rgbSha256]) */
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val metadata: JsonObject,
    val pngSha256: String,
    val rgbSha256: String,
    /** stage → ms (tokenize, textEncoder, unet, unetPerCall, scheduler, vae, png, total); not part of any hash */
    val timings: Map<String, Long>,
    /** diagnostics: latent/eps statistics (not hashed) */
    val stats: Map<String, Double>,
)

class SdPipeline(
    private val pool: ModelPool,
    private val runtime: ModelRuntime,
    /** CLIP BPE for the model (merges file in the model folder); see [bpeFrom]. */
    private val tokenizer: (ModelManifest) -> ClipBpe,
    val modelId: String = MODEL_ID,
) {
    suspend fun generate(p: GenParams, onStep: ((Int, Int) -> Unit)? = null): GenImage {
        require(p.size == 512) { "only 512 px is supported by $modelId" }
        require(p.steps in 1..100) { "steps must be 1..100" }
        return pool.with(modelId) { s -> run(s, p, onStep) }.getOrThrow()
    }

    private suspend fun run(s: ModelSession, p: GenParams, onStep: ((Int, Int) -> Unit)?): GenImage {
        val m = s.manifest
        val tAll = System.nanoTime()
        val times = LinkedHashMap<String, Long>()
        val ins = ManifestSpecs.parse(m.inputSpec)
        val outs = ManifestSpecs.parse(m.outputSpec)
        fun inSpec(g: String, n: String) = ins.firstOrNull { it.graph == g && it.name == n } ?: error("${m.id}: no input $g.$n in inputSpec")
        fun outSpec(g: String) = outs.firstOrNull { it.graph == g } ?: error("${m.id}: no output for graph $g in outputSpec")
        val tokSpec = inSpec(TEXT, "tokens")
        val latSpec = inSpec(UNET, "latent")
        val embOut = outSpec(TEXT); val epsOut = outSpec(UNET); val imgOut = outSpec(VAE)
        val ctx = tokSpec.shape.lastOrNull()?.toInt() ?: 77
        val latentN = latSpec.elements.toInt()

        // ---- text
        var t = System.nanoTime()
        val bpe = tokenizer(m)
        val condIds = tokens(bpe, p.prompt, ctx)
        val uncondIds = tokens(bpe, p.negative, ctx)
        times["tokenize"] = ms(t)
        t = System.nanoTime()
        val condEmb = read(call(s, TEXT, mapOf("tokens" to int32(condIds))), embOut)
        val uncondEmb = if (p.guidance == 1.0) null else read(call(s, TEXT, mapOf("tokens" to int32(uncondIds))), embOut)
        times["textEncoder"] = ms(t)

        // ---- denoise
        val sched = DpmSolverPP2M(p.steps)
        var x = sched.initLatent(Noise.gaussian(p.seed, latentN))
        var unetNs = 0L; var schedNs = 0L; var unetCalls = 0
        var lastEpsStd = 0.0
        for (i in 0 until p.steps) {
            val ts = floatArrayOf(sched.timesteps[i].toFloat())
            val xf = FloatArray(latentN) { x[it].toFloat() }
            var t0 = System.nanoTime()
            val ec = read(call(s, UNET, mapOf("latent" to xf, "timestep" to ts, "text_emb" to condEmb)), epsOut)
            unetCalls++
            val eps: DoubleArray = if (uncondEmb == null) DoubleArray(latentN) { ec[it].toDouble() } else {
                val eu = read(call(s, UNET, mapOf("latent" to xf, "timestep" to ts, "text_emb" to uncondEmb)), epsOut)
                unetCalls++
                cfg(eu, ec, p.guidance)
            }
            unetNs += System.nanoTime() - t0
            lastEpsStd = std(eps)
            t0 = System.nanoTime()
            x = sched.step(eps, x)
            schedNs += System.nanoTime() - t0
            onStep?.invoke(i + 1, p.steps)
        }
        times["unet"] = unetNs / 1_000_000
        times["unetPerCall"] = if (unetCalls == 0) 0 else unetNs / 1_000_000 / unetCalls
        times["scheduler"] = schedNs / 1_000_000

        // ---- decode
        t = System.nanoTime()
        val vin = FloatArray(latentN) { (x[it] * p.vaeInputScale).toFloat() }
        val rawImg = call(s, VAE, mapOf("latent" to vin))[imgOut.name] as? ByteArray ?: error("${m.id}: vae output ${imgOut.name} missing")
        times["vae"] = ms(t)
        t = System.nanoTime()
        val h = imgOut.shape.getOrElse(1) { 512 }.toInt(); val w = imgOut.shape.getOrElse(2) { 512 }.toInt()
        val rgb = toRgb8(rawImg, imgOut, w * h * 3)
        val rgbSha = sha256(rgb)
        val meta = metadata(m, p, rgbSha)
        val png = withText(Png.encode(Img(w, h, IntArray(w * h) { i -> Img.rgb(rgb[3 * i].toInt() and 255, rgb[3 * i + 1].toInt() and 255, rgb[3 * i + 2].toInt() and 255) }), gray = false),
            mapOf("Software" to "HiveCore / ${m.id}", "AI-Generated" to "true", "hivecore.gen" to meta.toString()))
        times["png"] = ms(t)
        times["total"] = ms(tAll)
        val stats = linkedMapOf("x0Mean" to x.average(), "x0Std" to std(x), "x0Min" to (x.minOrNull() ?: 0.0), "x0Max" to (x.maxOrNull() ?: 0.0),
            "lastEpsStd" to lastEpsStd, "rgbMean" to rgb.sumOf { (it.toInt() and 255).toLong() }.toDouble() / rgb.size)
        return GenImage(png, rgb, w, h, meta, sha256(png), rgbSha, times, stats)
    }

    private suspend fun call(s: ModelSession, graph: String, feeds: Map<String, Any>): Map<String, Any> =
        runtime.run(s, LinkedHashMap<String, Any>().apply { put(TensorSession.GRAPH, graph); putAll(feeds) }).getOrThrow()

    private fun read(out: Map<String, Any>, d: TensorDesc): FloatArray {
        val b = out[d.name] as? ByteArray ?: error("output ${d.graph}.${d.name} missing (got ${out.keys})")
        return TensorIo.dequantize(b, d.dtype, d.scale, d.zeroPoint ?: 0)
    }

    private fun metadata(m: ModelManifest, p: GenParams, rgbSha: String): JsonObject = buildJsonObject {
        put("aiGenerated", true)
        put("generator", "HiveCore")
        put("model", m.id)
        put("modelSha256", m.sha256)
        put("licence", m.licence)
        put("runtime", m.runtime)
        put("backend", m.backend)
        put("scheduler", SCHEDULER)
        put("seed", p.seed)
        put("steps", p.steps)
        put("guidance", p.guidance)
        put("size", p.size)
        put("promptSha256", sha256(p.prompt.toByteArray(Charsets.UTF_8)))
        put("negativeSha256", sha256(p.negative.toByteArray(Charsets.UTF_8)))
        if (p.vaeInputScale != 1.0) put("vaeInputScale", p.vaeInputScale)
        put("rgbSha256", rgbSha)
    }

    companion object {
        const val MODEL_ID = "sd15"
        const val TEXT = "text_encoder"
        const val UNET = "unet"
        const val VAE = "vae"
        const val SCHEDULER = "dpmsolver++2m;scaled_linear 0.00085-0.012/1000;leading+1;eps"
        const val LATENT_SCALE = 0.18215

        /** SD's CLIP ids: <sot> + BPE (≤ ctx−2) + <eot>, padded with <eot>. */
        fun tokens(bpe: ClipBpe, text: String, ctx: Int = 77): IntArray {
            val body = bpe.encode(text).take(ctx - 2)
            val ids = IntArray(ctx) { bpe.eot }
            ids[0] = bpe.sot
            body.forEachIndexed { i, v -> ids[i + 1] = v }
            return ids
        }

        /** ε = εu + g·(εc − εu), in double. */
        fun cfg(uncond: FloatArray, cond: FloatArray, g: Double): DoubleArray =
            DoubleArray(cond.size) { uncond[it] + g * (cond[it].toDouble() - uncond[it]) }

        fun int32(v: IntArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()

        /** VAE output → RGB bytes: real = (q − zp)·scale in [0,1] → round(real·255), clamped. */
        fun toRgb8(raw: ByteArray, d: TensorDesc, n: Int): ByteArray {
            val v = TensorIo.dequantize(raw, d.dtype, d.scale, d.zeroPoint ?: 0)
            require(v.size >= n) { "vae output has ${v.size} values, expected $n" }
            return ByteArray(n) { Math.round(v[it].toDouble() * 255.0).toInt().coerceIn(0, 255).toByte() }
        }

        fun std(x: DoubleArray): Double {
            if (x.isEmpty()) return 0.0
            val m = x.average()
            var s = 0.0
            for (v in x) s += (v - m) * (v - m)
            return Math.sqrt(s / x.size)
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

        /** The model folder's CLIP merges file (bpe_simple_vocab_16e6.txt.gz), loaded once per model. */
        fun bpeFrom(registry: ModelRegistry): (ModelManifest) -> ClipBpe {
            val cache = HashMap<String, ClipBpe>()
            return { m ->
                synchronized(cache) {
                    cache.getOrPut(m.id) {
                        val dir = registry.dir(m.id) ?: error("${m.id}: no model folder")
                        val f = ClipBpe.find(dir) ?: error("${m.id}: CLIP BPE merges missing (one of ${ClipBpe.MERGES_FILES} in ${dir.path})")
                        ClipBpe.load(f)
                    }
                }
            }
        }

        /** Inserts tEXt chunks (Latin-1 keyword/value) before IEND of a PNG. */
        fun withText(png: ByteArray, entries: Map<String, String>): ByteArray {
            val iend = png.size - 12
            require(iend > 8 && String(png, iend + 4, 4, Charsets.US_ASCII) == "IEND") { "not a PNG ending in IEND" }
            val out = ByteArrayOutputStream(png.size + 1024)
            out.write(png, 0, iend)
            val d = DataOutputStream(out)
            for ((k, v) in entries) {
                val data = k.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + v.toByteArray(Charsets.ISO_8859_1)
                val type = "tEXt".toByteArray(Charsets.US_ASCII)
                d.writeInt(data.size); d.write(type); d.write(data)
                val crc = CRC32(); crc.update(type); crc.update(data); d.writeInt(crc.value.toInt())
            }
            out.write(png, iend, 12)
            return out.toByteArray()
        }
    }
}
