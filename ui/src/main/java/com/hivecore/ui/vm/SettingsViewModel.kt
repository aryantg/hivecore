package com.hivecore.ui.vm

import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.SLog
import com.hivecore.core.NodeRole
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.DemoKeys
import com.hivecore.ui.ports.DemoPort
import com.hivecore.ui.ports.PermissionItem
import com.hivecore.ui.ports.ResetOptions
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.SettingsKeys
import com.hivecore.ui.ports.SettingsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings screen (spec section 17): cloud policy, OpenRouter key (write-only), per-job and monthly caps in rupees,
 * schema sharing for the cloud planner, mobile data for blobs, relay address, heartbeat and deadline tuning, export
 * ledger, forget a peer. Plain settings persist through [KeyValuePrefs] under [SettingsKeys]; the key, the ledger
 * export and the peers go through [SettingsPort].
 */
class SettingsViewModel(
    private val prefs: KeyValuePrefs,
    private val port: SettingsPort,
    private val scope: CoroutineScope,
    /** the presenter's controls; null hides the Demo group */
    private val demo: DemoPort? = null,
) {
    /** Settings > Demo: everything a presenter flips before or during a demo. */
    data class DemoState(
        val debugBuild: Boolean,
        val demoMode: Boolean,
        val speedX10: Int,
        val role: NodeRole,
        val autoApprove: Boolean,
        val keepScreenOn: Boolean,
        val largeText: Boolean,
        val hideHashes: Boolean,
        val permissions: List<PermissionItem> = emptyList(),
        /** "Seeding SALES…" while a demo action runs */
        val working: String? = null,
        val reset: ResetOptions = ResetOptions(),
    ) {
        val missingPermissions: Int get() = permissions.count { !it.granted }
    }

    data class UiState(
        val policy: CloudPolicy = CloudPolicy.ASK,
        val hasKey: Boolean = false,
        /** "…a1b2" or null; the full key is never shown back */
        val keyHint: String? = null,
        val keyDraft: String = "",
        val capJobInr: Int = SettingsKeys.DEFAULT_CAP_JOB_INR,
        val capMonthInr: Int = SettingsKeys.DEFAULT_CAP_MONTH_INR,
        val schemaSharing: Boolean = false,
        val mobileDataBlobs: Boolean = false,
        val relayAddress: String = "",
        val heartbeatLanS: Int = SettingsKeys.DEFAULT_HEARTBEAT_LAN_S,
        val heartbeatWanS: Int = SettingsKeys.DEFAULT_HEARTBEAT_WAN_S,
        val deadlineFloorS: Int = SettingsKeys.DEFAULT_DEADLINE_FLOOR_S,
        /** `3 × expected`; stored ×10 so prefs can hold it as an Int */
        val deadlineFactor: Double = SettingsKeys.DEFAULT_DEADLINE_FACTOR_X10 / 10.0,
        val advanced: Boolean = false,
        val peers: List<MemberView> = emptyList(),
        val busy: Boolean = false,
        val lastExport: String? = null,
        val message: String? = null,
        val demo: DemoState? = null,
    ) {
        val keyLine: String get() = if (hasKey) "Key stored (ends $keyHint)" else "No key: the CLOUD option is unavailable"
        val canSaveKey: Boolean get() = keyDraft.isNotBlank()
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun load(): UiState = UiState(
        policy = CloudPolicy.of(prefs.getString(SettingsKeys.CLOUD_POLICY, null)),
        hasKey = port.hasKey(), keyHint = port.keyHint(),
        capJobInr = prefs.getInt(SettingsKeys.CAP_JOB_INR, SettingsKeys.DEFAULT_CAP_JOB_INR),
        capMonthInr = prefs.getInt(SettingsKeys.CAP_MONTH_INR, SettingsKeys.DEFAULT_CAP_MONTH_INR),
        schemaSharing = prefs.getBoolean(SettingsKeys.SCHEMA_SHARING, false),
        mobileDataBlobs = prefs.getBoolean(SettingsKeys.MOBILE_DATA_BLOBS, false),
        relayAddress = prefs.getString(SettingsKeys.RELAY_ADDRESS, "").orEmpty(),
        heartbeatLanS = prefs.getInt(SettingsKeys.HEARTBEAT_LAN_S, SettingsKeys.DEFAULT_HEARTBEAT_LAN_S),
        heartbeatWanS = prefs.getInt(SettingsKeys.HEARTBEAT_WAN_S, SettingsKeys.DEFAULT_HEARTBEAT_WAN_S),
        deadlineFloorS = prefs.getInt(SettingsKeys.DEADLINE_FLOOR_S, SettingsKeys.DEFAULT_DEADLINE_FLOOR_S),
        deadlineFactor = prefs.getInt(SettingsKeys.DEADLINE_FACTOR_X10, SettingsKeys.DEFAULT_DEADLINE_FACTOR_X10) / 10.0,
        peers = runCatching { port.knownPeers() }.getOrDefault(emptyList()),
        demo = demo?.let { d ->
            DemoState(
                debugBuild = d.debugBuild,
                demoMode = prefs.getBoolean(DemoKeys.DEMO_MODE, false),
                speedX10 = prefs.getInt(DemoKeys.DEMO_SPEED_X10, 10),
                role = NodeRole.entries.firstOrNull { it.name == prefs.getString(DemoKeys.NODE_ROLE, null) } ?: NodeRole.SALES,
                autoApprove = prefs.getBoolean(DemoKeys.DEBUG_AUTO_APPROVE, false),
                keepScreenOn = prefs.getBoolean(DemoKeys.KEEP_SCREEN_ON, false),
                largeText = prefs.getBoolean(DemoKeys.LARGE_TEXT, false),
                hideHashes = prefs.getBoolean(DemoKeys.HIDE_HASHES, false),
                permissions = d.permissions.value,
            )
        },
    )

    /** Photos generated from the Demo group land here (the app adds them to the Jobs screen and opens it). */
    var onPhotosReady: (List<String>) -> Unit = { SLog.w(TAG, "onPhotosReady not wired") }

    init {
        demo?.let { d -> scope.launch { d.permissions.collect { p -> _state.update { s -> s.copy(demo = s.demo?.copy(permissions = p)) } } } }
    }

    /** Re-reads everything (peers may have changed on the Swarm screen). */
    fun refresh() {
        demo?.refreshPermissions()
        _state.update { s -> load().copy(keyDraft = s.keyDraft, advanced = s.advanced, lastExport = s.lastExport, message = s.message,
            demo = load().demo?.copy(working = s.demo?.working, reset = s.demo?.reset ?: ResetOptions())) }
    }

    fun setPolicy(p: CloudPolicy) { prefs.putString(SettingsKeys.CLOUD_POLICY, p.key); _state.update { it.copy(policy = p) }; SLog.d(TAG, "cloud policy=${p.key}") }

    // ---- key (spec section 15.7): write-only ----
    fun setKeyDraft(text: String) { _state.update { it.copy(keyDraft = text) } }
    fun saveKey() {
        val k = _state.value.keyDraft.trim()
        if (k.isEmpty()) return
        port.setKey(k)
            .onSuccess { _state.update { it.copy(keyDraft = "", hasKey = port.hasKey(), keyHint = port.keyHint(), message = "OpenRouter key stored") }; SLog.d(TAG, "key stored") }
            .onFailure { e -> _state.update { it.copy(message = "Key not stored: ${e.message ?: e.javaClass.simpleName}") } }
    }
    fun clearKey() { port.clearKey(); _state.update { it.copy(keyDraft = "", hasKey = false, keyHint = null, message = "OpenRouter key removed") } }

    // ---- caps (rupees, whole numbers; 0 = no cloud spend) ----
    fun setCapJob(inr: Int) { val v = inr.coerceIn(0, 100_000); prefs.putInt(SettingsKeys.CAP_JOB_INR, v); _state.update { it.copy(capJobInr = v) } }
    fun setCapMonth(inr: Int) { val v = inr.coerceIn(0, 1_000_000); prefs.putInt(SettingsKeys.CAP_MONTH_INR, v); _state.update { it.copy(capMonthInr = v) } }

    fun setSchemaSharing(on: Boolean) { prefs.putBoolean(SettingsKeys.SCHEMA_SHARING, on); _state.update { it.copy(schemaSharing = on) } }
    fun setMobileDataBlobs(on: Boolean) { prefs.putBoolean(SettingsKeys.MOBILE_DATA_BLOBS, on); _state.update { it.copy(mobileDataBlobs = on) } }
    fun setRelayAddress(text: String) { val v = text.trim(); prefs.putString(SettingsKeys.RELAY_ADDRESS, v.ifEmpty { null }); _state.update { it.copy(relayAddress = v) } }

    // ---- advanced ----
    fun setAdvanced(on: Boolean) { _state.update { it.copy(advanced = on) } }
    fun setHeartbeatLan(s: Int) { val v = s.coerceIn(5, 120); prefs.putInt(SettingsKeys.HEARTBEAT_LAN_S, v); _state.update { it.copy(heartbeatLanS = v) } }
    fun setHeartbeatWan(s: Int) { val v = s.coerceIn(10, 300); prefs.putInt(SettingsKeys.HEARTBEAT_WAN_S, v); _state.update { it.copy(heartbeatWanS = v) } }
    fun setDeadlineFloor(s: Int) { val v = s.coerceIn(1, 120); prefs.putInt(SettingsKeys.DEADLINE_FLOOR_S, v); _state.update { it.copy(deadlineFloorS = v) } }
    fun setDeadlineFactor(x: Double) { val v = (x * 10).toInt().coerceIn(10, 100); prefs.putInt(SettingsKeys.DEADLINE_FACTOR_X10, v); _state.update { it.copy(deadlineFactor = v / 10.0) } }
    fun resetTuning() {
        setHeartbeatLan(SettingsKeys.DEFAULT_HEARTBEAT_LAN_S); setHeartbeatWan(SettingsKeys.DEFAULT_HEARTBEAT_WAN_S)
        setDeadlineFloor(SettingsKeys.DEFAULT_DEADLINE_FLOOR_S); setDeadlineFactor(SettingsKeys.DEFAULT_DEADLINE_FACTOR_X10 / 10.0)
        _state.update { it.copy(message = "Tuning reset to the spec defaults") }
    }

    // ---- actions ----
    fun exportLedger() = action("export the ledger") { port.exportLedger().map { path -> _state.update { it.copy(lastExport = path, message = "Ledger written to $path") } } }
    fun forgetPeer(nodeId: String) = action("forget the peer") {
        val name = _state.value.peers.firstOrNull { it.nodeId == nodeId }?.name ?: nodeId.take(8)
        port.forgetPeer(nodeId).map { _state.update { it.copy(peers = runCatching { port.knownPeers() }.getOrDefault(it.peers.filterNot { p -> p.nodeId == nodeId }), message = "Forgot $name") } }
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    // ---- demo (Settings > Demo) ----
    private fun patchDemo(f: (DemoState) -> DemoState) { _state.update { s -> s.copy(demo = s.demo?.let(f)) } }

    /** Switches between the real swarm and the scripted one; the app restarts so the graph is rebuilt. */
    fun setDemoMode(on: Boolean) {
        prefs.putBoolean(DemoKeys.DEMO_MODE, on); patchDemo { it.copy(demoMode = on) }
        SLog.d(TAG, "demo mode -> $on"); demo?.restart()
    }
    fun setDemoSpeed(x10: Int) { prefs.putInt(DemoKeys.DEMO_SPEED_X10, x10); patchDemo { it.copy(speedX10 = x10) } }
    fun setAutoApprove(on: Boolean) { prefs.putBoolean(DemoKeys.DEBUG_AUTO_APPROVE, on); patchDemo { it.copy(autoApprove = on) } }
    fun setKeepScreenOn(on: Boolean) { prefs.putBoolean(DemoKeys.KEEP_SCREEN_ON, on); patchDemo { it.copy(keepScreenOn = on) } }
    fun setLargeText(on: Boolean) { prefs.putBoolean(DemoKeys.LARGE_TEXT, on); patchDemo { it.copy(largeText = on) } }
    fun setHideHashes(on: Boolean) { prefs.putBoolean(DemoKeys.HIDE_HASHES, on); patchDemo { it.copy(hideHashes = on) } }
    fun setResetOptions(o: ResetOptions) { patchDemo { it.copy(reset = o) } }

    fun setRole(role: NodeRole) { patchDemo { it.copy(role = role) }; demoAction("Seeding ${role.name.lowercase()}…") { it.setRoleAndSeed(role) } }
    fun seedAll() = demoAction("Seeding every role…") { it.seedAll() }
    fun previewApproval() = demoAction("Sending a sample approval request…") { it.previewApproval() }
    fun previewConsent() = demoAction("Opening the consent sheet…") { it.previewConsent() }
    fun generatePhotos(n: Int) = demoAction("Drawing $n sample photos…") { d -> d.generateSamplePhotos(n).map { paths -> onPhotosReady(paths); "${paths.size} sample photos are ready on the Jobs page" } }
    fun resetDemo() {
        val o = _state.value.demo?.reset ?: return
        demoAction("Resetting…") { d -> d.resetDemoState(o).onSuccess { d.restart() } }
    }
    fun openPermission(id: String) { demo?.openPermission(id) }
    fun refreshPermissions() { demo?.refreshPermissions() }

    private fun demoAction(working: String, block: suspend (DemoPort) -> Result<String>) {
        val d = demo ?: return
        if (_state.value.demo?.working != null) return
        patchDemo { it.copy(working = working) }
        scope.launch {
            val r = try { block(d) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            r.onSuccess { m -> _state.update { it.copy(message = m) } }
                .onFailure { e -> SLog.e(TAG, "demo action failed", e); _state.update { it.copy(message = "Demo action failed: ${e.message ?: e.javaClass.simpleName}") } }
            patchDemo { it.copy(working = null) }
        }
    }

    private fun action(what: String, block: suspend () -> Result<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            val r = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            r.onFailure { e -> SLog.e(TAG, "$what failed", e); _state.update { it.copy(message = "Could not $what: ${e.message ?: e.javaClass.simpleName}") } }
            _state.update { it.copy(busy = false) }
        }
    }

    companion object { private const val TAG = "SettingsVM" }
}
