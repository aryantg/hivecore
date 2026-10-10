package com.hivecore.ui.vm

import com.hivecore.core.JobProgress
import com.hivecore.engine.workload.RecipeDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the result viewer shows for a finished job. */
sealed interface ResultView {
    /** OCR-like output: one text per item (photo, page). */
    data class Texts(val items: List<ItemText>) : ResultView
    data class Table(val columns: List<String>, val rows: List<List<String>>) : ResultView
    data class Raw(val json: String) : ResultView
}

data class ItemText(val item: String, val lines: Int, val text: String)

/**
 * Turns a job's merged output into something a person reads (pure, JVM-tested). A recipe's output is
 * `{"stages":{"<id>":<merged>}}`; the last stage's output is shown. `items[]` whose entries carry `lines[].text`
 * read as texts, other `items[]` as a table (columns from the recipe's output schema when they are present).
 */
object ResultViews {
    private const val MAX_RAW = 4_000

    fun of(p: JobProgress, recipe: RecipeDef?): ResultView? {
        val r = p.result ?: return null
        val stages = (r as? JsonObject)?.get("stages") as? JsonObject
        val out = if (stages != null) recipe?.stages?.lastOrNull()?.id?.let { stages[it] } ?: stages.values.lastOrNull() ?: r else r
        return view(out, recipe)
    }

    fun view(e: JsonElement, recipe: RecipeDef? = null): ResultView {
        val obj = e as? JsonObject
        val items = (obj?.get("items") as? JsonArray) ?: (e as? JsonArray)
        if (items != null && items.isNotEmpty() && items.all { it is JsonObject }) {
            val objs = items.map { it as JsonObject }
            if (objs.any { it["lines"] is JsonArray }) return ResultView.Texts(objs.mapIndexed { i, o ->
                val lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { l ->
                    when (l) { is JsonObject -> (l["text"] as? JsonPrimitive)?.contentOrNull; is JsonPrimitive -> l.contentOrNull; else -> null }
                }
                ItemText(itemName(o["item"], i), lines.size, lines.joinToString("\n"))
            })
            val schema = recipe?.outputSchema?.map { it.name }?.filter { c -> objs.any { c in it } }.orEmpty()
            val cols = schema.ifEmpty { objs.flatMap { it.keys }.distinct() }
            return ResultView.Table(cols, objs.map { o -> cols.map { c -> cell(o[c]) } })
        }
        val columns = obj?.get("columns") as? JsonArray
        val rows = obj?.get("rows") as? JsonArray
        if (columns != null && rows != null) return ResultView.Table(columns.map { cell(it) }, rows.map { r -> (r as? JsonArray).orEmpty().map { cell(it) } })
        return ResultView.Raw(e.toString().take(MAX_RAW))
    }

    /** "Item 3" when unnamed; a path or blob reference shortened to its last segment. */
    fun itemName(e: JsonElement?, index: Int): String {
        val s = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return "Item ${index + 1}"
        val last = s.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
        return if (last.length > 28) last.take(12) + "…" + last.takeLast(8) else last
    }

    private fun cell(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.contentOrNull ?: ""
        else -> e.toString()
    }

    /** The view as CSV for "Copy as CSV" (texts become item,lines,text rows). */
    fun csv(v: ResultView): String {
        fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return when (v) {
            is ResultView.Texts -> (listOf("item,lines,text") + v.items.map { "${q(it.item)},${it.lines},${q(it.text)}" }).joinToString("\n")
            is ResultView.Table -> (listOf(v.columns.joinToString(",") { q(it) }) + v.rows.map { r -> r.joinToString(",") { q(it) } }).joinToString("\n")
            is ResultView.Raw -> v.json
        }
    }
}
