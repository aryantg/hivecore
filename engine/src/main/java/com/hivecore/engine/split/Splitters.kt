package com.hivecore.engine.split

import com.hivecore.engine.pixels.Tile
import com.hivecore.engine.text.Sentence
import com.hivecore.core.Canon
import com.hivecore.engine.text.Sentences
import com.hivecore.engine.pixels.Pixels
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What a split job runs over (spec section 10.2). Every input can be marked `private`: its chunks never leave the
 * owner (the job's `owner`, default the orchestrator). Default false: anything not marked private may be chunked out
 * to opted-in idle phones.
 */
@Serializable sealed interface SplitInput {
    /** N independent items placed in the task params under [key] (`texts` [{id,text}] for EMBED/TEXT_CLASSIFY, `corpus` for KNN, …). */
    @Serializable @SerialName("items")
    data class Items(val items: List<JsonElement>, val key: String = "texts", val private: Boolean = false) : SplitInput

    /** N released items (content hashes in the [com.hivecore.engine.BlobStore]); [private] per blob (missing = false). */
    @Serializable @SerialName("blobs")
    data class Blobs(val blobIds: List<String>, val sizes: List<Long> = emptyList(), val private: List<Boolean> = emptyList()) : SplitInput

    /** One long text, cut into sentence chunks (sentence units `{doc, unit: "s<i>", text}`). */
    @Serializable @SerialName("text")
    data class Text(val text: String, val doc: String = "text", val overlap: Int? = null, val private: Boolean = false) : SplitInput

    /**
     * One recording, cut into time windows of ≤ 28 s owned + [overlapMs] on each side (≤ 30 s per Whisper call), or,
     * when [segments] (`[startMs, endMs]` from a VAD_SPLIT output) are given, one chunk per VAD segment.
     */
    @Serializable @SerialName("audio")
    data class Audio(val durationMs: Long, val blobId: String? = null, val overlapMs: Long = 1_000, val private: Boolean = false,
                     val segments: List<List<Long>> = emptyList()) : SplitInput

    /** One video's sampled frames, cut into frame ranges. */
    @Serializable @SerialName("video")
    data class Video(val frames: Int, val blobId: String? = null, val private: Boolean = false) : SplitInput

    /** One image, cut into tiles ([w]×[h] after the executor's fit; tiles of [tile] px with [overlap]). */
    @Serializable @SerialName("image")
    data class Image(val blobId: String, val w: Int, val h: Int, val tile: Int = 128, val overlap: Int = 16, val private: Boolean = false) : SplitInput

    /** One table's rows, cut into row ranges [rowFrom, rowTo) for partial aggregates (MONOID). */
    @Serializable @SerialName("table")
    data class Table(val table: String, val rows: Int, val private: Boolean = false) : SplitInput

    /** Fan-out over seeds: chunk i runs `seeds` [base + …]. */
    @Serializable @SerialName("seeds")
    data class Seeds(val n: Int, val base: Long = 0, val private: Boolean = false) : SplitInput

    /** Fan-out over variants of the same task (prompts, option orders): each variant is one chunk's params overlay. */
    @Serializable @SerialName("fanout")
    data class FanOut(val variants: List<JsonObject>, val private: Boolean = false) : SplitInput
}

/**
 * One chunk of a split job. [params] is overlaid on the job's params; [blobs] are the released items it reads.
 * [ownFrom, ownTo) is the slice of the input this chunk is responsible for in the merge (items, sentences, ms,
 * frames, tiles, rows, seeds); [overlapBefore]/[overlapAfter] the extra context it sees on each side.
 * `chunkId = sha256(op@modelSha256 | params | slice | blobIds)` (section 10.3) makes chunks idempotent across
 * retries, duplicates and jobs. [recutOf] names the parent after an adaptive re-cut (section 14).
 */
@Serializable data class Chunk(
    val index: Int,
    val chunkId: String,
    val params: JsonObject,
    val blobs: List<String> = emptyList(),
    val units: Int,
    val ownFrom: Long,
    val ownTo: Long,
    val overlapBefore: Long = 0,
    val overlapAfter: Long = 0,
    /** bytes that must travel to a remote phone (inline params + blobs) */
    val bytes: Long = 0,
    val private: Boolean = false,
    val recutOf: Int? = null,
)

/** Deterministic splitters: same input + same chunk size ⇒ same chunks, same ids, on every phone. */
object Splitters {

    /** sha256 over the op key (op@modelSha256), the job's pinned params, the chunk's slice and its blob ids (section 10.3). */
    fun chunkId(opKey: String, jobParams: JsonObject, slice: JsonObject, blobs: List<String>): String =
        Canon.sha256Hex(opKey + "|" + Canon.canonical(jobParams) + "|" + Canon.canonical(slice) + "|" + blobs.joinToString(","))

    /** [0, n) cut into consecutive ranges of [per] (the last one shorter). */
    fun ranges(n: Int, per: Int): List<Pair<Int, Int>> {
        require(per >= 1) { "chunk size must be ≥ 1" }
        val out = ArrayList<Pair<Int, Int>>()
        var a = 0
        while (a < n) { val b = minOf(n, a + per); out += a to b; a = b }
        return out
    }

    private fun chunk(i: Int, opKey: String, base: JsonObject, slice: JsonObject, blobs: List<String>, units: Int, from: Long, to: Long,
                      before: Long = 0, after: Long = 0, bytes: Long = 0, private: Boolean = false, recutOf: Int? = null) =
        Chunk(i, chunkId(opKey, base, slice, blobs), slice, blobs, units, from, to, before, after,
            bytes + Canon.canonical(slice).length, private, recutOf)

    /** Shards of [per] items under params[key]. */
    fun items(opKey: String, base: JsonObject, input: SplitInput.Items, per: Int): List<Chunk> =
        ranges(input.items.size, per).mapIndexed { i, (a, b) -> itemsChunk(i, opKey, base, input, a, b) }

    private fun itemsChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Items, a: Int, b: Int, recutOf: Int? = null): Chunk {
        val slice = JsonObject(mapOf(input.key to JsonArray(input.items.subList(a, b))))
        return chunk(i, opKey, base, slice, emptyList(), b - a, a.toLong(), b.toLong(), private = input.private, recutOf = recutOf)
    }

    /** Shards of [per] blobs; a chunk never mixes private and public blobs (boundaries also fall where privacy changes). */
    fun blobs(opKey: String, base: JsonObject, input: SplitInput.Blobs, per: Int): List<Chunk> {
        val ids = input.blobIds
        fun priv(i: Int) = input.private.getOrElse(i) { false }
        val runs = ArrayList<Pair<Int, Int>>()
        var s = 0
        for (i in 1..ids.size) if (i == ids.size || priv(i) != priv(s)) { if (i > s) runs += s to i; s = i }
        val out = ArrayList<Chunk>()
        for ((ra, rb) in runs) for ((a, b) in ranges(rb - ra, per)) out += blobsChunk(out.size, opKey, base, input, ra + a, ra + b)
        return out
    }

    private fun blobsChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Blobs, from: Int, to: Int, recutOf: Int? = null): Chunk {
        val bl = input.blobIds.subList(from, to)
        val bytes = (from until to).sumOf { input.sizes.getOrElse(it) { 0L } }
        return chunk(i, opKey, base, JsonObject(emptyMap()), bl, to - from, from.toLong(), to.toLong(), bytes = bytes,
            private = input.private.getOrElse(from) { false }, recutOf = recutOf)
    }

    /** Sentence units of a text: `{doc, unit: "s<i>", text}`, one per sentence, in order. */
    fun sentenceUnits(doc: String, text: String): List<JsonObject> =
        Sentences.split(text).mapIndexed { i, s -> buildJsonObject { put("doc", doc); put("unit", "s$i"); put("text", s) } }

    /**
     * Sentence chunks: chunk i owns sentences [a, b) and also sees the [overlap] sentences before a (context). STITCH
     * keeps each sentence's result from the first chunk that saw it, which is the chunk that owns it.
     */
    fun text(opKey: String, base: JsonObject, input: SplitInput.Text, per: Int, overlap: Int): List<Chunk> {
        val units = sentenceUnits(input.doc, input.text)
        return ranges(units.size, per).mapIndexed { i, (a, b) -> textChunk(i, opKey, base, input, units, a, b, overlap) }
    }

    private fun textChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Text, units: List<JsonObject>, a: Int, b: Int, overlap: Int, recutOf: Int? = null): Chunk {
        val from = maxOf(0, a - overlap)
        val slice = JsonObject(mapOf("units" to JsonArray(units.subList(from, b))))
        return chunk(i, opKey, base, slice, emptyList(), b - a, a.toLong(), b.toLong(), before = (a - from).toLong(), private = input.private, recutOf = recutOf)
    }

    /** Time windows of [windowMs] (owned) plus [SplitInput.Audio.overlapMs] each side: params fromMs/toMs/ownFromMs/ownToMs. */
    fun audio(opKey: String, base: JsonObject, input: SplitInput.Audio, windowMs: Long): List<Chunk> {
        require(windowMs > 0) { "window must be > 0" }
        if (input.segments.isNotEmpty()) return vadSegments(opKey, base, input)
        val out = ArrayList<Chunk>()
        var a = 0L
        while (a < input.durationMs) {
            val b = minOf(input.durationMs, a + windowMs)
            out += audioChunk(out.size, opKey, base, input, a, b)
            a = b
        }
        return out
    }

    private fun audioChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Audio, a: Long, b: Long, recutOf: Int? = null): Chunk {
        val from = maxOf(0L, a - input.overlapMs); val to = minOf(input.durationMs, b + input.overlapMs)
        val slice = buildJsonObject {
            put("i", i); put("startMs", from); put("endMs", to)      // media ASR: one shard per startMs/endMs
            put("fromMs", from); put("toMs", to); put("ownFromMs", a); put("ownToMs", b)
        }
        return chunk(i, opKey, base, slice, listOfNotNull(input.blobId), ((b - a + 999) / 1000).toInt(), a, b, a - from, to - b, private = input.private, recutOf = recutOf)
    }

    /**
     * One chunk per VAD segment (already cut at quiet frames with 1 s overlap): params i/startMs/endMs. No owned-window
     * filter; the ASR merge removes the overlap words with the transcript stitcher.
     */
    private fun vadSegments(opKey: String, base: JsonObject, input: SplitInput.Audio): List<Chunk> =
        input.segments.mapIndexed { i, s ->
            require(s.size == 2 && s[1] >= s[0]) { "segment $i is not [startMs, endMs]" }
            val slice = buildJsonObject { put("i", i); put("startMs", s[0]); put("endMs", s[1]) }
            chunk(i, opKey, base, slice, listOfNotNull(input.blobId), ((s[1] - s[0] + 999) / 1000).toInt(), s[0], s[1], private = input.private)
        }

    /** Frame ranges [frameFrom, frameTo). */
    fun video(opKey: String, base: JsonObject, input: SplitInput.Video, per: Int): List<Chunk> =
        ranges(input.frames, per).mapIndexed { i, (a, b) -> videoChunk(i, opKey, base, input, a, b) }

    private fun videoChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Video, a: Int, b: Int, recutOf: Int? = null): Chunk {
        val slice = buildJsonObject { put("frameFrom", a); put("frameTo", b) }
        return chunk(i, opKey, base, slice, listOfNotNull(input.blobId), b - a, a.toLong(), b.toLong(), private = input.private, recutOf = recutOf)
    }

    /** Tile groups of the [Pixels.tilePlan] (VisionExecutor SUPERRES runs `params.tiles` and returns `tileOutputs`). */
    fun image(opKey: String, base: JsonObject, input: SplitInput.Image, per: Int): List<Chunk> {
        val plan = Pixels.tilePlan(input.w, input.h, input.tile, input.overlap)
        return ranges(plan.tiles.size, per).mapIndexed { i, (a, b) -> imageChunk(i, opKey, base, input, a, b) }
    }

    private fun imageChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Image, a: Int, b: Int, recutOf: Int? = null): Chunk {
        val slice = buildJsonObject { putJsonArray("tiles") { for (t in a until b) add(JsonPrimitive(t)) } }
        return chunk(i, opKey, base, slice, listOf(input.blobId), b - a, a.toLong(), b.toLong(), private = input.private, recutOf = recutOf)
    }

    /** Row ranges [rowFrom, rowTo) for partial aggregates. */
    fun table(opKey: String, base: JsonObject, input: SplitInput.Table, per: Int): List<Chunk> =
        ranges(input.rows, per).mapIndexed { i, (a, b) -> tableChunk(i, opKey, base, input, a, b) }

    private fun tableChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Table, a: Int, b: Int, recutOf: Int? = null): Chunk {
        val slice = buildJsonObject { put("table", input.table); put("rowFrom", a); put("rowTo", b) }
        return chunk(i, opKey, base, slice, emptyList(), b - a, a.toLong(), b.toLong(), private = input.private, recutOf = recutOf)
    }

    /** Seed groups: chunk i runs `seeds` [base + a, base + b). */
    fun seeds(opKey: String, base: JsonObject, input: SplitInput.Seeds, per: Int): List<Chunk> =
        ranges(input.n, per).mapIndexed { i, (a, b) -> seedsChunk(i, opKey, base, input, a, b) }

    private fun seedsChunk(i: Int, opKey: String, base: JsonObject, input: SplitInput.Seeds, a: Int, b: Int, recutOf: Int? = null): Chunk {
        val slice = buildJsonObject { putJsonArray("seeds") { for (s in a until b) add(JsonPrimitive(input.base + s)) } }
        return chunk(i, opKey, base, slice, emptyList(), b - a, a.toLong(), b.toLong(), private = input.private, recutOf = recutOf)
    }

    /** One chunk per variant (params overlay + `variant` index). */
    fun fanOut(opKey: String, base: JsonObject, input: SplitInput.FanOut): List<Chunk> =
        input.variants.mapIndexed { i, v ->
            val slice = JsonObject(v + ("variant" to JsonPrimitive(i)))
            chunk(i, opKey, base, slice, emptyList(), 1, i.toLong(), i + 1L, private = input.private)
        }

    /**
     * Spec section 14 adaptive re-cut: [c] halved once at a unit boundary into two child chunks (indices [firstIndex]
     * and [firstIndex] + 1, `recutOf = c.index`), cut exactly as a fresh cut of that size would be so their ids match
     * the memo. Null when the chunk is one unit, a VAD segment, a fan-out variant, or an audio window under 2 s.
     */
    fun recut(opKey: String, base: JsonObject, input: SplitInput, c: Chunk, overlap: Int, firstIndex: Int): List<Chunk>? {
        if (c.units < 2 || c.ownTo - c.ownFrom < 2) return null
        val a = c.ownFrom; val b = c.ownTo
        val k = firstIndex; val p = c.index
        return when (input) {
            is SplitInput.Items -> { val m = mid(a, b).toInt(); listOf(itemsChunk(k, opKey, base, input, a.toInt(), m, p), itemsChunk(k + 1, opKey, base, input, m, b.toInt(), p)) }
            is SplitInput.Blobs -> { val m = mid(a, b).toInt(); listOf(blobsChunk(k, opKey, base, input, a.toInt(), m, p), blobsChunk(k + 1, opKey, base, input, m, b.toInt(), p)) }
            is SplitInput.Text -> {
                val units = sentenceUnits(input.doc, input.text); val m = mid(a, b).toInt()
                listOf(textChunk(k, opKey, base, input, units, a.toInt(), m, overlap, p), textChunk(k + 1, opKey, base, input, units, m, b.toInt(), overlap, p))
            }
            is SplitInput.Audio -> {
                if (input.segments.isNotEmpty() || b - a < 2_000) return null
                val m = a + (b - a) / 2_000 * 1_000
                listOf(audioChunk(k, opKey, base, input, a, m, p), audioChunk(k + 1, opKey, base, input, m, b, p))
            }
            is SplitInput.Video -> { val m = mid(a, b).toInt(); listOf(videoChunk(k, opKey, base, input, a.toInt(), m, p), videoChunk(k + 1, opKey, base, input, m, b.toInt(), p)) }
            is SplitInput.Image -> { val m = mid(a, b).toInt(); listOf(imageChunk(k, opKey, base, input, a.toInt(), m, p), imageChunk(k + 1, opKey, base, input, m, b.toInt(), p)) }
            is SplitInput.Table -> { val m = mid(a, b).toInt(); listOf(tableChunk(k, opKey, base, input, a.toInt(), m, p), tableChunk(k + 1, opKey, base, input, m, b.toInt(), p)) }
            is SplitInput.Seeds -> { val m = mid(a, b).toInt(); listOf(seedsChunk(k, opKey, base, input, a.toInt(), m, p), seedsChunk(k + 1, opKey, base, input, m, b.toInt(), p)) }
            is SplitInput.FanOut -> null
        }
    }

    private fun mid(a: Long, b: Long): Long = a + (b - a) / 2

    /** Short human label of [c]'s slice of [input] (1-based ranges). */
    fun label(input: SplitInput, c: Chunk): String {
        fun range(noun: String) = if (c.ownTo - c.ownFrom <= 1) "$noun ${c.ownFrom + 1}" else "$noun ${c.ownFrom + 1}–${c.ownTo}"
        fun mmss(ms: Long) = "%02d:%02d".format(ms / 60_000, ms / 1000 % 60)
        return when (input) {
            is SplitInput.Items -> range("items")
            is SplitInput.Blobs -> range("items")
            is SplitInput.Text -> range("sentences")
            is SplitInput.Audio -> "audio ${mmss(c.ownFrom)}–${mmss(c.ownTo)}"
            is SplitInput.Video -> range("frames")
            is SplitInput.Image -> range("tiles")
            is SplitInput.Table -> range("rows")
            is SplitInput.Seeds -> if (c.ownTo - c.ownFrom <= 1) "seed ${input.base + c.ownFrom}" else "seeds ${input.base + c.ownFrom}–${input.base + c.ownTo - 1}"
            is SplitInput.FanOut -> "variant ${c.index + 1}"
        }
    }

    /** Units of work in [input] (items, sentences, seconds, frames, tiles, rows, seeds, variants). */
    fun units(input: SplitInput): Int = when (input) {
        is SplitInput.Items -> input.items.size
        is SplitInput.Blobs -> input.blobIds.size
        is SplitInput.Text -> Sentences.split(input.text).size
        is SplitInput.Audio -> if (input.segments.isNotEmpty()) input.segments.sumOf { ((it[1] - it[0] + 999) / 1000).toInt() } else ((input.durationMs + 999) / 1000).toInt()
        is SplitInput.Video -> input.frames
        is SplitInput.Image -> Pixels.tilePlan(input.w, input.h, input.tile, input.overlap).tiles.size
        is SplitInput.Table -> input.rows
        is SplitInput.Seeds -> input.n
        is SplitInput.FanOut -> input.variants.size
    }

    /** Params overlay of a job with one chunk (the unsplit run): the same representation as the chunks, all of it. */
    fun whole(opKey: String, base: JsonObject, input: SplitInput): Chunk = when (input) {
        is SplitInput.Items -> items(opKey, base, input, maxOf(1, input.items.size)).single()
        is SplitInput.Blobs -> chunk(0, opKey, base, JsonObject(emptyMap()), input.blobIds, input.blobIds.size, 0, input.blobIds.size.toLong())
        is SplitInput.Text -> text(opKey, base, input, maxOf(1, units(input)), 0).single()
        is SplitInput.Audio -> chunk(0, opKey, base, JsonObject(emptyMap()), listOfNotNull(input.blobId), units(input), 0, input.durationMs, private = input.private)
        is SplitInput.Video -> video(opKey, base, input, maxOf(1, input.frames)).single()
        is SplitInput.Image -> image(opKey, base, input, maxOf(1, units(input))).single()
        is SplitInput.Table -> table(opKey, base, input, maxOf(1, input.rows)).single()
        is SplitInput.Seeds -> seeds(opKey, base, input, maxOf(1, input.n)).single()
        is SplitInput.FanOut -> throw IllegalArgumentException("a fan-out has no unsplit run")
    }
}
