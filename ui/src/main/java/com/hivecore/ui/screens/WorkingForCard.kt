package com.hivecore.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hivecore.core.WorkerEvent
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.StepItem
import com.hivecore.ui.design.StepList
import com.hivecore.ui.design.StepMark
import com.hivecore.ui.design.Tone
import com.hivecore.ui.vm.WorkerTimeline
import com.hivecore.ui.vm.WorkerTimeline.StepState

/**
 * On a worker: "Working for <orchestrator>", the four steps of the chunk it holds (the model it picked for the task
 * type and booted, the chunk it got, the run, the result sent back and countersigned).
 */
@Composable
internal fun WorkingForCard(w: WorkerTimeline.WorkingFor) {
    HiveCard(Modifier.animateContentSize(), tone = CardTone.Tonal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(w.commanderName)
            Spacer(Modifier.width(HiveSpacing.md))
            Column(Modifier.weight(1f)) {
                Text("Working for ${w.commanderName}", style = MaterialTheme.typography.titleMedium)
                Text(w.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(if (w.settled) "done" else "live", if (w.settled) Tone.Ok else Tone.Accent, dot = !w.settled)
        }
        Spacer(Modifier.height(HiveSpacing.md))
        StepList(w.steps.map { s -> StepItem(s.title, s.detail, mark = markOf(s.state)) })
    }
}

private fun markOf(s: StepState): StepMark = when (s) {
    StepState.PENDING -> StepMark.Number
    StepState.ACTIVE -> StepMark.Active
    StepState.DONE -> StepMark.Done
    StepState.FAILED -> StepMark.Failed
    StepState.SKIPPED -> StepMark.Skipped
}

/** On the orchestrator, before the chunk grid exists: each worker's latest event while placement boots models. */
@Composable
internal fun PreparingList(preparing: List<Pair<String, WorkerEvent>>) {
    Column(verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
        preparing.forEach { (name, e) -> ActivityLine(name, WorkerTimeline.phrase(e), WorkerTimeline.busy(e)) }
    }
}

/** "booting qwen2.5-1.5b…" under a phone lane. */
@Composable
internal fun ActivityLine(who: String?, line: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(HiveSpacing.spinnerSm), strokeWidth = HiveSpacing.xxs)
        else Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(HiveSpacing.sm))
        Text((who?.let { "$it: " } ?: "") + line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
