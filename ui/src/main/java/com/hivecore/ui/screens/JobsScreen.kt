package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.JobProgress
import com.hivecore.core.NodeLoad
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.engine.workload.Recipes
import com.hivecore.ui.Format
import com.hivecore.ui.Screen
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.Capability
import com.hivecore.ui.design.HeroCard
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextField
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.ListRow
import com.hivecore.ui.design.LoadingRow
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.StatRow
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.SwitchRow
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.error
import com.hivecore.ui.design.listOrEmpty
import com.hivecore.ui.design.loading
import com.hivecore.ui.design.section
import com.hivecore.ui.vm.JobFormat
import com.hivecore.ui.vm.JobsViewModel
import com.hivecore.ui.vm.JobsViewModel.Phase
import java.io.File
import java.util.Locale

/**
 * Jobs route: collects [JobsViewModel]. [onPickFiles] opens the app's file picker (SAF) and returns local paths via
 * `vm.addItems`; [onSamplePhotos] generates N sample invoice photos for the demo; [onOpenResult] handles a result
 * the screen cannot show itself.
 */
@Composable
fun JobsRoute(vm: JobsViewModel, onPickFiles: ((List<String>) -> Unit)? = null, onOpenResult: (String) -> Unit = {}, onSamplePhotos: ((Int) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.openResult = onOpenResult; if (vm.state.value.recipes.isEmpty()) vm.loadRecipes() }
    JobsScreen(state, onSelectRecipe = vm::selectRecipe, onPickFiles = onPickFiles, onRemoveItem = vm::removeItem, onClearItems = vm::clearItems,
        onPrompt = vm::setPrompt, onPrivate = vm::setPrivate, onRun = vm::run, onCancel = vm::cancel, onNewJob = vm::newJob, onSelectChunk = vm::selectChunk,
        onOpenResult = vm::openResult, onOpenHistory = vm::openHistory, onResume = vm::resume, onRerun = vm::rerun, onRetryRecipes = vm::loadRecipes,
        onDismissMessage = vm::dismissMessage, onSamplePhotos = onSamplePhotos)
}

/** "2.6×" for the best finished job so far, or null. */
fun bestSpeedup(history: List<JobProgress>): Double? = history.filter { it.done && it.error == null }.mapNotNull { it.speedup }.filter { it > 0 }.maxOrNull()

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
    onSamplePhotos: ((Int) -> Unit)? = null,
) {
    val best = bestSpeedup(state.history)
    val selected = state.selected
    HiveScreen(
        Screen.JOBS, modifier,
        capability = Capability(HiveIcons.jobs, "Many phones, one result",
            "Big work is split into chunks, run on your phones in parallel, and merged into a result identical to one phone's.",
            if (best != null) Stat(String.format(Locale.US, "%.1f×", best), "best speed-up so far", Tone.Ok, HiveIcons.bolt)
            else Stat(state.history.size.toString(), "jobs run on this phone", icon = HiveIcons.history)),
        message = state.message, onDismissMessage = onDismissMessage,
        primaryAction = if (state.phase == Phase.IDLE && selected != null) HiveAction("Run ${selected.title}", HiveIcons.play, enabled = state.canRun, onClick = onRun) else null,
    ) {
        // on a worker: the chunks this phone is doing for another phone, step by step
        items(state.working.size, key = { "working-" + state.working[it].planId }) { i -> WorkingForCard(state.working[i]) }

        val current = state.current
        if (state.phase != Phase.IDLE) {
            item("live") { LiveJob(state, current, onSelectChunk, onCancel) }
            if (state.lanes.isNotEmpty() && current != null) {
                section("Phones")
                item("lanes") {
                    HiveCard {
                        val maxRate = state.lanes.maxOf { it.load.itemsPerSec }.coerceAtLeast(0.0001)
                        state.lanes.forEachIndexed { i, l ->
                            if (i > 0) Spacer(Modifier.height(HiveSpacing.sm))
                            PhoneLane(l, maxRate, current.elapsedMs)
                        }
                    }
                }
            }
            if (state.phase == Phase.DONE && current != null) item("result") {
                ResultHero(current, state.resultHash, onOpenResult, onRerun = if (current.jobId in state.rerunnable) { { onRerun(current.jobId) } } else null)
            }
            state.refusal?.let { r -> item("refusal") { RefusalCard(r, title = if (state.phase == Phase.FAILED) "Job stopped" else "Partial result",
                actions = if (current != null && current.jobId in state.rerunnable) { { HiveButton(HiveAction("Re-run (unchanged chunks are free)", HiveIcons.refresh) { onRerun(current.jobId) }, ButtonKind.Outlined) } } else null) } }
            if (state.phase != Phase.RUNNING) item("new") { HiveButton(HiveAction("New job", HiveIcons.add, onClick = onNewJob), ButtonKind.Tonal, Modifier.fillMaxWidth()) }
        } else {
            val resumable = state.resumable
            if (resumable.isNotEmpty()) {
                section("Resume after restart")
                items(resumable.size, key = { "r-" + resumable[it].jobId }) { i ->
                    val p = resumable[i]
                    HiveCard(tone = CardTone.Warn) {
                        ListRow(p.title, supporting = JobFormat.chunkLine(p), leading = { Avatar(p.title, tone = Tone.Warn, icon = HiveIcons.history) },
                            trailing = { HiveButton(HiveAction("Resume", HiveIcons.play) { onResume(p.jobId) }) })
                    }
                }
            }
            section("Run a recipe")
            when {
                state.loadingRecipes && state.recipes.isEmpty() -> loading("recipes-loading", "Loading recipes…")
                state.recipesError != null -> error("recipes-error", state.recipesError, onRetryRecipes)
                state.recipes.isEmpty() -> item("recipes-empty") { com.hivecore.ui.design.EmptyState("No recipes installed", "Recipes live in assets/swarm/recipes", icon = HiveIcons.jobs) }
            }
            items(state.recipes.size, key = { "recipe-" + state.recipes[it].id }) { i -> val r = state.recipes[i]; RecipeCard(r, r.id == state.selectedId) { onSelectRecipe(r.id) } }
            if (selected != null) {
                item("items") { ItemPicker(selected, state, onPickFiles, onRemoveItem, onClearItems, onPrompt, onSamplePhotos) }
                item("private") {
                    HiveCard {
                        SwitchRow("Private (keep on this phone)", if (state.private) "Every chunk runs here; nothing leaves this phone" else "Chunks may run on opted-in idle phones",
                            state.private, onChange = onPrivate)
                    }
                }
            }
        }

        section("History")
        listOrEmpty(state.history, key = { "h-" + it.jobId }, emptyKey = "history-empty", emptyTitle = "No jobs yet",
            emptyDetail = "Jobs this phone commands appear here", emptyIcon = HiveIcons.history) { p ->
            HistoryRow(p, enabled = !state.running) { onOpenHistory(p.jobId) }
        }
    }
}

@Composable
private fun RecipeCard(r: RecipeDef, selected: Boolean, onClick: () -> Unit) {
    HiveCard(onClick = onClick, selected = selected, tone = if (selected) CardTone.Accent else CardTone.Plain,
        semantics = { stateDescription = if (selected) "selected" else "not selected" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusPill(Recipes.axisLabel(r.axis), Tone.Ok)
        }
        Text(r.produces, style = MaterialTheme.typography.bodySmall)
        Text("Merge: ${Recipes.mergeLabel(r.merge)} · ${r.stages.joinToString(" → ") { it.op.name }}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemPicker(r: RecipeDef, state: JobsViewModel.UiState, onPickFiles: ((List<String>) -> Unit)?, onRemoveItem: (String) -> Unit, onClearItems: () -> Unit,
                       onPrompt: (String) -> Unit, onSamplePhotos: ((Int) -> Unit)?) {
    HiveCard {
        when (r.input) {
            "prompt" -> HiveTextField(state.prompt, onPrompt, Modifier.fillMaxWidth(), label = "Brief", singleLine = false, minLines = 2)
            "none" -> Text("No items to pick: the job runs over ${r.itemKinds.joinToString()}.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(if (state.items.isEmpty()) "Pick ${r.itemKinds.joinToString()}" else Format.plural(state.items.size, "item") + " picked",
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(HiveSpacing.sm))
                val samples = onSamplePhotos != null && ("image" in r.itemKinds || "images" in r.itemKinds)
                ActionRow(
                    primary = if (samples) HiveAction("Use 24 sample photos", HiveIcons.photo) { onSamplePhotos?.invoke(24) } else null,
                    secondaries = listOfNotNull(
                        if (samples) HiveAction("120 samples", HiveIcons.photo) { onSamplePhotos?.invoke(120) } else null,
                        onPickFiles?.let { pick -> HiveAction("Choose files", HiveIcons.add) { pick(r.itemKinds) } },
                        if (state.items.isNotEmpty()) HiveAction("Clear", HiveIcons.close, onClick = onClearItems) else null,
                    ),
                )
                if (onPickFiles == null && !samples) Text("No file picker on this build", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.items.take(5).forEach { p ->
                    ListRow(File(p).name, leading = { Avatar(File(p).name, size = HiveSpacing.avatarSm, tone = Tone.Neutral, icon = HiveIcons.photo) },
                        trailing = { com.hivecore.ui.design.HeaderAction(HiveIcons.close, "Remove ${File(p).name}") { onRemoveItem(p) } })
                }
                if (state.items.size > 5) Text("and ${state.items.size - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LiveJob(state: JobsViewModel.UiState, p: JobProgress?, onSelectChunk: (Int?) -> Unit, onCancel: () -> Unit) {
    val done = p?.chunks?.count { it.state == ChunkState.DONE } ?: 0
    val total = p?.chunks?.size ?: 0
    HeroCard(
        headline = if (p == null || total == 0) "Starting…" else "$done / $total",
        headlineStyle = if (p == null || total == 0) MaterialTheme.typography.headlineSmall else HiveTextStyles.stat,
        caption = p?.title ?: state.selected?.title ?: "Job",
        tone = CardTone.Tonal, icon = HiveIcons.jobs,
        pills = if (p == null) null else { {
            if (p.axis.isNotBlank()) StatusPill(Recipes.axisLabel(p.axis), Tone.Ok)
            if (state.cloudChunks > 0) StatusPill("${state.cloudChunks} cloud", Tone.Cloud, icon = HiveIcons.cloud)
            StatusPill(JobFormat.ms(p.elapsedMs), Tone.Neutral, icon = HiveIcons.history)
            StatusPill("moved ${JobFormat.bytes(p.bytesMoved)}", Tone.Neutral)
        } },
        primary = if (state.running) HiveAction("Cancel job", HiveIcons.stop, onClick = onCancel) else null,
    ) {
        Spacer(Modifier.height(HiveSpacing.md))
        if (p == null) {
            LoadingRow(if (state.preparing.isEmpty()) "Splitting into chunks…" else "The phones are booting their models…")
            if (state.preparing.isNotEmpty()) PreparingList(state.preparing)
        } else {
            val frac = JobFormat.progress(p)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Progress ${(frac * 100).toInt()} percent" })
            Spacer(Modifier.height(HiveSpacing.xs))
            Text(JobFormat.chunkLine(p), style = MaterialTheme.typography.bodySmall)
            if (p.merge.isNotBlank()) Text("Merge: ${Recipes.mergeLabel(p.merge)} on this phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(HiveSpacing.md))
            ChunkGrid(p.chunks, state.selectedChunk, onSelectChunk)
        }
    }
}

/** Per-phone load: chunks, busy ms, items/s, link kind (spec section 17), and the worker's live line. */
@Composable
private fun PhoneLane(l: JobsViewModel.PhoneLane, maxRate: Double, elapsedMs: Long) {
    val n: NodeLoad = l.load
    val frac = (n.itemsPerSec / maxRate).toFloat().coerceIn(0f, 1f)
    val util = if (elapsedMs > 0) (n.busyMs * 100 / elapsedMs).coerceIn(0, 100) else null
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "${n.name} via ${l.linkWord}: ${n.chunksDone} chunks done, ${JobFormat.rate(n.itemsPerSec)}" + (util?.let { ", busy $it percent" } ?: "")
    }) {
        ListRow(n.name, supporting = "${n.chunksDone} chunks · ${JobFormat.ms(n.busyMs)} busy · ${JobFormat.rate(n.itemsPerSec)}" + (util?.let { " · $it%" } ?: ""),
            leading = { Avatar(n.name, online = true) }, trailing = { StatusPill(l.linkWord, Tone.Neutral) })
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
        l.activityLine?.let { Spacer(Modifier.height(HiveSpacing.xs)); ActivityLine(null, it, l.busy) }
    }
}

@Composable
private fun ResultHero(p: JobProgress, hash: String?, onOpenResult: () -> Unit, onRerun: (() -> Unit)?) {
    val speed = p.speedup
    HeroCard(
        headline = if (speed != null && speed > 0) String.format(Locale.US, "%.1f× faster", speed) else "Done",
        headlineDescription = JobFormat.speedup(speed),
        caption = "${JobFormat.ms(p.elapsedMs)} on ${Format.plural(p.nodes.size, "phone")}" + (p.singlePhoneMs?.let { " vs about ${JobFormat.ms(it)} on this phone alone" } ?: ""),
        tone = CardTone.Answer, icon = HiveIcons.bolt,
        pills = {
            p.identicalToSingle?.let { same -> StatusPill(if (same) "identical to one phone" else "differs from one phone", if (same) Tone.Ok else Tone.Warn, icon = if (same) HiveIcons.check else HiveIcons.warning) }
            JobFormat.cloudCount(p).takeIf { it > 0 }?.let { StatusPill("$it chunks on the cloud", Tone.Cloud, icon = HiveIcons.cloud) }
            JobFormat.mergeNote(p)?.let { StatusPill(it, Tone.Neutral) }
        },
        primary = p.resultRef?.let { ref -> HiveAction(if (JobFormat.isFolder(ref)) "Open folder" else "Open result", HiveIcons.table, onClick = onOpenResult) },
        secondaries = listOfNotNull(onRerun?.let { HiveAction("Re-run", HiveIcons.refresh, onClick = it) }),
    ) {
        Spacer(Modifier.height(HiveSpacing.md))
        StatRow(listOf(
            Stat(JobFormat.ms(p.elapsedMs), "elapsed"),
            Stat(p.nodes.size.toString(), if (p.nodes.size == 1) "phone" else "phones"),
            Stat(JobFormat.bytes(p.bytesMoved), "moved"),
        ))
        p.resultSummary?.let { Spacer(Modifier.height(HiveSpacing.sm)); Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text("result hash ${Format.shortHash(hash)}", style = HiveTextStyles.monoSmall, modifier = Modifier.padding(top = HiveSpacing.xs))
    }
}

@Composable
private fun HistoryRow(p: JobProgress, enabled: Boolean, onClick: () -> Unit) {
    val status = when { p.error != null -> "failed"; p.done -> "done"; else -> "resumable" }
    HiveCard(onClick = onClick, enabled = enabled, contentPadding = HiveSpacing.cardTight) {
        ListRow(p.title,
            supporting = listOfNotNull("${p.chunks.size} chunks", Format.plural(p.nodes.size, "phone"), JobFormat.ms(p.elapsedMs),
                p.speedup?.let { String.format(Locale.US, "%.1f×", it) }).joinToString(" · ") + ((p.resultSummary ?: p.error)?.let { "\n$it" } ?: ""),
            leading = { Avatar(p.title, tone = if (status == "done") Tone.Ok else if (status == "failed") Tone.Danger else Tone.Neutral, icon = HiveIcons.jobs) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (JobFormat.cloudCount(p) > 0) { StatusPill("cloud", Tone.Cloud); Spacer(Modifier.width(HiveSpacing.xs)) }
                    StatusPill(status, when (status) { "done" -> Tone.Ok; "failed" -> Tone.Danger; else -> Tone.Neutral })
                }
            },
            supportingLines = 3,
        )
    }
}

@HivePreviews
@Composable
private fun JobsPreview() = HiveCoreTheme {
    val nodes = listOf(NodeLoad("self", "This phone", 6, 9_000, 1.0, null), NodeLoad("sales", "Sales", 11, 9_500, 1.6, "LAN"), NodeLoad("accounts", "Accounts", 7, 8_800, 1.0, "LAN"))
    val chunks = (0 until 24).map { i -> ChunkView(i, "c$i", ChunkState.DONE, listOf("This phone", "Sales", "Accounts")[i % 3], 3_100, "invoices ${i * 5 + 1}–${i * 5 + 5}") }
    val p = JobProgress("j1", "Photo text (OCR)", "shard", "concat", chunks, nodes, bytesMoved = 3_400_000, elapsedMs = 46_000, singlePhoneMs = 120_000,
        speedup = 2.6, identicalToSingle = true, done = true, resultSummary = "120 photos → 120 pages of text", resultRef = "split:9f2c41ab77e0d3c5")
    JobsScreen(JobsViewModel.UiState(loadingRecipes = false, phase = Phase.DONE, current = p, history = listOf(p), lanes = nodes.map { JobsViewModel.PhoneLane(it, null) }),
        {}, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
