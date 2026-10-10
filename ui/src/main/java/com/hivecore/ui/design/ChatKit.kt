package com.hivecore.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** The user's message: a right-aligned honey bubble with a tail corner. */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val max = maxWidth * HiveSpacing.BUBBLE_MAX_FRACTION
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(Modifier.widthIn(max = max), shape = HiveShape.userBubble, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = HiveSpacing.lg, vertical = HiveSpacing.md))
            }
        }
    }
}

/** The swarm's turn: the hex avatar on the left and its content (text, cards) without a bubble, like Claude and Gemini. */
@Composable
fun SwarmTurn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(HiveSpacing.avatarSm).clip(HiveShape.circle).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(HiveIcons.hexMark, contentDescription = "HiveCore", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(HiveSpacing.iconSm))
        }
        Spacer(Modifier.width(HiveSpacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HiveSpacing.sm), content = content)
    }
}

/** Three pulsing dots and the live status line ("Placing 3 steps…"). */
@Composable
fun TypingIndicator(status: String?, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = status ?: "Working" }.heightIn(min = HiveSpacing.avatarSm),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(DOT_MIN, 1f, infiniteRepeatable(tween(DOT_MS, delayMillis = i * DOT_STAGGER_MS), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.padding(end = HiveSpacing.xs).size(HiveSpacing.dot).alpha(a).clip(HiveShape.circle).background(MaterialTheme.colorScheme.primary))
        }
        if (status != null) {
            Spacer(Modifier.width(HiveSpacing.sm))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One card of the empty Home's suggestion grid. */
@Composable
fun SuggestionCard(icon: ImageVector, title: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HiveCard(modifier.heightIn(min = HiveSpacing.suggestionMinHeight), onClick = onClick, contentPadding = HiveSpacing.md) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.icon))
        Spacer(Modifier.size(HiveSpacing.sm))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val DOT_MIN = 0.25f
private const val DOT_MS = 520
private const val DOT_STAGGER_MS = 160
