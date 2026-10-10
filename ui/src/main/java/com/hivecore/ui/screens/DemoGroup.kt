package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hivecore.core.NodeRole
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.ListRow
import com.hivecore.ui.design.PreferenceDivider
import com.hivecore.ui.design.PreferenceGroup
import com.hivecore.ui.design.SegmentedChoice
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.SwitchRow
import com.hivecore.ui.design.Tone
import com.hivecore.ui.ports.ResetOptions
import com.hivecore.ui.vm.SettingsViewModel

/** Everything the Demo group can do; the route builds it from the view model. */
class DemoActions(
    val setDemoMode: (Boolean) -> Unit = {},
    val setSpeed: (Int) -> Unit = {},
    val setRole: (NodeRole) -> Unit = {},
    val seedAll: () -> Unit = {},
    val setAutoApprove: (Boolean) -> Unit = {},
    val previewApproval: () -> Unit = {},
    val previewConsent: () -> Unit = {},
    val generatePhotos: (Int) -> Unit = {},
    val setKeepScreenOn: (Boolean) -> Unit = {},
    val setLargeText: (Boolean) -> Unit = {},
    val setHideHashes: (Boolean) -> Unit = {},
    val openPermission: (String) -> Unit = {},
    val setReset: (ResetOptions) -> Unit = {},
    val reset: () -> Unit = {},
)

private fun NodeRole.label() = when (this) { NodeRole.HR -> "HR"; NodeRole.SALES -> "Sales"; NodeRole.ACCOUNTS -> "Accounts" }

/** Settings > Demo: the presenter's one-tap controls, first on the page so nothing needs scrolling on stage. */
@Composable
fun DemoGroup(d: SettingsViewModel.DemoState, a: DemoActions) {
    val busy = d.working != null
    PreferenceGroup("Demo", icon = HiveIcons.bolt) {
        if (d.working != null) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(HiveSpacing.iconSm), strokeWidth = HiveSpacing.xxs)
            Text("  ${d.working}", style = MaterialTheme.typography.bodySmall)
        }

        SwitchRow("Demo mode", if (d.demoMode) "Three scripted phones on this device; turn off for the real swarm" else "Run a scripted swarm on this phone alone (rehearsals, no Wi-Fi)", d.demoMode, onChange = a.setDemoMode)
        if (d.demoMode) SegmentedChoice(listOf(10, 30), d.speedX10, label = { if (it == 10) "Real time" else "3× faster" }, onSelect = a.setSpeed)
        PreferenceDivider()

        Text("This phone is", style = MaterialTheme.typography.bodyLarge)
        SegmentedChoice(NodeRole.entries, d.role, label = { it.label() }, onSelect = a.setRole, enabled = !busy)
        Text("Picking a role loads its sample tables and documents (HR: employees; Sales: sales; Accounts: invoices).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HiveButton(HiveAction("Seed all three roles here", HiveIcons.data, enabled = !busy, onClick = a.seedAll), ButtonKind.Text)
        PreferenceDivider()

        Text("Show a moment", style = MaterialTheme.typography.bodyLarge)
        ActionRow(secondaries = listOf(
            HiveAction("24 sample photos", HiveIcons.photo, enabled = !busy) { a.generatePhotos(24) },
            HiveAction("Approval screen", HiveIcons.lock, enabled = !busy, onClick = a.previewApproval),
            HiveAction("Consent sheet", HiveIcons.cloud, enabled = !busy, onClick = a.previewConsent),
            HiveAction("120 sample photos", HiveIcons.photo, enabled = !busy) { a.generatePhotos(120) },
        ), maxInline = 3)
        if (d.debugBuild) SwitchRow("Auto-approve plans (debug)", "Owner phones approve without a fingerprint. Release builds never do.", d.autoApprove, onChange = a.setAutoApprove)
        PreferenceDivider()

        SwitchRow("Keep the screen on", "While demoing or while a job runs", d.keepScreenOn, onChange = a.setKeepScreenOn)
        SwitchRow("Large text", "Easier to read on a projector", d.largeText, onChange = a.setLargeText)
        SwitchRow("Hide hashes", "Plan and result hashes stay out of sight", d.hideHashes, onChange = a.setHideHashes)
        PreferenceDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Permissions", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StatusPill(if (d.missingPermissions == 0) "all granted" else "${d.missingPermissions} missing", if (d.missingPermissions == 0) Tone.Ok else Tone.Warn)
        }
        d.permissions.forEach { p ->
            ListRow(p.title, supporting = p.detail,
                trailing = { if (p.granted) StatusPill("on", Tone.Ok, icon = HiveIcons.check) else HiveButton(HiveAction("Grant") { a.openPermission(p.id) }, ButtonKind.Tonal) })
        }
        PreferenceDivider()

        Text("Reset demo state", style = MaterialTheme.typography.bodyLarge)
        Column {
            ResetCheck("Jobs and their history", d.reset.jobs) { a.setReset(d.reset.copy(jobs = it)) }
            ResetCheck("Picked and sample photos", d.reset.pickedFiles) { a.setReset(d.reset.copy(pickedFiles = it)) }
            ResetCheck("The conversation", d.reset.askHistory) { a.setReset(d.reset.copy(askHistory = it)) }
            ResetCheck("Releases, hidden tables, private marks", d.reset.importedData) { a.setReset(d.reset.copy(importedData = it)) }
            ResetCheck("Also unpair this phone", d.reset.pairing) { a.setReset(d.reset.copy(pairing = it)) }
        }
        HiveButton(HiveAction("Reset and restart", HiveIcons.refresh, enabled = !busy, destructive = true, onClick = a.reset), ButtonKind.Outlined)
    }
}

@Composable
private fun ResetCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
