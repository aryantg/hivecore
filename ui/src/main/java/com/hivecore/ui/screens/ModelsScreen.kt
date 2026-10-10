package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.vm.ModelsViewModel

/** Models route: collects [ModelsViewModel] and reads the registry when the screen appears. */
@Composable
fun ModelsRoute(vm: ModelsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    ModelsScreen(state, onDownload = vm::download, onLoad = vm::load, onUnload = vm::unload, onBench = vm::bench, onPin = vm::pin, onRetry = vm::refresh, onDismissMessage = vm::dismissMessage)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(
    state: ModelsViewModel.UiState,
    onDownload: (String) -> Unit,
    onLoad: (String) -> Unit,
    onUnload: (String) -> Unit,
    onBench: (String) -> Unit,
    onPin: (String, String) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") { ScreenTitle("Models", "${state.bigText} · warm: ${state.warm.ifEmpty { listOf("none") }.joinToString(", ")}") }
        item("message") { MessageBar(state.message, onDismissMessage) }
        state.error?.let { item("error") { ErrorState(it, onRetry) } }
        if (state.loading && state.catalog.isEmpty()) item("loading") { LoadingRow("Reading the catalog…") }
        if (!state.loading && state.catalog.isEmpty()) item("empty") { EmptyState("No catalog", "models/catalog.json ships with the runtime phase") }
        state.groups.forEach { (capability, rows) ->
            item("cap-$capability") { SectionTitle(capability) }
            items(rows, key = { "m-" + it.id }) { r -> ModelRow(r, state.now, onDownload, onLoad, onUnload, onBench, onPin) }
        }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(r: ModelsViewModel.Row, now: Long, onDownload: (String) -> Unit, onLoad: (String) -> Unit, onUnload: (String) -> Unit, onBench: (String) -> Unit, onPin: (String, String) -> Unit) {
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (r.pinned) Badge("default", Tone.OK)
                if (r.loadedHere) Badge("loaded", Tone.OK) else if (r.installedHere) Badge("installed", Tone.NEUTRAL) else Badge("available", Tone.NEUTRAL)
            }
            Text(r.specLine + " · " + r.sizeText, style = MaterialTheme.typography.bodySmall)
            Text(r.hashText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.chips.forEach { c -> Badge(c.label(now), if (c.loaded) Tone.OK else Tone.NEUTRAL) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val busy = r.busy
                if (!r.installedHere) TextButton(onClick = { onDownload(r.id) }, enabled = !busy) { Text("Download") }
                if (r.installedHere && !r.loadedHere) TextButton(onClick = { onLoad(r.id) }, enabled = !busy) { Text("Load") }
                if (r.loadedHere) TextButton(onClick = { onUnload(r.id) }, enabled = !busy) { Text("Unload") }
                if (r.installedHere) TextButton(onClick = { onBench(r.id) }, enabled = !busy) { Text(if (busy) "Working…" else "BENCH") }
                if (!r.pinned) TextButton(onClick = { onPin(r.entry.capability, r.id) }, enabled = !busy) { Text("Pin as default") }
            }
        }
    }
}
