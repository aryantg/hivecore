package com.hivecore.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.core.JobProgress
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.engine.workload.Recipes
import com.hivecore.ui.Format
import com.hivecore.ui.HiveTheme
import com.hivecore.ui.vm.JobFormat
import com.hivecore.ui.vm.JobsViewModel
import com.hivecore.ui.vm.JobsViewModel.Phase
import java.io.File

/**
 * Jobs route: collects [JobsViewModel]. [onPickFiles] opens the app's file picker (SAF) and returns local paths via
 * `vm.addItems`; [onOpenResult] navigates to a table or folder result.
 */
@Composable
fun JobsRoute(vm: JobsViewModel, onPickFiles: ((List<String>) -> Unit)? = null, onOpenResult: (String) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.openResult = onOpenResult; if (vm.state.value.recipes.isEmpty()) vm.loadRecipes() }
    JobsScreen(state, onSelectRecipe = vm::selectRecipe, onPickFiles = onPickFiles, onRemoveItem = vm::removeItem, onClearItems = vm::clearItems,
        onPrompt = vm::setPrompt, onPrivate = vm::setPrivate, onRun = vm::run, onCancel = vm::cancel, onNewJob = vm::newJob, onSelectChunk = vm::selectChunk,
        onOpenResult = vm::openResult, onOpenHistory = vm::openHistory, onResume = vm::resume, onRerun = vm::rerun, onRetryRecipes = vm::loadRecipes,
        onDismissMessage = vm::dismissMessage)
}

@Composable
fun JobsScreen(
    state: JobsViewModel.UiState,
    onSelectRecipe: (String) -> Unit,
    onPickFiles: ((List<String>) -> Unit)?,
    onRemoveItem: (String) -> Unit,
    onClearItems: () -> Unit,
    onPrompt: (String) -> Unit,
    onPrivate: (Boolean) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onNewJob: () -> Unit,
    onSelectChunk: (Int?) -> Unit,
    onOpenResult: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onResume: (String) -> Unit,
    onRerun: (String) -> Unit,
    onRetryRecipes: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") { ScreenTitle("Jobs", "Chunks across your phones, merged into one identical result") }
        item("message") { MessageBar(state.message, onDismissMessage) }

        val current = state.current
        if (state.phase != Phase.IDLE) {
            item("live") { LiveJob(state, current, onSelectChunk, onCancel) }
            if (state.lanes.isNotEmpty() && current != null) {
                item("lanes-title") { SectionTitle("Phones") }
                val maxRate = state.lanes.maxOf { it.load.itemsPerSec }.coerceAtLeast(0.0001)
                items(state.lanes, key = { "lane-" + it.load.nodeId }) { l -> PhoneLane(l, maxRate, current.elapsedMs) }
            }
            if (state.phase == Phase.DONE && current != null) item("result") { ResultCard(current, state.resultHash, state.currentRecipe, onOpenResult, onRerun = if (current.jobId in state.rerunnable) { { onRerun(current.jobId) } } else null) }
            state.refusal?.let { r -> item("refusal") { RefusalCard(r, title = if (state.phase == Phase.FAILED) "Job stopped" else "Partial result",
                actions = if (current != null && current.jobId in state.rerunnable) { { OutlinedButton(onClick = { onRerun(current.jobId) }) { Text("Re-run (unchanged chunks are free)") } } } else null) } }
            if (state.phase != Phase.RUNNING) item("new") { OutlinedButton(onClick = onNewJob, modifier = Modifier.fillMaxWidth()) { Text("New job") } }
        } else {
            val resumable = state.resumable
            if (resumable.isNotEmpty()) {
                item("resume-title") { SectionTitle("Resume after restart") }
                items(resumable, key = { "r-" + it.jobId }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleSmall)
                                Text(JobFormat.chunkLine(p), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { onResume(p.jobId) }) { Text("Resume") }
                        }
                    }
                }
            }
            item("recipes-title") { SectionTitle("Run a recipe") }
            when {
                state.loadingRecipes && state.recipes.isEmpty() -> item("recipes-loading") { LoadingRow("Loading recipes…") }
                state.recipesError != null -> item("recipes-error") { ErrorState(state.recipesError, onRetry = onRetryRecipes) }
                state.recipes.isEmpty() -> item("recipes-empty") { EmptyState("No recipes installed", "Recipes live in assets/swarm/recipes") }
            }
            items(state.recipes, key = { "recipe-" + it.id }) { r -> RecipeCard(r, r.id == state.selectedId) { onSelectRecipe(r.id) } }
            state.selected?.let { r ->
                item("items") { ItemPicker(r, state, onPickFiles, onRemoveItem, onClearItems, onPrompt) }
                item("private") { SwitchRow("Private (keep on this phone)", if (state.private) "Every chunk runs here; nothing leaves this phone" else "Chunks may run on opted-in idle phones", state.private, onChange = onPrivate) }
                item("run") { Button(onClick = onRun, enabled = state.canRun, modifier = Modifier.fillMaxWidth()) { Text("Run ${r.title}") } }
            }
        }

        item("history-title") { SectionTitle("History") }
        if (state.history.isEmpty()) item("history-empty") { EmptyState("No jobs yet", "Jobs this phone commands appear here") }
        items(state.history, key = { "h-" + it.jobId }) { p -> HistoryRow(p, enabled = !state.running) { onOpenHistory(p.jobId) } }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecipeCard(r: RecipeDef, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { stateDescription = if (selected) "selected" else "not selected" },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge(Recipes.axisLabel(r.axis), Tone.OK)
            }
            Text(r.produces, style = MaterialTheme.typography.bodySmall)
            Text("Merge: ${Recipes.mergeLabel(r.merge)} · ${r.stages.joinToString(" → ") { it.op.name }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemPicker(r: RecipeDef, state: JobsViewModel.UiState, onPickFiles: ((List<String>) -> Unit)?, onRemoveItem: (String) -> Unit, onClearItems: () -> Unit, onPrompt: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        when (r.input) {
            "prompt" -> OutlinedTextField(state.prompt, onPrompt, Modifier.fillMaxWidth(), label = { Text("Brief") }, minLines = 2)
            "none" -> Text("No items to pick: the job runs over ${r.itemKinds.joinToString()}.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onPickFiles != null) OutlinedButton(onClick = { onPickFiles(r.itemKinds) }) { Text("Choose files") }
                    else Text("No file picker on this build", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.items.isNotEmpty()) TextButton(onClick = onClearItems) { Text("Clear") }
                }
                if (state.items.isEmpty()) Text("Nothing picked yet (${r.itemKinds.joinToString()})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.items.take(6).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(File(p).name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { onRemoveItem(p) }) { Text("Remove") }
                    }
                }
                if (state.items.size > 6) Text("and ${state.items.size - 6} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LiveJob(state: JobsViewModel.UiState, p: JobProgress?, onSelectChunk: (Int?) -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p?.title ?: state.selected?.title ?: "Job", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (p != null && p.axis.isNotBlank()) Badge(Recipes.axisLabel(p.axis), Tone.OK)
                if (state.cloudChunks > 0) Badge("${state.cloudChunks} cloud", Tone.CLOUD)
            }
            if (p != null && p.merge.isNotBlank()) Text("Merge: ${Recipes.mergeLabel(p.merge)} on this phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (p == null) LoadingRow("Starting: splitting into chunks…")
            else {
                val frac = JobFormat.progress(p)
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Progress ${(frac * 100).toInt()} percent" })
                Spacer(Modifier.height(4.dp))
                Text(JobFormat.chunkLine(p), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                ChunkGrid(p.chunks, state.selectedChunk, onSelectChunk)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(JobFormat.elapsedLine(p), Tone.NEUTRAL)
                    Badge("Moved ${JobFormat.bytes(p.bytesMoved)}", Tone.NEUTRAL)
                }
            }
            if (state.running) { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onCancel) { Text("Cancel job") } }
        }
    }
}

/** Per-phone load: chunks, busy ms, items/s, link kind (spec section 17). */
@Composable
private fun PhoneLane(l: JobsViewModel.PhoneLane, maxRate: Double, elapsedMs: Long) {
    val n = l.load
    val frac = (n.itemsPerSec / maxRate).toFloat().coerceIn(0f, 1f)
    val util = if (elapsedMs > 0) (n.busyMs * 100 / elapsedMs).coerceIn(0, 100) else null
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "${n.name} via ${l.linkWord}: ${n.chunksDone} chunks done, ${JobFormat.rate(n.itemsPerSec)}" + (util?.let { ", busy $it percent" } ?: "")
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(JobFormat.initial(n.name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(8.dp))
            Text(n.name, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Badge(l.linkWord, Tone.NEUTRAL)
            Spacer(Modifier.weight(1f))
            Text("${n.chunksDone} chunks · ${JobFormat.ms(n.busyMs)} busy · ${JobFormat.rate(n.itemsPerSec)}" + (util?.let { " · $it%" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp))
    }
}

@Composable
private fun ResultCard(p: JobProgress, hash: String?, recipe: RecipeDef?, onOpenResult: () -> Unit, onRerun: (() -> Unit)?) {
    val x = HiveTheme.colors
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = x.answerContainer)) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}) {
            Text(JobFormat.speedup(p.speedup), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = x.onAnswerContainer)
            Text("${JobFormat.ms(p.elapsedMs)} on ${Format.plural(p.nodes.size, "phone")}" + (p.singlePhoneMs?.let { " vs about ${JobFormat.ms(it)} on this phone alone" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = x.onAnswerContainer)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(JobFormat.identical(p.identicalToSingle), when (p.identicalToSingle) { true -> Tone.OK; false -> Tone.WARN; null -> Tone.NEUTRAL })
                JobFormat.cloudCount(p).takeIf { it > 0 }?.let { Badge("$it chunks on the cloud", Tone.CLOUD) }
            }
            JobFormat.mergeNote(p)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = x.onAnswerContainer, modifier = Modifier.padding(top = 4.dp)) }
            p.resultSummary?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyLarge, color = x.onAnswerContainer) }
            Text("result hash ${Format.shortHash(hash)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = x.onAnswerContainer, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.resultRef?.let { ref -> Button(onClick = onOpenResult) { Text(if (JobFormat.isFolder(ref)) "Open folder" else "Open $ref") } }
                if (onRerun != null) OutlinedButton(onClick = onRerun) { Text("Re-run") }
            }
        }
    }
}

@Composable
private fun HistoryRow(p: JobProgress, enabled: Boolean, onClick: () -> Unit) {
    val status = when { p.error != null -> "failed"; p.done -> "done"; else -> "resumable" }
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (JobFormat.cloudCount(p) > 0) Badge("cloud", Tone.CLOUD)
                Badge(status, when (status) { "done" -> Tone.OK; "failed" -> Tone.WARN; else -> Tone.NEUTRAL })
            }
            Text(listOfNotNull("${p.chunks.size} chunks", Format.plural(p.nodes.size, "phone"), JobFormat.ms(p.elapsedMs), p.speedup?.let { String.format(java.util.Locale.US, "%.1f×", it) }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (p.resultSummary ?: p.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}
