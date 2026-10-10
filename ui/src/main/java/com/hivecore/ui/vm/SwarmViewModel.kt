package com.hivecore.ui.vm

import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.WorkerPolicy
import com.hivecore.engine.device.Eligibility
import com.hivecore.ui.Format
import com.hivecore.ui.ports.InviteView
import com.hivecore.ui.ports.JoinRequestView
import com.hivecore.ui.ports.RosterView
import com.hivecore.ui.ports.SasView
import com.hivecore.ui.ports.SwarmPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Swarm screen (spec section 17): member tiles (name, how it is reached, battery, thermal, loaded big model, queue
 * depth, cooldowns), the admin's roster version, the pairing entry points (show QR, scan QR, send invite link,
 * confirm the 6 digits), the worker opt-in and the admin actions. Codes against [SwarmPort] and [WorkerPolicy].
 *
 * @param policy this phone's [WorkerPolicy]; null when the mesh is not wired on this build (the toggle explains).
 */
class SwarmViewModel(
    private val port: SwarmPort,
    private val policy: WorkerPolicy?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Tile(
        val nodeId: String,
        val name: String,
        val self: Boolean,
        val admin: Boolean,
        /** "online via LAN" / "offline (last seen 3 min ago)" */
        val presence: String,
        val online: Boolean,
        val battery: String,
        val batteryWarn: Boolean,
        val thermal: String,
        val thermalWarn: Boolean,
        /** "qwen3-4b (big slot)" / "no big model" */
        val bigModel: String,
        val warm: List<String>,
        val queueDepth: Int,
        /** "llm.json until 14:32" */
        val cooldowns: List<String>,
        /** "Eligible · heavy work OK" / "Not sharing" / "Not eligible: …" */
        val status: String,
        val eligible: Boolean,
    )

    /** The pairing sheet on screen. */
    sealed interface Pairing {
        data object None : Pairing
        data class ShowInvite(val invite: InviteView, val asLink: Boolean) : Pairing
        data class EnterLink(val link: String, val busy: Boolean, val error: String?) : Pairing
    }

    data class UiState(
        val wired: Boolean = false,
        val roster: RosterView = RosterView("", 0, "", emptyList()),
        val isAdmin: Boolean = false,
        val tiles: List<Tile> = emptyList(),
        val optIn: Boolean = false,
        val eligibleNow: Boolean = false,
        val reasons: List<String> = emptyList(),
        val pairing: Pairing = Pairing.None,
        /** the 6 digits waiting for Confirm, oldest first */
        val sas: List<SasView> = emptyList(),
        val pendingJoins: List<JoinRequestView> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
    ) {
        val rosterLine: String get() = if (roster.groupId.isEmpty()) "No swarm yet" else "Roster v${roster.version} · ${Format.plural(roster.members.size, "member")} · admin ${roster.nameOf(roster.adminId)}"
        val eligibilityLine: String get() = eligibilityLine(eligibleNow, reasons)
    }

    private val _state = MutableStateFlow(UiState(wired = policy != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---- integration lambdas (the app wires them) ----
    /** Opens the camera scanner; the result comes back through [join]. Null = no scanner on this build (paste the link). */
    var openScanner: (() -> Unit)? = null
    /** Shares the invite link through the system share sheet. */
    var shareLink: (String) -> Unit = { SLog.w(TAG, "shareLink not wired") }
    /** Copies the invite link to the clipboard. */
    var copyLink: (String) -> Unit = { SLog.w(TAG, "copyLink not wired") }
    /** Spec section 17: opting in requests the battery optimisation exemption. */
    var requestBatteryExemption: () -> Unit = { SLog.w(TAG, "requestBatteryExemption not wired") }

    init {
        scope.launch { port.roster.collect { r -> _state.update { it.copy(roster = r, isAdmin = r.adminId == port.selfId) }; refresh() } }
        scope.launch { port.peers.collect { refresh() } }
        scope.launch { port.sasPrompts.collect { s -> _state.update { it.copy(sas = s) } } }
        scope.launch { port.pendingJoins.collect { j -> _state.update { it.copy(pendingJoins = j) } } }
        if (policy != null) {
            scope.launch { policy.profile.collect { refresh() } }
            scope.launch { policy.optIn.collect { o -> _state.update { it.copy(optIn = o) } } }
        }
    }

    /** Rebuilds the tiles from the latest profiles (the screen also calls this every few seconds while visible). */
    fun refresh() {
        val now = clock()
        val roster = port.roster.value
        val mine = policy?.profile?.value?.copy(nodeId = port.selfId)
        val peers = port.peers.value.filter { it.nodeId != port.selfId }
            .sortedWith(compareByDescending<Profile> { isOnline(it, now) }.thenBy { roster.nameOf(it.nodeId).lowercase(Locale.ROOT) })
        val known = (listOfNotNull(mine) + peers).map { it.nodeId }.toSet()
        val absent = roster.members.filter { it.nodeId !in known && it.nodeId != port.selfId }
        val tiles = listOfNotNull(mine?.let { tile(it, true, roster, now) }) + peers.map { tile(it, false, roster, now) } +
            absent.map { m -> Tile(m.nodeId, m.name, false, m.admin, "offline (no profile yet)", false, "—", false, "—", false, "—", emptyList(), 0, emptyList(), "Not seen", false) }
        val reasons = mine?.let { reasons(it) } ?: listOf("this phone's profile is not available yet")
        val eligible = mine?.eligible == true
        _state.update { it.copy(tiles = tiles, optIn = policy?.optIn?.value ?: false, eligibleNow = eligible, reasons = if (eligible) emptyList() else reasons) }
    }

    fun setOptIn(on: Boolean) {
        val p = policy ?: run { _state.update { it.copy(message = "The swarm is not running on this phone yet") }; return }
        SLog.d(TAG, "worker opt-in -> $on")
        p.setOptIn(on)
        if (on) runCatching { requestBatteryExemption() }.onFailure { SLog.e(TAG, "battery exemption request failed", it) }
        _state.update { it.copy(optIn = on) }
        refresh()
    }

    // ---- pairing (spec section 6.2 and the section 17 flows) ----

    /** Show QR (asLink = false) or send an invite link (asLink = true). */
    fun showInvite(asLink: Boolean) = action("invite") {
        port.createInvite().map { inv -> _state.update { it.copy(pairing = Pairing.ShowInvite(inv, asLink)) }; if (asLink) shareLink(inv.link) }
    }

    fun copyInvite() { (_state.value.pairing as? Pairing.ShowInvite)?.let { copyLink(it.invite.link); _state.update { s -> s.copy(message = "Invite link copied") } } }

    /** Scan QR: the camera scanner when the build has one, else the paste-a-link sheet. */
    fun scan() {
        val s = openScanner
        if (s != null) runCatching { s() }.onFailure { e -> _state.update { it.copy(message = "Scanner failed: ${e.message}") } }
        else _state.update { it.copy(pairing = Pairing.EnterLink("", false, null)) }
    }

    fun setLink(text: String) { _state.update { s -> s.copy(pairing = (s.pairing as? Pairing.EnterLink)?.copy(link = text, error = null) ?: s.pairing) } }

    /** Joins with a scanned or pasted `swarm://join?...` link; the SAS then appears in [UiState.sas]. */
    fun join(link: String = (_state.value.pairing as? Pairing.EnterLink)?.link.orEmpty()) {
        val l = link.trim()
        if (!l.startsWith("swarm://join?")) { _state.update { s -> s.copy(pairing = Pairing.EnterLink(l, false, "That is not an invite link (swarm://join?…)")) }; return }
        _state.update { s -> s.copy(pairing = Pairing.EnterLink(l, true, null)) }
        scope.launch {
            val r = try { port.join(l) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            r.onSuccess { sas -> SLog.d(TAG, "join: SAS from ${sas.peerName}"); _state.update { it.copy(pairing = Pairing.None, message = "Compare the 6 digits with ${sas.peerName}") } }
                .onFailure { e -> SLog.e(TAG, "join failed", e); _state.update { s -> s.copy(pairing = Pairing.EnterLink(l, false, "Could not join: ${e.message ?: e.javaClass.simpleName}")) } }
        }
    }

    /** Confirm (true) or reject (false) one 6-digit prompt. */
    fun confirmSas(sas: SasView, matches: Boolean) = action("confirm") {
        port.confirmSas(sas.handshakeId, matches).map { _state.update { it.copy(message = if (matches) "Paired with ${sas.peerName}" else "Rejected ${sas.peerName}") } }
    }

    fun closePairing() { _state.update { it.copy(pairing = Pairing.None) } }

    // ---- admin (spec section 6.4) ----
    fun approve(nodeId: String) = action("approve") {
        val n = _state.value.pendingJoins.firstOrNull { it.nodeId == nodeId }?.name ?: _state.value.roster.nameOf(nodeId)
        port.approve(nodeId).map { _state.update { it.copy(message = "Approved $n") } }
    }
    fun revoke(nodeId: String) = action("revoke") { val n = _state.value.roster.nameOf(nodeId); port.revoke(nodeId).map { _state.update { it.copy(message = "Revoked $n") } } }
    fun handOver(nodeId: String) = action("hand over") { port.handOver(nodeId).map { _state.update { it.copy(message = "${_state.value.roster.nameOf(nodeId)} is the admin now") } } }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun action(what: String, block: suspend () -> Result<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            val r = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            r.onFailure { e -> SLog.e(TAG, "$what failed", e); _state.update { it.copy(message = "Could not $what: ${e.message ?: e.javaClass.simpleName}") } }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun tile(p: Profile, self: Boolean, roster: RosterView, now: Long): Tile {
        val online = self || isOnline(p, now)
        val r = reasons(p)
        val big = p.big.takeIf { it != "none" }
        return Tile(
            nodeId = p.nodeId, name = if (self) "This phone" else roster.nameOf(p.nodeId), self = self, admin = p.nodeId == roster.adminId,
            presence = if (self) "this phone" else if (online) "online via ${Format.linkWord(p.link?.kind)}" else "offline (last seen ${Format.ago(now - p.ts)})",
            online = online,
            battery = "${p.batteryPct} %" + if (p.charging) " ⚡" else "",
            batteryWarn = p.batteryPct < Eligibility.MIN_BATTERY_PCT && !p.charging,
            thermal = Format.thermalWord(p.thermal) + (Format.temperature(p.batteryTempC)?.let { " · $it" } ?: ""),
            thermalWarn = p.thermal > Eligibility.MAX_THERMAL || (p.batteryTempC ?: 0f) >= Eligibility.MAX_BATTERY_TEMP_C,
            bigModel = big?.let { kind -> (p.capabilities.firstOrNull { c -> c.warm && c.capability.startsWith(if (kind == "llm") "llm" else kind) }?.modelId ?: kind) + " (big slot)" } ?: "no big model",
            warm = p.warm,
            queueDepth = p.queueDepth,
            cooldowns = p.cooldowns.filter { it.untilTs > now }.map { "${it.capability} until ${Format.clock(it.untilTs)}" },
            status = when {
                p.eligible -> if (p.heavyOk) "Eligible · heavy work OK" else "Eligible"
                !p.workerOptIn -> "Not sharing"
                else -> "Not eligible: " + r.joinToString("; ")
            },
            eligible = p.eligible,
        )
    }

    companion object {
        private const val TAG = "SwarmVM"
        /** Spec section 5.5: stale after three missed beats (45 s LAN, 90 s WAN). */
        const val STALE_LAN_MS = 45_000L
        const val STALE_WAN_MS = 90_000L

        fun isOnline(p: Profile, now: Long): Boolean {
            val wan = p.link?.kind == com.hivecore.core.LinkKind.WAN_DIRECT || p.link?.kind == com.hivecore.core.LinkKind.WAN_RELAY
            return now - p.ts <= if (wan) STALE_WAN_MS else STALE_LAN_MS
        }

        /** The worker-mode rules, spelled out (spec section 4.5). */
        val RULES: List<String> = listOf(
            "You turned on sharing (off by default). Turning it on asks to exempt this app from battery optimisation.",
            "The phone is idle: screen off for at least ${Eligibility.SCREEN_OFF_IDLE_MS / 1000} s, or on the charger with this app in the background.",
            "Battery at ${Eligibility.MIN_BATTERY_PCT} % or more, or charging.",
            "The phone is cool: thermal ${Format.thermalWord(Eligibility.MAX_THERMAL).lowercase(Locale.ROOT)} or better and battery under ${Eligibility.MAX_BATTERY_TEMP_C.toInt()} °C.",
            "Not in a call.",
            "Heavy work (image generation, upscaling, long audio) also needs the charger.",
            "You pick the phone up: queued work for others stops at once.",
        )

        /** Why [p] is not eligible right now, in the order of [RULES]; empty when every rule holds. */
        fun reasons(p: Profile): List<String> = buildList {
            if (!p.workerOptIn) add("sharing is off")
            if (!p.idle) add(if (p.charging) "the app is open (on the charger it must be in the background)" else "the phone is in use (needs the screen off for ${Eligibility.SCREEN_OFF_IDLE_MS / 1000} s, or the charger)")
            if (p.batteryPct < Eligibility.MIN_BATTERY_PCT && !p.charging) add("battery ${p.batteryPct} % (needs ${Eligibility.MIN_BATTERY_PCT} % or the charger)")
            if (p.thermal > Eligibility.MAX_THERMAL) add("the phone is ${Format.thermalWord(p.thermal).lowercase(Locale.ROOT)} (thermal ${p.thermal}, needs ${Eligibility.MAX_THERMAL} or less)")
            val t = p.batteryTempC
            if (t != null && t >= Eligibility.MAX_BATTERY_TEMP_C) add("battery at ${Format.temperature(t)} (needs under ${Eligibility.MAX_BATTERY_TEMP_C.toInt()} °C)")
            if (p.inCall) add("in a call")
        }

        fun eligibilityLine(eligible: Boolean, reasons: List<String>): String = when {
            eligible -> "Eligible now: other phones' work can run here."
            reasons.isEmpty() -> "Not eligible now."
            else -> "Not eligible now because " + reasons.joinToString("; ") + "."
        }
    }
}
