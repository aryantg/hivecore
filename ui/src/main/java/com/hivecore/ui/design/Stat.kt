package com.hivecore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** A number worth reading from across the room: "2.6×", "3 phones", "1.2 MB". */
data class Stat(val value: String, val label: String, val tone: Tone = Tone.Neutral, val icon: ImageVector? = null)

@Composable
fun StatTile(stat: Stat, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "${stat.label}: ${stat.value}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stat.icon != null) {
                Icon(stat.icon, contentDescription = null, tint = toneAccent(stat.tone), modifier = Modifier.size(if (large) HiveSpacing.iconLg else HiveSpacing.iconSm))
                Spacer(Modifier.width(HiveSpacing.xs))
            }
            Text(stat.value, style = if (large) HiveTextStyles.stat else HiveTextStyles.statSmall,
                color = if (stat.tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else toneAccent(stat.tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stat.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Equal-width stat tiles in a row. */
@Composable
fun StatRow(stats: List<Stat>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
        stats.forEach { s ->
            Surface(Modifier.weight(1f), shape = HiveShape.tile, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                StatTile(s, Modifier.padding(HiveSpacing.md))
            }
        }
    }
}
