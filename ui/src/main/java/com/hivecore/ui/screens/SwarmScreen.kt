package com.hivecore.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Format
import com.hivecore.ui.ports.JoinRequestView
import com.hivecore.ui.ports.SasView
import com.hivecore.ui.qr.QrCode
import com.hivecore.ui.vm.SwarmViewModel
import com.hivecore.ui.vm.SwarmViewModel.Pairing
import com.hivecore.ui.vm.SwarmViewModel.Tile
import kotlinx.coroutines.delay

/** Swarm route: collects [SwarmViewModel] and refreshes the tiles every few seconds while visible. */
@Composable
fun SwarmRoute(vm: SwarmViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(5_000) } }
    SwarmScreen(state, onOptIn = vm::setOptIn, onShowQr = { vm.showInvite(asLink = false) }, onInviteLink = { vm.showInvite(asLink = true) }, onScan = vm::scan,
        onCopyInvite = vm::copyInvite, onLink = vm::setLink, onJoin = { vm.join() }, onClosePairing = vm::closePairing, onConfirm = vm::confirmSas,
        onApprove = vm::approve, onRevoke = vm::revoke, onHandOver = vm::handOver, onDismissMessage = vm::dismissMessage)
}

@Composable
fun SwarmScreen(
    state: SwarmViewModel.UiState,
    onOptIn: (Boolean) -> Unit,
    onShowQr: () -> Unit,
    onInviteLink: () -> Unit,
    onScan: () -> Unit,
    onCopyInvite: () -> Unit,
    onLink: (String) -> Unit,
    onJoin: () -> Unit,
    onClosePairing: () -> Unit,
    onConfirm: (SasView, Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onHandOver: (String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") { ScreenTitle("Swarm", state.rosterLine) }
        item("message") { MessageBar(state.message, onDismissMessage) }

        items(state.sas, key = { "sas-" + it.handshakeId }) { s -> SasCard(s, onConfirm) }

        when (val p = state.pairing) {
            is Pairing.ShowInvite -> item("invite") { InviteCard(p, onCopyInvite, onClosePairing) }
            is Pairing.EnterLink -> item("enter") { EnterLinkCard(p, onLink, onJoin, onClosePairing) }
            Pairing.None -> item("pairing") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onShowQr, enabled = !state.busy) { Text("Show QR") }
                    OutlinedButton(onClick = onScan, enabled = !state.busy) { Text("Scan QR") }
                    OutlinedButton(onClick = onInviteLink, enabled = !state.busy) { Text("Invite link") }
                }
            }
        }

        if (state.isAdmin && state.pendingJoins.isNotEmpty()) {
            item("joins-title") { SectionTitle("Waiting for approval") }
            items(state.pendingJoins, key = { "join-" + it.nodeId }) { j -> JoinCard(j, onApprove, onRevoke) }
        }

        item("worker") {
            Column {
                SwitchRow("Share this phone when idle", state.eligibilityLine, state.optIn, enabled = state.wired, onChange = onOptIn)
                if (!state.wired) Text("The mesh is not running on this build.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item("tiles-title") { SectionTitle("Phones") }
        if (state.tiles.isEmpty()) item("tiles-empty") { EmptyState("No phones yet", "Show a QR code or send an invite link to pair the first one") }
        items(state.tiles, key = { "tile-" + it.nodeId }) { t -> PhoneTile(t, admin = state.isAdmin, onRevoke, onHandOver) }

        item("rules-title") { SectionTitle("When this phone works for others") }
        item("rules") { Column { SwarmViewModel.RULES.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
        item("bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PhoneTile(t: Tile, admin: Boolean, onRevoke: (String) -> Unit, onHandOver: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${t.name}, ${t.presence}, battery ${t.battery}, ${t.status}" },
        colors = CardDefaults.cardColors(containerColor = if (t.online) cs.surfaceVariant else cs.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (t.admin) Badge("admin", Tone.NEUTRAL)
                Badge(t.presence, if (t.online) Tone.OK else Tone.WARN)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("battery ${t.battery}", if (t.batteryWarn) Tone.WARN else Tone.NEUTRAL)
                Badge(t.thermal, if (t.thermalWarn) Tone.WARN else Tone.NEUTRAL)
                Badge("queue ${t.queueDepth}", Tone.NEUTRAL)
            }
            Spacer(Modifier.height(4.dp))
            Text(t.bigModel + (if (t.warm.isNotEmpty()) " · warm: ${t.warm.joinToString(", ")}" else ""), style = MaterialTheme.typography.bodySmall)
            t.cooldowns.forEach { Text("cooldown $it", style = MaterialTheme.typography.labelSmall, color = cs.error) }
            Text(t.status, style = MaterialTheme.typography.labelSmall, color = if (t.eligible) cs.primary else cs.onSurfaceVariant)
            if (admin && !t.self) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onRevoke(t.nodeId) }) { Text("Revoke") }
                TextButton(onClick = { onHandOver(t.nodeId) }) { Text("Hand over admin") }
            }
        }
    }
}

/** The 6 digits both phones show (spec section 6.2); Confirm on both pins the keys. */
@Composable
private fun SasCard(s: SasView, onConfirm: (SasView, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pairing with ${s.peerName}", style = MaterialTheme.typography.titleSmall)
            Text("Both phones must show the same digits", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(s.digits, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = "Digits ${s.digits.replace(" ", "")}" })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(s, true) }) { Text("They match") }
                OutlinedButton(onClick = { onConfirm(s, false) }) { Text("Different") }
            }
        }
    }
}

@Composable
private fun InviteCard(p: Pairing.ShowInvite, onCopy: () -> Unit, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (p.asLink) "Invite link sent" else "Scan this on the other phone", style = MaterialTheme.typography.titleSmall)
            Text("Valid until ${Format.clock(p.invite.expiresTs)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            QrImage(p.invite.link, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
            Spacer(Modifier.height(6.dp))
            Text(p.invite.link, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text("Copy link") }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
    }
}

@Composable
private fun EnterLinkCard(p: Pairing.EnterLink, onLink: (String) -> Unit, onJoin: () -> Unit, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Paste the invite link", style = MaterialTheme.typography.titleSmall)
            Text("No camera scanner on this build; paste the swarm://join link the other phone shared.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(p.link, onLink, Modifier.fillMaxWidth(), placeholder = { Text("swarm://join?g=…&k=…&t=…") }, isError = p.error != null, enabled = !p.busy)
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onJoin, enabled = !p.busy && p.link.isNotBlank()) { Text(if (p.busy) "Joining…" else "Join") }
                TextButton(onClick = onClose, enabled = !p.busy) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun JoinCard(j: JoinRequestView, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${j.name} asks to join", style = MaterialTheme.typography.titleSmall)
                Text(Format.clock(j.ts), style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = { onApprove(j.nodeId) }) { Text("Approve") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { onReject(j.nodeId) }) { Text("Reject") }
        }
    }
}

/** Draws [text] as a QR symbol with a 4-module quiet zone; falls back to the text when it does not fit version 10. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val symbol = remember(text) { QrCode.encode(text) }
    if (symbol == null) { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = modifier); return }
    Canvas(modifier.background(Color.White).semantics { contentDescription = "QR code for the invite link" }) {
        val n = symbol.size + 8
        val cell = minOf(size.width, size.height) / n
        for (r in 0 until symbol.size) for (c in 0 until symbol.size) if (symbol.get(r, c)) {
            drawRect(Color.Black, Offset((c + 4) * cell, (r + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
