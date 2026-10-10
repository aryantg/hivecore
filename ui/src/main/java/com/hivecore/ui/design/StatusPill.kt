package com.hivecore.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

/** The state a pill (or a stat) reports. Pills show state, never actions. */
enum class Tone { Neutral, Accent, Ok, Warn, Cloud, Danger }

/** (container, content) for a tone. */
@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val x = HiveTheme.colors
    return when (tone) {
        Tone.Neutral -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        Tone.Accent -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.Ok -> x.okContainer to x.onOkContainer
        Tone.Warn -> x.warnContainer to x.onWarnContainer
        Tone.Cloud -> x.cloudContainer to x.onCloudContainer
        Tone.Danger -> cs.errorContainer to cs.onErrorContainer
    }
}

/** The strong colour of a tone, for a dot, an icon or a big number. */
@Composable
fun toneAccent(tone: Tone): Color {
    val cs = MaterialTheme.colorScheme
    val x = HiveTheme.colors
    return when (tone) {
        Tone.Neutral -> cs.onSurface
        Tone.Accent -> cs.primary
        Tone.Ok -> x.ok
        Tone.Warn -> x.warn
        Tone.Cloud -> x.cloud
        Tone.Danger -> cs.error
    }
}

@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, dot: Boolean = false, icon: ImageVector? = null) {
    val (bg, fg) = toneColors(tone)
    Row(
        modifier.clip(HiveShape.pill).background(bg).padding(horizontal = HiveSpacing.pillH, vertical = HiveSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs),
    ) {
        if (dot) Box(Modifier.size(HiveSpacing.dot).clip(HiveShape.circle).background(toneAccent(tone)))
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(HiveSpacing.iconXs))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
