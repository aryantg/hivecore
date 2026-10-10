package com.hivecore.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.hivecore.ui.Screen

/** The shell's top bar slots: the screen on top registers its header actions here. */
class TopBarSlots {
    var owner: Screen? by mutableStateOf(null)
    var actions: @Composable RowScope.() -> Unit by mutableStateOf({})
}

val LocalHiveTopBar = staticCompositionLocalOf<TopBarSlots?> { null }

/**
 * The fixed template every screen uses (docs/ui-template.md):
 *  - the title comes from [screen] and lives in the shell's top bar ([actions] go there too); without a shell
 *    (previews, tests) a local header is drawn instead;
 *  - [capability] is the header every non-chat page opens with; [subtitle] and one [statusPill] follow it;
 *  - [message] is pinned above the list; one [primaryAction] docks bottom-end; [bottomBar] (the chat composer) sits
 *    under the list above the keyboard; [emptyContent] replaces the list while [showEmpty].
 */
@Composable
fun HiveScreen(
    screen: Screen,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    statusPill: (@Composable () -> Unit)? = null,
    capability: Capability? = null,
    actions: @Composable RowScope.() -> Unit = {},
    message: String? = null,
    onDismissMessage: () -> Unit = {},
    primaryAction: HiveAction? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    showEmpty: Boolean = false,
    emptyContent: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val slots = LocalHiveTopBar.current
    if (slots != null) {
        val latest by rememberUpdatedState(actions)
        DisposableEffect(slots, screen) {
            slots.owner = screen
            slots.actions = { latest(this) }
            onDispose { if (slots.owner == screen) { slots.owner = null; slots.actions = {} } }
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (slots == null) LocalHeader(screen.title, actions)
            if (message != null) MessageBar(message, onDismissMessage, Modifier.padding(horizontal = HiveSpacing.screenH, vertical = HiveSpacing.xs))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showEmpty && emptyContent != null) emptyContent()
                else LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(start = HiveSpacing.screenH, end = HiveSpacing.screenH, top = HiveSpacing.screenV,
                        bottom = if (primaryAction != null) HiveSpacing.fabClearance else HiveSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(HiveSpacing.listGap),
                ) {
                    if (capability != null) item("capability") { CapabilityHeader(capability) }
                    if (subtitle != null || statusPill != null) item("subtitle") { SubtitleRow(subtitle, statusPill) }
                    content()
                }
            }
            if (bottomBar != null) Box(Modifier.fillMaxWidth().imePadding()) { bottomBar() }
        }
        if (primaryAction != null) {
            ExtendedFloatingActionButton(
                onClick = { if (primaryAction.enabled) primaryAction.onClick() },
                icon = { primaryAction.icon?.let { Icon(it, contentDescription = null) } },
                text = { Text(primaryAction.label, maxLines = 1) },
                containerColor = if (primaryAction.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (primaryAction.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(HiveSpacing.lg),
            )
        }
    }
}

@Composable
private fun LocalHeader(title: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = HiveSpacing.screenH, end = HiveSpacing.xs, top = HiveSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

@Composable
private fun SubtitleRow(subtitle: String?, pill: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        if (pill != null) { Spacer(Modifier.width(HiveSpacing.sm)); pill() }
    }
}

// ---- list helpers every screen uses ----

/** A section heading with an optional trailing control. */
fun LazyListScope.section(title: String, key: String = "sec-$title", trailing: (@Composable () -> Unit)? = null) {
    item(key) {
        Row(Modifier.fillMaxWidth().padding(top = HiveSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            trailing?.invoke()
        }
    }
}

/** The items, or an empty state that says what is missing and what to do next. */
fun <T : Any> LazyListScope.listOrEmpty(
    items: List<T>, key: (T) -> Any, emptyKey: String, emptyTitle: String, emptyDetail: String? = null, emptyIcon: ImageVector = HiveIcons.hexMark,
    item: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) item(emptyKey) { EmptyState(emptyTitle, emptyDetail, icon = emptyIcon) }
    else items(items, key = key) { item(it) }
}

fun LazyListScope.loading(key: String, text: String) { item(key) { LoadingRow(text) } }

fun LazyListScope.error(key: String, message: String, onRetry: (() -> Unit)?) { item(key) { ErrorState(message, onRetry) } }

fun LazyListScope.spacer(key: String) { item(key) { Spacer(Modifier.height(HiveSpacing.xl)) } }
