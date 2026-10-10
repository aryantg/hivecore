package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Screen
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.Capability
import com.hivecore.ui.design.HeaderAction
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.ListRow
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.error
import com.hivecore.ui.design.loading
import com.hivecore.ui.design.section
import com.hivecore.ui.ports.PhoneModelState
import com.hivecore.ui.ports.fakes.FakeModelsPort
import com.hivecore.ui.vm.ModelsViewModel

/** Models route: collects [ModelsViewModel] and reads the registry when the screen appears. */
@Composable
fun ModelsRoute(vm: ModelsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    ModelsScreen(state, onDownload = vm::download, onLoad = vm::load, onUnload = vm::unload, onBench = vm::bench, onPin = vm::pin, onRetry = vm::refresh, onDismissMessage = vm::dismissMessage)
}

/** A capability's plain name and icon ("llm.json" → "Language"). */
fun capabilityLabel(cap: String): Pair<String, ImageVector> = when (cap.substringBefore('.').lowercase()) {
    "llm" -> "Language" to HiveIcons.ask
    "vlm" -> "Vision and language" to HiveIcons.photo
    "ocr" -> "Reading text (OCR)" to HiveIcons.document
    "asr" -> "Speech to text" to HiveIcons.ask
    "detect" -> "Object detection" to HiveIcons.scan
    "embed" -> "Embeddings" to HiveIcons.data
    "image", "gen_image", "sd" -> "Image generation" to HiveIcons.photo
    "iqa" -> "Image quality" to HiveIcons.photo
    else -> cap to HiveIcons.models
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(
    state: ModelsViewModel.UiState,
    onDownload: (String) -> Unit,
    onLoad: (String) -> Unit,
    onUnload: (String) -> Unit,
    onBench: (String) -> Unit,
    onPin: (String, String) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadedHere = state.catalog.count { state.row(it).loadedHere }
    HiveScreen(
        Screen.MODELS, modifier,
        capability = Capability(HiveIcons.models, "On-device AI", "Language, vision and reading models run on each phone's NPU. Nothing is sent to a server.",
            Stat("$loadedHere of ${state.catalog.size}", "models loaded on this phone", if (loadedHere > 0) Tone.Ok else Tone.Neutral, HiveIcons.models),
            tryPrompt = "Summarise the Q3 review deck"),
        subtitle = "${state.bigText} · warm: ${state.warm.ifEmpty { listOf("none") }.joinToString(", ")}",
        actions = { HeaderAction(HiveIcons.refresh, "Refresh", onClick = onRetry) },
        message = state.message, onDismissMessage = onDismissMessage,
    ) {
        state.error?.let { error("error", it, onRetry) }
        if (state.loading && state.catalog.isEmpty()) loading("loading", "Reading the catalog…")
        if (!state.loading && state.catalog.isEmpty()) item("empty") {
            com.hivecore.ui.design.EmptyState("No catalog", "models/catalog.json ships with the runtime phase", icon = HiveIcons.models)
        }
        state.groups.forEach { (capability, rows) ->
            section(capabilityLabel(capability).first, key = "cap-$capability")
            rows.forEach { r -> item("m-" + r.id) { ModelCard(r, state.now, onDownload, onLoad, onUnload, onBench, onPin) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(r: ModelsViewModel.Row, now: Long, onDownload: (String) -> Unit, onLoad: (String) -> Unit, onUnload: (String) -> Unit, onBench: (String) -> Unit, onPin: (String, String) -> Unit) {
    HiveCard(semantics = {}) {
        ListRow(
            r.id, supporting = r.specLine + " · " + r.sizeText,
            leading = { Avatar(r.id, tone = if (r.loadedHere) Tone.Ok else Tone.Neutral, icon = capabilityLabel(r.entry.capability).second) },
            trailing = {
                when {
                    r.loadedHere -> StatusPill("loaded", Tone.Ok, dot = true)
                    r.installedHere -> StatusPill("installed", Tone.Neutral)
                    else -> StatusPill("available", Tone.Neutral, icon = HiveIcons.download)
                }
            },
        )
        Text(r.hashText, style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(HiveSpacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
            if (r.pinned) StatusPill("default", Tone.Accent, icon = HiveIcons.check)
            r.chips.forEach { c -> StatusPill(c.label(now), if (c.loaded) Tone.Ok else Tone.Neutral, dot = c.loaded) }
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        val busy = r.busy
        val primary = when {
            r.loadedHere -> HiveAction("Unload", HiveIcons.stop, enabled = !busy) { onUnload(r.id) }
            r.installedHere -> HiveAction(if (busy) "Working…" else "Load", HiveIcons.play, enabled = !busy) { onLoad(r.id) }
            else -> HiveAction(if (busy) "Working…" else "Download", HiveIcons.download, enabled = !busy) { onDownload(r.id) }
        }
        ActionRow(primary, listOfNotNull(
            if (r.installedHere) HiveAction(if (busy) "Working…" else "BENCH", HiveIcons.bolt, enabled = !busy) { onBench(r.id) } else null,
            if (!r.pinned) HiveAction("Pin as default", HiveIcons.check, enabled = !busy) { onPin(r.entry.capability, r.id) } else null,
        ))
    }
}

@HivePreviews
@Composable
private fun ModelsPreview() = HiveCoreTheme {
    val now = 1_760_000_000_000L
    ModelsScreen(ModelsViewModel.UiState(loading = false, catalog = FakeModelsPort.SAMPLE_CATALOG, installedHere = setOf("qwen3-4b-q4", "yolo11n"),
        big = "qwen3-4b-q4", warm = listOf("yolo11n"), pinned = mapOf("llm.json" to "qwen3-4b-q4"), now = now,
        phones = listOf(PhoneModelState("sales", "Sales", "qwen3-4b-q4", installed = true, loaded = true, benchP50Ms = 41.0, lastBenchTs = now - 86_400_000L))),
        {}, {}, {}, {}, { _, _ -> }, {}, {})
}
