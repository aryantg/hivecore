package com.hivecore.node.media

import com.hivecore.engine.text.Stitch
import com.hivecore.engine.text.SegmentText
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Swarm plan §4A.3 media executors: VAD_SPLIT, ASR, FRAME_SAMPLE, TRACK_AGG. Outputs are JSON with keys in a fixed
 * order, integers only (milliseconds, sample indices, token ids, counts), so replicas compare by canonical-JSON hash.
 *
 *  - `VAD_SPLIT` (owner): blob[0] audio → `{op, item, sampleRate, samples, durationMs, segments:[{i, start, end,
 *    startMs, endMs, speech}]}`. Params: maxSec (30), overlapSec (1), searchSec (5).
 *  - `ASR`: blob[0] audio; the segments come from params `startMs`/`endMs` (one shard), else from a VAD_SPLIT input,
 *    else the whole audio (split by [Vad] when longer than 30 s). Segments without speech are not transcribed.
 *    → `{op, model, item, segments:[{i, startMs, endMs, text, tokens}], text}` where `text` is the [Stitch] merge.
 *    With no blobs and ASR outputs as inputs it is the shard merge: segments of all inputs in time order → `{op,
 *    model, segments, text}`.
 *  - `FRAME_SAMPLE` (owner): blob[0] video → `{op, item, durationMs, frames:[{i, tMs, reason, file, sha256, w, h}]}`;
 *    the JPEGs are written content-addressed to [outDir]. Params: everyMs (5000), sceneStepMs (1000),
 *    sceneThreshold (0.5), maxSide (640).
 *  - `TRACK_AGG` (reduce): inputs = FRAME_SAMPLE outputs (frame file → tMs) and DETECT outputs (`items[{item, w, h,
 *    detections[{label, box, score}]}]`) → `{op, frames, tracks:[{id, label, firstMs, lastMs, frames, crossedMs}],
 *    counts:[{minute, label, n}]}`. Params: iou (0.3), maxMissed (2), line {x1,y1,x2,y2,reverse} in 0..1 frame
 *    coordinates or "none" (default "none": count new tracks), labels (filter), startMs (added to every frame time
 *    before the minute bucket, e.g. the recording's start time of day), frameMs (spacing for detection items with no
 *    known time, in item-name order; 1000).
 */
class MediaExecutor(
    private val asr: (suspend (modelId: String, pcm: ShortArray) -> AsrResult)?,
    private val decoder: AudioDecoder?,
    private val frames: FrameSource?,
    private val outDir: File,
) : OpExecutor {
    override val ops: Set<Op> = setOf(Op.VAD_SPLIT, Op.ASR, Op.FRAME_SAMPLE, Op.TRACK_AGG)

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = runCatching {
        when (task.op) {
            Op.VAD_SPLIT -> vadSplit(task.params, blob(task, blobs))
            Op.ASR -> if (blobs.isEmpty()) mergeAsr(inputs, task.modelId ?: task.params.str("modelId") ?: WhisperAsr.MODEL_ID)
                      else asrRun(task, inputs, blobs[0])
            Op.FRAME_SAMPLE -> frameSample(task.params, blob(task, blobs))
            Op.TRACK_AGG -> trackAgg(task.params, inputs)
            else -> throw IllegalArgumentException("MediaExecutor does not run ${task.op}")
        }
    }

    private fun blob(task: TaskSpec, blobs: List<File>): File = blobs.firstOrNull() ?: throw IllegalArgumentException("${task.op} needs a blob")

    private fun audio(f: File): ShortArray = (decoder ?: throw IllegalStateException("no audio decoder")).decode(f)

    // ------------------------------------------------------------------------------------------------- VAD_SPLIT

    private fun vadSplit(p: JsonObject, f: File): JsonElement {
        val pcm = audio(f)
        val segs = Vad.split(pcm, p.int("maxSec") ?: 30, p.int("overlapSec") ?: 1, p.int("searchSec") ?: 5)
        return buildJsonObject {
            put("op", Op.VAD_SPLIT.name); put("item", f.name); put("sampleRate", Vad.SAMPLE_RATE)
            put("samples", pcm.size); put("durationMs", pcm.size * 1000L / Vad.SAMPLE_RATE)
            putJsonArray("segments") {
                segs.forEach { s -> add(buildJsonObject {
                    put("i", s.index); put("start", s.start); put("end", s.end)
                    put("startMs", s.startMs); put("endMs", s.endMs); put("speech", s.speech)
                }) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- ASR

    private suspend fun asrRun(task: TaskSpec, inputs: List<JsonElement>, f: File): JsonElement {
        val run = asr ?: throw IllegalStateException("no Whisper model on this phone")
        val modelId = task.modelId ?: task.params.str("modelId") ?: WhisperAsr.MODEL_ID
        val pcm = audio(f)
        val p = task.params
        val fromVad = inputs.mapNotNull { (it as? JsonObject)?.get("segments") as? JsonArray }.firstOrNull()
        val segs: List<AudioSegment> = when {
            p.long("startMs") != null -> {
                val s = (p.long("startMs")!! * 16).toInt().coerceIn(0, pcm.size)
                val e = ((p.long("endMs") ?: (pcm.size / 16L)) * 16).toInt().coerceIn(s, pcm.size)
                listOf(AudioSegment(p.int("i") ?: 0, s, e, true))
            }
            fromVad != null -> fromVad.map { it as JsonObject }.map { o ->
                AudioSegment(o.int("i") ?: 0, o.int("start") ?: 0, o.int("end") ?: 0, o.bool("speech") ?: true)
            }
            else -> Vad.split(pcm)
        }
        val out = segs.map { s ->
            val r = if (s.speech && s.end > s.start) run(modelId, pcm.copyOfRange(s.start, minOf(s.end, pcm.size))) else null
            Triple(s, r?.text ?: "", r?.tokens ?: emptyList())
        }
        return buildJsonObject {
            put("op", Op.ASR.name); put("model", modelId); put("item", f.name)
            putJsonArray("segments") { out.forEach { (s, text, toks) -> add(segJson(s.index, s.startMs, s.endMs, text, toks)) } }
            put("text", Stitch.merge(out.map { (s, t, _) -> SegmentText(s.startMs, s.endMs, t) }))
        }
    }

    // ---------------------------------------------------------------------------------------------- FRAME_SAMPLE

    private fun frameSample(p: JsonObject, f: File): JsonElement {
        val src = frames ?: throw IllegalStateException("no frame source")
        val d = FrameParams()
        val fp = FrameParams(p.long("everyMs") ?: d.everyMs, p.long("sceneStepMs") ?: d.sceneStepMs,
            p.dbl("sceneThreshold") ?: d.sceneThreshold, p.int("maxSide") ?: d.maxSide, p.int("quality") ?: d.quality,
            p.int("maxFrames") ?: d.maxFrames)
        val r = src.sample(f, fp, outDir)
        return buildJsonObject {
            put("op", Op.FRAME_SAMPLE.name); put("item", f.name); put("durationMs", r.durationMs)
            putJsonArray("frames") {
                r.frames.forEachIndexed { i, fr -> add(buildJsonObject {
                    put("i", i); put("tMs", fr.tMs); put("reason", fr.reason); put("file", fr.file)
                    put("sha256", fr.sha256); put("w", fr.w); put("h", fr.h)
                }) }
            }
        }
    }

    companion object {
        /** Builds the executor over a real pool: one [WhisperAsr] per model id, tokenizer from the model folder. */
        fun create(pool: ModelPool, runtime: ModelRuntime, registry: ModelRegistry, decoder: AudioDecoder?,
                   frames: FrameSource?, outDir: File): MediaExecutor {
            val cache = HashMap<String, WhisperAsr>()
            return MediaExecutor({ id, pcm ->
                val a = synchronized(cache) {
                    cache.getOrPut(id) { WhisperAsr(pool, runtime, registry.dir(id)?.let { MediaModels.tokenizer(it) }, id) }
                }
                a.transcribe(pcm).getOrThrow()
            }, decoder, frames, outDir)
        }

        private fun segJson(i: Int, startMs: Long, endMs: Long, text: String, tokens: List<Int>) = buildJsonObject {
            put("i", i); put("startMs", startMs); put("endMs", endMs); put("text", text)
            putJsonArray("tokens") { tokens.forEach { add(JsonPrimitive(it)) } }
        }

        /** ASR shard merge: every input's segments, ordered by (startMs, endMs, i), stitched. */
        fun mergeAsr(inputs: List<JsonElement>, modelId: String): JsonElement {
            val segs = inputs.flatMap { ((it as? JsonObject)?.get("segments") as? JsonArray)?.map { s -> s as JsonObject } ?: emptyList() }
                .sortedWith(compareBy<JsonObject> { it.long("startMs") ?: 0 }.thenBy { it.long("endMs") ?: 0 }.thenBy { it.int("i") ?: 0 })
            return buildJsonObject {
                put("op", Op.ASR.name); put("model", modelId)
                putJsonArray("segments") { segs.forEach { add(it) } }
                put("text", Stitch.merge(segs.map { SegmentText(it.long("startMs") ?: 0, it.long("endMs") ?: 0, it.str("text") ?: "") }))
            }
        }

        fun trackAgg(p: JsonObject, inputs: List<JsonElement>): JsonElement {
            val times = HashMap<String, Long>()
            val dets = ArrayList<JsonObject>()
            for (e in inputs) {
                val o = e as? JsonObject ?: continue
                (o["frames"] as? JsonArray)?.forEach { fr -> (fr as JsonObject).let { f -> f.str("file")?.let { n -> times[n] = f.long("tMs") ?: 0 } } }
                (o["items"] as? JsonArray)?.forEach { dets += it as JsonObject }
            }
            val wanted = (p["labels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
            val frameMs = p.long("frameMs") ?: 1000L
            val offset = p.long("startMs") ?: 0L
            val unknown = dets.filter { (it.str("item") ?: "") !in times }.map { it.str("item") ?: "" }.distinct().sorted()
            val frames = dets.map { d ->
                val name = d.str("item") ?: ""
                val t = times[name] ?: d.long("tMs") ?: (unknown.indexOf(name) * frameMs)
                val boxes = ((d["detections"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { b ->
                    val bo = b as JsonObject
                    val label = bo.str("label") ?: return@mapNotNull null
                    if (wanted != null && label !in wanted) return@mapNotNull null
                    val box = (bo["box"] as? JsonArray)?.map { (it as JsonPrimitive).doubleOrNull ?: 0.0 } ?: return@mapNotNull null
                    TBox(label, box[0], box[1], box[2], box[3], bo.dbl("score") ?: 1.0)
                }
                TFrame(offset + t, boxes, d.int("w") ?: 0, d.int("h") ?: 0)
            }.sortedBy { it.tMs }
            val line = when (val l = p["line"]) {
                is JsonObject -> CountLine(l.dbl("x1") ?: 0.0, l.dbl("y1") ?: 0.5, l.dbl("x2") ?: 1.0, l.dbl("y2") ?: 0.5, l.bool("reverse") ?: false)
                else -> null
            }
            val r = Tracker(p.dbl("iou") ?: 0.3, p.int("maxMissed") ?: 2, line).run(frames)
            return buildJsonObject {
                put("op", Op.TRACK_AGG.name); put("frames", frames.size)
                putJsonArray("tracks") {
                    r.tracks.forEach { t -> add(buildJsonObject {
                        put("id", t.id); put("label", t.label); put("firstMs", t.firstMs); put("lastMs", t.lastMs)
                        put("frames", t.frames); put("crossedMs", t.crossedMs)
                    }) }
                }
                putJsonArray("counts") {
                    r.counts.forEach { (k, n) -> add(buildJsonObject { put("minute", k.first); put("label", k.second); put("n", n) }) }
                }
            }
        }

        private fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
        internal fun JsonObject.str(k: String): String? = prim(k)?.takeIf { it.isString }?.content
        internal fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull
        internal fun JsonObject.long(k: String): Long? = prim(k)?.longOrNull
        internal fun JsonObject.dbl(k: String): Double? = prim(k)?.doubleOrNull
        internal fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull
    }
}
