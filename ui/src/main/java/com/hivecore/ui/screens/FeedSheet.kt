package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.hivecore.core.FeedEvent
import com.hivecore.core.FeedKind
import com.hivecore.ui.Format
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.EmptyState
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.Tone

/** The kind's icon and colour in the feed. */
fun FeedKind.icon(): ImageVector = when (this) {
    FeedKind.PEER -> HiveIcons.phone
    FeedKind.PAIR -> HiveIcons.lock
    FeedKind.ROSTER -> HiveIcons.swarm
    FeedKind.MODEL -> HiveIcons.models
    FeedKind.JOB -> HiveIcons.jobs
    FeedKind.CHUNK -> HiveIcons.play
    FeedKind.MERGE -> HiveIcons.check
    FeedKind.PLAN -> HiveIcons.ask
    FeedKind.APPROVAL -> HiveIcons.lock
    FeedKind.CLOUD -> HiveIcons.cloud
    FeedKind.WARN -> HiveIcons.warning
    FeedKind.DEMO -> HiveIcons.hexMark
}

fun FeedKind.tone(): Tone = when (this) {
    FeedKind.MERGE, FeedKind.PAIR, FeedKind.APPROVAL -> Tone.Ok
    FeedKind.CLOUD -> Tone.Cloud
    FeedKind.WARN -> Tone.Warn
    FeedKind.CHUNK, FeedKind.PEER -> Tone.Neutral
    else -> Tone.Accent
}

/** "12:04:31 · Chunk 7 done on Sales in 3.1 s" for the one-line status strip. */
fun feedLine(e: FeedEvent): String = "${Format.clock(e.ts)} · ${e.text}"

/** What the swarm is doing, newest first, for narrating a demo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedSheet(events: List<FeedEvent>, onClear: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = HiveShape.sheet) {
        Column(Modifier.fillMaxHeight(SHEET_FRACTION)) {
            Row(Modifier.padding(horizontal = HiveSpacing.screenH), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Activity", style = MaterialTheme.typography.titleLarge)
                    Text("What your phones are doing, live", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (events.isNotEmpty()) HiveButton(HiveAction("Clear", HiveIcons.delete, onClick = onClear), ButtonKind.Text)
            }
            val list = rememberLazyListState()
            LaunchedEffect(events.firstOrNull()?.ts) { if (events.isNotEmpty()) list.animateScrollToItem(0) }
            if (events.isEmpty()) EmptyState("Nothing yet", "Pair a phone, run a job or ask a question and it shows up here", icon = HiveIcons.activity)
            LazyColumn(state = list, contentPadding = PaddingValues(HiveSpacing.screenH), verticalArrangement = Arrangement.spacedBy(HiveSpacing.md)) {
                items(events, key = { "${it.ts}-${it.text.hashCode()}-${it.kind}" }) { e -> FeedRow(e) }
            }
        }
    }
}

@Composable
private fun FeedRow(e: FeedEvent) {
    Row(verticalAlignment = Alignment.Top) {
        Avatar(e.kind.name, size = HiveSpacing.avatarSm, tone = e.kind.tone(), icon = e.kind.icon())
        Spacer(Modifier.width(HiveSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(e.text, style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(Format.clock(e.ts), e.detail).joinToString(" · "), style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val SHEET_FRACTION = 0.85f
