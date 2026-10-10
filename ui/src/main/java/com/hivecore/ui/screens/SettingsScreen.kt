package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Screen
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.Capability
import com.hivecore.ui.design.DoubleField
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextField
import com.hivecore.ui.design.IntField
import com.hivecore.ui.design.KeyValue
import com.hivecore.ui.design.ListRow
import com.hivecore.ui.design.PreferenceDivider
import com.hivecore.ui.design.PreferenceGroup
import com.hivecore.ui.design.SaveableTextField
import com.hivecore.ui.design.SegmentedChoice
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.SwitchRow
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.listOrEmpty
import com.hivecore.ui.design.section
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.vm.SettingsViewModel

/**
 * Settings route: collects [SettingsViewModel]. [cloud] = the cloud phase flag; off hides the key and caps.
 * [demoSection] is the presenter's Demo group (the app supplies it); it renders first so nobody scrolls on stage.
 */
@Composable
fun SettingsRoute(vm: SettingsViewModel, cloud: Boolean = true, demoSection: (@Composable () -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    SettingsScreen(state, cloud, onPolicy = vm::setPolicy, onKeyDraft = vm::setKeyDraft, onSaveKey = vm::saveKey, onClearKey = vm::clearKey,
        onCapJob = vm::setCapJob, onCapMonth = vm::setCapMonth, onSchemaSharing = vm::setSchemaSharing, onMobileData = vm::setMobileDataBlobs,
        onRelay = vm::setRelayAddress, onAdvanced = vm::setAdvanced, onHeartbeatLan = vm::setHeartbeatLan, onHeartbeatWan = vm::setHeartbeatWan,
        onDeadlineFloor = vm::setDeadlineFloor, onDeadlineFactor = vm::setDeadlineFactor, onResetTuning = vm::resetTuning,
        onExportLedger = vm::exportLedger, onForgetPeer = vm::forgetPeer, onDismissMessage = vm::dismissMessage, demoSection = demoSection)
}

/** The icon of each cloud policy on the segmented control. */
fun CloudPolicy.icon() = when (this) {
    CloudPolicy.ASK -> HiveIcons.ask
    CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE -> HiveIcons.lock
    CloudPolicy.FASTEST -> HiveIcons.bolt
}

/** The short label the segmented control and the composer's policy pill use. */
val CloudPolicy.short: String get() = when (this) {
    CloudPolicy.ASK -> "Ask me"
    CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE -> "Local first"
    CloudPolicy.FASTEST -> "Fastest"
}

@Composable
fun SettingsScreen(
    state: SettingsViewModel.UiState,
    cloud: Boolean,
    onPolicy: (CloudPolicy) -> Unit,
    onKeyDraft: (String) -> Unit,
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onCapJob: (Int) -> Unit,
    onCapMonth: (Int) -> Unit,
    onSchemaSharing: (Boolean) -> Unit,
    onMobileData: (Boolean) -> Unit,
    onRelay: (String) -> Unit,
    onAdvanced: (Boolean) -> Unit,
    onHeartbeatLan: (Int) -> Unit,
    onHeartbeatWan: (Int) -> Unit,
    onDeadlineFloor: (Int) -> Unit,
    onDeadlineFactor: (Double) -> Unit,
    onResetTuning: () -> Unit,
    onExportLedger: () -> Unit,
    onForgetPeer: (String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    demoSection: (@Composable () -> Unit)? = null,
) {
    HiveScreen(
        Screen.SETTINGS, modifier,
        capability = Capability(HiveIcons.settings, "Your rules", "You decide when the cloud is allowed, and what it may cost.",
            Stat(state.policy.short, "cloud policy", Tone.Accent, state.policy.icon())),
        message = state.message, onDismissMessage = onDismissMessage,
    ) {
        if (demoSection != null) item("demo") { demoSection() }

        item("policy") {
            PreferenceGroup("Cloud policy", icon = HiveIcons.cloud) {
                SegmentedChoice(CloudPolicy.entries, state.policy, label = { it.short }, onSelect = onPolicy, icon = { it.icon() })
                Text("${state.policy.label}: ${state.policy.blurb}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (cloud) {
            item("key") {
                PreferenceGroup("OpenRouter key", icon = HiveIcons.lock) {
                    KeyValue("Key", if (state.hasKey) "stored · ends ${state.keyHint}" else "none")
                    Text("Stored in the phone's keystore; read only by the cloud client on this phone; never shown back, never sent to a peer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HiveTextField(state.keyDraft, onKeyDraft, Modifier, label = if (state.hasKey) "Replace key" else "Key", password = true)
                    ActionRow(HiveAction("Save key", HiveIcons.check, enabled = state.canSaveKey, onClick = onSaveKey),
                        if (state.hasKey) listOf(HiveAction("Remove key", HiveIcons.delete, destructive = true, onClick = onClearKey)) else emptyList())
                }
            }
            item("caps") {
                PreferenceGroup("Spending caps (₹)", icon = HiveIcons.bolt) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
                        IntField("Per job", state.capJobInr, Modifier.weight(1f), onCapJob)
                        IntField("Per month", state.capMonthInr, Modifier.weight(1f), onCapMonth)
                    }
                    PreferenceDivider()
                    SwitchRow("Share the schema with the cloud planner", "Table and column names only, never values, for T2 plans", state.schemaSharing, onChange = onSchemaSharing)
                }
            }
        }

        item("mesh") {
            PreferenceGroup("Mesh", icon = HiveIcons.swarm) {
                SwitchRow("Blobs over mobile data", "Photos and audio may move to a phone on a metered link", state.mobileDataBlobs, onChange = onMobileData)
                PreferenceDivider()
                SaveableTextField("Relay address", state.relayAddress, "relay.example.invalid:443", onSave = onRelay)
            }
        }

        item("advanced") {
            PreferenceGroup("Advanced", icon = HiveIcons.tune) {
                SwitchRow("Heartbeat and deadline tuning", "Spec defaults: 15 s / 30 s heartbeats, deadline max(4 s, 3 × expected)", state.advanced, onChange = onAdvanced)
                if (state.advanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
                        IntField("Heartbeat LAN (s)", state.heartbeatLanS, Modifier.weight(1f), onHeartbeatLan)
                        IntField("Heartbeat WAN (s)", state.heartbeatWanS, Modifier.weight(1f), onHeartbeatWan)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
                        IntField("Deadline floor (s)", state.deadlineFloorS, Modifier.weight(1f), onDeadlineFloor)
                        DoubleField("Deadline × expected", state.deadlineFactor, Modifier.weight(1f), onDeadlineFactor)
                    }
                    HiveButton(HiveAction("Reset to defaults", HiveIcons.refresh, onClick = onResetTuning), ButtonKind.Text)
                }
            }
        }

        item("ledger") {
            PreferenceGroup("Ledger", icon = HiveIcons.history) {
                Text("Every merge and executed chunk leaves a signed receipt on this phone.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionRow(secondaries = listOf(HiveAction("Export ledger", HiveIcons.upload, enabled = !state.busy, onClick = onExportLedger)))
                state.lastExport?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        section("Paired phones")
        listOrEmpty(state.peers, key = { "peer-" + it.nodeId }, emptyKey = "peers-empty", emptyTitle = "No paired phones",
            emptyDetail = "Pair one from the Swarm page", emptyIcon = HiveIcons.phone) { p ->
            ListRow(p.name, supporting = if (p.admin) "admin" else "member", leading = { Avatar(p.name) },
                trailing = { HiveButton(HiveAction("Forget", enabled = !state.busy, destructive = true) { onForgetPeer(p.nodeId) }, ButtonKind.Text) })
        }
    }
}

@HivePreviews
@Composable
private fun SettingsPreview() = HiveCoreTheme {
    SettingsScreen(SettingsViewModel.UiState(hasKey = true, keyHint = "a1b2", peers = listOf(MemberView("sales", "Sales", admin = true), MemberView("accounts", "Accounts"))),
        cloud = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
