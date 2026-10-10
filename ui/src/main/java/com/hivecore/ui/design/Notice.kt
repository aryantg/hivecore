package com.hivecore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign

/** A one-line notice inside a screen ("The mesh is not running on this build"). */
@Composable
fun InlineNotice(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, icon: ImageVector? = HiveIcons.info) {
    val (bg, fg) = toneColors(tone)
    Surface(modifier.fillMaxWidth(), shape = HiveShape.field, color = bg, contentColor = fg) {
        Row(Modifier.padding(horizontal = HiveSpacing.md, vertical = HiveSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(HiveSpacing.iconSm)); Spacer(Modifier.width(HiveSpacing.sm)) }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A transient result or error with a dismiss button; pinned above the list so it never scrolls away. */
@Composable
fun MessageBar(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(modifier.fillMaxWidth(), shape = HiveShape.field, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Row(Modifier.padding(start = HiveSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = HiveSpacing.sm))
            HeaderAction(HiveIcons.close, "Dismiss", onClick = onDismiss)
        }
    }
}

/** An empty list: what is missing and what to do next. */
@Composable
fun EmptyState(title: String, detail: String? = null, modifier: Modifier = Modifier, icon: ImageVector = HiveIcons.hexMark) {
    Column(modifier.fillMaxWidth().padding(vertical = HiveSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(HiveSpacing.iconXl))
        Spacer(Modifier.height(HiveSpacing.sm))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(HiveSpacing.xs))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = HiveSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(HiveSpacing.icon), strokeWidth = HiveSpacing.xxs)
        Spacer(Modifier.width(HiveSpacing.md))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    HiveCard(modifier, tone = CardTone.Refusal) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            Icon(HiveIcons.warning, contentDescription = null, modifier = Modifier.size(HiveSpacing.icon))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (onRetry != null) {
            Spacer(Modifier.height(HiveSpacing.sm))
            HiveButton(HiveAction("Retry", HiveIcons.refresh, onClick = onRetry), ButtonKind.Outlined)
        }
    }
}
