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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.HiveTheme
import com.hivecore.ui.ports.ItemKind
import com.hivecore.ui.vm.DataViewModel
import com.hivecore.ui.vm.DataViewModel.Item

/** Data route: collects [DataViewModel] and reads the schema when the screen appears. */
@Composable
fun DataRoute(vm: DataViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    DataScreen(state, onPrivate = vm::setPrivate, onReleaseJob = vm::releaseForJob, onReleaseMonth = vm::releaseForMonth, onClearRelease = vm::clearRelease,
        onHide = vm::hideTable, onEditColumns = vm::editColumns, onSetColumns = vm::setConfidentialColumns, onDeleteTable = vm::deleteTable,
        onDeleteDoc = { vm.deleteDoc(it.doc!!) }, onRetry = vm::refresh, onDismissMessage = vm::dismissMessage)
}

@Composable
fun DataScreen(
    state: DataViewModel.UiState,
    onPrivate: (Item, Boolean) -> Unit,
    onReleaseJob: (Item, String) -> Unit,
    onReleaseMonth: (Item) -> Unit,
    onClearRelease: (Item) -> Unit,
    onHide: (Item, Boolean) -> Unit,
    onEditColumns: (String?) -> Unit,
    onSetColumns: (Item, Set<String>) -> Unit,
    onDeleteTable: (String) -> Unit,
    onDeleteDoc: (Item) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") { ScreenTitle("Data", "What the swarm catalog sees: names and flags, never values · ${state.privateCount} private") }
        item("message") { MessageBar(state.message, onDismissMessage) }
        state.error?.let { item("error") { ErrorState(it, onRetry) } }
        if (state.loading && state.all.isEmpty()) item("loading") { LoadingRow("Reading the data registry…") }

        section("Tables", state.tables, "No tables yet") { t ->
            ItemCard(t, state.now, state.editingColumns == t.id, onPrivate, onReleaseJob, onReleaseMonth, onClearRelease, onHide, onEditColumns, onSetColumns, onDelete = { onDeleteTable(t.id) })
        }
        section("Documents", state.docs, "No documents yet") { d ->
            ItemCard(d, state.now, false, onPrivate, onReleaseJob, onReleaseMonth, onClearRelease, onHide, onEditColumns, onSetColumns, onDelete = { onDeleteDoc(d) })
        }
        section("Media", state.media, "No photos, audio or video yet") { m ->
            ItemCard(m, state.now, false, onPrivate, onReleaseJob, onReleaseMonth, onClearRelease, onHide, onEditColumns, onSetColumns, onDelete = null)
        }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, list: List<Item>, empty: String, content: @Composable (Item) -> Unit) {
    item("sec-$title") { SectionTitle(title) }
    if (list.isEmpty()) item("empty-$title") { EmptyState(empty) }
    items(list, key = { "${it.kind}-${it.id}" }) { content(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(
    item: Item, now: Long, editing: Boolean,
    onPrivate: (Item, Boolean) -> Unit, onReleaseJob: (Item, String) -> Unit, onReleaseMonth: (Item) -> Unit, onClearRelease: (Item) -> Unit,
    onHide: (Item, Boolean) -> Unit, onEditColumns: (String?) -> Unit, onSetColumns: (Item, Set<String>) -> Unit, onDelete: (() -> Unit)?,
) {
    var jobId by remember(item.id) { mutableStateOf("") }
    var releasing by remember(item.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge(item.stateLine(now), when { item.hidden -> Tone.NEUTRAL; item.private -> Tone.WARN; item.visibility.release != null -> Tone.CLOUD; else -> Tone.OK })
            }
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.kind == ItemKind.TABLE) {
                val cols = item.confidentialColumns
                Text(if (cols.isEmpty()) "No confidential columns" else "Confidential: ${cols.sorted().joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall, color = HiveTheme.colors.confidential, fontWeight = FontWeight.SemiBold)
            }
            SwitchRow("Private (never leaves this phone)", null, item.private, onChange = { onPrivate(item, it) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (item.visibility.release == null) {
                    TextButton(onClick = { releasing = !releasing }, enabled = !item.private) { Text("Release for a job") }
                    TextButton(onClick = { onReleaseMonth(item) }, enabled = !item.private) { Text("Release for a month") }
                } else TextButton(onClick = { onClearRelease(item) }) { Text("End release") }
                if (item.kind == ItemKind.TABLE) {
                    TextButton(onClick = { onHide(item, !item.hidden) }) { Text(if (item.hidden) "Show in catalog" else "Hide table") }
                    TextButton(onClick = { onEditColumns(if (editing) null else item.id) }) { Text("Confidential columns") }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
            }
            if (releasing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(jobId, { jobId = it }, Modifier.weight(1f), label = { Text("Job id") }, singleLine = true)
                Button(onClick = { onReleaseJob(item, jobId); releasing = false }, enabled = jobId.isNotBlank()) { Text("Release") }
            }
            if (editing && item.table != null) ColumnEditor(item, onSetColumns)
        }
    }
}

@Composable
private fun ColumnEditor(item: Item, onSet: (Item, Set<String>) -> Unit) {
    var chosen by remember(item.id) { mutableStateOf(item.confidentialColumns) }
    Column(Modifier.padding(top = 6.dp)) {
        Text("Confidential columns: only AVG and COUNT ever leave, with a count floor", style = MaterialTheme.typography.bodySmall)
        item.table!!.columns.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c.name in chosen, { on -> chosen = if (on) chosen + c.name else chosen - c.name }, modifier = Modifier.semantics { contentDescription = "Confidential ${c.name}" })
                Text("${c.name} · ${c.type.name.lowercase()}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = { onSet(item, chosen) }) { Text("Save columns") }
    }
}
