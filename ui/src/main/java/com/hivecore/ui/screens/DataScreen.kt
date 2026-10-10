package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Screen
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.Capability
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextField
import com.hivecore.ui.design.HiveTheme
import com.hivecore.ui.design.ListRow
import com.hivecore.ui.design.SegmentedChoice
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.SwitchRow
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.error
import com.hivecore.ui.design.listOrEmpty
import com.hivecore.ui.design.loading
import com.hivecore.ui.ports.ItemKind
import com.hivecore.ui.ports.Visibility
import com.hivecore.ui.vm.DataViewModel
import com.hivecore.ui.vm.DataViewModel.Item

/**
 * Data route: collects [DataViewModel] and reads the schema when the screen appears. [roleControl] is the app's
 * "this phone is HR / Sales / Accounts" seed control, shown under the header when supplied.
 */
@Composable
fun DataRoute(vm: DataViewModel, roleControl: (@Composable () -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    DataScreen(state, onPrivate = vm::setPrivate, onReleaseJob = vm::releaseForJob, onReleaseMonth = vm::releaseForMonth, onClearRelease = vm::clearRelease,
        onHide = vm::hideTable, onEditColumns = vm::editColumns, onSetColumns = vm::setConfidentialColumns, onDeleteTable = vm::deleteTable,
        onDeleteDoc = { vm.deleteDoc(it.doc!!) }, onRetry = vm::refresh, onDismissMessage = vm::dismissMessage, roleControl = roleControl)
}

private fun ItemKind.label(): String = when (this) { ItemKind.TABLE -> "Tables"; ItemKind.DOC -> "Documents"; ItemKind.MEDIA -> "Media" }
private fun ItemKind.icon() = when (this) { ItemKind.TABLE -> HiveIcons.table; ItemKind.DOC -> HiveIcons.document; ItemKind.MEDIA -> HiveIcons.photo }

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
    roleControl: (@Composable () -> Unit)? = null,
) {
    var kind by rememberSaveable { mutableStateOf(ItemKind.TABLE) }
    val list = when (kind) { ItemKind.TABLE -> state.tables; ItemKind.DOC -> state.docs; ItemKind.MEDIA -> state.media }
    HiveScreen(
        Screen.DATA, modifier,
        capability = Capability(HiveIcons.lock, "Your data stays home", "Private data never leaves the phone that owns it. The swarm sees names and flags, and only answers move.",
            Stat("${state.all.size}", "items · ${state.privateCount} private", Tone.Accent, HiveIcons.data), tryPrompt = "Revenue per rep by region this year"),
        message = state.message, onDismissMessage = onDismissMessage,
    ) {
        if (roleControl != null) item("role") { roleControl() }
        item("kinds") {
            SegmentedChoice(ItemKind.entries, kind, label = { k ->
                val n = when (k) { ItemKind.TABLE -> state.tables.size; ItemKind.DOC -> state.docs.size; ItemKind.MEDIA -> state.media.size }
                "${k.label()} ($n)"
            }, onSelect = { kind = it }, icon = { it.icon() })
        }
        state.error?.let { error("error", it, onRetry) }
        if (state.loading && state.all.isEmpty()) loading("loading", "Reading the data registry…")
        listOrEmpty(list, key = { "${it.kind}-${it.id}" }, emptyKey = "empty-$kind", emptyTitle = "No ${kind.label().lowercase()} yet",
            emptyDetail = if (kind == ItemKind.MEDIA) "Photos, audio and video this phone shares appear here" else "Seed this phone's role to load sample data",
            emptyIcon = kind.icon()) { item ->
            ItemCard(item, state.now, state.editingColumns == item.id, onPrivate, onReleaseJob, onReleaseMonth, onClearRelease, onHide, onEditColumns, onSetColumns,
                onDelete = when (item.kind) { ItemKind.TABLE -> { { onDeleteTable(item.id) } }; ItemKind.DOC -> { { onDeleteDoc(item) } }; ItemKind.MEDIA -> null })
        }
    }
}

@Composable
private fun ItemCard(
    item: Item, now: Long, editing: Boolean,
    onPrivate: (Item, Boolean) -> Unit, onReleaseJob: (Item, String) -> Unit, onReleaseMonth: (Item) -> Unit, onClearRelease: (Item) -> Unit,
    onHide: (Item, Boolean) -> Unit, onEditColumns: (String?) -> Unit, onSetColumns: (Item, Set<String>) -> Unit, onDelete: (() -> Unit)?,
) {
    var jobId by remember(item.id) { mutableStateOf("") }
    var releasing by remember(item.id) { mutableStateOf(false) }
    val tone = when { item.hidden -> Tone.Neutral; item.private -> Tone.Warn; item.visibility.release != null -> Tone.Cloud; else -> Tone.Ok }
    HiveCard {
        ListRow(item.name, supporting = item.detail, leading = { Avatar(item.name, tone = tone, icon = item.kind.icon()) },
            trailing = { StatusPill(if (item.private) "private" else if (item.hidden) "hidden" else if (item.visibility.release != null) "released" else "shared", tone,
                icon = if (item.private) HiveIcons.lock else null) })
        Text(item.stateLine(now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.kind == ItemKind.TABLE) {
            val cols = item.confidentialColumns
            Text(if (cols.isEmpty()) "No confidential columns" else "Confidential: ${cols.sorted().joinToString(", ")}",
                style = MaterialTheme.typography.labelMedium, color = if (cols.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else HiveTheme.colors.confidential,
                fontWeight = FontWeight.SemiBold)
        }
        SwitchRow("Private (never leaves this phone)", null, item.private, onChange = { onPrivate(item, it) })
        val release = if (item.visibility.release == null) listOf(
            HiveAction("Release for a job", HiveIcons.link, enabled = !item.private) { releasing = !releasing },
            HiveAction("Release for a month", HiveIcons.history, enabled = !item.private) { onReleaseMonth(item) },
        ) else listOf(HiveAction("End release", HiveIcons.close) { onClearRelease(item) })
        val table = if (item.kind == ItemKind.TABLE) listOf(
            HiveAction(if (item.hidden) "Show in catalog" else "Hide table", HiveIcons.lock) { onHide(item, !item.hidden) },
            HiveAction("Confidential columns", HiveIcons.tune) { onEditColumns(if (editing) null else item.id) },
        ) else emptyList()
        ActionRow(secondaries = release + table + listOfNotNull(onDelete?.let { HiveAction("Delete", HiveIcons.delete, destructive = true, onClick = it) }))
        if (releasing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            HiveTextField(jobId, { jobId = it }, Modifier.weight(1f), label = "Job id")
            com.hivecore.ui.design.HiveButton(HiveAction("Release", enabled = jobId.isNotBlank()) { onReleaseJob(item, jobId); releasing = false })
        }
        if (editing && item.table != null) ColumnEditor(item, onSetColumns)
    }
}

@Composable
private fun ColumnEditor(item: Item, onSet: (Item, Set<String>) -> Unit) {
    var chosen by remember(item.id) { mutableStateOf(item.confidentialColumns) }
    Column(Modifier.padding(top = HiveSpacing.sm)) {
        Text("Confidential columns: only AVG and COUNT ever leave, with a count floor", style = MaterialTheme.typography.bodySmall)
        item.table!!.columns.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c.name in chosen, { on -> chosen = if (on) chosen + c.name else chosen - c.name }, modifier = Modifier.semantics { contentDescription = "Confidential ${c.name}" })
                Text("${c.name} · ${c.type.name.lowercase()}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        com.hivecore.ui.design.HiveButton(HiveAction("Save columns", HiveIcons.check) { onSet(item, chosen) })
    }
}

@HivePreviews
@Composable
private fun DataPreview() = HiveCoreTheme {
    DataScreen(DataViewModel.UiState(loading = false, tables = listOf(
        Item(ItemKind.TABLE, "sales", "sales", "1,204 rows · 7 columns", Visibility(ItemKind.TABLE, "sales")),
        Item(ItemKind.TABLE, "employees", "employees", "61 rows · 6 columns · confidential", Visibility(ItemKind.TABLE, "employees", private = true)),
    )), { _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {})
}
