package com.hivecore.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.hivecore.engine.chart.ChartArtifact
import com.hivecore.engine.deck.Chart
import com.hivecore.ui.Format
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone

// The chart as the chat shows it (spec amendment "open paths"): one series from an owner's table, bars for groups and
// a line for months, drawn with the kit. Every figure on it came from the table through the chart step, never a model.

/** Under a chart answer: the title, the series as bars or a line, the summary line. */
@Composable
fun ChartCard(c: ChartArtifact) {
    HiveCard(tone = CardTone.Tonal, contentPadding = HiveSpacing.cardTight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HiveIcons.activity, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.icon))
            Spacer(Modifier.width(HiveSpacing.sm))
            Text(c.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            StatusPill(Format.plural(c.chart.values.size, "point"), Tone.Accent)
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        val fg = MaterialTheme.colorScheme.onSurface
        val accent = MaterialTheme.colorScheme.primary
        if (c.style == "line") LineChart(c.chart, Modifier.fillMaxWidth().height(HiveSpacing.chart), fg, accent)
        else BarChart(c.chart, Modifier.fillMaxWidth().height(HiveSpacing.chart), compact = c.chart.values.size > 6, fg = fg, accent = accent)
        Spacer(Modifier.height(HiveSpacing.xs))
        val dropped = if (c.dropped > 0) " · ${Format.plural(c.dropped, "more point")} not shown" else ""
        Text("${c.measure} by ${c.dims.joinToString(", ")}$dropped", style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A line through the points, the figures at the ends and the peak, up to six category labels under it. */
@Composable
internal fun LineChart(c: Chart, modifier: Modifier, fg: Color, accent: Color) {
    val n = c.values.size
    val max = c.values.maxOrNull() ?: 0.0
    val min = minOf(0.0, c.values.minOrNull() ?: 0.0)
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val peak = c.values.indices.maxByOrNull { c.values[it] } ?: 0
    val style = HiveTextStyles.count
    Column(modifier.semantics { contentDescription = c.categories.indices.joinToString("; ") { i -> "${c.categories[i]} ${c.labels.getOrNull(i) ?: c.values[i]}" } }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(c.labels.firstOrNull() ?: "", style = style, color = fg, maxLines = 1)
            if (peak != 0 && peak != n - 1) Text("peak ${c.labels.getOrNull(peak) ?: ""}", style = style, color = accent, maxLines = 1)
            Text(c.labels.lastOrNull() ?: "", style = style, color = fg, maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (n == 0) return@Canvas
            val w = size.width; val h = size.height
            val stroke = HiveSpacing.chartStroke.toPx(); val r = HiveSpacing.chartPoint.toPx()
            fun x(i: Int) = if (n == 1) w / 2 else r + (w - 2 * r) * i / (n - 1)
            fun y(v: Double) = (h - r) - ((v - min) / span).toFloat() * (h - 2 * r)
            val base = y(0.0)
            drawLine(fg.copy(alpha = ALPHA_PANEL * 2), Offset(0f, base), Offset(w, base), strokeWidth = stroke / 3)
            val path = Path()
            c.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
            c.values.forEachIndexed { i, v -> drawCircle(if (i == peak) accent else fg, r, Offset(x(i), y(v))) }
        }
        Spacer(Modifier.height(HiveSpacing.xs))
        val shown = if (n <= 6) c.categories.indices.toList() else (0 until 6).map { it * (n - 1) / 5 }.distinct()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            shown.forEach { i -> Text(c.categories[i], style = style, color = fg.copy(alpha = ALPHA_MUTED), maxLines = 1, textAlign = TextAlign.Center) }
        }
    }
}

@HivePreviews
@Composable
private fun ChartCardPreviews() = HiveCoreTheme {
    Column(verticalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
        val months = (1..12).map { "2025-%02d".format(it) }
        val values = listOf(31.0, 28.0, 35.0, 40.0, 38.0, 44.0, 47.0, 45.0, 52.0, 55.0, 58.0, 61.0).map { it * 100_000 }
        ChartCard(ChartArtifact("Net sales by month", "line", Chart("Net sales by month", months, values, values.map { "₹${(it / 100_000).toInt()} L" }),
            "sum_net_sales", listOf("month"), "Net sales by month: ₹31 L in 2025-01 to ₹61 L in 2025-12, +97 % over the period."))
        ChartCard(ChartArtifact("Net sales by branch", "bar", Chart("Net sales by branch", listOf("Bengaluru", "Chennai", "Kochi"), listOf(63.7, 57.6, 29.4), listOf("₹63.7 L", "₹57.6 L", "₹29.4 L")),
            "sum_net_sales", listOf("branch"), "Net sales by branch: 3 groups; highest Bengaluru at ₹63.7 L, lowest Kochi at ₹29.4 L."))
    }
}
