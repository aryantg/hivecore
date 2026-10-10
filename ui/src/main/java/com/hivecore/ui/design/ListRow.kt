package com.hivecore.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/** A list row: leading avatar or icon, title, supporting line, trailing pill or action. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    supportingLines: Int = 2,
) {
    var m = modifier.fillMaxWidth()
    if (onClick != null) m = m.clickable(enabled = enabled, onClick = onClick)
    Row(m.alpha(if (enabled) 1f else DISABLED_ALPHA).padding(vertical = HiveSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(HiveSpacing.md)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = supportingLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(HiveSpacing.sm)); trailing() }
    }
}

/** A round avatar with a phone's initial (or an icon) and an optional presence dot. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = HiveSpacing.avatar, tone: Tone = Tone.Accent, icon: ImageVector? = null, online: Boolean? = null) {
    val (bg, fg) = toneColors(tone)
    Box(modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(HiveShape.circle).background(bg), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * ICON_SHARE))
            else Text(initialOf(name), style = if (size >= HiveSpacing.avatarLg) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall, color = fg)
        }
        if (online != null) {
            Box(Modifier.align(Alignment.BottomEnd).size(HiveSpacing.presence).clip(HiveShape.circle).background(MaterialTheme.colorScheme.surface).padding(HiveSpacing.xxs)
                .clip(HiveShape.circle).background(if (online) HiveTheme.colors.ok else MaterialTheme.colorScheme.outline))
        }
    }
}

/** "Sales" → "S", "this phone" → "T", "" → "?". */
fun initialOf(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

private const val ICON_SHARE = 0.55f
