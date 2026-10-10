package com.hivecore.node.vision

import com.hivecore.engine.pixels.Letterbox
import com.hivecore.engine.pixels.Png
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels
import com.hivecore.core.Decision
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest

// Swarm plan §4A.3 computer-vision and image-media executors. Input: the task's blob files (images), params JSON
// (modelId, thresholds, vocabulary, …). Output: {"op", "model"?, "items": [{"item": <blob file name>, …}]} with items
// in blob order, lists in a fixed order and scores rounded to 4 dp, so replicas compare by canonical-JSON hash.
// Image outputs (masks, preprocessed and upscaled images) are written content-addressed to [outDir] as
// "<sha256>.<ext>" and referenced by name + sha256.

internal object VJson {
    fun r4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
    fun r6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    fun JsonObject.strs(k: String): List<String>? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    fun JsonObject.ints(k: String): List<Int>? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    fun JsonObject.floats(k: String): FloatArray? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }?.toFloatArray()
}

/** IMG_CLASSIFY, DETECT, SEGMENT, TAG, IQA, IMG_PREPROC, SUPERRES. [registry] locates side files (labels.txt, CLIP merges). */
class VisionExecutor(
    private val pool: ModelPool,
    private val runtime: ModelRuntime,
    private val codec: ImageCodec,
    private val outDir: File,
    private val registry: ModelRegistry? = null,
) : OpExecutor {
    override val ops: Set<Op> = setOf(Op.IMG_CLASSIFY, Op.DETECT, Op.SEGMENT, Op.TAG, Op.IQA, Op.IMG_PREPROC, Op.SUPERRES)

    private val tokenizers = HashMap<String, ClipBpe>()

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = runCatching {
        require(task.op in ops) { "VisionExecutor does not run ${task.op}" }
        require(blobs.isNotEmpty()) { "${task.op} needs at least one image blob" }
        val p = task.params
        val modelId = with(VJson) { task.modelId ?: p.str("modelId") }
        if (task.op !in setOf(Op.IQA, Op.IMG_PREPROC)) requireNotNull(modelId) { "${task.op} needs a modelId" }
        val items = blobs.map { f ->
            val img = codec.decode(f)
            val body = when (task.op) {
                Op.IQA -> iqa(img, p)
                Op.IMG_PREPROC -> preproc(img, p)
                Op.IMG_CLASSIFY -> classify(img, p, modelId!!)
                Op.DETECT -> detect(img, p, modelId!!)
                Op.SEGMENT -> segment(img, p, modelId!!)
                Op.TAG -> tag(img, p, modelId!!)
                Op.SUPERRES -> superres(img, p, modelId!!)
                else -> error("unreachable")
            }
            JsonObject(linkedMapOf<String, JsonElement>("item" to JsonPrimitive(f.name)) + body)
        }
        buildJsonObject {
            put("op", task.op.name)
            if (modelId != null && task.op !in setOf(Op.IQA, Op.IMG_PREPROC)) put("model", modelId)
            put("items", JsonArray(items))
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    private suspend fun <T> withModel(modelId: String, block: suspend (ModelSession) -> T): T = pool.with(modelId, block).getOrThrow()

    private suspend fun infer(s: ModelSession, inputs: Map<String, Any>): Map<String, Any> = runtime.run(s, inputs).getOrThrow()

    private fun labels(m: ModelManifest): List<String> {
        if (m.labels.isNotEmpty()) return m.labels
        val f = registry?.dir(m.id)?.let { File(it, "labels.txt") }
        return if (f != null && f.isFile) f.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    private fun pre(m: ModelManifest, key: String): String? = with(VJson) { m.preprocess.str(key) }

    private fun imageInput(m: ModelManifest): TensorSpec =
        TensorIo.inputs(m.inputSpec).firstOrNull { it.shape.size == 4 } ?: error("${m.id}: no NHWC image input in inputSpec")

    /** Fit [img] to the model input by [mode] (letterbox | stretch | center_crop). */
    private fun fit(img: Img, spec: TensorSpec, mode: String): Pair<Img, Letterbox?> {
        val (h, w) = TensorIo.imageHw(spec)
        return when (mode) {
            "letterbox" -> Pixels.letterbox(img, w, h).let { it.img to it }
            "center_crop" -> Pixels.centerCrop(img, w, h) to null
            else -> Pixels.resize(img, w, h) to null
        }
    }

    private fun pack(m: ModelManifest, img: Img, spec: TensorSpec): Any = with(VJson) {
        TensorIo.packImage(img, spec, m.preprocess.floats("mean"), m.preprocess.floats("std"))
    }

    private fun writeOut(bytes: ByteArray, ext: String): Pair<String, String> {
        val sha = VJson.sha256(bytes)
        val name = "$sha.$ext"
        outDir.mkdirs()
        val f = File(outDir, name)
        if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        return name to sha
    }

    // ----------------------------------------------------------------------------------------------------- IQA

    private fun iqa(img: Img, p: JsonObject): Map<String, JsonElement> = with(VJson) {
        val d = Iqa.Limits()
        val lim = Iqa.Limits(p.int("minSide") ?: d.minSide, p.dbl("blurMin") ?: d.blurMin, p.dbl("darkMean") ?: d.darkMean,
            p.dbl("darkClip") ?: d.darkClip, p.dbl("brightMean") ?: d.brightMean, p.dbl("brightClip") ?: d.brightClip)
        val r = Iqa.assess(img, lim)
        buildJsonObject {
            put("blur", r4(r.blur))
            putJsonObject("exposure") { put("dark", r4(r.darkClip)); put("bright", r4(r.brightClip)); put("mean", r4(r.meanLuma)) }
            putJsonArray("res") { add(JsonPrimitive(r.width)); add(JsonPrimitive(r.height)) }
            put("verdict", r.verdict)
        }
    }

    // ---------------------------------------------------------------------------------------------- IMG_PREPROC

    /**
     * steps (in order, default [{"op":"fit","max":1600}]): fit{max} | resize{w,h} | crop{x,y,w,h} | rotate{quarter}
     * | gray | clahe{grid,clip}. EXIF rotation is applied at decode. format: jpeg (default) | png; quality (90).
     */
    private suspend fun preproc(src: Img, p: JsonObject): Map<String, JsonElement> = with(VJson) {
        var img = src
        val steps = (p["steps"] as? JsonArray)?.map { it as JsonObject } ?: listOf(buildJsonObject { put("op", "fit"); put("max", 1600) })
        for (s in steps) img = when (val op = s.str("op")) {
            "fit" -> Pixels.fitMax(img, s.int("max") ?: 1600)
            "resize" -> Pixels.resize(img, s.int("w") ?: img.w, s.int("h") ?: img.h)
            "crop" -> Pixels.crop(img, s.int("x") ?: 0, s.int("y") ?: 0, s.int("w") ?: img.w, s.int("h") ?: img.h)
            "rotate" -> Pixels.rotate90(img, s.int("quarter") ?: 1)
            "gray" -> Pixels.grayscale(img)
            "clahe" -> Pixels.clahe(img, s.int("grid") ?: 8, s.dbl("clip") ?: 2.0)
            else -> throw IllegalArgumentException("unknown IMG_PREPROC step $op")
        }
        val format = (p.str("format") ?: "jpeg").lowercase()
        require(format == "jpeg" || format == "png") { "format must be jpeg or png" }
        val bytes = if (format == "png") Png.encode(img) else codec.encode(img, "jpeg", p.int("quality") ?: 90)
        val (name, sha) = writeOut(bytes, if (format == "png") "png" else "jpg")
        buildJsonObject { put("out", name); put("sha256", sha); put("w", img.w); put("h", img.h); put("bytes", bytes.size) }
    }

    // --------------------------------------------------------------------------------------------- IMG_CLASSIFY

    private suspend fun classify(img: Img, p: JsonObject, modelId: String): Map<String, JsonElement> = withModel(modelId) { s ->
        val m = s.manifest
        val spec = imageInput(m)
        val (x, _) = fit(img, spec, pre(m, "resize") ?: "center_crop")
        val out = infer(s, mapOf(spec.name to pack(m, x, spec)))
        val oSpec = TensorIo.outputs(m.outputSpec).firstOrNull() ?: TensorSpec("0", 0, emptyList(), "float32", null, 0)
        val logits = TensorIo.read(out, oSpec)
        with(VJson) {
            val top = Classification.topK(logits, labels(m), p.int("topK") ?: Classification.TOP_K, p.dbl("minScore") ?: Classification.MIN_SCORE,
                probabilities = p.bool("softmax") ?: (m.preprocess.bool("softmax") ?: true))
            buildJsonObject {
                putJsonArray("labels") { top.forEach { t -> add(buildJsonObject { put("label", t.label); put("score", r4(t.score)) }) } }
            }
        }
    }

    // --------------------------------------------------------------------------------------------------- DETECT

    private suspend fun detect(img: Img, p: JsonObject, modelId: String): Map<String, JsonElement> = withModel(modelId) { s ->
        val m = s.manifest
        val spec = imageInput(m)
        val lb = Pixels.letterbox(img, TensorIo.imageHw(spec).second, TensorIo.imageHw(spec).first)
        val out = infer(s, mapOf(spec.name to pack(m, lb.img, spec)))
        val outs = TensorIo.outputs(m.outputSpec)
        fun o(name: String): TensorSpec = outs.firstOrNull { it.name == name } ?: error("${m.id}: output $name not in outputSpec")
        val names = outs.map { it.name }.toSet()
        val decoder = pre(m, "decoder") ?: when {
            "class_idx" in names -> "yolox"
            "classes" in names -> "dfine"
            "box_coords_1" in names -> "blazeface"
            else -> error("${m.id}: cannot infer the detection decoder from outputs $names")
        }
        with(VJson) {
            val scoreMin = p.dbl("score") ?: Detection.SCORE
            val iou = p.dbl("iou") ?: Detection.IOU
            val raw = when (decoder) {
                "yolox" -> Detection.decodeYolox(TensorIo.read(out, o("boxes")), TensorIo.read(out, o("scores")),
                    TensorIo.ints(TensorIo.find(out, o("class_idx")), o("class_idx")), labels(m), scoreMin)
                "dfine" -> {
                    val norm = m.preprocess.bool("boxNormalized") ?: false
                    val (h, w) = TensorIo.imageHw(spec)
                    Detection.decodeDfine(TensorIo.read(out, o("boxes")), TensorIo.read(out, o("scores")),
                        TensorIo.ints(TensorIo.find(out, o("classes")), o("classes")), labels(m), scoreMin,
                        pre(m, "boxFormat") ?: "xyxy", if (norm) w else 0, if (norm) h else 0)
                }
                "blazeface" -> {
                    val c = TensorIo.read(out, o("box_coords_1")) + TensorIo.read(out, o("box_coords_2"))
                    val l = TensorIo.read(out, o("box_scores_1")) + TensorIo.read(out, o("box_scores_2"))
                    Faces.decode(c, l, TensorIo.imageHw(spec).first, p.dbl("score") ?: Faces.SCORE, p.dbl("iou") ?: Faces.IOU)
                }
                else -> error("unknown decoder $decoder")
            }
            val wanted = p.strs("classes")?.toSet()
            val filtered = if (wanted == null) raw else raw.filter { it.label in wanted }
            val dets = Detection.unletterbox(Detection.nms(filtered, iou, p.int("maxDetections") ?: Detection.MAX_DETECTIONS), lb)
            buildJsonObject {
                put("w", img.w); put("h", img.h)
                putJsonArray("detections") {
                    dets.forEach { d ->
                        add(buildJsonObject {
                            put("label", d.label)
                            putJsonArray("box") { listOf(d.x1, d.y1, d.x2, d.y2).forEach { add(JsonPrimitive(Math.round(it).toInt())) } }
                            put("score", r4(d.score))
                        })
                    }
                }
                putJsonObject("counts") { Detection.counts(dets).forEach { (k, v) -> put(k, v) } }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------- SEGMENT

    private suspend fun segment(img: Img, p: JsonObject, modelId: String): Map<String, JsonElement> = withModel(modelId) { s ->
        val m = s.manifest
        val spec = imageInput(m)
        val (x, _) = fit(img, spec, pre(m, "resize") ?: "stretch")
        val out = infer(s, mapOf(spec.name to pack(m, x, spec)))
        val oSpec = TensorIo.outputs(m.outputSpec).firstOrNull() ?: error("${m.id}: no outputSpec")
        val sh = oSpec.shape
        val mask = when {
            sh.size == 3 -> Segmentation.fromIndexMask(TensorIo.ints(TensorIo.find(out, oSpec), oSpec), sh[1], sh[2])
            sh.size == 4 && sh[3] == 1 -> Segmentation.fromIndexMask(TensorIo.ints(TensorIo.find(out, oSpec), oSpec), sh[1], sh[2])
            sh.size == 4 -> Segmentation.argmax(TensorIo.read(out, oSpec), sh[1], sh[2], sh[3])
            else -> error("${m.id}: unsupported mask shape $sh")
        }
        val shares = Segmentation.shares(mask, labels(m))
        val png = Png.encode(Segmentation.maskImage(mask), gray = true)
        val (name, sha) = writeOut(png, "png")
        with(VJson) {
            buildJsonObject {
                put("mask", name); put("maskSha256", sha); put("w", mask.w); put("h", mask.h)
                putJsonArray("shares") { shares.forEach { c -> add(buildJsonObject { put("label", c.label); put("share", r4(c.share)) }) } }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ TAG

    private fun tokenizer(m: ModelManifest): ClipBpe = tokenizers.getOrPut(m.id) {
        val dir = registry?.dir(m.id) ?: error("${m.id}: no model folder to load the CLIP BPE merges from")
        val f = ClipBpe.find(dir) ?: error("${m.id}: CLIP BPE merges file missing (expected one of ${ClipBpe.MERGES_FILES} in the model folder)")
        ClipBpe.load(f)
    }

    /**
     * params: vocabulary [tags] (required: the owner's closed tag set), template ("a photo of {}."), mode
     * (softmax | cosine), threshold (0.15), includeEmbedding (false: adds the L2-normalized image embedding, 6 dp).
     */
    private suspend fun tag(img: Img, p: JsonObject, modelId: String): Map<String, JsonElement> = withModel(modelId) { s ->
        val m = s.manifest
        val vocab = with(VJson) { p.strs("vocabulary") } ?: emptyList()
        require(vocab.isNotEmpty()) { "TAG needs a non-empty vocabulary" }
        val ins = TensorIo.inputs(m.inputSpec)
        val imgSpec = ins.firstOrNull { it.shape.size == 4 } ?: error("${m.id}: no image input")
        val txtSpec = ins.firstOrNull { it !== imgSpec } ?: error("${m.id}: no text input")
        val batch = if (txtSpec.shape.size >= 3) txtSpec.shape[txtSpec.shape.size - 2] else 1
        val ctx = txtSpec.shape.lastOrNull() ?: 77
        val outs = TensorIo.outputs(m.outputSpec)
        val imgOut = outs.firstOrNull { "image" in it.name } ?: outs.getOrNull(0) ?: error("${m.id}: no outputSpec")
        val txtOut = outs.firstOrNull { "text" in it.name } ?: outs.getOrNull(1) ?: error("${m.id}: no text output")
        val tok = tokenizer(m)
        val template = with(VJson) { p.str("template") } ?: ClipTagger.TEMPLATE
        val (x, _) = fit(img, imgSpec, pre(m, "resize") ?: "center_crop")
        val pixels = pack(m, x, imgSpec)
        var imageFeat: FloatArray? = null
        val textFeats = ArrayList<FloatArray>()
        for (chunk in vocab.chunked(batch)) {
            val ids = IntArray(batch * ctx)
            for (i in 0 until batch) tok.tokenize(if (i < chunk.size) ClipTagger.prompt(chunk[i], template) else "", ctx).copyInto(ids, i * ctx)
            val out = infer(s, mapOf(imgSpec.name to pixels, txtSpec.name to TensorIo.int32Bytes(ids)))
            if (imageFeat == null) imageFeat = TensorIo.read(out, imgOut)
            val t = TensorIo.read(out, txtOut)
            val d = t.size / batch
            for (i in chunk.indices) textFeats += t.copyOfRange(i * d, (i + 1) * d)
        }
        with(VJson) {
            val tags = ClipTagger.score(imageFeat!!, textFeats, vocab, p.str("mode") ?: "softmax", p.dbl("threshold") ?: ClipTagger.THRESHOLD)
            buildJsonObject {
                putJsonArray("tags") { tags.forEach { t -> add(buildJsonObject { put("tag", t.tag); put("score", r4(t.score)) }) } }
                if (p.bool("includeEmbedding") == true) putJsonArray("embedding") { ClipTagger.normalize(imageFeat!!).forEach { add(JsonPrimitive(r6(it))) } }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- SUPERRES

    /**
     * Tiled ×N upscaling: tile = the model's input size, overlap (16 px), source first fitted to maxInputSide (1024).
     * params.tiles = [indices] runs only those tiles (one phone's share) and returns them as separate PNGs plus the
     * plan; [Pixels.stitch] joins the shares. Without it, the stitched image is returned.
     */
    private suspend fun superres(src: Img, p: JsonObject, modelId: String): Map<String, JsonElement> = withModel(modelId) { s ->
        val m = s.manifest
        val spec = imageInput(m)
        val (th, tw) = TensorIo.imageHw(spec)
        require(th == tw) { "${m.id}: square tiles expected, got ${tw}x$th" }
        val oSpec = TensorIo.outputs(m.outputSpec).firstOrNull { it.shape.size == 4 } ?: error("${m.id}: no NHWC output")
        val factor = oSpec.shape[1] / th
        require(factor >= 1 && oSpec.shape[1] == th * factor) { "${m.id}: output ${oSpec.shape} is not a multiple of $th" }
        val (overlap, maxSide, subset) = with(VJson) { Triple(p.int("overlap") ?: 16, p.int("maxInputSide") ?: 1024, p.ints("tiles")) }
        val img = Pixels.fitMax(src, maxSide)
        val plan = Pixels.tilePlan(img.w, img.h, th, overlap)
        val wanted = subset?.toSortedSet() ?: plan.tiles.map { it.index }.toSortedSet()
        require(wanted.all { it in plan.tiles.indices }) { "tile index out of range (plan has ${plan.tiles.size})" }
        val results = LinkedHashMap<Int, Img>()
        for (t in plan.tiles) {
            if (t.index !in wanted) continue
            val out = infer(s, mapOf(spec.name to pack(m, Pixels.tileImage(img, t), spec)))
            results[t.index] = Pixels.fromNhwcFloat(TensorIo.read(out, oSpec), th * factor, th * factor)
        }
        buildJsonObject {
            putJsonObject("plan") {
                put("w", img.w); put("h", img.h); put("tile", th); put("overlap", overlap); put("factor", factor)
                put("cols", plan.cols); put("rows", plan.rows); put("tiles", plan.tiles.size)
            }
            if (subset == null) {
                val full = Pixels.stitch(plan, plan.tiles.map { results.getValue(it.index) }, factor)
                val (name, sha) = writeOut(Png.encode(full), "png")
                put("out", name); put("sha256", sha); put("w", full.w); put("h", full.h)
            } else {
                val arr = ArrayList<JsonElement>()
                for ((i, t) in results) {
                    val (name, sha) = writeOut(Png.encode(t), "png")
                    arr += buildJsonObject { put("index", i); put("out", name); put("sha256", sha) }
                }
                put("tileOutputs", JsonArray(arr))
            }
        }
    }
}

/**
 * VLM_ASK: a closed question over each image (§4A.3), answered by [ask] (bound by the integrator to the GenieX VLM
 * runtime's closed chooser; it owns claiming the big NPU slot). params: question (required), options (default
 * yes/no/unclear). Output per item: value (null = no confident choice), confidence, agreeing, runs, votes.
 */
class VlmAskExecutor(
    private val ask: suspend (imagePath: String, question: String, options: List<String>) -> Decision<String>,
) : OpExecutor {
    override val ops: Set<Op> = setOf(Op.VLM_ASK)

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = runCatching {
        require(task.op == Op.VLM_ASK) { "VlmAskExecutor does not run ${task.op}" }
        require(blobs.isNotEmpty()) { "VLM_ASK needs at least one image blob" }
        val (question, options) = with(VJson) { task.params.str("question") to (task.params.strs("options") ?: listOf("yes", "no", "unclear")) }
        require(!question.isNullOrBlank()) { "VLM_ASK needs a question" }
        require(options.size >= 2) { "VLM_ASK needs at least two options" }
        val items = blobs.map { f ->
            val d = ask(f.path, question, options)
            require(d.value == null || d.value in options) { "VLM answered outside the options: ${d.value}" }
            buildJsonObject {
                put("item", f.name)
                put("value", d.value?.let { JsonPrimitive(it) } ?: JsonNull)
                put("confidence", VJson.r4(d.confidence))
                put("agreeing", d.agreeing); put("runs", d.runs)
                putJsonObject("votes") { options.forEachIndexed { i, o -> put(o, d.votes.getOrElse(i) { 0 }) } }
            }
        }
        buildJsonObject {
            put("op", Op.VLM_ASK.name)
            put("question", question)
            put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
            put("items", JsonArray(items))
        }
    }
}
