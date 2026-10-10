package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.vm.SettingsViewModel

/** Settings route: collects [SettingsViewModel]. [cloud] = the cloud phase flag; off hides the key and caps. */
@Composable
fun SettingsRoute(vm: SettingsViewModel, cloud: Boolean = true) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    SettingsScreen(state, cloud, onPolicy = vm::setPolicy, onKeyDraft = vm::setKeyDraft, onSaveKey = vm::saveKey, onClearKey = vm::clearKey,
        onCapJob = vm::setCapJob, onCapMonth = vm::setCapMonth, onSchemaSharing = vm::setSchemaSharing, onMobileData = vm::setMobileDataBlobs,
        onRelay = vm::setRelayAddress, onAdvanced = vm::setAdvanced, onHeartbeatLan = vm::setHeartbeatLan, onHeartbeatWan = vm::setHeartbeatWan,
        onDeadlineFloor = vm::setDeadlineFloor, onDeadlineFactor = vm::setDeadlineFactor, onResetTuning = vm::resetTuning,
        onExportLedger = vm::exportLedger, onForgetPeer = vm::forgetPeer, onDismissMessage = vm::dismissMessage)
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
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") { ScreenTitle("Settings", "Cloud policy, caps, sharing, relay, tuning") }
        item("message") { MessageBar(state.message, onDismissMessage) }

        item("policy-title") { SectionTitle("Cloud policy") }
        item("policy") {
            Column {
                CloudPolicy.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.policy == p, onClick = { onPolicy(p) }, modifier = Modifier.semantics { contentDescription = p.label })
                        Column { Text(p.label, style = MaterialTheme.typography.bodyLarge); Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }

        if (cloud) {
            item("key-title") { SectionTitle("OpenRouter key") }
            item("key") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.keyLine, style = MaterialTheme.typography.bodyMedium)
                        Text("Stored in the phone's keystore; read only by the cloud client on this phone; never shown back, never sent to a peer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(state.keyDraft, onKeyDraft, Modifier.fillMaxWidth(), label = { Text(if (state.hasKey) "Replace key" else "Key") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSaveKey, enabled = state.canSaveKey) { Text("Save key") }
                            if (state.hasKey) OutlinedButton(onClick = onClearKey) { Text("Remove key") }
                        }
                    }
                }
            }
            item("caps-title") { SectionTitle("Spending caps (₹)") }
            item("caps") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Per job", state.capJobInr, Modifier.weight(1f), onCapJob)
                    IntField("Per month", state.capMonthInr, Modifier.weight(1f), onCapMonth)
                }
            }
            item("schema") { SwitchRow("Share the schema with the cloud planner", "Table and column names only, never values, for T2 plans", state.schemaSharing, onChange = onSchemaSharing) }
        }

        item("mesh-title") { SectionTitle("Mesh") }
        item("mobile") { SwitchRow("Blobs over mobile data", "Photos and audio may move to a phone on a metered link", state.mobileDataBlobs, onChange = onMobileData) }
        item("relay") { TextSetting("Relay address", state.relayAddress, "relay.example.invalid:443", onRelay) }

        item("adv-title") { SectionTitle("Advanced") }
        item("adv") { SwitchRow("Heartbeat and deadline tuning", "Spec defaults: 15 s / 30 s heartbeats, deadline max(4 s, 3 × expected)", state.advanced, onChange = onAdvanced) }
        if (state.advanced) item("tuning") {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Heartbeat LAN (s)", state.heartbeatLanS, Modifier.weight(1f), onHeartbeatLan)
                    IntField("Heartbeat WAN (s)", state.heartbeatWanS, Modifier.weight(1f), onHeartbeatWan)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Deadline floor (s)", state.deadlineFloorS, Modifier.weight(1f), onDeadlineFloor)
                    DoubleField("Deadline × expected", state.deadlineFactor, Modifier.weight(1f), onDeadlineFactor)
                }
                TextButton(onClick = onResetTuning) { Text("Reset to defaults") }
            }
        }

        item("actions-title") { SectionTitle("Actions") }
        item("export") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportLedger, enabled = !state.busy) { Text("Export ledger") }
                state.lastExport?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
        item("peers-title") { SectionTitle("Forget a peer") }
        if (state.peers.isEmpty()) item("peers-empty") { EmptyState("No paired peers") }
        items(state.peers, key = { "peer-" + it.nodeId }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onForgetPeer(p.nodeId) }, enabled = !state.busy) { Text("Forget") }
            }
        }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun IntField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { t -> text = t; t.toIntOrNull()?.let(onChange) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun DoubleField(label: String, value: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { t -> text = t; t.toDoubleOrNull()?.let(onChange) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun TextSetting(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
        trailingIcon = { if (text != value) TextButton(onClick = { onChange(text) }) { Text("Save") } })
}
