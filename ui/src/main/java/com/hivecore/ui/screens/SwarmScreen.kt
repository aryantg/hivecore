package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Format
import com.hivecore.ui.Screen
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.Avatar
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.Capability
import com.hivecore.ui.design.HeroCard
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextField
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.InlineNotice
import com.hivecore.ui.design.ListRow
import com.hivecore.ui.design.OverflowMenu
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.StatRow
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.SwitchRow
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.listOrEmpty
import com.hivecore.ui.design.section
import com.hivecore.ui.ports.JoinRequestView
import com.hivecore.ui.ports.SasView
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
        onCopyInvite = vm::copyInvite, onShareInvite = vm::shareInvite, onLink = vm::setLink, onJoin = { vm.join() }, onClosePairing = vm::closePairing, onConfirm = vm::confirmSas,
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
    onShareInvite: () -> Unit,
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
    val self = state.tiles.firstOrNull { it.self }
    val others = state.tiles.filter { !it.self }
    val online = state.tiles.count { it.online }
    HiveScreen(
        Screen.SWARM, modifier,
        capability = Capability(HiveIcons.swarm, "Your swarm", "Phones paired end to end, with no server in between. Idle phones lend their NPU to the group.",
            Stat("$online of ${state.tiles.size.coerceAtLeast(1)}", "phones online", if (online > 1) Tone.Ok else Tone.Neutral, HiveIcons.phone),
            tryPrompt = "Compare invoice spend with revenue in Q3"),
        subtitle = state.rosterLine,
        message = state.message, onDismissMessage = onDismissMessage,
        primaryAction = if (state.pairing == Pairing.None && state.sas.isEmpty()) HiveAction("Pair a phone", HiveIcons.qr, enabled = !state.busy, onClick = onShowQr) else null,
    ) {
        items(state.sas.size, key = { "sas-" + state.sas[it].handshakeId }) { i -> SasCard(state.sas[i], onConfirm) }

        item("self") { ThisPhoneCard(self, state, onOptIn) }

        section("Pairing")
        when (val p = state.pairing) {
            is Pairing.ShowInvite -> item("invite") { InviteCard(p, onCopyInvite, onShareInvite, onClosePairing) }
            is Pairing.EnterLink -> item("enter") { EnterLinkCard(p, onLink, onJoin, onClosePairing) }
            Pairing.None -> item("pairing") {
                HiveCard {
                    Text("Same room: show a QR code and scan it on the other phone. Far away: send an invite link and read the six digits aloud.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(HiveSpacing.md))
                    ActionRow(HiveAction("Show QR", HiveIcons.qr, enabled = !state.busy, onClick = onShowQr), listOf(
                        HiveAction("Scan QR", HiveIcons.scan, enabled = !state.busy, onClick = onScan),
                        HiveAction("Invite link", HiveIcons.share, enabled = !state.busy, onClick = onInviteLink),
                    ))
                }
            }
        }

        if (state.isAdmin && state.pendingJoins.isNotEmpty()) {
            section("Waiting for approval")
            items(state.pendingJoins.size, key = { "join-" + state.pendingJoins[it].nodeId }) { i -> JoinCard(state.pendingJoins[i], onApprove, onRevoke) }
        }

        section("Phones", trailing = { StatusPill(Format.plural(others.size, "phone"), Tone.Neutral) })
        if (others.isEmpty()) listOrEmpty(emptyList<Tile>(), key = { it.nodeId }, emptyKey = "tiles-empty", emptyTitle = "No other phones yet",
            emptyDetail = "Show a QR code or send an invite link to pair the first one", emptyIcon = HiveIcons.phone) {}
        else items(others.chunked(2).size, key = { "row-" + others.chunked(2)[it].first().nodeId }) { i ->
            val pair = others.chunked(2)[i]
            Row(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
                pair.forEach { t -> PhoneTile(t, admin = state.isAdmin, onRevoke, onHandOver, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        section("When this phone works for others")
        item("rules") {
            HiveCard {
                SwarmViewModel.RULES.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { }) }
            }
        }
    }
}

@Composable
private fun ThisPhoneCard(t: Tile?, state: SwarmViewModel.UiState, onOptIn: (Boolean) -> Unit) {
    HiveCard(tone = CardTone.Tonal, contentPadding = HiveSpacing.hero) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(t?.name ?: "This phone", size = HiveSpacing.avatarLg, icon = HiveIcons.phone, online = t?.online)
            Spacer(Modifier.width(HiveSpacing.md))
            Column(Modifier.weight(1f)) {
                Text("This phone", style = MaterialTheme.typography.titleLarge)
                Text(t?.bigModel ?: "Profile not available yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t?.admin == true) StatusPill("admin", Tone.Accent)
        }
        if (t != null) {
            Spacer(Modifier.height(HiveSpacing.md))
            StatRow(listOf(
                Stat(t.battery, "battery", if (t.batteryWarn) Tone.Warn else Tone.Neutral),
                Stat(t.thermal.substringBefore(" ·"), "thermal", if (t.thermalWarn) Tone.Warn else Tone.Neutral),
                Stat(t.queueDepth.toString(), "queued"),
            ))
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        SwitchRow("Share this phone when idle", state.eligibilityLine, state.optIn, enabled = state.wired, onChange = onOptIn)
        if (!state.wired) InlineNotice("The mesh is not running on this build.", Tone.Warn, icon = HiveIcons.warning)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneTile(t: Tile, admin: Boolean, onRevoke: (String) -> Unit, onHandOver: (String) -> Unit, modifier: Modifier = Modifier) {
    HiveCard(modifier, enabled = t.online, contentPadding = HiveSpacing.cardTight,
        semantics = { contentDescription = "${t.name}, ${t.presence}, battery ${t.battery}, ${t.status}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(t.name, online = t.online)
            Spacer(Modifier.width(HiveSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.presence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (admin) OverflowMenu(listOf(
                HiveAction("Hand over admin", HiveIcons.swarm) { onHandOver(t.nodeId) },
                HiveAction("Revoke", HiveIcons.delete, destructive = true) { onRevoke(t.nodeId) },
            ), description = "Actions for ${t.name}")
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
            if (t.admin) StatusPill("admin", Tone.Accent)
            StatusPill(t.battery, if (t.batteryWarn) Tone.Warn else Tone.Neutral)
            StatusPill(t.thermal.substringBefore(" ·"), if (t.thermalWarn) Tone.Warn else Tone.Neutral)
        }
        Spacer(Modifier.height(HiveSpacing.xs))
        Text(t.bigModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        t.cooldowns.forEach { Text("cooldown $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        Text(t.status, style = MaterialTheme.typography.labelSmall, color = if (t.eligible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The 6 digits both phones show (spec section 6.2); Confirm on both pins the keys. */
@Composable
private fun SasCard(s: SasView, onConfirm: (SasView, Boolean) -> Unit) {
    HeroCard(
        headline = s.digits, headlineStyle = HiveTextStyles.digits, headlineDescription = "Digits ${s.digits.replace(" ", "")}",
        caption = "Pairing with ${s.peerName}. Both phones must show the same six digits.", tone = CardTone.Accent, icon = HiveIcons.lock,
        primary = HiveAction("They match", HiveIcons.check) { onConfirm(s, true) },
        secondaries = listOf(HiveAction("Different", HiveIcons.close) { onConfirm(s, false) }),
    )
}

@Composable
private fun InviteCard(p: Pairing.ShowInvite, onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    HiveCard(contentPadding = HiveSpacing.hero) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (p.asLink) "Invite link ready" else "Scan this on the other phone", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text("Valid until ${Format.clock(p.invite.expiresTs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(HiveSpacing.md))
            QrImage(p.invite.link, Modifier.fillMaxWidth(HiveSpacing.QR_FRACTION).aspectRatio(1f))
            Spacer(Modifier.height(HiveSpacing.sm))
            Text(p.invite.link, style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(HiveSpacing.md))
            ActionRow(secondaries = listOf(HiveAction("Copy", HiveIcons.copy, onClick = onCopy), HiveAction("Share", HiveIcons.share, onClick = onShare),
                HiveAction("Done", HiveIcons.close, onClick = onClose)), maxInline = 3)
        }
    }
}

@Composable
private fun EnterLinkCard(p: Pairing.EnterLink, onLink: (String) -> Unit, onJoin: () -> Unit, onClose: () -> Unit) {
    HiveCard {
        Text("Paste the invite link", style = MaterialTheme.typography.titleMedium)
        Text("Paste the swarm://join link the other phone shared.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(HiveSpacing.sm))
        HiveTextField(p.link, onLink, Modifier.fillMaxWidth(), placeholder = "swarm://join?g=…&k=…&t=…", isError = p.error != null, enabled = !p.busy, supporting = p.error)
        Spacer(Modifier.height(HiveSpacing.sm))
        ActionRow(HiveAction(if (p.busy) "Joining…" else "Join", HiveIcons.link, enabled = !p.busy && p.link.isNotBlank(), onClick = onJoin),
            listOf(HiveAction("Cancel", HiveIcons.close, enabled = !p.busy, onClick = onClose)))
    }
}

@Composable
private fun JoinCard(j: JoinRequestView, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    HiveCard(tone = CardTone.Warn) {
        ListRow("${j.name} asks to join", supporting = "asked at ${Format.clock(j.ts)}", leading = { Avatar(j.name) })
        ActionRow(HiveAction("Approve", HiveIcons.check) { onApprove(j.nodeId) }, listOf(HiveAction("Reject", HiveIcons.close, destructive = true) { onReject(j.nodeId) }))
    }
}

@HivePreviews
@Composable
private fun SwarmPreview() = HiveCoreTheme {
    val tiles = listOf(
        Tile("self", "This phone", true, true, "this phone", true, "80 % ⚡", false, "Cool · 31 °C", false, "qwen3-4b (big slot)", listOf("yolo11n"), 0, emptyList(), "Eligible", true),
        Tile("sales", "Sales", false, false, "online via LAN", true, "64 % ⚡", false, "Cool · 31 °C", false, "qwen3-4b (big slot)", emptyList(), 2, emptyList(), "Eligible · heavy work OK", true),
        Tile("accounts", "Accounts", false, false, "offline (last seen 48 s ago)", false, "31 %", true, "Hot · 36 °C", true, "no big model", emptyList(), 0, listOf("llm.json until 14:32"), "Not eligible: battery 31 %", false),
    )
    SwarmScreen(SwarmViewModel.UiState(wired = true, isAdmin = true, tiles = tiles, optIn = true, eligibleNow = true,
        roster = com.hivecore.ui.ports.RosterView("g1", 3, "self", listOf(com.hivecore.ui.ports.MemberView("self", "This phone", true)))),
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
}
