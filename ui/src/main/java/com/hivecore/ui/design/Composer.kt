package com.hivecore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * The chat composer (Home): a rounded pill with a "+" menu, a growing field and a round send button that turns into
 * stop while the swarm is busy. Suggestion chips ride above it once a conversation has started; [above] holds the
 * small policy pill.
 */
@Composable
fun Composer(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask your phones anything",
    enabled: Boolean = true,
    suggestions: List<String> = emptyList(),
    onSuggest: (String) -> Unit = {},
    menu: List<HiveAction> = emptyList(),
    above: (@Composable () -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = HiveSpacing.md, end = HiveSpacing.md, top = HiveSpacing.xs, bottom = HiveSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
            if (suggestions.isNotEmpty() && !busy) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm), contentPadding = PaddingValues(horizontal = HiveSpacing.xs)) {
                    items(suggestions) { s -> SuggestionChip(onClick = { onSuggest(s) }, label = { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
            }
            above?.invoke()
            Surface(Modifier.fillMaxWidth().heightIn(min = HiveSpacing.composerMinHeight), shape = HiveShape.composer, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(HiveSpacing.xs), verticalAlignment = Alignment.Bottom) {
                    if (menu.isNotEmpty()) ComposerMenu(menu)
                    TextField(
                        value = value, onValueChange = onChange, modifier = Modifier.weight(1f), enabled = enabled,
                        placeholder = { Text(placeholder) }, maxLines = 6, textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    val canSend = busy || (enabled && value.isNotBlank())
                    FilledIconButton(onClick = { if (busy) onStop() else onSend() }, enabled = canSend, modifier = Modifier.padding(HiveSpacing.xxs).size(HiveSpacing.send)) {
                        Icon(if (busy) HiveIcons.stop else HiveIcons.send, contentDescription = if (busy) "Stop" else "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerMenu(menu: List<HiveAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(HiveSpacing.send)) { Icon(HiveIcons.add, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, leadingIcon = a.icon?.let { i -> { Icon(i, contentDescription = null) } }, enabled = a.enabled,
                    onClick = { open = false; a.onClick() })
            }
        }
    }
}
