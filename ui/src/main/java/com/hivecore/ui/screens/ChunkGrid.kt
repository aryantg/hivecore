package com.hivecore.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.ui.design.ChunkTone
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.chunkColors
import com.hivecore.ui.vm.JobFormat

/**
 * The chunk grid (spec section 17 Jobs): one cell per chunk, coloured by state, with the initial of the phone that
 * ran it (a cloud glyph for an escalated chunk, spec section 9.4). Duplicated (straggler) chunks get a "2" corner mark
 * and a thick border. Tapping a cell selects it and the line under the grid shows its label, phone and time.
 */
@Composable
fun ChunkGrid(
    chunks: List<ChunkView>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier,
    cellSize: Dp = HiveSpacing.chunk, legend: Boolean = true, detailLine: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (chunks.isEmpty()) {
            Text("Splitting the job into chunks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = HiveSpacing.chunkGap
            val perRow = ((maxWidth + gap) / (cellSize + gap)).toInt().coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                chunks.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { c -> ChunkCell(c, c.index == selected, cellSize) { onSelect(c.index) } }
                    }
                }
            }
        }
        if (detailLine) {
            Spacer(Modifier.height(HiveSpacing.sm))
            val sel = chunks.firstOrNull { it.index == selected }
            if (sel != null) Text(JobFormat.chunkDetail(sel), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            else Text("Tap a chunk for its label, phone and time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (legend) { Spacer(Modifier.height(HiveSpacing.xs)); ChunkLegend() }
    }
}

/** The chunk's colour role: cloud-run chunks that finished read as cloud, everything else by state. */
fun toneOf(c: ChunkView): ChunkTone = if (JobFormat.isCloud(c) && c.state == ChunkState.DONE) ChunkTone.Cloud else when (c.state) {
    ChunkState.QUEUED -> ChunkTone.Queued
    ChunkState.RUNNING -> ChunkTone.Running
    ChunkState.DONE -> ChunkTone.Done
    ChunkState.DUPLICATED -> ChunkTone.Duplicated
    ChunkState.FAILED -> ChunkTone.Failed
}

@Composable
private fun ChunkCell(c: ChunkView, isSelected: Boolean, size: Dp, onClick: () -> Unit) {
    val (bg, fg) = chunkColors(toneOf(c))
    val shape = HiveShape.chunk
    var m = Modifier.size(size).background(bg, shape)
    if (isSelected) m = m.border(HiveSpacing.borderSelected, MaterialTheme.colorScheme.onSurface, shape)
    else if (c.state == ChunkState.DUPLICATED) m = m.border(HiveSpacing.borderSelected, MaterialTheme.colorScheme.tertiary, shape)
    Box(
        m.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = JobFormat.chunkDetail(c) },
        contentAlignment = Alignment.Center,
    ) {
        Text(JobFormat.initial(c.node), color = fg, style = HiveTextStyles.chunkInitial)
        if (c.state == ChunkState.DUPLICATED) Text("2", color = fg, style = HiveTextStyles.chunkMark, modifier = Modifier.align(Alignment.TopEnd).padding(end = HiveSpacing.xxs, top = HiveSpacing.xxs))
        if (c.state == ChunkState.FAILED) Text("!", color = fg, style = HiveTextStyles.chunkBang, modifier = Modifier.align(Alignment.TopEnd).padding(end = HiveSpacing.xxs, top = HiveSpacing.xxs))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChunkLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.md), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xxs),
        modifier = Modifier.semantics(mergeDescendants = true) {}) {
        listOf(ChunkTone.Queued to "queued", ChunkTone.Running to "running", ChunkTone.Done to "done", ChunkTone.Duplicated to "duplicated",
            ChunkTone.Failed to "failed", ChunkTone.Cloud to "cloud").forEach { (t, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(HiveSpacing.swatch).background(chunkColors(t).first, HiveShape.swatch))
                Spacer(Modifier.width(HiveSpacing.xs))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
