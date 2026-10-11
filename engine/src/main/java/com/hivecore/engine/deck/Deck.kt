package com.hivecore.engine.deck

import com.hivecore.core.Canon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.math.abs

/**
 * A slide deck as the swarm produces it (spec amendment "decks"): the structured form every renderer reads — the
 * chat Home draws it, [PptxWriter] writes it as a .pptx, and the plan's answer text is its [summary]. The structure
 * carries no model-written prose except what the slide-text step returned, validated against its schema; the
 * figures come from the owner's table and are formatted by deterministic code.
 */
@Serializable data class Deck(
    val title: String,
    val subtitle: String = "",
    val slides: List<Slide>,
    /** one sentence for the answer card ("Revenue grew 38 % from FY2024 to FY2026") */
    val summary: String = "",
    /** the written .pptx (absolute path on the orchestrator), when a deck directory was configured */
    val file: String? = null,
    val fileSha256: String? = null,
    val bytes: Long = 0,
) {
    /** The deck as the RENDER step's output: the structure plus the `kind` marker the renderers look for. */
    fun toJson(): JsonElement = JsonObject(mapOf(KIND_KEY to JsonPrimitive(KIND)) + Canon.json.encodeToJsonElement(serializer(), this).jsonObject)

    companion object {
        const val KIND = "deck"
        const val KIND_KEY = "kind"

        fun isDeck(e: JsonElement?): Boolean = (e as? JsonObject)?.get(KIND_KEY)?.let { (it as? JsonPrimitive)?.content } == KIND

        /** The deck in a RENDER output, or null when [e] is not one. */
        fun fromJson(e: JsonElement?): Deck? {
            if (!isDeck(e)) return null
            return runCatching { Canon.json.decodeFromJsonElement(serializer(), JsonObject((e as JsonObject) - KIND_KEY)) }.getOrNull()
        }
    }
}

@Serializable enum class SlideKind { TITLE, BULLETS, CHART, IMAGE }

@Serializable data class Slide(
    val kind: SlideKind,
    val title: String,
    val bullets: List<String> = emptyList(),
    val chart: Chart? = null,
    /** absolute path of a PNG on the orchestrator (an IMAGE slide); null when the picture did not arrive */
    val image: String? = null,
    /** the line under a chart or a picture */
    val caption: String? = null,
)

/** One series of bars: [labels] are the figures as shown, [deltas] the change from the previous bar ("+38 %"), both per category. */
@Serializable data class Chart(
    val title: String,
    val categories: List<String>,
    val values: List<Double>,
    val labels: List<String> = emptyList(),
    val deltas: List<String?> = emptyList(),
) {
    init {
        require(categories.size == values.size) { "chart has ${categories.size} categories and ${values.size} values" }
        require(labels.isEmpty() || labels.size == values.size) { "chart labels do not match its values" }
        require(deltas.isEmpty() || deltas.size == values.size) { "chart deltas do not match its values" }
    }
}

/** Figures as a slide shows them: Indian scale (K / L / Cr), one decimal, a currency sign for money-like measures. */
object DeckFormat {
    private val MONEY = setOf("revenue", "sales", "turnover", "income", "spend", "total", "amount", "cost", "earnings", "value", "billing", "profit")

    fun isMoney(measure: String): Boolean = MONEY.any { measure.lowercase().contains(it) }

    fun compact(v: Double, money: Boolean): String {
        val a = abs(v)
        val sign = if (v < 0) "-" else ""
        val body = when {
            a >= 1e7 -> one(a / 1e7) + " Cr"
            a >= 1e5 -> one(a / 1e5) + " L"
            a >= 1e3 -> one(a / 1e3) + " K"
            a == Math.rint(a) -> a.toLong().toString()
            else -> one(a)
        }
        return sign + (if (money) "₹" else "") + body
    }

    fun pct(from: Double, to: Double): String? {
        if (from == 0.0) return null
        val p = (to - from) / abs(from) * 100
        val r = Math.round(p)
        return (if (r > 0) "+" else "") + "$r %"
    }

    private fun one(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else String.format(Locale.ROOT, "%.1f", x)

    /** "revenue" → "Revenue"; "sum_revenue" → "Revenue". */
    fun measureTitle(measure: String): String =
        measure.removePrefix("sum_").removePrefix("avg_").removePrefix("c_").replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}
