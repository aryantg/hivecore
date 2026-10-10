package com.hivecore.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.hivecore.core.Inbound
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Offline phone-to-phone mesh over Nearby Connections (plan 10). Flat full mesh of <= 3 phones: every phone
 * advertises and discovers, and requests a connection to every endpoint it finds. Structure ported from Hush
 * `NearbyLink.kt`, which ran on these iQOO phones: P2P_CLUSTER, `ConnectionType.NON_DISRUPTIVE` (no Bluetooth to
 * Wi-Fi hotspot upgrade, which stalled links 2.5-4 s at a time), retry every 3 s, 8001/8002 harmless, bandwidth
 * changes logged.
 *
 * Trust at the link level is only a gate; MeshRouter verifies the HELLO signature afterwards.
 * - endpoint name = `<role>|<first 8 hex of nodeId>`; the caller passes it as `localName` to [start]
 * - pairing mode off: accept when [isPinnedPrefix] says the name's nodeId prefix is pinned, else reject
 * - pairing mode on: raise a [PairingRequest] with the auth digits and wait for [acceptPairing]
 *
 * Permissions (BLUETOOTH_SCAN/ADVERTISE/CONNECT, NEARBY_WIFI_DEVICES) must be granted before [start]; a
 * permission failure is surfaced in [status] and retried, not thrown.
 */
class NearbyTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val isPinnedPrefix: (String) -> Boolean,
) : Transport, Disconnectable {
    companion object {
        const val NAME = "nearby"
        const val SERVICE_ID = "com.hivecore.v1"
        private const val TAG = "Nearby"
        private const val RETRY_MS = 3_000L

        /** `HR|a1b2c3d4` -> `a1b2c3d4`; a name without the separator yields the whole name. */
        fun nodePrefixOf(endpointName: String): String = endpointName.substringAfter('|', endpointName)

        /**
         * One side of each pair asks for the link: the phone whose nodeId prefix sorts first. Two phones requesting
         * each other at the same moment fail with STATUS_ENDPOINT_IO_ERROR (8012) again and again (measured on two
         * iQOO 15 when pairing mode turned on on both). The other side still asks after [FALLBACK_MS] in case the
         * initiator cannot see it (discovery is not always symmetric).
         */
        fun initiates(localName: String, remoteName: String): Boolean = nodePrefixOf(localName) < nodePrefixOf(remoteName)

        const val FALLBACK_MS = 8_000L
    }

    override val name: String = NAME

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val strategy = Strategy.P2P_CLUSTER

    private val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 16)
    override val pairingRequests: SharedFlow<PairingRequest> = pairingFlow
    private val statusState = MutableStateFlow("stopped")
    override val status: StateFlow<String> = statusState

    // All maps are touched on the main thread (Nearby callbacks) or under [lock] from send/acceptPairing.
    private val lock = Any()
    private var localName = ""
    @Volatile private var stopped = true
    @Volatile private var pairingMode = false
    private var advertising = false
    private var discovering = false
    private var lastError: String? = null
    /** endpointId → advertised name of endpoints discovered and still in range. */
    private val seen = LinkedHashMap<String, String>()
    /** endpointId → name while a connection request / handshake is in flight. */
    private val pending = LinkedHashMap<String, String>()
    /** endpointId → name of connected peers. */
    private val peers = LinkedHashMap<String, String>()
    /** endpointId → request awaiting the owner's Accept while pairing mode is on. */
    private val awaitingPairing = LinkedHashMap<String, PairingRequest>()

    override suspend fun start(localName: String) {
        this.localName = localName
        if (!stopped) { SLog.d(TAG, "start ignored: already running as $localName"); return }
        stopped = false
        lastError = null
        SLog.d(TAG, "starting as $localName (service $SERVICE_ID, P2P_CLUSTER, NON_DISRUPTIVE)")
        main.post { advertise(); discover() }
        refreshStatus()
    }

    override suspend fun stop() {
        if (stopped) return
        stopped = true
        SLog.d(TAG, "stopping")
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (e: Exception) {
            SLog.d(TAG, "stop threw $e")
        }
        synchronized(lock) {
            advertising = false; discovering = false
            seen.clear(); pending.clear(); peers.clear(); awaitingPairing.clear()
        }
        connectedState.value = emptyList()
        statusState.value = "stopped"
    }

    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        if (stopped) return Result.failure(IllegalStateException("Nearby transport is stopped"))
        if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            return Result.failure(IllegalArgumentException("payload of ${bytes.size} B exceeds ${ConnectionsClient.MAX_BYTES_DATA_SIZE}"))
        }
        val id = endpoint.endpointId
        if (synchronized(lock) { !peers.containsKey(id) }) return Result.failure(IllegalStateException("not connected to ${endpoint.displayName} ($id)"))
        return suspendCancellableCoroutine { cont ->
            client.sendPayload(id, Payload.fromBytes(bytes))
                .addOnSuccessListener { if (cont.isActive) cont.resume(Result.success(Unit)) }
                .addOnFailureListener { e ->
                    SLog.w(TAG, "sendPayload to ${endpoint.displayName} failed: ${describe(e)}")
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
        }
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) {
        val id = req.endpoint.endpointId
        val known = synchronized(lock) { awaitingPairing.remove(id) }
        if (known == null) { SLog.w(TAG, "acceptPairing($id, $accept): no pending request (timed out or already decided)"); return }
        if (accept) {
            SLog.d(TAG, "owner accepted pairing with ${req.endpoint.displayName} ($id), digits ${req.digits}")
            client.acceptConnection(id, payloads).addOnFailureListener { e -> SLog.w(TAG, "acceptConnection $id failed: ${describe(e)}") }
        } else {
            SLog.d(TAG, "owner rejected pairing with ${req.endpoint.displayName} ($id)")
            synchronized(lock) { pending.remove(id) }
            client.rejectConnection(id).addOnFailureListener { e -> SLog.d(TAG, "rejectConnection $id failed: ${describe(e)}") }
        }
    }

    /** Drops the link to [endpoint] (the router saw an unpinned HELLO on it outside pairing mode). */
    override fun disconnect(endpoint: PeerEndpoint) {
        val id = endpoint.endpointId
        synchronized(lock) { peers.remove(id); pending.remove(id) }
        try { client.disconnectFromEndpoint(id) } catch (e: Exception) { SLog.d(TAG, "disconnect $id threw $e") }
        SLog.d(TAG, "disconnected ${endpoint.displayName} ($id)")
        refreshStatus()
    }

    override fun setPairingMode(on: Boolean) {
        pairingMode = on
        SLog.d(TAG, "pairing mode ${if (on) "on" else "off"}")
        if (!on) {
            // Requests nobody answered die with pairing mode.
            val stale = synchronized(lock) { awaitingPairing.keys.toList().also { awaitingPairing.clear() } }
            stale.forEach { id ->
                synchronized(lock) { pending.remove(id) }
                client.rejectConnection(id)
            }
        } else if (!stopped) {
            // A link rejected while pairing mode was off is not retried by itself (8004 is final): ask again now
            // for every phone in range, or two phones that saw each other first have to rediscover to pair.
            main.post {
                val ids = synchronized(lock) { seen.keys.filter { !peers.containsKey(it) } }
                if (ids.isNotEmpty()) SLog.d(TAG, "pairing mode on: re-requesting ${ids.size} endpoint(s) in range")
                ids.forEach { requestOrWait(it) }
            }
        }
        refreshStatus()
    }

    // ---- advertising / discovery ----------------------------------------------------------------------------------

    private fun advertise() {
        if (stopped || advertising) return
        val options = AdvertisingOptions.Builder().setStrategy(strategy).setConnectionType(ConnectionType.NON_DISRUPTIVE).build()
        client.startAdvertising(localName, SERVICE_ID, lifecycle, options)
            .addOnSuccessListener { advertising = true; lastError = null; SLog.d(TAG, "advertising as $localName"); refreshStatus() }
            .addOnFailureListener { e ->
                val code = codeOf(e)
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) { advertising = true; refreshStatus(); return@addOnFailureListener }
                SLog.w(TAG, "advertising FAILED: ${describe(e)}")
                noteError("advertising", e)
                retry { advertise() }
            }
    }

    private fun discover() {
        if (stopped || discovering) return
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(SERVICE_ID, discovery, options)
            .addOnSuccessListener { discovering = true; SLog.d(TAG, "discovering"); refreshStatus() }
            .addOnFailureListener { e ->
                val code = codeOf(e)
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) { discovering = true; refreshStatus(); return@addOnFailureListener }   // 8002: harmless
                SLog.w(TAG, "discovery FAILED: ${describe(e)}")
                noteError("discovery", e)
                retry { discover() }
            }
    }

    /** Retry with 0-1.5 s of jitter so two phones that collided do not collide again on the same beat. */
    private fun retryJittered(block: () -> Unit) {
        if (!stopped) main.postDelayed({ if (!stopped) block() }, RETRY_MS + (0L..1_500L).random())
    }

    private fun retry(block: () -> Unit) {
        if (!stopped) main.postDelayed({ if (!stopped) block() }, RETRY_MS)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val adv = info.endpointName
            SLog.d(TAG, "found $adv ($endpointId)")
            if (adv == localName) return
            synchronized(lock) { seen[endpointId] = adv }
            requestOrWait(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            val adv = synchronized(lock) { seen.remove(endpointId) }
            if (adv != null) SLog.d(TAG, "lost $adv ($endpointId)")
        }
    }

    /** [initiates] decides who asks; the other side asks only if no link or handshake exists after [FALLBACK_MS]. */
    private fun requestOrWait(endpointId: String) {
        val adv = synchronized(lock) { seen[endpointId] } ?: return
        if (initiates(localName, adv)) { requestConnection(endpointId); return }
        SLog.d(TAG, "$adv initiates; waiting ${FALLBACK_MS / 1000} s before asking ourselves")
        main.postDelayed({ if (!stopped) requestConnection(endpointId) }, FALLBACK_MS + (0L..2_000L).random())
    }

    /** Flat mesh: ask for a link to every endpoint in range that we are not already linked to. */
    private fun requestConnection(endpointId: String) {
        if (stopped) return
        val adv = synchronized(lock) {
            if (peers.containsKey(endpointId) || pending.containsKey(endpointId)) return
            seen[endpointId] ?: return
        }
        synchronized(lock) { pending[endpointId] = adv }
        SLog.d(TAG, "requesting connection to $adv ($endpointId)")
        val options = ConnectionOptions.Builder().setConnectionType(ConnectionType.NON_DISRUPTIVE).build()
        client.requestConnection(localName, endpointId, lifecycle, options)
            .addOnFailureListener { e ->
                val code = codeOf(e)
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) { SLog.d(TAG, "already connected to $adv"); return@addOnFailureListener }
                SLog.d(TAG, "requestConnection to $adv FAILED: ${describe(e)}")
                // The other phone may have requested us at the same moment; its handshake, if any, is on its way.
                synchronized(lock) { if (pending[endpointId] == adv && !peers.containsKey(endpointId)) pending.remove(endpointId) }
                retryJittered { requestConnection(endpointId) }
            }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val adv = info.endpointName
            val prefix = nodePrefixOf(adv)
            val peer = PeerEndpoint(NAME, endpointId, adv)
            synchronized(lock) { pending[endpointId] = adv }
            val pairing = pairingMode
            when {
                !pairing && isPinnedPrefix(prefix) -> {
                    SLog.d(TAG, "connection initiated by $adv ($endpointId): pinned prefix $prefix, accepting")
                    client.acceptConnection(endpointId, payloads).addOnFailureListener { e -> SLog.w(TAG, "acceptConnection $endpointId failed: ${describe(e)}") }
                }
                pairing -> {
                    val req = PairingRequest(peer, info.authenticationDigits)
                    synchronized(lock) { awaitingPairing[endpointId] = req }
                    SLog.d(TAG, "connection initiated by $adv ($endpointId) in pairing mode: digits ${req.digits}, waiting for the owner")
                    if (!pairingFlow.tryEmit(req)) SLog.w(TAG, "pairing request for $adv dropped: no room in the flow")
                    refreshStatus()
                }
                else -> {
                    SLog.w(TAG, "connection initiated by $adv ($endpointId): prefix $prefix not pinned and pairing mode off, rejecting")
                    synchronized(lock) { pending.remove(endpointId) }
                    client.rejectConnection(endpointId)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val adv = synchronized(lock) { pending.remove(endpointId) ?: seen[endpointId] ?: "?" }
            synchronized(lock) { awaitingPairing.remove(endpointId) }
            if (!result.status.isSuccess) {
                val code = result.status.statusCode
                SLog.d(TAG, "connection with $adv FAILED: ${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)")
                // Rejected (by them or by us) is final until something changes; anything else is retried while in range.
                if (code != ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) retryJittered { requestOrWait(endpointId) }
                refreshStatus()
                return
            }
            synchronized(lock) { peers[endpointId] = adv }
            SLog.d(TAG, "CONNECTED to $adv ($endpointId); ${peers.size} peers")
            refreshStatus()
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            SLog.d(TAG, "link ${nameOf(endpointId)} bandwidth quality=${info.quality} (1=low: Bluetooth, 2=medium, 3=high: Wi-Fi)")
        }

        override fun onDisconnected(endpointId: String) {
            val adv = synchronized(lock) { peers.remove(endpointId) ?: pending.remove(endpointId) }
            SLog.d(TAG, "DISCONNECTED from ${adv ?: endpointId}; ${peers.size} peers")
            refreshStatus()
            // Reconnect while they are still in range; discovery will also re-fire onEndpointFound.
            retryJittered { requestOrWait(endpointId) }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: run { SLog.w(TAG, "non-bytes payload from ${nameOf(endpointId)} ignored"); return }
            val adv = synchronized(lock) { peers[endpointId] ?: pending[endpointId] ?: endpointId }
            if (!inboundFlow.tryEmit(Inbound(PeerEndpoint(NAME, endpointId, adv), bytes))) {
                SLog.w(TAG, "inbound ${bytes.size} B from $adv dropped: no room in the flow")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private fun nameOf(endpointId: String): String = synchronized(lock) { peers[endpointId] ?: pending[endpointId] ?: seen[endpointId] ?: endpointId }

    private fun codeOf(e: Exception): Int = (e as? ApiException)?.statusCode ?: -1

    private fun describe(e: Exception): String {
        val code = codeOf(e)
        return if (code >= 0) "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)" else "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun noteError(what: String, e: Exception) {
        val code = codeOf(e)
        val text = ConnectionsStatusCodes.getStatusCodeString(code)
        lastError = when {
            e is SecurityException || text.startsWith("MISSING_PERMISSION") -> "$what needs a permission: $text"
            code == ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR || code == ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "$what: radio error (Bluetooth and Wi-Fi on?)"
            else -> "$what failed: ${describe(e)}"
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val live = synchronized(lock) { peers.map { (id, adv) -> PeerEndpoint(NAME, id, adv) } }
        connectedState.value = live
        val err = lastError
        statusState.value = when {
            stopped -> "stopped"
            err != null -> "Nearby · $err · retrying"
            else -> buildString {
                append("Nearby")
                append(if (advertising) " · advertising as $localName" else " · not advertising")
                append(if (discovering) " · discovering" else " · not discovering")
                append(" · ${live.size} peer${if (live.size == 1) "" else "s"}")
                if (pairingMode) append(" · pairing")
            }
        }
    }
}
