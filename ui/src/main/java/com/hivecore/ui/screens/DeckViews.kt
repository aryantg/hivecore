package com.hivecore.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.hivecore.engine.deck.Chart
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.deck.Slide
import com.hivecore.engine.deck.SlideKind
import com.hivecore.ui.Format
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone
import java.io.File

// The deck as the chat shows it (spec amendment "decks"): the same [Deck] the .pptx is written from, drawn with the
// kit, so what the jury sees on the phone is what opens in PowerPoint. Slides are 16:9 panels on the inverse
// surface (dark in light mode) with the primary colour as the accent, like the written file's slate and amber.

/** Under a deck answer: the first slides as thumbnails, the file line and Present / Open / Share. */
@Composable
fun DeckCard(deck: Deck, onPresent: () -> Unit, onOpenFile: ((String) -> Unit)?, onShareFile: ((String) -> Unit)?) {
    HiveCard(tone = CardTone.Tonal, contentPadding = HiveSpacing.cardTight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HiveIcons.deck, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.icon))
            Spacer(Modifier.width(HiveSpacing.sm))
            Text(deck.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusPill(Format.plural(deck.slides.size, "slide"), Tone.Accent)
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            deck.slides.forEachIndexed { i, s ->
                item("s$i") { SlideView(s, Modifier.width(HiveSpacing.slideThumb), compact = true, index = i + 1, count = deck.slides.size) }
            }
        }
        deck.file?.let { f ->
            Spacer(Modifier.height(HiveSpacing.xs))
            Text("${File(f).name} · ${Format.bytes(deck.bytes)} · opens in PowerPoint, Keynote or Slides", style = HiveTextStyles.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(HiveSpacing.xs))
        ActionRow(
            primary = HiveAction("Present", HiveIcons.play, onClick = onPresent),
            secondaries = listOfNotNull(
                deck.file?.let { f -> onOpenFile?.let { HiveAction("Open .pptx", HiveIcons.openExternal) { it(f) } } },
                deck.file?.let { f -> onShareFile?.let { HiveAction("Share", HiveIcons.share) { it(f) } } },
            ),
        )
    }
}

/** The presenter: one slide per page, swipe to advance, the file actions under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckSheet(deck: Deck, onOpenFile: ((String) -> Unit)?, onShareFile: ((String) -> Unit)?, onClose: () -> Unit) {
    val pager = rememberPagerState { deck.slides.size }
    ModalBottomSheet(onDismissRequest = onClose, shape = HiveShape.sheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = HiveSpacing.screenH).padding(bottom = HiveSpacing.xl), verticalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HiveIcons.deck, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.iconLg))
                Spacer(Modifier.width(HiveSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(deck.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Slide ${pager.currentPage + 1} of ${deck.slides.size} · swipe to advance", style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalPager(pager, pageSpacing = HiveSpacing.sm) { page ->
                SlideView(deck.slides[page], Modifier.fillMaxWidth(), compact = false, index = page + 1, count = deck.slides.size)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                deck.slides.indices.forEach { i ->
                    Box(Modifier.padding(HiveSpacing.xxs).size(HiveSpacing.dot).clip(HiveShape.circle)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            ActionRow(secondaries = listOfNotNull(
                deck.file?.let { f -> onOpenFile?.let { HiveAction("Open .pptx", HiveIcons.openExternal) { it(f) } } },
                deck.file?.let { f -> onShareFile?.let { HiveAction("Share", HiveIcons.share) { it(f) } } },
                HiveAction("Close", HiveIcons.close, onClick = onClose),
            ))
        }
    }
}

/** One 16:9 slide drawn from the kit; [compact] is the thumbnail form (smaller type, no bullets beyond the first lines). */
@Composable
fun SlideView(slide: Slide, modifier: Modifier = Modifier, compact: Boolean = false, index: Int = 1, count: Int = 1) {
    val bg = MaterialTheme.colorScheme.inverseSurface
    val fg = MaterialTheme.colorScheme.inverseOnSurface
    val accent = MaterialTheme.colorScheme.inversePrimary
    val pad = if (compact) HiveSpacing.sm else HiveSpacing.lg
    val titleStyle = if (compact) HiveTextStyles.monoSmall else if (slide.kind == SlideKind.TITLE) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
    val bodyStyle = if (compact) HiveTextStyles.count else MaterialTheme.typography.bodyMedium
    Box(modifier.aspectRatio(16f / 9f).clip(HiveShape.tile).background(bg).semantics { contentDescription = "Slide $index of $count: ${slide.title}" }) {
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (slide.kind == SlideKind.TITLE) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(if (compact) HiveSpacing.lg else HiveSpacing.xxxl).height(if (compact) HiveSpacing.border else HiveSpacing.xs).background(accent))
                Spacer(Modifier.height(if (compact) HiveSpacing.xxs else HiveSpacing.sm))
                Text(slide.title, style = titleStyle, color = fg, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
                slide.bullets.firstOrNull()?.let { Text(it, style = bodyStyle, color = fg.copy(alpha = ALPHA_MUTED), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.weight(1f))
            } else {
                Text(slide.title, style = titleStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.width(if (compact) HiveSpacing.md else HiveSpacing.xl).height(if (compact) HiveSpacing.border else HiveSpacing.xxs).background(accent))
                Spacer(Modifier.height(if (compact) HiveSpacing.xxs else HiveSpacing.sm))
                when (slide.kind) {
                    SlideKind.CHART -> {
                        slide.chart?.let { BarChart(it, Modifier.weight(1f).fillMaxWidth(), compact, fg, accent) }
                        if (!compact) slide.caption?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    SlideKind.IMAGE -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(pad)) {
                        GeneratedImage(slide.image, Modifier.fillMaxHeight().aspectRatio(1f), fg)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xxs)) {
                            slide.bullets.take(if (compact) 1 else 3).forEach { Bullet(it, bodyStyle, fg, accent) }
                            if (!compact) slide.caption?.let { Text(it, style = HiveTextStyles.monoSmall, color = fg.copy(alpha = ALPHA_MUTED)) }
                        }
                    }
                    else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) HiveSpacing.xxs else HiveSpacing.xs)) {
                        slide.bullets.take(if (compact) 3 else 5).forEach { Bullet(it, bodyStyle, fg, accent) }
                    }
                }
            }
        }
        Text("$index / $count", style = HiveTextStyles.count, color = fg.copy(alpha = ALPHA_MUTED),
            modifier = Modifier.align(Alignment.BottomEnd).padding(if (compact) HiveSpacing.xs else HiveSpacing.sm))
    }
}

@Composable
private fun Bullet(text: String, style: androidx.compose.ui.text.TextStyle, fg: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = style, color = accent)
        Spacer(Modifier.width(HiveSpacing.xs))
        Text(text, style = style, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Bars by weight, the figure above and the category below each; the last bar is the accent, the rest a muted version. */
@Composable
private fun BarChart(c: Chart, modifier: Modifier, compact: Boolean, fg: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color) {
    val max = c.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val style = if (compact) HiveTextStyles.count else MaterialTheme.typography.labelMedium
    Row(modifier.semantics { contentDescription = c.categories.indices.joinToString("; ") { i -> "${c.categories[i]} ${c.labels.getOrNull(i) ?: c.values[i]}" } },
        horizontalArrangement = Arrangement.spacedBy(if (compact) HiveSpacing.xs else HiveSpacing.md), verticalAlignment = Alignment.Bottom) {
        c.categories.indices.forEach { i ->
            val frac = (c.values[i] / max).toFloat().coerceIn(0.04f, 1f) * 0.72f
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                if (!compact) c.deltas.getOrNull(i)?.let { Text(it, style = style, color = accent, maxLines = 1) }
                Text(c.labels.getOrNull(i) ?: c.values[i].toString(), style = style, color = fg, maxLines = 1, textAlign = TextAlign.Center)
                Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight(frac).clip(HiveShape.chunk).background(if (i == c.categories.lastIndex) accent else accent.copy(alpha = ALPHA_BAR)))
                Text(c.categories[i], style = style, color = fg.copy(alpha = ALPHA_MUTED), maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A generated PNG by path (a slide's picture, the picture card), or the photo placeholder when it is missing. */
@Composable
internal fun GeneratedImage(path: String?, modifier: Modifier, fg: androidx.compose.ui.graphics.Color, contentScale: ContentScale = ContentScale.Crop) {
    val bitmap: ImageBitmap? = remember(path) { path?.let { p -> runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }
    Box(modifier.clip(HiveShape.tile).background(fg.copy(alpha = ALPHA_PANEL)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = "Generated illustration", contentScale = contentScale, modifier = Modifier.fillMaxSize())
        else Icon(HiveIcons.photo, contentDescription = "No illustration", tint = fg.copy(alpha = ALPHA_MUTED), modifier = Modifier.size(HiveSpacing.iconLg))
    }
}

internal const val ALPHA_MUTED = 0.72f
private const val ALPHA_BAR = 0.45f
internal const val ALPHA_PANEL = 0.12f

/** A sample deck for previews and the demo script. */
object SampleDeck {
    val DECK = Deck("Pharma sales FY2024-26", "Revenue by fiscal year, from the Sales phone", listOf(
        Slide(SlideKind.TITLE, "Pharma sales FY2024-26", bullets = listOf("Revenue by fiscal year, from the Sales phone")),
        Slide(SlideKind.CHART, "Revenue by fiscal year", chart = Chart("Revenue by fiscal year", listOf("FY2024", "FY2025", "FY2026"),
            listOf(4.26e8, 5.19e8, 6.38e8), listOf("₹42.6 Cr", "₹51.9 Cr", "₹63.8 Cr"), listOf(null, "+22 %", "+23 %")),
            caption = "Revenue grew 50 % from FY2024 (₹42.6 Cr) to FY2026 (₹63.8 Cr)."),
        Slide(SlideKind.BULLETS, "Highlights", bullets = listOf("Revenue rose 50 % over two fiscal years", "FY2026 added ₹11.9 Cr, the biggest step yet",
            "Growth held above 20 % in both years", "Every figure came from the Sales phone; nothing left the swarm")),
        Slide(SlideKind.IMAGE, "In one picture", bullets = listOf("FY2024: ₹42.6 Cr", "FY2025: ₹51.9 Cr (+22 %)", "FY2026: ₹63.8 Cr (+23 %)"), caption = "Generated on a phone of the swarm"),
        Slide(SlideKind.BULLETS, "Next steps", bullets = listOf("Carry the FY2026 pace into FY2027", "Ask the Sales phone for any figure behind a slide")),
    ), summary = "Revenue grew 50 % from FY2024 (₹42.6 Cr) to FY2026 (₹63.8 Cr).")
}

@HivePreviews
@Composable
private fun DeckCardPreview() = HiveCoreTheme {
    Column(Modifier.padding(HiveSpacing.screenH), verticalArrangement = Arrangement.spacedBy(HiveSpacing.md)) {
        DeckCard(SampleDeck.DECK.copy(file = "/data/decks/deck-3f9a1c.pptx", bytes = 48_120), onPresent = {}, onOpenFile = {}, onShareFile = {})
        SlideView(SampleDeck.DECK.slides[1], Modifier.fillMaxWidth(), index = 2, count = 5)
        SlideView(SampleDeck.DECK.slides[3], Modifier.fillMaxWidth(), index = 4, count = 5)
    }
}
