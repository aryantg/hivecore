package com.hivecore.engine.split

import com.hivecore.core.MergeKind
import com.hivecore.core.AggTable
import com.hivecore.core.Canon
import com.hivecore.engine.ReducerImpl
import com.hivecore.engine.text.Extractive
import com.hivecore.engine.text.Sentence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * TREE's model seam: pick at most `k` of the candidate sentences (in document order); returns their ids. The default
 * ([Mergers.extractiveSelector]) is the deterministic BM25 path of [Extractive] (no LLM); the integrator injects an
 * LLM-backed [Extractive] on the phones. Either way the merge only picks ids from its children (R3).
 */
typealias TreeSelector = suspend (candidates: List<Sentence>, k: Int, params: JsonObject) -> List<String>

/**
 * Deterministic merges per [MergeKind] (§4B.2). Inputs are the chunk outputs **in chunk-index order** and the chunks
 * themselves (for owned ranges). For CONCAT/STITCH/MONOID/TOPK the merge of a split run equals the unsplit run.
 *
 * List-valued output keys that are concatenated: [LIST_KEYS] (the executors' `items`, ASR `segments`, SUPERRES
 * `tileOutputs`, …). Every other key is taken from the first chunk (op, model, labels: equal on every chunk).
 */
object Mergers {
    val LIST_KEYS = listOf("items", "segments", "tileOutputs", "frames", "results")

    val extractiveSelector: TreeSelector = { cands, k, params ->
        val q = (params["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ex = Extractive(null)
        (if (q != null) ex.extract(q, cands, k) else ex.summarize(cands, k)).ids
    }

    suspend fun merge(kind: MergeKind, outputs: List<JsonElement>, chunks: List<Chunk>, params: JsonObject,
                      selector: TreeSelector = extractiveSelector): JsonElement {
        require(outputs.isNotEmpty()) { "nothing to merge" }
        require(chunks.isEmpty() || chunks.size == outputs.size) { "${outputs.size} outputs for ${chunks.size} chunks" }
        return when (kind) {
            MergeKind.CONCAT -> concat(outputs)
            MergeKind.STITCH -> stitch(outputs, chunks)
            MergeKind.TREE -> tree(outputs, params, selector)
            MergeKind.MONOID -> monoid(outputs)
            MergeKind.TOPK -> topK(outputs, params)
            MergeKind.VOTE -> vote(outputs)
            MergeKind.RANK -> rank(outputs, params)
        }
    }

    // ------------------------------------------------------------------ CONCAT

    fun concat(outputs: List<JsonElement>): JsonElement = combineLists(outputs) { _, items -> items }

    private fun combineLists(outputs: List<JsonElement>, per: (Int, List<JsonElement>) -> List<JsonElement>): JsonElement {
        if (outputs.all { it is JsonArray }) return JsonArray(outputs.flatMapIndexed { i, o -> per(i, o as JsonArray) })
        if (outputs.all { it is JsonObject }) {
            val objs = outputs.map { it as JsonObject }
            val out = LinkedHashMap<String, JsonElement>(objs[0])
            for (k in LIST_KEYS) {
                if (objs.none { it[k] is JsonArray }) continue
                out[k] = JsonArray(objs.flatMapIndexed { i, o -> per(i, (o[k] as? JsonArray).orEmpty()) })
            }
            return JsonObject(out)
        }
        return JsonArray(outputs)
    }

    // ------------------------------------------------------------------ STITCH

    /**
     * Ordered chunks with overlap removed, at any depth of the [LIST_KEYS] lists:
     *  - a timed item (`start`/`end` ms) is kept only by the chunk whose owned window holds its midpoint;
     *  - an identified item (`id`, `index`, `item`) is kept from the first chunk that produced it (overlap is context
     *    *before* the owned range, so that is its owner) — unless it carries lists itself (one image's `tileOutputs`,
     *    one recording's `segments`), then the repeats are stitched into it;
     *  - lists whose items all carry `index` (tiles) end up in index order.
     */
    fun stitch(outputs: List<JsonElement>, chunks: List<Chunk>): JsonElement {
        val lastIndex = chunks.lastOrNull()?.index
        val filtered = outputs.mapIndexed { i, o -> chunks.getOrNull(i)?.takeIf { "ownFromMs" in it.params }?.let { c -> owned(o, c, c.index == lastIndex) } ?: o }
        return retext(stitchMerge(filtered))
    }

    /** A transcript (`segments` [{startMs, endMs, text}] + `text`): the text is re-stitched from the kept segments (media/Stitch). */
    private fun retext(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        val segs = (o["segments"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return e
        if (o["text"] == null || segs.any { it["startMs"] == null || it["text"] == null }) return e
        val text = com.hivecore.engine.text.Stitch.merge(segs.map { s ->
            com.hivecore.engine.text.SegmentText(Canon.decimal(s["startMs"])!!.toLong(), Canon.decimal(s["endMs"])?.toLong() ?: 0L, str(s, "text") ?: "")
        })
        return JsonObject(LinkedHashMap<String, JsonElement>(o).apply { put("text", JsonPrimitive(text)) })
    }

    private fun owned(e: JsonElement, c: Chunk, last: Boolean): JsonElement = when (e) {
        is JsonArray -> JsonArray(e.filter { keepTimed(it, c, last) }.map { owned(it, c, last) })
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k in LIST_KEYS && v is JsonArray) owned(v, c, last) else v })
        else -> e
    }

    private fun keepTimed(e: JsonElement, c: Chunk, last: Boolean): Boolean {
        val o = e as? JsonObject ?: return true
        val s = Canon.decimal(o["startMs"] ?: o["start"]) ?: return true
        val en = Canon.decimal(o["endMs"] ?: o["end"]) ?: return true
        val mid = (s + en).divide(BigDecimal(2))
        val to = BigDecimal(c.ownTo)
        return mid >= BigDecimal(c.ownFrom) && (mid < to || (last && mid.compareTo(to) == 0))
    }

    private fun stitchMerge(parts: List<JsonElement>): JsonElement {
        if (parts.all { it is JsonArray }) return JsonArray(dedupe(parts.flatMap { it as JsonArray }))
        if (parts.all { it is JsonObject }) {
            val objs = parts.map { it as JsonObject }
            val out = LinkedHashMap<String, JsonElement>(objs[0])
            for (k in LIST_KEYS) if (objs.any { it[k] is JsonArray }) out[k] = JsonArray(dedupe(objs.flatMap { (it[k] as? JsonArray).orEmpty() }))
            return JsonObject(out)
        }
        return JsonArray(parts)
    }

    private fun dedupe(items: List<JsonElement>): List<JsonElement> {
        val order = ArrayList<Any>()                      // a key String, or an unkeyed element
        val byKey = HashMap<String, JsonElement>()
        for (e in items) {
            val o = e as? JsonObject
            val key = o?.let { it["id"] ?: it["index"] ?: it["item"] }?.let { Canon.canonical(it) }
            if (key == null) { order += e; continue }
            val held = byKey[key]
            if (held == null) { byKey[key] = e; order += key }
            else if (LIST_KEYS.any { o[it] is JsonArray }) byKey[key] = stitchMerge(listOf(held, e))
        }
        val out = order.map { if (it is String) byKey.getValue(it) else it as JsonElement }
        return if (out.isNotEmpty() && out.all { (it as? JsonObject)?.get("index")?.let(Canon::decimal) != null })
            out.sortedBy { Canon.decimal((it as JsonObject)["index"]) } else out
    }

    // ------------------------------------------------------------------ TREE

    /**
     * Pairwise extractive reduce over the chunks' `picks` [{id, doc, unit, text}]: level by level, each pair's picks
     * (de-duplicated by id, in document order) become the candidates of one [selector] call that keeps ≤ k ids.
     * k = params.sentences ?: params.maxPick ?: 5. Output: {op, mode?, question?, method: "tree", chunks, levels, picks, citations}.
     */
    suspend fun tree(outputs: List<JsonElement>, params: JsonObject, selector: TreeSelector): JsonElement {
        val k = (params["sentences"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (params["maxPick"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 5
        // Document order = order of first appearance across chunks in chunk order.
        val order = LinkedHashMap<String, Int>()
        val leaves = outputs.map { o ->
            ((o as? JsonObject)?.get("picks") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .also { ps -> ps.forEach { p -> str(p, "id")?.let { order.putIfAbsent(it, order.size) } } }
        }
        var level: List<List<JsonObject>> = leaves
        var levels = 0
        while (level.size > 1) {
            val next = ArrayList<List<JsonObject>>()
            for (i in level.indices step 2) {
                if (i + 1 >= level.size) { next += level[i]; continue }
                val cands = (level[i] + level[i + 1]).distinctBy { str(it, "id") }.sortedBy { order[str(it, "id")] ?: Int.MAX_VALUE }
                val sentences = cands.map { sentenceOf(it) }
                val picked = if (cands.size <= k) cands.map { str(it, "id") }.toSet() else selector(sentences, k, params).toSet()
                val known = cands.mapNotNull { str(it, "id") }.toSet()
                require(picked.all { it in known }) { "the tree selector picked an id outside its candidates" }
                next += cands.filter { str(it, "id") in picked }
            }
            level = next
            levels++
        }
        val picks = level.firstOrNull().orEmpty()
        val head = outputs.first() as? JsonObject
        return buildJsonObject {
            head?.get("op")?.let { put("op", it) }
            head?.get("mode")?.let { put("mode", it) }
            head?.get("question")?.let { put("question", it) }
            put("method", "tree")
            put("chunks", outputs.size)
            put("levels", levels)
            put("picks", JsonArray(picks))
            put("citations", JsonArray(picks.map { "${str(it, "doc")} · ${str(it, "unit")}" }.distinct().map { JsonPrimitive(it) }))
        }
    }

    private fun sentenceOf(p: JsonObject): Sentence {
        val id = str(p, "id") ?: ""
        val doc = str(p, "doc") ?: id.substringBefore('|')
        val unit = str(p, "unit") ?: "0"
        return Sentence(doc, unit, id.substringAfterLast('|').toIntOrNull() ?: 0, str(p, "text") ?: "")
    }

    // ------------------------------------------------------------------ MONOID

    /** AggTable partials merged by group key, objects merged per key, plain numbers summed. */
    fun monoid(outputs: List<JsonElement>): JsonElement = when {
        outputs.all { isTable(it) } -> mergeTables(outputs.map { Canon.decodeTable(it) })
        outputs.all { it is JsonObject } -> mergeObjects(outputs.map { it as JsonObject })
        outputs.all { Canon.decimal(it) != null } -> Canon.number(outputs.fold(BigDecimal.ZERO) { a, e -> a + Canon.decimal(e)!! })
        else -> throw IllegalArgumentException("MONOID needs tables, objects or numbers")
    }

    private fun isTable(e: JsonElement) = e is JsonObject && e["columns"] is JsonArray && e["rows"] is JsonArray

    /**
     * Group key = the non-measure columns. `n`, `count_*`, `sum_*` add; `min_*`/`max_*` take the min/max; `avg_x` is
     * recomputed as `sum_x / n` (the partials must carry `sum_x` and `n`). Rows sorted by key ([ReducerImpl.compareKeys]).
     */
    fun mergeTables(ts: List<AggTable>): JsonElement {
        val cols = ts[0].columns
        ts.forEach { require(it.columns == cols) { "partials have different columns: ${it.columns} vs $cols" } }
        val keyIdx = cols.indices.filter { !ReducerImpl.isMeasure(cols[it]) }
        val mIdx = cols.indices.filter { ReducerImpl.isMeasure(cols[it]) }
        for (i in mIdx) if (cols[i].startsWith("avg_")) {
            val x = cols[i].removePrefix("avg_")
            require("sum_$x" in cols && "n" in cols) { "${cols[i]} can only be merged when the partials carry sum_$x and n" }
        }
        val groups = LinkedHashMap<String, Pair<List<JsonElement>, Array<BigDecimal?>>>()
        for (t in ts) for (r in t.rows) {
            val key = keyIdx.map { r.getOrElse(it) { JsonNull } }
            val acc = groups.getOrPut(Canon.canonical(JsonArray(key))) { key to arrayOfNulls(cols.size) }.second
            for (i in mIdx) {
                val v = Canon.decimalLoose(r.getOrNull(i)) ?: continue
                val c = cols[i]
                val cur = acc[i]
                acc[i] = when {
                    cur == null -> v
                    c.startsWith("min_") -> cur.min(v)
                    c.startsWith("max_") -> cur.max(v)
                    else -> cur + v
                }
            }
        }
        val rows = groups.values.map { (key, acc) ->
            var ki = 0
            cols.indices.map { i ->
                when {
                    i in keyIdx -> key[ki++]
                    cols[i].startsWith("avg_") -> {
                        val s = acc[cols.indexOf("sum_" + cols[i].removePrefix("avg_"))]; val n = acc[cols.indexOf("n")]
                        if (s == null || n == null || n.signum() == 0) JsonNull else Canon.number(Canon.div(s, n))
                    }
                    else -> acc[i]?.let { Canon.number(it) } ?: JsonNull
                }
            }
        }.sortedWith { a, b -> ReducerImpl.compareKeys(keyIdx.map { a[it] }, keyIdx.map { b[it] }) }
        val f = ts[0]
        return Canon.encodeTable(AggTable(ReducerImpl.NODE, f.title, cols, rows, ts.sumOf { it.rowsScanned }, ts.maxOf { it.rowsHeld }, f.source))
    }

    /** Per key: numbers add (`min*`/`max*` take min/max; `mean`/`avg*` recomputed from sum and count/n); objects recurse; anything else from the first. */
    fun mergeObjects(objs: List<JsonObject>): JsonObject {
        val keys = LinkedHashSet<String>().also { s -> objs.forEach { s.addAll(it.keys) } }
        val out = LinkedHashMap<String, JsonElement>()
        for (k in keys) {
            val vs = objs.mapNotNull { it[k] }
            out[k] = when {
                vs.all { Canon.decimal(it) != null } -> {
                    val ds = vs.map { Canon.decimal(it)!! }
                    Canon.number(when {
                        k.startsWith("min") -> ds.min()
                        k.startsWith("max") -> ds.max()
                        else -> ds.fold(BigDecimal.ZERO, BigDecimal::add)
                    })
                }
                vs.all { it is JsonObject } -> mergeObjects(vs.map { it as JsonObject })
                else -> vs.first()
            }
        }
        for (k in keys) if (k == "mean" || k.startsWith("mean_") || k.startsWith("avg")) {
            val suffix = k.removePrefix("mean").removePrefix("avg")
            val s = Canon.decimal(out["sum$suffix"]) ?: continue
            val n = Canon.decimal(out["count$suffix"] ?: out["count"] ?: out["n"]) ?: continue
            out[k] = if (n.signum() == 0) JsonNull else Canon.number(Canon.div(s, n))
        }
        return JsonObject(out)
    }

    // ------------------------------------------------------------------ TOPK

    /** Every chunk's `hits` [{id, score}], best score first, ties by id ascending, one hit per id, first k. */
    fun topK(outputs: List<JsonElement>, params: JsonObject): JsonElement {
        val head = outputs.first() as? JsonObject ?: JsonObject(emptyMap())
        val k = (params["k"] as? JsonPrimitive)?.content?.toIntOrNull() ?: (head["k"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10
        val hits = outputs.flatMap { ((it as? JsonObject)?.get("hits") as? JsonArray).orEmpty() }.mapNotNull { it as? JsonObject }
            .sortedWith(compareByDescending<JsonObject> { Canon.decimal(it["score"]) ?: BigDecimal(-1e9) }.thenBy { str(it, "id") ?: "" })
            .distinctBy { str(it, "id") }.take(k)
        return JsonObject(LinkedHashMap<String, JsonElement>(head).apply { put("hits", JsonArray(hits)) })
    }

    // ------------------------------------------------------------------ VOTE

    /**
     * Majority over fan-out runs. A run's answer is its `value`, `label` or `class` (or the output itself); with
     * `items` the vote is per item (`item`/`id`). Ties go to the answer seen first in chunk order. Confidence =
     * agreeing / runs (4 dp).
     */
    fun vote(outputs: List<JsonElement>): JsonElement {
        val head = outputs.first() as? JsonObject
        val perItem = outputs.all { (it as? JsonObject)?.get("items") is JsonArray }
        return buildJsonObject {
            head?.get("op")?.let { put("op", it) }
            put("method", "vote")
            put("runs", outputs.size)
            if (!perItem) {
                tally(outputs.map { answer(it) }).forEach { (k, v) -> put(k, v) }
            } else {
                val byItem = LinkedHashMap<String, Pair<JsonElement, MutableList<JsonElement>>>()
                for (o in outputs) for (it in ((o as JsonObject)["items"] as JsonArray)) {
                    val io = it as? JsonObject ?: continue
                    val key = io["item"] ?: io["id"] ?: continue
                    byItem.getOrPut(Canon.canonical(key)) { key to ArrayList() }.second += answer(io)
                }
                put("items", JsonArray(byItem.values.map { (key, answers) ->
                    JsonObject(linkedMapOf<String, JsonElement>("item" to key) + tally(answers))
                }))
            }
        }
    }

    private fun answer(e: JsonElement): JsonElement = (e as? JsonObject)?.let { it["value"] ?: it["label"] ?: it["class"] } ?: e

    private fun tally(answers: List<JsonElement>): Map<String, JsonElement> {
        val counts = LinkedHashMap<String, Pair<JsonElement, Int>>()
        for (a in answers) { val c = Canon.canonical(a); counts[c] = (counts[c]?.let { it.first to it.second + 1 }) ?: (a to 1) }
        val ranked = counts.values.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<JsonElement, Int>>> { it.value.second }.thenBy { it.index }).map { it.value }
        val (win, n) = ranked.first()
        return linkedMapOf(
            "value" to win,
            "agreeing" to JsonPrimitive(n),
            "confidence" to JsonPrimitive(Math.round(n * 10_000.0 / answers.size) / 10_000.0),
            "votes" to JsonArray(ranked.map { (v, c) -> buildJsonObject { put("value", v); put("n", c) } }),
        )
    }

    // ------------------------------------------------------------------ RANK

    /** Every chunk's items by `params.measure` (default `score`) descending, nulls last, ties by item/id; adds `rank`. */
    fun rank(outputs: List<JsonElement>, params: JsonObject): JsonElement {
        val measure = (params["measure"] as? JsonPrimitive)?.content ?: "score"
        val items = (concat(outputs) as? JsonObject)?.get("items") as? JsonArray ?: JsonArray(emptyList())
        val sorted = items.mapNotNull { it as? JsonObject }.sortedWith(
            compareBy<JsonObject> { if (Canon.decimal(it[measure]) == null) 1 else 0 }
                .thenByDescending { Canon.decimal(it[measure]) ?: BigDecimal.ZERO }
                .thenBy { Canon.canonical(it["item"] ?: it["id"] ?: JsonNull) })
        val head = outputs.first() as? JsonObject
        return buildJsonObject {
            head?.get("op")?.let { put("op", it) }
            put("method", "rank")
            put("measure", measure)
            put("items", JsonArray(sorted.mapIndexed { i, o -> JsonObject(linkedMapOf<String, JsonElement>("rank" to JsonPrimitive(i + 1)) + o) }))
        }
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
