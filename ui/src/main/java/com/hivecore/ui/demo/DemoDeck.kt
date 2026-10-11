package com.hivecore.ui.demo

import com.hivecore.engine.deck.Chart
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.deck.DeckFormat
import com.hivecore.engine.deck.Slide
import com.hivecore.engine.deck.SlideKind

/**
 * The scripted deck of Demo mode: Kavira Pharmaceuticals' net sales over three fiscal years (the seeded figures in
 * [KaviraFigures]), built the way the real
 * assembler builds one (the chart and every figure from the numbers, the words as a slide-text step would return them).
 * [illustration] is a PNG the app drew on the device (null = the picture slide shows its placeholder).
 */
object DemoDeck {
    val YEARS = listOf("FY2024", "FY2025", "FY2026")
    val REVENUE = YEARS.map { KaviraFigures.NET_SALES.getValue(it).toDouble() }

    fun build(illustration: String? = null): Deck {
        val labels = REVENUE.map { DeckFormat.compact(it, money = true) }
        val deltas = REVENUE.mapIndexed { i, v -> if (i == 0) null else DeckFormat.pct(REVENUE[i - 1], v) }
        val facts = YEARS.indices.map { i -> "${YEARS[i]}: Revenue ${labels[i]}" + (deltas[i]?.let { " ($it on ${YEARS[i - 1]})" } ?: "") }
        val total = DeckFormat.pct(REVENUE.first(), REVENUE.last())!!.removePrefix("+")
        val summary = "Net sales grew $total from ${YEARS.first()} (${labels.first()}) to ${YEARS.last()} (${labels.last()})."
        val chart = Chart("Net sales by fiscal year", YEARS, REVENUE, labels, deltas)
        return Deck(
            title = "Kavira Pharmaceuticals: sales FY2024-26",
            subtitle = "Net sales by fiscal year, from the Sales phone",
            slides = listOf(
                Slide(SlideKind.TITLE, "Kavira Pharmaceuticals: sales FY2024-26", bullets = listOf("Net sales by fiscal year, from the Sales phone")),
                Slide(SlideKind.CHART, chart.title, chart = chart, caption = summary),
                Slide(SlideKind.BULLETS, "Highlights", bullets = listOf(
                    "Net sales rose $total over two fiscal years",
                    "FY2026 added ${DeckFormat.compact(REVENUE[2] - REVENUE[1], true)}, the biggest step yet",
                    "FY2025: the Tamil Nadu tender and the Vildora M launch; FY2026: Acneclear and contract client C",
                    "Every figure came from the Sales phone; nothing left the swarm")),
                Slide(SlideKind.IMAGE, "In one picture", bullets = facts, image = illustration,
                    caption = if (illustration != null) "Generated on a phone of the swarm" else "The illustration did not reach this phone"),
                Slide(SlideKind.BULLETS, "Next steps", bullets = listOf("Carry the FY2026 pace into FY2027 and the Hyderabad branch", "Ask the Sales phone for any figure behind a slide")),
            ),
            summary = summary,
        )
    }
}
