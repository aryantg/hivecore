package com.hivecore.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Format
import com.hivecore.ui.HiveTheme
import com.hivecore.ui.ports.AnswerBadge
import com.hivecore.ui.ports.AskAnswer
import com.hivecore.ui.ports.ModeEstimate
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.vm.AskViewModel
import com.hivecore.ui.vm.AskViewModel.Phase

/** Ask route: collects [AskViewModel] and re-reads the cloud policy when the screen appears. */
@Composable
fun AskRoute(vm: AskViewModel, onOpenJob: (String) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshPolicy(); vm.openJob = onOpenJob }
    AskScreen(state, onRequest = vm::setRequest, onSubmit = vm::submit, onCancel = vm::cancel, onChoose = vm::choose, onReading = vm::answerClarification,
        onNotAccurate = { item, step -> vm.notAccurate(item, step) }, onToggleSteps = vm::toggleSteps, onOpenJob = vm::openJob,
        onClearError = vm::clearError, onClearHistory = vm::clearHistory, onDismissMessage = vm::dismissMessage)
}

@Composable
fun AskScreen(
    state: AskViewModel.UiState,
    onRequest: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onChoose: (RunMode) -> Unit,
    onReading: (String) -> Unit,
    onNotAccurate: (AskViewModel.HistoryItem, String?) -> Unit,
    onToggleSteps: (Long) -> Unit,
    onOpenJob: (AskViewModel.HistoryItem) -> Unit,
    onClearError: () -> Unit,
    onClearHistory: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("title") { ScreenTitle("Ask", "Policy: ${state.policy.label}") }
        item("message") { MessageBar(state.message, onDismissMessage) }
        item("input") {
            Column {
                OutlinedTextField(state.request, onRequest, Modifier.fillMaxWidth(), placeholder = { Text("What should your phones do?") },
                    minLines = 2, maxLines = 4, enabled = !state.busy)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy || state.phase is Phase.Choosing || state.phase is Phase.Clarifying) OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    else Button(onClick = onSubmit, enabled = state.request.isNotBlank()) { Text("Plan") }
                    Spacer(Modifier.weight(1f))
                    if (state.history.isNotEmpty() && !state.busy) TextButton(onClick = onClearHistory) { Text("Clear") }
                }
                when (val ph = state.phase) {
                    is Phase.Planning -> { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Planning…", style = MaterialTheme.typography.bodyMedium) }
                    is Phase.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ph.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearError) { Text("OK") }
                    }
                    else -> Unit
                }
            }
        }

        val ph = state.phase
        if (ph is Phase.Clarifying) item("clarify") { ClarifyCard(ph.clarify, onReading) }
        state.panel?.let { options ->
            item("plan") { PlanPanel(options, running = (ph as? Phase.Running)?.let { it.mode to it.status }, onChoose) }
        }

        if (state.history.isEmpty() && ph is Phase.Idle) item("empty") { EmptyState("No answers yet", "Try: read the invoice photos into a table") }

        items(state.history, key = { "h-" + it.id }) { h ->
            when (val o = h.outcome) {
                is AskViewModel.Outcome.Answer -> AnswerCard(h, o.answer, expanded = h.id in state.expandedSteps, reRunning = state.reRunningId == o.answer.answerId,
                    onToggle = { onToggleSteps(h.id) }, onNotAccurate = { onNotAccurate(h, null) }, onOpenJob = { onOpenJob(h) })
                is AskViewModel.Outcome.Refused -> RefusalCard(o.refusal, header = { QuestionLine(h.request, h.askedAt, "Refused") })
            }
        }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

/** Spec section 17: the plan panel, one line per step (op, phone, estimate), and the LOCAL / CLOUD choice. */
@Composable
fun PlanPanel(o: PlanOptions.Ready, running: Pair<RunMode, String>?, onChoose: (RunMode) -> Unit) {
    val plan = o.plan
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Plan · ${plan.tier} · confidence ${(plan.confidence * 100).toInt()} %", style = MaterialTheme.typography.labelLarge)
            Text(plan.request, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            plan.steps.forEach { s ->
                Text(AskViewModel.stepLine(s), style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = "Step ${s.op} on ${s.phone}" })
            }
            Text("plan ${Format.shortHash(plan.planHash)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            ModeOption(o.local, RunMode.LOCAL, o.localBlockedReason, selected = running?.first == RunMode.LOCAL, suggested = o.suggested == RunMode.LOCAL, enabled = running == null || running.first != RunMode.LOCAL, onChoose)
            Spacer(Modifier.height(6.dp))
            ModeOption(o.cloud, RunMode.CLOUD, o.cloudBlockedReason, selected = running?.first == RunMode.CLOUD, suggested = o.suggested == RunMode.CLOUD, enabled = running == null || running.first != RunMode.CLOUD, onChoose)
            if (running != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(running.second, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ModeOption(e: ModeEstimate?, mode: RunMode, blocked: String?, selected: Boolean, suggested: Boolean, enabled: Boolean, onChoose: (RunMode) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val available = e != null
    Card(
        Modifier.fillMaxWidth().clickable(enabled = available && enabled) { onChoose(mode) }.semantics {
            stateDescription = when { selected -> "running"; !available -> "unavailable"; else -> "available" }
        },
        border = if (selected) BorderStroke(2.dp, cs.primary) else null,
        colors = CardDefaults.cardColors(containerColor = when { !available -> cs.surfaceVariant.copy(alpha = 0.5f); selected -> cs.secondaryContainer; else -> cs.surfaceVariant }),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mode.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (mode == RunMode.CLOUD && available) Badge("cloud", Tone.CLOUD)
                if (suggested && available) { Spacer(Modifier.width(6.dp)); Badge("suggested", Tone.NEUTRAL) }
            }
            if (e != null) {
                Text("about ${Format.duration(e.ms)} on ${Format.plural(e.phones, "phone")} · ${Format.inr(e.costInr)}", style = MaterialTheme.typography.bodyMedium)
                Text("Leaves: ${e.leaves}", style = MaterialTheme.typography.bodySmall)
                if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            } else Text("Not available: ${blocked ?: "no estimate"}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClarifyCard(c: PlanOptions.Clarify, onReading: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Which one? (confidence ${(c.confidence * 100).toInt()} %)", style = MaterialTheme.typography.labelLarge)
            Text(c.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            c.readings.forEach { r -> OutlinedButton(onClick = { onReading(r) }, modifier = Modifier.fillMaxWidth()) { Text(r) } }
        }
    }
}

@Composable
private fun QuestionLine(question: String, askedAt: Long, label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(question, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("$label · ${Format.clock(askedAt)}", style = MaterialTheme.typography.labelSmall)
    }
}

/** The answer with provenance and badges (verified, unverified, cloud) and the "not accurate" action. */
@Composable
fun AnswerCard(item: AskViewModel.HistoryItem, a: AskAnswer, expanded: Boolean, reRunning: Boolean, onToggle: () -> Unit, onNotAccurate: () -> Unit, onOpenJob: () -> Unit) {
    val c = HiveTheme.colors
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.answerContainer, contentColor = c.onAnswerContainer)) {
        Column(Modifier.padding(14.dp)) {
            QuestionLine(item.request, item.askedAt, a.mode.name)
            Spacer(Modifier.height(8.dp))
            Text(a.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (a.badge) {
                    AnswerBadge.VERIFIED -> Badge("verified", Tone.OK)
                    AnswerBadge.UNVERIFIED -> Badge("unverified", Tone.WARN)
                    AnswerBadge.CLOUD -> Badge("cloud", Tone.CLOUD)
                }
                if (a.costInr > 0) Badge(Format.inr(a.costInr), Tone.NEUTRAL)
                if (a.elapsedMs > 0) Badge(Format.duration(a.elapsedMs), Tone.NEUTRAL)
            }
            if (a.provenance.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Provenance", style = MaterialTheme.typography.labelMedium)
                a.provenance.forEach { p -> Text("${p.node}: ${p.line}", style = MaterialTheme.typography.bodySmall) }
            }
            a.notes.forEach { n -> Text("Note: $n", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${Format.plural(a.steps.size, "step")} · plan ${Format.shortHash(a.planHash)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggle) { Text(if (expanded) "Hide steps" else "Show steps") }
            }
            if (expanded) a.steps.forEach { s -> Text(AskViewModel.stepLine(s), style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNotAccurate, enabled = !reRunning) { Text(if (reRunning) "Re-running…" else "Not accurate") }
                if (a.jobId != null) TextButton(onClick = onOpenJob) { Text("Open job") }
            }
        }
    }
}
