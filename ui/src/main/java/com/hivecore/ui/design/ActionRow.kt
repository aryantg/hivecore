package com.hivecore.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** One thing the user can do. [destructive] renders it in the error colour. */
data class HiveAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

enum class ButtonKind { Filled, Tonal, Outlined, Text }

@Composable
fun HiveButton(action: HiveAction, kind: ButtonKind = ButtonKind.Filled, modifier: Modifier = Modifier) {
    val m = modifier.heightIn(min = HiveSpacing.button)
    val label: @Composable RowScope.() -> Unit = {
        if (action.icon != null) {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(HiveSpacing.iconSm))
            Spacer(Modifier.width(HiveSpacing.sm))
        }
        Text(action.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
    val err = MaterialTheme.colorScheme.error
    when {
        action.destructive && kind != ButtonKind.Text -> OutlinedButton(onClick = action.onClick, enabled = action.enabled, modifier = m,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = err), border = BorderStroke(HiveSpacing.border, err), content = label)
        kind == ButtonKind.Filled -> Button(onClick = action.onClick, enabled = action.enabled, modifier = m, content = label)
        kind == ButtonKind.Tonal -> FilledTonalButton(onClick = action.onClick, enabled = action.enabled, modifier = m, content = label)
        kind == ButtonKind.Outlined -> OutlinedButton(onClick = action.onClick, enabled = action.enabled, modifier = m, content = label)
        else -> TextButton(onClick = action.onClick, enabled = action.enabled, modifier = m,
            colors = if (action.destructive) ButtonDefaults.textButtonColors(contentColor = err) else ButtonDefaults.textButtonColors(), content = label)
    }
}

/**
 * The only button group: one filled [primary], up to [maxInline] tonal secondaries, the rest behind a "more" menu.
 * Wraps on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(primary: HiveAction? = null, secondaries: List<HiveAction> = emptyList(), modifier: Modifier = Modifier, maxInline: Int = 2) {
    if (primary == null && secondaries.isEmpty()) return
    val inline = secondaries.take(maxInline)
    val overflow = secondaries.drop(maxInline)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
        primary?.let { HiveButton(it, ButtonKind.Filled) }
        inline.forEach { HiveButton(it, ButtonKind.Tonal) }
        if (overflow.isNotEmpty()) OverflowMenu(overflow)
    }
}

@Composable
fun OverflowMenu(actions: List<HiveAction>, modifier: Modifier = Modifier, description: String = "More actions") {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(HiveIcons.more, contentDescription = description) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    leadingIcon = a.icon?.let { i -> { Icon(i, contentDescription = null) } },
                    enabled = a.enabled,
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

/** An icon-only header action with its label as the content description. */
@Composable
fun HeaderAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
}
