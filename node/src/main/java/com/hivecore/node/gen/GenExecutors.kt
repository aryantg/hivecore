package com.hivecore.node.gen

import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.node.vision.Iqa
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

// Swarm plan §4A.3 image generation executors. Outputs are deterministic JSON (fixed key order, scores rounded to
// 4 dp, no timings), so replicas compare by canonical-JSON hash; images are written content-addressed to [outDir]
// as "<pngSha256>.png" plus a "<pngSha256>.json" metadata sidecar. Guardrails: the prompt blocklist runs before
// any model call, the post-check after; a blocked output is dropped and never written.

/** Generates one image (the GEN_IMAGE / GEN_VARIANTS work unit). Bound to [SdPipeline.generate] by default. */
fun interface ImageGenerator { suspend fun generate(p: GenParams): GenImage }

/** CLIP text–image similarity for SCORE_IMAGE (bound by the integrator to the CLIP model): cosine in [-1, 1]. */
fun interface ClipScorer { suspend fun score(image: File, prompt: String): Double }

/** UPSCALE delegate: runs vision SUPERRES (tiled) on [task] (op already SUPERRES) over [blobs]. */
fun interface SuperRes { suspend fun run(task: TaskSpec, blobs: List<File>): Result<JsonElement> }

class GenExecutor(
    private val generator: ImageGenerator,
    private val outDir: File,
    private val codec: ImageCodec? = null,
    private val postCheck: ImageSafetyCheck? = null,
    private val clip: ClipScorer? = null,
    private val superRes: SuperRes? = null,
    private val modelId: String = SdPipeline.MODEL_ID,
) : OpExecutor {
    override val ops: Set<Op> = setOf(Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.SCORE_IMAGE, Op.UPSCALE)

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = runCatching {
        require(task.op in ops) { "GenExecutor does not run ${task.op}" }
        when (task.op) {
            Op.GEN_IMAGE -> gen(task, listOf(seedOf(task.params)))
            Op.GEN_VARIANTS -> gen(task, seedsOf(task.params))
            Op.SCORE_IMAGE -> score(task, blobs)
            Op.UPSCALE -> upscale(task, blobs)
            else -> error("unreachable")
        }
    }

    // ---------------------------------------------------------------------------------------------- GEN_IMAGE

    /**
     * params: prompt (required), negative (""), seed (0) | seeds [..] | seed + count (GEN_VARIANTS), steps (20),
     * guidance (7.5), size (512). Normally the commander shards seeds across phones (one GEN_IMAGE per seed);
     * GEN_VARIANTS fans out within this task only when asked to.
     */
    private suspend fun gen(task: TaskSpec, seeds: List<Long>): JsonElement {
        val p = task.params
        val prompt = p.str("prompt")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("${task.op} needs a prompt")
        val base = GenParams(prompt, p.str("negative") ?: "", 0, p.int("steps") ?: 20, p.dbl("guidance") ?: 7.5, p.int("size") ?: 512)
        val verdict = PromptSafety.check(prompt)
        val items = if (!verdict.ok) emptyList() else seeds.map { seed ->
            val img = generator.generate(base.copy(seed = seed))
            val safe = postCheck?.safe(img.png)
            buildJsonObject {
                put("seed", seed)
                if (safe == false) { put("blocked", true); put("stage", "output"); return@buildJsonObject }
                val name = write(img)
                put("out", name); put("meta", name.removeSuffix(".png") + ".json")
                put("sha256", img.pngSha256); put("rgbSha256", img.rgbSha256)
                put("w", img.width); put("h", img.height)
                put("aiGenerated", true)
                put("postCheck", if (safe == null) "none" else "passed")
            }
        }
        return buildJsonObject {
            put("op", task.op.name)
            put("model", task.modelId ?: modelId)
            if (!verdict.ok) {
                put("blocked", true); put("stage", "prompt"); put("category", verdict.category)
                put("items", JsonArray(emptyList()))
                return@buildJsonObject
            }
            put("blocked", false)
            put("steps", base.steps); put("guidance", base.guidance); put("size", base.size)
            put("promptSha256", SdPipeline.sha256(prompt.toByteArray(Charsets.UTF_8)))
            put("items", JsonArray(items))
        }
    }

    private fun write(img: GenImage): String {
        outDir.mkdirs()
        val name = "${img.pngSha256}.png"
        val f = File(outDir, name)
        if (!f.isFile || f.length() != img.png.size.toLong()) f.writeBytes(img.png)
        File(outDir, "${img.pngSha256}.json").writeText(img.metadata.toString())
        return name
    }

    // -------------------------------------------------------------------------------------------- SCORE_IMAGE

    /**
     * blobs: candidate PNGs; params: prompt (required). Per item {item, clip?, iqa{verdict, blur, meanLuma}, safe?};
     * plus "ranking": items ordered by safe (unsafe last), IQA ok first, clip desc, item name — a fixed rule, no model
     * judgement in the order (§4A.3).
     */
    private suspend fun score(task: TaskSpec, blobs: List<File>): JsonElement {
        require(blobs.isNotEmpty()) { "SCORE_IMAGE needs at least one image blob" }
        val prompt = task.params.str("prompt") ?: ""
        class Row(val item: String, val clip: Double?, val iqaOk: Boolean, val safe: Boolean?, val json: JsonObject)
        val rows = blobs.map { f ->
            val c = if (clip != null && prompt.isNotEmpty()) r4(clip.score(f, prompt)) else null
            val iqa = codec?.let { Iqa.assess(it.decode(f)) }
            val safe = postCheck?.safe(f.readBytes())
            Row(f.name, c, iqa?.verdict == "ok", safe, buildJsonObject {
                put("item", f.name)
                if (c != null) put("clip", c)
                if (iqa != null) putJsonObject("iqa") { put("verdict", iqa.verdict); put("blur", r4(iqa.blur)); put("meanLuma", r4(iqa.meanLuma)) }
                if (safe != null) put("safe", safe)
            })
        }
        val ranked = rows.sortedWith(compareBy<Row> { if (it.safe == false) 1 else 0 }
            .thenBy { if (it.iqaOk) 0 else 1 }
            .thenByDescending { it.clip ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.item })
        return buildJsonObject {
            put("op", task.op.name)
            put("items", JsonArray(rows.map { it.json }))
            putJsonArray("ranking") { ranked.filter { it.safe != false }.forEach { add(JsonPrimitive(it.item)) } }
        }
    }

    // ------------------------------------------------------------------------------------------------ UPSCALE

    /** Delegates to vision SUPERRES (tiles of the model input size, 16 px overlap; params pass through). */
    private suspend fun upscale(task: TaskSpec, blobs: List<File>): JsonElement {
        val sr = superRes ?: throw IllegalStateException("UPSCALE needs a SUPERRES delegate")
        require(blobs.isNotEmpty()) { "UPSCALE needs an image blob" }
        val model = task.modelId ?: task.params.str("modelId") ?: DEFAULT_SR
        val r = sr.run(task.copy(op = Op.SUPERRES, modelId = model), blobs).getOrThrow()
        return buildJsonObject {
            put("op", task.op.name)
            put("model", model)
            put("aiGenerated", true)
            put("superres", r)
        }
    }

    companion object {
        const val DEFAULT_SR = "real_esrgan_w8a8"
        const val MAX_VARIANTS = 16

        fun seedOf(p: JsonObject): Long = p.long("seed") ?: 0L

        fun seedsOf(p: JsonObject): List<Long> {
            (p["seeds"] as? JsonArray)?.let { a ->
                val s = a.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
                require(s.isNotEmpty() && s.size <= MAX_VARIANTS) { "seeds: 1..$MAX_VARIANTS values" }
                return s
            }
            val n = p.int("count") ?: 1
            require(n in 1..MAX_VARIANTS) { "count must be 1..$MAX_VARIANTS" }
            val s0 = seedOf(p)
            return List(n) { s0 + it }
        }

        private fun r4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    }
}
