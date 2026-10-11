package com.hivecore.engine.chart

import com.hivecore.core.AggTable
import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.PlanRenderer
import com.hivecore.engine.ReducerImpl
import com.hivecore.engine.deck.Chart
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.deck.DeckFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A chart as the swarm produces it (spec amendment "open paths"): the output of a `RENDER` step with `kind: chart`
 * over a table step. The chat draws it ([style] bar or line); [summary] is the answer sentence, written by
 * [ChartAssembler] from the table (R2: every figure comes from the owner's rows, never from a model).
 */
@Serializable data class ChartArtifact(
    val title: String,
    /** "bar" | "line" */
    val style: String,
    val chart: Chart,
    /** the measure column charted */
    val measure: String,
    /** the category columns, joined " / " per point */
    val dims: List<String>,
    val summary: String,
    /** points dropped to keep the chart readable (0 = all shown) */
    val dropped: Int = 0,
) {
    fun toJson(): JsonElement = JsonObject(mapOf(Deck.KIND_KEY to JsonPrimitive(KIND)) + Canon.json.encodeToJsonElement(serializer(), this).jsonObject)

    companion object {
        const val KIND = "chart"
        fun isChart(e: JsonElement?): Boolean = (e as? JsonObject)?.get(Deck.KIND_KEY)?.let { (it as? JsonPrimitive)?.content } == KIND
        fun fromJson(e: JsonElement?): ChartArtifact? {
            if (!isChart(e)) return null
            return runCatching { Canon.json.decodeFromJsonElement(serializer(), JsonObject((e as JsonObject) - Deck.KIND_KEY)) }.getOrNull()
        }
    }
}

/**
 * The chart step (`RENDER` with `kind: chart`): the first table among its inputs becomes one series. Params:
 * `measure` (the column to chart; default the last measure column), `style` ("bar" | "line"; default line when the
 * table has a month column, else bar), `title`. Categories are the table's other columns joined with " / "; labels
 * use the deck's Indian-scale figures; deltas are the change from the previous point. Runs on the orchestrator.
 */
object ChartAssembler {
    const val KIND = ChartArtifact.KIND
    const val PARAM_KIND = Deck.KIND_KEY
    const val PARAM_MEASURE = "measure"
    const val PARAM_STYLE = "style"
    const val PARAM_TITLE = "title"
    const val MAX_POINTS = 36
    private const val MONTH = "month"

    fun isChartStep(spec: TaskSpec): Boolean =
        spec.op == Op.RENDER && (spec.params[PARAM_KIND] as? JsonPrimitive)?.content == KIND

    fun assemble(spec: TaskSpec, inputs: List<Pair<TaskSpec, JsonElement>>): ChartArtifact {
        val table = inputs.firstNotNullOfOrNull { (_, out) -> runCatching { Canon.decodeTable(out) }.getOrNull() }
            ?: throw IllegalArgumentException("the chart step has no table to chart")
        return of(table, str(spec.params, PARAM_MEASURE), str(spec.params, PARAM_STYLE), str(spec.params, PARAM_TITLE))
    }

    /** One series from [t]: [measure] (or the last measure column), the other columns as the category. */
    fun of(t: AggTable, measure: String? = null, style: String? = null, title: String? = null): ChartArtifact {
        if (t.rows.isEmpty()) throw IllegalArgumentException("${t.title}: no rows to chart")
        val mi = measureIndex(t, measure) ?: throw IllegalArgumentException("${t.title} has no numeric column to chart")
        val m = t.columns[mi]
        val dims = t.columns.indices.filter { it != mi && !ReducerImpl.isMeasure(t.columns[it]) && it != t.columns.indexOf("n") }
            .ifEmpty { t.columns.indices.filter { it != mi } }
        val rows = t.rows.take(MAX_POINTS)
        val categories = rows.map { r -> dims.joinToString(" / ") { PlanRenderer.text(r.getOrNull(it) ?: JsonPrimitive("")) }.ifBlank { "all" } }
        val values = rows.map { r -> Canon.decimalLoose(r.getOrNull(mi))?.toDouble() ?: 0.0 }
        val money = DeckFormat.isMoney(m) || DeckFormat.isMoney(t.title)
        val labels = values.map { DeckFormat.compact(it, money) }
        val timed = dims.any { t.columns[it] == MONTH }
        val st = style?.lowercase()?.takeIf { it == "bar" || it == "line" } ?: if (timed) "line" else "bar"
        val deltas = values.mapIndexed { i, v -> if (i == 0 || !timed) null else DeckFormat.pct(values[i - 1], v) }
        val measureName = PlanRenderer.measureName(m)
        val dimNames = dims.map { t.columns[it] }
        val ttl = title ?: "${measureName.replaceFirstChar { it.uppercase() }} by ${dimNames.joinToString(" and ").ifBlank { "group" }}"
        val chart = Chart(ttl, categories, values, labels, deltas)
        return ChartArtifact(ttl, st, chart, m, dimNames, summary(ttl, categories, values, labels, timed), (t.rows.size - rows.size).coerceAtLeast(0))
    }

    private fun summary(title: String, cats: List<String>, values: List<Double>, labels: List<String>, timed: Boolean): String {
        if (values.size == 1) return "$title: ${cats[0]} ${labels[0]}."
        val hi = values.indices.maxByOrNull { values[it] }!!
        val lo = values.indices.minByOrNull { values[it] }!!
        return if (timed) {
            val change = DeckFormat.pct(values.first(), values.last())?.let { ", $it over the period" } ?: ""
            "$title: ${labels.first()} in ${cats.first()} to ${labels.last()} in ${cats.last()}$change; peak ${labels[hi]} in ${cats[hi]}."
        } else "$title: ${values.size} groups; highest ${cats[hi]} at ${labels[hi]}, lowest ${cats[lo]} at ${labels[lo]}."
    }

    private fun measureIndex(t: AggTable, measure: String?): Int? {
        if (measure != null) {
            t.columns.indexOfFirst { it.equals(measure, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
            t.columns.indexOfFirst { it.endsWith("_$measure", ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
        }
        t.columns.indices.lastOrNull { ReducerImpl.isMeasure(t.columns[it]) && t.columns[it] != "n" && t.columns[it] != "rank" }?.let { return it }
        return t.columns.indices.lastOrNull { i -> t.rows.all { r -> Canon.decimalLoose(r.getOrNull(i)) != null } }
    }

    private fun str(p: JsonObject, k: String): String? = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}
