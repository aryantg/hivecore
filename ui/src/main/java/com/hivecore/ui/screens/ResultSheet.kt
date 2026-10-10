package com.hivecore.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.hivecore.ui.Format
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone
import com.hivecore.ui.vm.ResultView
import com.hivecore.ui.vm.ResultViews

/** The merged result of a finished job: the text of every photo, a table, or the raw output. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultSheet(title: String, view: ResultView?, hash: String?, onCopy: (String) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, shape = HiveShape.sheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = HiveSpacing.screenH)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HiveIcons.table, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.iconLg))
                Spacer(Modifier.width(HiveSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("$title result", style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(countLine(view), "hash ${Format.shortHash(hash)}").joinToString(" · "), style = HiveTextStyles.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (view != null) {
                Spacer(Modifier.padding(top = HiveSpacing.sm))
                ActionRow(secondaries = listOf(HiveAction("Copy as CSV", HiveIcons.copy) { onCopy(ResultViews.csv(view)) }, HiveAction("Close", HiveIcons.close, onClick = onClose)))
            }
        }
        LazyColumn(contentPadding = PaddingValues(HiveSpacing.screenH), verticalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            when (view) {
                null -> item("none") { Text("This job's output is not kept on this phone.", style = MaterialTheme.typography.bodyMedium) }
                is ResultView.Texts -> view.items.forEachIndexed { i, t -> item("t$i") { TextCard(t.item, t.lines, t.text) } }
                is ResultView.Table -> item("table") { TableView(view) }
                is ResultView.Raw -> item("raw") { Text(view.json, style = HiveTextStyles.mono) }
            }
        }
    }
}

private fun countLine(v: ResultView?): String? = when (v) {
    is ResultView.Texts -> Format.plural(v.items.size, "item") + " · " + Format.plural(v.items.sumOf { it.lines }, "line")
    is ResultView.Table -> Format.plural(v.rows.size, "row")
    else -> null
}

@Composable
private fun TextCard(item: String, lines: Int, text: String) {
    var all by rememberSaveable(item) { mutableStateOf(false) }
    val shown = if (all) text else text.lineSequence().take(PREVIEW_LINES).joinToString("\n")
    HiveCard(tone = CardTone.Tonal, contentPadding = HiveSpacing.cardTight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HiveIcons.photo, contentDescription = null, modifier = Modifier.size(HiveSpacing.iconSm))
            Spacer(Modifier.width(HiveSpacing.sm))
            Text(item, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusPill(Format.plural(lines, "line"), if (lines > 0) Tone.Ok else Tone.Warn)
        }
        Spacer(Modifier.padding(top = HiveSpacing.xs))
        Text(shown.ifBlank { "(no text found)" }, style = HiveTextStyles.mono)
        if (lines > PREVIEW_LINES) HiveButton(HiveAction(if (all) "Show less" else "Show all $lines lines", if (all) HiveIcons.collapse else HiveIcons.expand) { all = !all }, ButtonKind.Text)
    }
}

@Composable
private fun TableView(t: ResultView.Table) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            t.columns.forEach { c -> Text(c, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(HiveSpacing.tableCell).padding(HiveSpacing.sm)) }
        }
        t.rows.take(MAX_ROWS).forEach { r ->
            Row { r.forEach { v -> Text(v, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(HiveSpacing.tableCell).padding(HiveSpacing.sm)) } }
        }
        if (t.rows.size > MAX_ROWS) Text("and ${t.rows.size - MAX_ROWS} more rows", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(HiveSpacing.sm))
    }
}

private const val PREVIEW_LINES = 12
private const val MAX_ROWS = 50
