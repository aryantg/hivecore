package com.hivecore.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hivecore.core.WorkerEvent
import com.hivecore.ui.vm.WorkerTimeline
import com.hivecore.ui.vm.WorkerTimeline.StepState

/**
 * On a worker: "Working for <orchestrator>", the four steps of the chunk it holds (the model it picked for the task
 * type and booted, the chunk it got, the run, the result sent back and countersigned).
 */
@Composable
internal fun WorkingForCard(w: WorkerTimeline.WorkingFor) {
    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Working for ${w.commanderName}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Badge(if (w.settled) "done" else "live", if (w.settled) Tone.OK else Tone.NEUTRAL)
            }
            Text(w.what, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            w.steps.forEachIndexed { i, s -> StepRow(i + 1, s, last = i == w.steps.lastIndex) }
        }
    }
}

@Composable
private fun StepRow(n: Int, s: WorkerTimeline.Step, last: Boolean) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "Step $n, ${s.title}: ${s.state.name.lowercase()}" + (s.detail?.let { ", $it" } ?: "")
    }, verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(targetState = s.state, label = "step-$n") { st -> StepDot(n, st) }
            if (!last) Box(Modifier.width(2.dp).height(14.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp, bottom = if (last) 0.dp else 6.dp)) {
            Text(s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (s.state == StepState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                color = if (s.state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
            s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun StepDot(n: Int, st: StepState) {
    val c = MaterialTheme.colorScheme
    val (bg, fg, glyph) = when (st) {
        StepState.DONE -> Triple(c.primary, c.onPrimary, "✓")
        StepState.FAILED -> Triple(c.error, c.onError, "!")
        StepState.SKIPPED -> Triple(c.surfaceVariant, c.onSurfaceVariant, "–")
        StepState.ACTIVE -> Triple(Color.Transparent, c.primary, "")
        StepState.PENDING -> Triple(c.surfaceVariant, c.onSurfaceVariant, n.toString())
    }
    Box(Modifier.size(22.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        if (st == StepState.ACTIVE) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(glyph, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** On the orchestrator, before the chunk grid exists: each worker's latest event while placement boots models. */
@Composable
internal fun PreparingList(preparing: List<Pair<String, WorkerEvent>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        preparing.forEach { (name, e) -> ActivityLine(name, WorkerTimeline.phrase(e), WorkerTimeline.busy(e)) }
    }
}

/** "⟳ booting qwen2.5-1.5b…" under a phone lane. */
@Composable
internal fun ActivityLine(who: String?, line: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        else Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text((who?.let { "$it: " } ?: "") + line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
