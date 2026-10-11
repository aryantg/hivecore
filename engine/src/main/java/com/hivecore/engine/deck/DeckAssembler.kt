package com.hivecore.engine.deck

import com.hivecore.core.AggTable
import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The last step of a deck plan (`RENDER` with `kind: deck`, spec amendment "decks"): turns what the other phones
 * produced into a [Deck] and, when a directory is given, a .pptx. It runs on the orchestrator like every reduce.
 *
 *  - the owner's table (AGG, or a table reduce over it) becomes the chart: rows rolled up by calendar year, or by
 *    fiscal year (April to March, labelled by the year it ends in) when the period phrase says "FY"; a table without a
 *    month column is charted by its first dimension instead
 *  - the slide-text step (`LLM_MAP` or `GEN_TEXT`, validated against [TEXT_SCHEMA]) supplies the title, subtitle,
 *    headline, highlights and closing points; when it was skipped or its output does not fit, deterministic sentences
 *    built from the figures take its place, so a deck is produced from the numbers alone
 *  - the picture step (`GEN_IMAGE`) supplies one illustration when its PNG reached this phone ([imageFile] by sha256)
 *
 * Every sentence that states a figure is written by this code from the table (R2: no model writes the numbers).
 */
object DeckAssembler {
    const val KIND = Deck.KIND
    const val PARAM_KIND = Deck.KIND_KEY
    const val PARAM_TITLE = "title"
    const val PARAM_MEASURE = "measure"
    const val PARAM_PERIOD = "period"
    const val PARAM_SUBJECT = "subject"
    private const val MONTH = "month"

    /** What the slide-text step is asked for; the keys the assembler reads back. */
    val TEXT_SCHEMA: JsonObject = Canon.json.parseToJsonElement("""
        {"type":"object","properties":{
          "title":{"type":"string","maxLength":60},
          "subtitle":{"type":"string","maxLength":90},
          "headline":{"type":"string","maxLength":120},
          "bullets":{"type":"array","items":{"type":"string","maxLength":110},"minItems":2,"maxItems":5},
          "closing":{"type":"array","items":{"type":"string","maxLength":110},"minItems":1,"maxItems":4}
        },"required":["title","headline","bullets"]}
    """.trimIndent()) as JsonObject

    /** True for the RENDER step that assembles a deck. */
    fun isDeckStep(spec: TaskSpec): Boolean =
        spec.op == Op.RENDER && (spec.params[PARAM_KIND] as? JsonPrimitive)?.content == KIND

    /**
     * [inputs] are the RENDER step's inputs in plan order with the step that produced each; [imageFile] finds a
     * generated PNG by its sha256 (the blob store); [outDir] receives `deck-<hash>.pptx` (null = structure only).
     */
    fun assemble(spec: TaskSpec, inputs: List<Pair<TaskSpec, JsonElement>>, imageFile: (String) -> File? = { null }, outDir: File? = null): Deck {
        val p = spec.params
        val measureParam = str(p, PARAM_MEASURE)
        val period = str(p, PARAM_PERIOD).orEmpty()
        val fiscal = period.lowercase().contains("fy")
        val table = inputs.firstNotNullOfOrNull { (_, out) -> runCatching { Canon.decodeTable(out) }.getOrNull()?.takeIf { it.rows.isNotEmpty() } }
        val series = table?.let { rollUp(it, measureParam, fiscal) }
        val measure = series?.measure ?: measureParam ?: "value"
        val measureTitle = DeckFormat.measureTitle(measure)
        val text = inputs.firstNotNullOfOrNull { (s, out) -> slideText(s, out) }
        val image = inputs.firstNotNullOfOrNull { (s, out) -> if (s.op == Op.GEN_IMAGE || s.op == Op.GEN_VARIANTS) imageOf(out, imageFile) else null }

        val subject = str(p, PARAM_SUBJECT) ?: listOfNotNull(measureTitle, period.ifBlank { null }).joinToString(" ")
        val title = text?.title ?: str(p, PARAM_TITLE) ?: subject
        val chart = series?.let { s ->
            Chart("$measureTitle by ${if (s.fiscal) "fiscal year" else s.axis}", s.categories, s.values,
                labels = s.values.map { DeckFormat.compact(it, s.money) },
                deltas = s.values.mapIndexed { i, v -> if (i == 0) null else DeckFormat.pct(s.values[i - 1], v) })
        }
        val summary = series?.let { summary(measureTitle, it) } ?: "A deck of ${if (text != null) "the generated text" else "the request"}: no figures were available."
        val headline = text?.headline ?: summary
        val facts = series?.let { facts(measureTitle, it) }.orEmpty()
        val bullets = text?.bullets?.takeIf { it.isNotEmpty() } ?: facts.ifEmpty { listOf("No figures reached this phone.") }
        val closing = text?.closing?.takeIf { it.isNotEmpty() } ?: listOf("Figures from the owner's phone; nothing left the swarm.", "Assembled on your phones.")

        val slides = ArrayList<Slide>()
        slides += Slide(SlideKind.TITLE, title, bullets = listOfNotNull((text?.subtitle ?: subject).takeIf { it.isNotBlank() }))
        if (chart != null) slides += Slide(SlideKind.CHART, chart.title, chart = chart, caption = headline)
        slides += Slide(SlideKind.BULLETS, "Highlights", bullets = bullets)
        if (image != null || inputs.any { it.first.op == Op.GEN_IMAGE || it.first.op == Op.GEN_VARIANTS })
            slides += Slide(SlideKind.IMAGE, "In one picture", bullets = facts.take(3), image = image,
                caption = if (image != null) "Generated on a phone of the swarm" else "The illustration did not reach this phone")
        slides += Slide(SlideKind.BULLETS, "Next steps", bullets = closing)

        var deck = Deck(title, text?.subtitle ?: subject, slides, summary)
        if (outDir != null) {
            val bytes = PptxWriter.bytes(deck)
            val sha = com.hivecore.core.Hashing.sha256Hex(bytes)
            val f = File(outDir, "deck-${sha.take(12)}.pptx")
            outDir.mkdirs()
            if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
            deck = deck.copy(file = f.absolutePath, fileSha256 = sha, bytes = bytes.size.toLong())
        }
        return deck
    }

    // ------------------------------------------------------------------ the figures

    class Series(val measure: String, val axis: String, val fiscal: Boolean, val categories: List<String>, val values: List<Double>, val money: Boolean)

    /**
     * The table's measure column (the named one, else the last numeric column) summed per year when it has a month
     * column ("YYYY-MM"), else per value of its first text column, in order of appearance.
     */
    fun rollUp(t: AggTable, measure: String?, fiscal: Boolean): Series? {
        val mi = measureIndex(t, measure) ?: return null
        val m = t.columns[mi]
        val money = DeckFormat.isMoney(m) || DeckFormat.isMoney(measure.orEmpty()) || DeckFormat.isMoney(t.title)
        val monthI = t.columns.indexOf(MONTH)
        val sums = LinkedHashMap<String, Double>()
        if (monthI >= 0) {
            val byKey = HashMap<String, Double>()
            for (r in t.rows) {
                val ym = (r.getOrNull(monthI) as? JsonPrimitive)?.contentOrNull ?: continue
                val v = Canon.decimalLoose(r.getOrNull(mi) ?: continue)?.toDouble() ?: continue
                val key = yearOf(ym, fiscal) ?: continue
                byKey[key] = (byKey[key] ?: 0.0) + v
            }
            for (k in byKey.keys.sorted()) sums[if (fiscal) "FY$k" else k] = byKey.getValue(k)
            return if (sums.isEmpty()) null else Series(m, "year", fiscal, sums.keys.toList(), sums.values.toList(), money)
        }
        val dimI = t.columns.indices.firstOrNull { it != mi && t.rows.all { r -> (r.getOrNull(it) as? JsonPrimitive)?.isString == true } } ?: return null
        for (r in t.rows) {
            val k = (r.getOrNull(dimI) as? JsonPrimitive)?.contentOrNull ?: continue
            val v = Canon.decimalLoose(r.getOrNull(mi) ?: continue)?.toDouble() ?: continue
            sums[k] = (sums[k] ?: 0.0) + v
        }
        return if (sums.isEmpty()) null else Series(m, t.columns[dimI], false, sums.keys.toList(), sums.values.toList(), money)
    }

    private fun measureIndex(t: AggTable, measure: String?): Int? {
        if (measure != null) {
            t.columns.indexOfFirst { it == measure || it == "sum_$measure" || it.endsWith("_$measure") }.takeIf { it >= 0 }?.let { return it }
        }
        return t.columns.indices.lastOrNull { i -> i != t.columns.indexOf(MONTH) && t.rows.any { r -> (r.getOrNull(i) as? JsonPrimitive)?.let { !it.isString && Canon.decimalLoose(it) != null } == true } }
    }

    /** "2024-07" → "2024"; fiscal: April 2023 to March 2024 is "2024" (the year the fiscal year ends in). */
    fun yearOf(ym: String, fiscal: Boolean): String? {
        val y = ym.take(4).toIntOrNull() ?: return null
        if (!fiscal) return y.toString()
        val m = ym.drop(5).take(2).toIntOrNull() ?: return y.toString()
        return (if (m >= 4) y + 1 else y).toString()
    }

    /** "Revenue grew 38 % from FY2024 (₹8.1 Cr) to FY2026 (₹11.2 Cr)." */
    fun summary(measureTitle: String, s: Series): String {
        if (s.values.size < 2) return "$measureTitle ${s.categories.first()}: ${DeckFormat.compact(s.values.first(), s.money)}."
        val a = s.values.first(); val b = s.values.last()
        val pct = DeckFormat.pct(a, b)
        val verb = when { pct == null -> "went from"; b > a -> "grew"; b < a -> "fell"; else -> "held at" }
        val change = pct?.let { " ${it.removePrefix("+").removePrefix("-")}" } ?: ""
        return "$measureTitle $verb$change from ${s.categories.first()} (${DeckFormat.compact(a, s.money)}) to ${s.categories.last()} (${DeckFormat.compact(b, s.money)})."
    }

    /** One line per category with its figure and change. */
    fun facts(measureTitle: String, s: Series): List<String> = s.categories.mapIndexed { i, c ->
        val v = DeckFormat.compact(s.values[i], s.money)
        val d = if (i == 0) null else DeckFormat.pct(s.values[i - 1], s.values[i])
        "$c: $measureTitle $v" + (d?.let { " ($it on ${s.categories[i - 1]})" } ?: "")
    }

    // ------------------------------------------------------------------ the words

    class SlideText(val title: String?, val subtitle: String?, val headline: String?, val bullets: List<String>, val closing: List<String>)

    /** The validated object a slide-text step returned: `LLM_MAP` (`items[0].output`) or `GEN_TEXT` (`value`). */
    fun slideText(spec: TaskSpec, out: JsonElement): SlideText? {
        val obj = when (spec.op) {
            Op.LLM_MAP -> (((out as? JsonObject)?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("output") as? JsonObject
            Op.GEN_TEXT -> (out as? JsonObject)?.get("value") as? JsonObject
            else -> null
        } ?: return null
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun list(k: String) = (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        return SlideText(s("title"), s("subtitle"), s("headline"), list("bullets"), list("closing"))
    }

    // ------------------------------------------------------------------ the picture

    /** The first unblocked generated image whose PNG this phone holds. */
    fun imageOf(out: JsonElement, imageFile: (String) -> File?): String? {
        val items = (out as? JsonObject)?.get("items") as? JsonArray ?: return null
        for (it in items) {
            val o = it as? JsonObject ?: continue
            if ((o["blocked"] as? JsonPrimitive)?.contentOrNull == "true") continue
            val sha = (o["sha256"] as? JsonPrimitive)?.contentOrNull ?: continue
            val f = imageFile(sha) ?: continue
            if (f.isFile) return f.absolutePath
        }
        return null
    }

    private fun str(p: JsonObject, k: String): String? = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
