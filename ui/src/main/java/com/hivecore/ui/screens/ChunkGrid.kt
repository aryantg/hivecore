package com.hivecore.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.ui.HiveTheme
import com.hivecore.ui.vm.JobFormat

/**
 * The chunk grid (spec section 17 Jobs): one cell per chunk, coloured by state, with the initial of the phone that
 * ran it (a cloud glyph for an escalated chunk, spec section 9.4). Duplicated (straggler) chunks get a "2" corner mark
 * and a thick border. Tapping a cell selects it and the line under the grid shows its label, phone and time.
 */
@Composable
fun ChunkGrid(chunks: List<ChunkView>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier, cellSize: Dp = 30.dp) {
    Column(modifier.fillMaxWidth()) {
        if (chunks.isEmpty()) {
            Text("Splitting the job into chunks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val perRow = ((maxWidth + gap) / (cellSize + gap)).toInt().coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                chunks.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { c -> ChunkCell(c, c.index == selected, cellSize) { onSelect(c.index) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val sel = chunks.firstOrNull { it.index == selected }
        if (sel != null) Text(JobFormat.chunkDetail(sel), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        else Text("Tap a chunk for its label, phone and time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        ChunkLegend()
    }
}

@Composable
private fun ChunkCell(c: ChunkView, isSelected: Boolean, size: Dp, onClick: () -> Unit) {
    val cloud = JobFormat.isCloud(c)
    val (bg, fg) = if (cloud && c.state == ChunkState.DONE) HiveTheme.colors.cloudBadge to HiveTheme.colors.onCloudBadge else chunkColors(c.state)
    val shape = RoundedCornerShape(6.dp)
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.onSurface
        c.state == ChunkState.DUPLICATED -> MaterialTheme.colorScheme.tertiary
        else -> Color.Transparent
    }
    Box(
        Modifier.size(size).background(bg, shape)
            .border(if (isSelected || c.state == ChunkState.DUPLICATED) 2.dp else 0.dp, borderColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = JobFormat.chunkDetail(c) },
        contentAlignment = Alignment.Center,
    ) {
        Text(JobFormat.initial(c.node), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (c.state == ChunkState.DUPLICATED) Text("2", color = fg, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
        if (c.state == ChunkState.FAILED) Text("!", color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp))
    }
}

@Composable
fun chunkColors(state: ChunkState): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val x = HiveTheme.colors
    return when (state) {
        ChunkState.QUEUED -> cs.surfaceVariant to cs.onSurfaceVariant
        ChunkState.RUNNING -> cs.primaryContainer to cs.onPrimaryContainer
        ChunkState.DONE -> x.okBadge to x.onOkBadge
        ChunkState.DUPLICATED -> cs.tertiaryContainer to cs.onTertiaryContainer
        ChunkState.FAILED -> cs.errorContainer to cs.onErrorContainer
    }
}

@Composable
private fun ChunkLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        listOf(ChunkState.QUEUED to "queued", ChunkState.RUNNING to "running", ChunkState.DONE to "done", ChunkState.DUPLICATED to "duplicated", ChunkState.FAILED to "failed").forEach { (s, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(chunkColors(s).first, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(3.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(HiveTheme.colors.cloudBadge, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(3.dp))
            Text("cloud", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
