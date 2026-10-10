package com.hivecore.mesh

import com.hivecore.mesh.PlanConsent
import com.hivecore.mesh.PlanConsentVerdict
import com.hivecore.core.Inbound
import com.hivecore.core.PairingRequest
import com.hivecore.core.Peer
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.PeerManager
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.core.Transport
import com.hivecore.core.Verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import java.util.Base64

/** One inbound message whose envelope (and, for CONSENT_RES, approval signature) has been verified. */
data class Verified(val type: MsgType, val from: String, val body: String)

/** A transport that can drop one link (the router closes a link whose HELLO is unpinned outside pairing mode). */
interface Disconnectable { fun disconnect(endpoint: PeerEndpoint) }

/**
 * Plan 10 `MeshRouter : PeerManager`. Owns the transport switch, maps `nodeId <-> PeerEndpoint`, verifies every
 * inbound envelope against the pinned key for `from` (HELLO: against the key inside the body, which must hash to
 * `from`), applies the replay guard to JOBs, enforces the CONSENT_RES and PLAN_CONSENT_RES approval-signature rules, and exposes the
 * result as [verified]. Unknown senders are dropped and logged unless pairing mode is on.
 *
 * Sessions (spec 6.3): every transport handed in is wrapped in a [SecureTransport] (one handed in already secured is
 * used as is), so envelopes leave and arrive only inside an authenticated AES-GCM session; a handshake with an
 * unpinned peer is refused there, and an authentication failure drops that link and is reported through
 * [onAuthFailure] / [authFailures] with the peer's name.
 *
 * Roster (spec 6.4): with a [RosterStore], ROSTER and REVOKE are applied (highest validly signed version wins) and
 * gossiped on; a newly revoked peer's key is deleted, its session closed and its messages dropped; HELLO carries this
 * phone's roster version and a peer behind it is sent the roster.
 *
 * Pairing: a transport with its own digits (Nearby) raises [Transport.pairingRequests]; those pass through to
 * [pairing] and [accept] forwards the decision to the transport, remembering the endpoint so its first HELLO pins.
 * A transport without digits (RFCOMM) gets its request from this router on the first unpinned HELLO received while
 * pairing mode is on, with digits = [Wire.pairingDigits] over the two HELLO keys; [accept] then pins that key.
 *
 * @param transports            selectable transports keyed by their PeerManager name ("nearby", "bt"; tests: "fake")
 * @param alwaysOn              names in [transports] that run beside the selected one (the relay and the WebRTC
 *                              channel, spec 5.1): a peer reached on one of them is sent to over that transport
 * @param localHello            builds this node's HELLO (name, holds, caps, approvalPubkey); nodeId, pubkey and ts
 *                              are filled in here
 * @param scope                 collectors run here; cancel it to stop everything
 * @param initialTransport      key into [transports] used by [start] (default: the first one)
 * @param bondedProvider        Settings-bonded phones for [bonded] (the Bluetooth transport agent supplies it)
 * @param connectBondedHandler  receives [connectBonded] (the Bluetooth transport agent supplies it)
 * @param debugAllowNodeSignedApprovals  `signedBy:"node"` approvals are accepted only when true (BuildConfig.DEBUG)
 * @param rosterStore           the admin-signed roster (spec 6.4); null = rosters are delivered unverified to [verified]
 * @param onAuthFailure         (peer name, reason) once per failure (deduplicated per minute) for the warning the UI shows
 */
class MeshRouter(
    transports: Map<String, Transport>,
    private val signer: Signer,
    private val peerStore: PeerStore,
    private val localHello: () -> HelloBody,
    private val scope: CoroutineScope,
    initialTransport: String? = null,
    private val bondedProvider: () -> List<Pair<String, String>> = { emptyList() },
    private val connectBondedHandler: (String) -> Unit = {},
    private val debugAllowNodeSignedApprovals: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pairingWindowMs: Long = 120_000L,
    replayGuard: ReplayGuard? = null,
    private val rosterStore: RosterStore? = null,
    onAuthFailure: (peerName: String, reason: String) -> Unit = { _, _ -> },
    private val alwaysOn: Set<String> = emptySet(),
) : PeerManager {

    val nodeId: String = Wire.nodeIdOf(signer.publicKeyB64) ?: error("signer public key is not base64")

    /** Spec 6.3: every transport runs below a session layer. */
    private val transports: Map<String, SecureTransport> = transports.mapValues { (_, t) ->
        t as? SecureTransport ?: SecureTransport(t, signer, pinnedKeyOf = { peerStore.get(it)?.pubkey }, clock = now, scope = scope)
    }
    var onAuthFailure: (peerName: String, reason: String) -> Unit = onAuthFailure
    private val authFailuresFlow = MutableSharedFlow<AuthFailure>(replay = 0, extraBufferCapacity = 64)
    /** Every session authentication failure, with `peer` = the peer's name (pinned name, HELLO name or nodeId prefix). */
    val authFailures: SharedFlow<AuthFailure> = authFailuresFlow
    private val reportedFailures = HashMap<String, Long>()

    private val verifiedFlow = MutableSharedFlow<Verified>(replay = 0, extraBufferCapacity = 256)
    /** Every inbound message that passed verification. Subscribe before [start]; nothing is replayed. */
    val verified: SharedFlow<Verified> = verifiedFlow

    private val peersState = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = peersState
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 16)
    override val pairing: SharedFlow<PairingRequest> = pairingFlow
    private val pendingState = MutableStateFlow<List<PairingRequest>>(emptyList())
    /**
     * Pairing requests raised (by the transport or by this router) and not yet decided, oldest first. A request
     * leaves the list when [accept] decides it, when its link drops, or when pairing mode ends; the Pairing screen
     * dialog and the debug hooks read it, so a request decided in one place disappears everywhere.
     */
    val pendingPairings: StateFlow<List<PairingRequest>> = pendingState
    private val pairingModeState = MutableStateFlow(false)
    /** Pairing mode as a flow (it switches itself off after the pairing window). */
    val pairingModeFlow: StateFlow<Boolean> = pairingModeState
    private val transportState = MutableStateFlow(initialTransport ?: transports.keys.firstOrNull() ?: "")
    override val transportName: StateFlow<String> = transportState
    private val bondedState = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    override val bonded: StateFlow<List<Pair<String, String>>> = bondedState

    private val replay = replayGuard ?: ReplayGuard(now = now)
    private val skewState = MutableStateFlow<Map<String, Long>>(emptyMap())
    /**
     * nodeId -> clock difference (their ts minus ours, ms) for pinned nodes whose clock is more than the replay
     * window (10 min) away: their JOBs are dropped, so the peer status and the Settings checklist should say
     * "clock differs". Empty when every clock agrees.
     */
    val clockSkew: StateFlow<Map<String, Long>> = skewState
    private val lock = Any()

    // ---- state guarded by `lock` ----
    private var active: SecureTransport? = null
    /** Every started transport: the selected one plus the always-on ones, by name. */
    private val running = LinkedHashMap<String, SecureTransport>()
    private val collectors = ArrayList<Job>()
    private var pairingMode = false
    private var pairingTimer: Job? = null
    private val connectedEndpoints = LinkedHashMap<String, PeerEndpoint>()      // endpoint key -> endpoint
    private val endpointsOfNode = HashMap<String, LinkedHashMap<String, PeerEndpoint>>()   // nodeId -> transport name -> its endpoint
    private val greeted = HashSet<String>()                                      // endpoint keys we sent HELLO to
    private val lastHello = HashMap<String, HelloBody>()                         // nodeId -> last verified HELLO
    private val pendingPairing = HashMap<String, PendingHello>()                 // endpoint key -> unpinned HELLO
    private val acceptedEndpoints = HashSet<String>()                            // transport-raised pairings accepted
    private val consentReqs = LinkedHashMap<String, String>()                    // "jobId|node" -> intentHash
    private val planConsentReqs = LinkedHashMap<String, PlanConsentReqBody>()    // "planId|node" -> the request

    private class PendingHello(val hello: HelloBody, val endpoint: PeerEndpoint, val digits: String)

    private fun key(e: PeerEndpoint) = "${e.transport}/${e.endpointId}"

    init {
        for (st in this.transports.values) {
            st.admitEndpoint = { ep -> synchronized(lock) { key(ep) in acceptedEndpoints } }
            val inner = st.onAuthFailure
            st.onAuthFailure = { peer, reason -> inner(peer, reason); reportAuthFailure(peer, reason) }
        }
    }

    /** The session layer of transport [name] (default: the selected one); pairing and the roster admin talk to it. */
    fun secure(name: String = transportState.value): SecureTransport? = transports[name]

    /** Every transport's session layer (pairing installs its admission rule on each). */
    fun secureTransports(): Collection<SecureTransport> = transports.values

    /** Registers [l] on every transport's session layer (pairing listens for CONTROL frames). */
    fun addSessionListener(l: SessionListener) = transports.values.forEach { it.addListener(l) }

    /** Re-evaluates every session against the pins (after a pin or forget made outside this router) and retries refused links. */
    fun refreshSessions() = runningTransports().forEach { it.refresh() }

    private fun runningTransports(): List<SecureTransport> = synchronized(lock) { running.values.toList() }

    /** The transport a message to [ep] goes out on: the one the endpoint names, else the selected one. */
    private fun transportFor(ep: PeerEndpoint): SecureTransport? = synchronized(lock) { running[ep.transport] ?: active }

    /** The endpoint [nodeId] is reachable on right now (any running transport), or null. */
    fun endpointOf(nodeId: String): PeerEndpoint? = endpointFor(nodeId)

    /** The measured link to [nodeId] over the transport it is reached on (spec 5.6), or null when it is not connected. */
    fun linkTo(nodeId: String): com.hivecore.core.Link? = endpointFor(nodeId)?.let { ep -> transportFor(ep)?.link(nodeId) }

    /** This phone's roster version for HELLO and the Swarm screen (null = no roster yet). */
    fun rosterVersion(): Long? = rosterStore?.latest()?.version

    private fun reportAuthFailure(peer: String, reason: String) {
        val name = when {
            peer.startsWith("endpoint:") -> peer.substringAfter("endpoint:")
            else -> peerStore.get(peer)?.name
                ?: synchronized(lock) { lastHello[peer]?.name ?: pendingPairing.values.firstOrNull { it.hello.nodeId == peer }?.hello?.name }
                ?: peer.take(8)
        }
        val k = "$name|$reason"
        val t = now()
        val fresh = synchronized(reportedFailures) {
            val last = reportedFailures[k]
            if (last != null && t - last < AUTH_FAILURE_REPEAT_MS) false else { reportedFailures[k] = t; true }
        }
        if (!fresh) return
        SLog.w(TAG, "authentication failure with $name: $reason")
        authFailuresFlow.tryEmit(AuthFailure(name, PeerEndpoint("", "", name), reason))
        runCatching { onAuthFailure(name, reason) }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the selected transport and its collectors; returns once the collectors are subscribed. */
    suspend fun start() {
        rosterStore?.latest()?.let { pinFromRoster(it) }   // members learned before this build ran, or while the app was off
        selectTransport(transportState.value)
    }

    /** Spec 6.4: every member of an accepted roster is pinned, so phones that paired only with the admin accept each other. */
    private fun pinFromRoster(after: RosterBody) {
        for (m in after.members) {
            if (m.nodeId == nodeId || m.nodeId in after.revoked || peerStore.isPinned(m.nodeId)) continue
            if (Wire.nodeIdOf(m.pubkey) != m.nodeId) { SLog.w(TAG, "roster member ${m.nodeId.take(8)}: key does not match its id; not pinned"); continue }
            peerStore.pin(m.nodeId, m.name, m.pubkey, null, emptyList(), now())
            SLog.d(TAG, "pinned ${m.name} (${m.nodeId.take(8)}) from roster ${after.groupId} v${after.version}")
        }
    }

    suspend fun stop() {
        val ts: List<Transport>
        synchronized(lock) {
            ts = running.values.toList()
            active = null
            running.clear()
            collectors.forEach { it.cancel() }
            collectors.clear()
            connectedEndpoints.clear()
            greeted.clear()
            pendingPairing.clear()
            acceptedEndpoints.clear()
        }
        pendingState.value = emptyList()
        for (t in ts) runCatching { t.stop() }.onFailure { SLog.w(TAG, "stop ${t.name}: ${it.message}") }
        refreshPeers()
    }

    override suspend fun selectTransport(name: String) {
        val t = transports[name]
        if (t == null) {
            SLog.w(TAG, "selectTransport: unknown transport '$name' (have ${transports.keys})")
            return
        }
        stop()
        transportState.value = name
        bondedState.value = runCatching { bondedProvider() }.getOrDefault(emptyList())
        synchronized(lock) { active = t }
        val hello = localHello()
        startOne(name, t, "${hello.name}|${nodeId.take(8)}")
        for (extra in alwaysOn) {
            if (extra == name) continue
            val e = transports[extra] ?: run { SLog.w(TAG, "always-on transport '$extra' is not configured"); continue }
            runCatching { startOne(extra, e, "${hello.name}|${nodeId.take(8)}") }.onFailure { SLog.w(TAG, "always-on transport '$extra' failed to start: ${it.message}") }
        }
    }

    private suspend fun startOne(name: String, t: SecureTransport, localName: String) {
        val inboundReady = CompletableDeferred<Unit>()
        val pairingReady = CompletableDeferred<Unit>()
        synchronized(lock) {
            running[name] = t
            collectors += scope.launch { t.inbound.onSubscription { inboundReady.complete(Unit) }.collect { onInbound(t, it) } }
            collectors += scope.launch { t.pairingRequests.onSubscription { pairingReady.complete(Unit) }.collect { onTransportPairing(it) } }
        }
        // Wait until the collectors are subscribed so nothing received right after start is lost.
        inboundReady.await()
        pairingReady.await()
        t.setPairingMode(synchronized(lock) { pairingMode })
        t.start(localName)
        // The connected collector starts after the transport so the first HELLOs go out on a started transport
        // (a StateFlow replays its current value, so nothing is missed).
        synchronized(lock) { collectors += scope.launch { t.connected.collect { onConnected(t, it) } } }
        SLog.d(TAG, "transport '$name' started as $localName")
    }

    fun refreshBonded() { bondedState.value = runCatching { bondedProvider() }.getOrDefault(emptyList()) }

    // ------------------------------------------------------------------ PeerManager

    override fun setPairingMode(on: Boolean) {
        val ts: List<Transport>
        synchronized(lock) {
            pairingMode = on
            pairingTimer?.cancel()
            pairingTimer = if (on) scope.launch { delay(pairingWindowMs); setPairingMode(false) } else null
            if (!on) pendingPairing.clear()
            ts = running.values.toList()
        }
        pairingModeState.value = on
        if (!on) pendingState.value = emptyList()
        ts.forEach { it.setPairingMode(on) }
        SLog.d(TAG, "pairing mode ${if (on) "on" else "off"}")
        refreshPeers()
    }

    fun pairingMode(): Boolean = synchronized(lock) { pairingMode }

    override fun accept(req: PairingRequest, ok: Boolean) {
        val k = key(req.endpoint)
        val pending = synchronized(lock) { pendingPairing[k] }
        if (pending != null && pending.digits != req.digits) {
            // A router-raised pairing for this endpoint is pending: a request with other digits is not the one the
            // owner compared, so it must neither pin nor fall through to "accepted" (the next HELLO would pin).
            SLog.w(TAG, "pairing for ${pending.hello.name} refused: digits ${req.digits} do not match ${pending.digits}")
            return
        }
        if (pending != null) {
            synchronized(lock) { pendingPairing.remove(k) }
            removePending(k)
            if (ok) {
                pin(pending.hello, pending.endpoint)
                scope.launch { sendHello(pending.endpoint) }
            } else {
                SLog.d(TAG, "pairing declined for ${pending.hello.name}")
            }
            refreshPeers()
            return
        }
        // Raised by the transport (Nearby digits): forward, and remember the endpoint so its first HELLO pins.
        val t = transportFor(req.endpoint)
        removePending(k)
        if (ok) synchronized(lock) { acceptedEndpoints.add(k) }
        t?.acceptPairing(req, ok)
        if (ok) t?.retry(req.endpoint)   // the link may already exist with a refused handshake
        SLog.d(TAG, "transport pairing ${if (ok) "accepted" else "declined"} for ${req.endpoint.displayName}")
    }

    override fun forget(nodeId: String) {
        peerStore.forget(nodeId)
        synchronized(lock) {
            lastHello.remove(nodeId)
            endpointsOfNode.remove(nodeId)
        }
        runningTransports().forEach { it.close(nodeId, "forgotten"); it.refresh() }
        SLog.d(TAG, "forgot ${nodeId.take(8)}")
        refreshPeers()
    }

    override fun connectBonded(address: String) = connectBondedHandler(address)

    // ------------------------------------------------------------------ sending

    /** Sends one signed message to a pinned, connected node. */
    suspend fun send(nodeId: String, type: MsgType, bodyJson: String): Result<Unit> {
        val ep = endpointFor(nodeId) ?: return Result.failure(IllegalStateException("node ${nodeId.take(8)} is not connected"))
        val t = transportFor(ep) ?: return Result.failure(IllegalStateException("no transport"))
        val r = t.send(ep, Wire.encode(type, this.nodeId, bodyJson, signer))
        if (r.isFailure) SLog.w(TAG, "send $type to ${nodeId.take(8)} failed: ${r.exceptionOrNull()?.message}")
        return r
    }

    /** Sends one signed message to every connected pinned node; returns the nodeIds it was sent to. */
    suspend fun broadcast(type: MsgType, bodyJson: String): List<String> {
        val bytes = Wire.encode(type, nodeId, bodyJson, signer)
        val out = ArrayList<String>()
        for ((id, ep) in connectedNodes()) {
            val t = transportFor(ep) ?: continue
            if (t.send(ep, bytes).isSuccess) out += id else SLog.w(TAG, "broadcast $type to ${id.take(8)} failed")
        }
        return out
    }

    /** Every pinned node, connected or not. */
    fun pinnedNodeIds(): List<String> = peerStore.all().map { it.nodeId }

    /** Pinned nodes with a live endpoint on a running transport. */
    fun connectedNodeIds(): List<String> = connectedNodes().map { it.first }

    /** Sends HELLO to every connected endpoint (after ingest/delete so peers' `holds` stay current). */
    suspend fun rebroadcastHello() {
        val eps = synchronized(lock) { connectedEndpoints.values.toList() }
        for (ep in eps) sendHello(ep)
    }

    /** The name a pinned node goes by in PeerStore (from its key-signed HELLO), or null when it is not pinned. */
    fun peerName(nodeId: String): String? = peerStore.get(nodeId)?.name

    /** The pinned node key of [nodeId] (base64), or null when it is not pinned (receipts are verified with it). */
    fun pinnedKey(nodeId: String): String? = peerStore.get(nodeId)?.pubkey

    /** The last verified HELLO from a node (caps decide the ask deadline; holds feed the NoNode screen). */
    fun lastHello(nodeId: String): HelloBody? = synchronized(lock) { lastHello[nodeId] }

    private fun connectedNodes(): List<Pair<String, PeerEndpoint>> = synchronized(lock) {
        endpointsOfNode.keys.filter { peerStore.isPinned(it) }.mapNotNull { id -> bestEndpointLocked(id)?.let { id to it } }
    }

    private fun endpointFor(nodeId: String): PeerEndpoint? = synchronized(lock) { bestEndpointLocked(nodeId) }

    /** Caller holds [lock]: the connected endpoint of [nodeId] on the best-ranked transport (spec 5.1 ladder: LAN before the relay). */
    private fun bestEndpointLocked(nodeId: String): PeerEndpoint? =
        endpointsOfNode[nodeId]?.values?.filter { key(it) in connectedEndpoints }?.minByOrNull { rankLocked(it) }

    /** Caller holds [lock]: the ladder rank of the transport an endpoint belongs to (unknown transports rank last). */
    private fun rankLocked(ep: PeerEndpoint): Int = (running[ep.transport] ?: active?.takeIf { it.name == ep.transport })?.kind?.ordinal ?: Int.MAX_VALUE

    /** Caller holds [lock]: records that [nodeId] was seen on [ep]'s transport. */
    private fun noteEndpointLocked(nodeId: String, ep: PeerEndpoint) { endpointsOfNode.getOrPut(nodeId) { LinkedHashMap() }[ep.transport] = ep }

    private suspend fun sendHello(ep: PeerEndpoint) {
        val t = transportFor(ep) ?: return
        val local = localHello()
        val body = local.copy(nodeId = nodeId, pubkey = signer.publicKeyB64, ts = now(), rosterVersion = local.rosterVersion ?: rosterVersion())
        synchronized(lock) { greeted.add(key(ep)) }
        val r = t.send(ep, Wire.encode(MsgType.HELLO, nodeId, MeshJson.encode(body), signer))
        if (r.isFailure) {
            synchronized(lock) { greeted.remove(key(ep)) }   // so the next HELLO from that side triggers a retry
            SLog.w(TAG, "HELLO to ${ep.displayName} failed: ${r.exceptionOrNull()?.message}")
        }
    }

    // ------------------------------------------------------------------ transport events

    private suspend fun onConnected(t: SecureTransport, list: List<PeerEndpoint>) {
        val fresh = ArrayList<PeerEndpoint>()
        synchronized(lock) {
            val keys = list.map { key(it) }.toSet()
            for (ep in list) {
                val k = key(ep)
                if (k !in connectedEndpoints && k !in greeted) fresh += ep
                connectedEndpoints[k] = ep
            }
            // [list] is the endpoints with an exposed session on transport [t]; pairing state is per link and survives a
            // refused handshake on a link the carrier still has (the owner's acceptance on one side comes before the other's).
            val gone = connectedEndpoints.keys.filter { it.startsWith(t.name + "/") && it !in keys }
            val linkGone = gone.filter { k -> connectedEndpoints[k]?.let { t.hasLink(it) } != true }
            linkGone.forEach { removePending(it) }
            for (k in gone) {
                connectedEndpoints.remove(k)
                greeted.remove(k)
            }
            for (k in linkGone) {
                pendingPairing.remove(k)
                acceptedEndpoints.remove(k)
            }
        }
        for (ep in fresh) sendHello(ep)
        refreshPeers()
    }

    private suspend fun onTransportPairing(req: PairingRequest) {
        SLog.d(TAG, "pairing request from transport: ${req.endpoint.displayName} digits=${req.digits}")
        addPending(req)
        pairingFlow.emit(req)
    }

    private suspend fun onInbound(t: Transport, inb: Inbound) {
        if (inb.bytes.size > MAX_ENVELOPE) {
            SLog.w(TAG, "dropped ${inb.bytes.size} B envelope from ${inb.endpoint.displayName}: over $MAX_ENVELOPE B")
            return
        }
        val env = try {
            Wire.decode(inb.bytes)
        } catch (e: IllegalArgumentException) {
            SLog.w(TAG, "dropped malformed envelope from ${inb.endpoint.displayName}: ${e.message}")
            return
        }
        val type = env.msgType
        if (type == null) {
            SLog.w(TAG, "dropped unknown type '${env.type}' from ${env.from.take(8)}")
            return
        }
        if (env.from == nodeId) return   // our own HELLO echoed back (loopback transports)
        if (type == MsgType.HELLO) { onHello(env, inb.endpoint); return }

        val peer = peerStore.get(env.from)
        if (peer == null) {
            SLog.w(TAG, "dropped $type from unknown signer ${env.from.take(8)} via ${inb.endpoint.displayName}")
            return
        }
        if (rosterStore?.isRevoked(env.from) == true) {
            SLog.w(TAG, "dropped $type from revoked ${peer.name}; forgetting its key")
            forget(env.from)
            return
        }
        if (!Wire.verify(env, peer.pubkey)) {
            SLog.w(TAG, "dropped $type from ${peer.name}: bad signature")
            return
        }
        if (!checkBody(type, env, peer)) return

        synchronized(lock) { noteEndpointLocked(env.from, inb.endpoint) }
        peerStore.touch(env.from, now(), inb.endpoint.transport, inb.endpoint.endpointId)
        verifiedFlow.emit(Verified(type, env.from, env.body))
    }

    /** Type-specific checks after the envelope signature passed. Returns false (and logs) to drop. */
    private fun checkBody(type: MsgType, env: Envelope, peer: PinnedPeer): Boolean {
        try {
            when (type) {
                MsgType.JOB -> {
                    val b = MeshJson.decode<JobBody>(env.body)
                    if (b.requester != env.from) return drop(type, peer, "requester != from")
                    noteSkew(env.from, peer.name, b.ts)
                    replay.check(b.requester, b.nonce, b.ts)?.let { return drop(type, peer, it) }
                }
                MsgType.CONSENT_REQ -> {
                    val b = MeshJson.decode<ConsentReqBody>(env.body)
                    if (b.node != env.from) return drop(type, peer, "node != from")
                    synchronized(lock) {
                        consentReqs["${b.jobId}|${b.node}"] = b.intentHash
                        while (consentReqs.size > 200) consentReqs.remove(consentReqs.keys.first())
                    }
                }
                MsgType.CONSENT_RES -> {
                    val b = MeshJson.decode<ConsentResBody>(env.body)
                    if (b.node != env.from) return drop(type, peer, "node != from")
                    if (b.approved) {
                        val sig = b.approvalSig?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                            ?: return drop(type, peer, "approved without approvalSig")
                        val expected = synchronized(lock) { consentReqs["${b.jobId}|${b.node}"] }
                        if (expected == null) return drop(type, peer, "approval without a CONSENT_REQ for job ${b.jobId}")
                        if (expected != b.intentHash) return drop(type, peer, "approval intentHash differs from CONSENT_REQ")
                        val canonical = Canonical.consent(b.jobId, b.intentHash).toByteArray(Charsets.UTF_8)
                        val key = when (b.signedBy) {
                            ConsentResBody.SIGNED_BY_APPROVAL -> peer.approvalPubkey
                                ?: return drop(type, peer, "approval signed but no approvalPubkey pinned")
                            ConsentResBody.SIGNED_BY_NODE -> {
                                if (!debugAllowNodeSignedApprovals) return drop(type, peer, "signedBy:node is debug-only")
                                SLog.w(TAG, "accepting node-signed approval from ${peer.name} (debug)")
                                peer.pubkey
                            }
                            else -> return drop(type, peer, "unknown signedBy '${b.signedBy}'")
                        }
                        if (!Verify.verify(key, canonical, sig)) return drop(type, peer, "approvalSig does not verify")
                    }
                    // One answer per CONSENT_REQ: a replayed approval for the same job and node finds nothing.
                    synchronized(lock) { consentReqs.remove("${b.jobId}|${b.node}") }
                }
                MsgType.RESULT -> {
                    val b = MeshJson.decode<ResultBody>(env.body)
                    if (b.node != env.from) return drop(type, peer, "node != from")
                    if (!b.hashMatches()) return drop(type, peer, "hash does not match its outcomes")
                }
                MsgType.RECEIPT -> {
                    val b = MeshJson.decode<ReceiptBody>(env.body)
                    if (b.node != env.from) return drop(type, peer, "node != from")
                }
                MsgType.NOT_RELEVANT -> {
                    val b = MeshJson.decode<NotRelevantBody>(env.body)
                    if (b.nodeId != env.from) return drop(type, peer, "nodeId != from")
                }
                MsgType.HELLO -> Unit
                // swarm plan §8.1: the named sender must be the envelope's signer; nonce-bearing bodies are replay-checked
                MsgType.PROFILE -> if (MeshJson.decode<ProfileBody>(env.body).profile.nodeId != env.from) return drop(type, peer, "profile.nodeId != from")
                MsgType.PLAN -> {
                    val b = MeshJson.decode<PlanBody>(env.body)
                    if (b.commander != env.from) return drop(type, peer, "commander != from")
                    noteSkew(env.from, peer.name, b.ts)
                    replay.check(b.commander, b.nonce, b.ts)?.let { return drop(type, peer, it) }
                }
                MsgType.PLAN_CONSENT_REQ -> {
                    val b = MeshJson.decode<PlanConsentReqBody>(env.body)
                    if (b.node != env.from) return drop(type, peer, "node != from")
                    synchronized(lock) {
                        planConsentReqs["${b.planId}|${b.node}"] = b
                        while (planConsentReqs.size > 200) planConsentReqs.remove(planConsentReqs.keys.first())
                    }
                }
                MsgType.PLAN_CONSENT_RES -> {
                    val b = MeshJson.decode<PlanConsentResBody>(env.body)
                    val k = "${b.planId}|${b.node}"
                    val expected = synchronized(lock) { planConsentReqs[k] }
                    when (val v = PlanConsent.verify(b, env.from, expected, peer.pubkey, peer.approvalPubkey, debugAllowNodeSignedApprovals)) {
                        is PlanConsentVerdict.Invalid -> return drop(type, peer, v.why)
                        PlanConsentVerdict.Approved -> if (b.signedBy == ConsentResBody.SIGNED_BY_NODE)
                            SLog.w(TAG, "accepting node-signed plan approval from ${peer.name} (debug)")
                        is PlanConsentVerdict.Declined -> Unit
                    }
                    // One answer per PLAN_CONSENT_REQ: a replayed approval for the same plan and node finds nothing.
                    synchronized(lock) { planConsentReqs.remove(k) }
                }
                MsgType.TASK -> {
                    val b = MeshJson.decode<TaskBody>(env.body)
                    if (b.commander != env.from) return drop(type, peer, "commander != from")
                    replay.check(b.commander, b.nonce, b.ts)?.let { return drop(type, peer, it) }
                }
                MsgType.TASK_RESULT -> if (MeshJson.decode<TaskResultBody>(env.body).node != env.from) return drop(type, peer, "node != from")
                MsgType.TASK_YIELD, MsgType.TASK_CANCEL -> if (MeshJson.decode<TaskSignalBody>(env.body).node != env.from) return drop(type, peer, "node != from")
                MsgType.BLOB -> if (MeshJson.decode<BlobBody>(env.body).owner != env.from) return drop(type, peer, "owner != from")
                MsgType.BLOB_DELETED -> if (MeshJson.decode<BlobDeletedBody>(env.body).node != env.from) return drop(type, peer, "node != from")
                MsgType.WORKLOAD -> {
                    val b = MeshJson.decode<WorkloadBody>(env.body)
                    if (b.owner != env.from) return drop(type, peer, "owner != from")
                    replay.check(b.owner, b.nonce, b.ts)?.let { return drop(type, peer, it) }
                }
                MsgType.RELEASE -> if (MeshJson.decode<ReleaseBody>(env.body).owner != env.from) return drop(type, peer, "owner != from")
                // ---- v2 plan §12.1 sender rules ----
                // Handshake frames are consumed by the session layer below the router; one that gets here is a bug or an attack.
                MsgType.SESSION_INIT, MsgType.SESSION_RESP, MsgType.SESSION_FIN -> return drop(type, peer, "handshake frame reached the router")
                // Gossiped by any member; the roster store verifies adminSig and keeps the highest version (spec 6.4).
                MsgType.ROSTER -> {
                    val b = MeshJson.decode<RosterBody>(env.body)
                    val rs = rosterStore ?: return true
                    val before = rs.current(b.groupId)
                    if (!rs.apply(b)) return drop(type, peer, "roster v${b.version} is not newer or its adminSig does not verify")
                    afterRosterChange(b.groupId, before, env.from, type, env.body)
                }
                MsgType.REVOKE -> {
                    val b = MeshJson.decode<RevokeBody>(env.body)
                    val rs = rosterStore ?: return true
                    val before = rs.current(b.groupId)
                    if (!rs.applyRevoke(b)) return drop(type, peer, "revoke v${b.version} is not newer or its adminSig does not verify")
                    afterRosterChange(b.groupId, before, env.from, type, env.body)
                }
                MsgType.SIGNAL -> if (MeshJson.decode<SignalBody>(env.body).from != env.from) return drop(type, peer, "signal.from != from")
                MsgType.MODEL_REQ -> {
                    val b = MeshJson.decode<ModelReqBody>(env.body)
                    if (b.commander != env.from) return drop(type, peer, "commander != from")
                    replay.check(b.commander, b.nonce, b.ts)?.let { return drop(type, peer, it) }
                }
                MsgType.MODEL_READY -> if (MeshJson.decode<ModelReadyBody>(env.body).node != env.from) return drop(type, peer, "node != from")
                MsgType.MODEL_FAIL -> if (MeshJson.decode<ModelFailBody>(env.body).node != env.from) return drop(type, peer, "node != from")
                MsgType.RECLAIM -> {
                    val b = MeshJson.decode<ReclaimBody>(env.body)
                    if (b.commander != env.from) return drop(type, peer, "commander != from")
                    replay.check(b.commander, b.nonce, b.ts)?.let { return drop(type, peer, it) }
                }
                MsgType.RECLAIM_RES -> if (MeshJson.decode<ReclaimResBody>(env.body).node != env.from) return drop(type, peer, "node != from")
                MsgType.JOURNAL -> if (MeshJson.decode<JournalBody>(env.body).commander != env.from) return drop(type, peer, "commander != from")
            }
        } catch (e: Exception) {
            return drop(type, peer, "body does not parse: ${e.message}")
        }
        return true
    }

    private fun drop(type: MsgType, peer: PinnedPeer, why: String): Boolean {
        SLog.w(TAG, "dropped $type from ${peer.name}: $why")
        return false
    }

    /**
     * After a ROSTER or REVOKE applied: every newly revoked peer loses its pinned key and its session (spec 6.4), and
     * the signed body is gossiped to every other connected member (a member that already holds it drops it as not
     * newer, so the gossip terminates).
     */
    private fun afterRosterChange(groupId: String, before: RosterBody?, from: String, type: MsgType, body: String) {
        val after = rosterStore?.current(groupId) ?: return
        val newlyRevoked = after.revoked.filter { it !in before?.revoked.orEmpty() }
        for (id in newlyRevoked) {
            if (id == nodeId) { SLog.w(TAG, "this phone was revoked from $groupId v${after.version}"); continue }
            if (peerStore.isPinned(id)) { SLog.w(TAG, "revoked ${peerStore.get(id)?.name ?: id.take(8)}: key deleted"); forget(id) }
        }
        // Phone gate finding: two phones that each paired with the admin refused each other as "not paired".
        pinFromRoster(after)
        scope.launch {
            val bytes = Wire.encode(type, nodeId, body, signer)
            for ((id, ep) in connectedNodes()) if (id != from) transportFor(ep)?.send(ep, bytes)
        }
    }

    /** Sends the signed roster and the revokes after it to a peer whose HELLO carried an older version. */
    private suspend fun sendRoster(to: PeerEndpoint, latest: RosterBody) {
        val rs = rosterStore ?: return
        val t = transportFor(to) ?: return
        val base = rs.signedRoster(latest.groupId) ?: return
        t.send(to, Wire.encode(MsgType.ROSTER, nodeId, MeshJson.encode(base), signer))
        for (r in rs.revokesAfter(latest.groupId, base.version)) t.send(to, Wire.encode(MsgType.REVOKE, nodeId, MeshJson.encode(r), signer))
    }

    private suspend fun onHello(env: Envelope, ep: PeerEndpoint) {
        val hello = try {
            MeshJson.decode<HelloBody>(env.body)
        } catch (e: Exception) {
            SLog.w(TAG, "dropped HELLO from ${ep.displayName}: body does not parse: ${e.message}"); return
        }
        val derived = Wire.nodeIdOf(hello.pubkey)
        if (derived == null || derived != env.from || hello.nodeId != env.from) {
            SLog.w(TAG, "dropped HELLO from ${ep.displayName}: key does not hash to from"); return
        }
        if (!Wire.verify(env, hello.pubkey)) {
            SLog.w(TAG, "dropped HELLO from ${ep.displayName}: bad signature"); return
        }
        val k = key(ep)
        val pinned = peerStore.get(env.from)
        if (pinned != null) {
            if (pinned.pubkey != hello.pubkey) {
                SLog.w(TAG, "dropped HELLO from ${hello.name}: node key differs from the pinned key (forget and re-pair)"); return
            }
            // A HELLO older than the last one is a replay (it could roll approvalPubkey back to a revoked key).
            val prev = synchronized(lock) { lastHello[env.from] }
            if (prev != null && hello.ts < prev.ts) {
                SLog.w(TAG, "dropped HELLO from ${hello.name}: ts ${hello.ts} is older than the last one (${prev.ts})"); return
            }
            noteSkew(env.from, hello.name, hello.ts)
            peerStore.updateFromHello(env.from, hello.name, hello.approvalPubkey, hello.holds, now(), ep.transport, ep.endpointId)
            remember(env.from, hello, ep)
            SLog.d(TAG, "HELLO from ${hello.name} (${env.from.take(8)}) via ${ep.displayName}; caps=${hello.caps}")
            if (synchronized(lock) { k !in greeted }) sendHello(ep)
            rosterStore?.latest()?.let { mine -> if ((hello.rosterVersion ?: -1L) < mine.version) sendRoster(ep, mine) }
            verifiedFlow.emit(Verified(MsgType.HELLO, env.from, env.body))
            refreshPeers()
            return
        }
        val (mode, accepted) = synchronized(lock) { pairingMode to (k in acceptedEndpoints) }
        if (!mode && !accepted) {
            SLog.w(TAG, "dropped HELLO from unpinned ${hello.name} (${env.from.take(8)}): pairing mode is off; closing the link")
            (transportFor(ep) as? Disconnectable)?.disconnect(ep)
            return
        }
        if (accepted) {
            synchronized(lock) { acceptedEndpoints.remove(k) }
            pin(hello, ep)
            if (synchronized(lock) { k !in greeted }) sendHello(ep)
            verifiedFlow.emit(Verified(MsgType.HELLO, env.from, env.body))
            refreshPeers()
            return
        }
        val digits = Wire.pairingDigits(signer.publicKeyB64, hello.pubkey)
        val isNew = synchronized(lock) {
            val existing = pendingPairing[k]
            pendingPairing[k] = PendingHello(hello, ep, digits)
            existing == null || existing.hello.pubkey != hello.pubkey
        }
        if (isNew) {
            SLog.d(TAG, "pairing request for ${hello.name} (${env.from.take(8)}) digits=$digits")
            val req = PairingRequest(ep, digits)
            addPending(req)
            pairingFlow.emit(req)
        }
        if (synchronized(lock) { k !in greeted }) sendHello(ep)   // so the other side can show the same digits
        refreshPeers()
    }

    private fun addPending(req: PairingRequest) {
        val k = key(req.endpoint)
        pendingState.value = pendingState.value.filter { key(it.endpoint) != k } + req
    }

    private fun removePending(k: String) {
        if (pendingState.value.any { key(it.endpoint) == k }) pendingState.value = pendingState.value.filter { key(it.endpoint) != k }
    }

    private fun noteSkew(nodeId: String, name: String, ts: Long) {
        if (ts <= 0L) return   // a peer that does not send ts
        val skew = ts - now()
        val off = skew > CLOCK_WINDOW_MS || skew < -CLOCK_WINDOW_MS
        val before = skewState.value
        if (off == (nodeId in before)) { if (off) skewState.value = before + (nodeId to skew); return }
        if (off) SLog.w(TAG, "clock differs from $name by ${skew / 60_000} min: its JOBs are dropped until the clocks agree")
        else SLog.d(TAG, "clock of $name agrees again")
        skewState.value = if (off) before + (nodeId to skew) else before - nodeId
    }

    private fun pin(hello: HelloBody, ep: PeerEndpoint) {
        peerStore.pin(hello.nodeId, hello.name, hello.pubkey, hello.approvalPubkey, hello.holds, now(), ep.transport, ep.endpointId)
        remember(hello.nodeId, hello, ep)
        synchronized(lock) { acceptedEndpoints.remove(key(ep)) }
        SLog.d(TAG, "pinned ${hello.name} (${hello.nodeId.take(8)}) via ${ep.transport}")
        runningTransports().forEach { it.refresh() }
    }

    private fun remember(nodeId: String, hello: HelloBody, ep: PeerEndpoint) = synchronized(lock) {
        lastHello[nodeId] = hello
        noteEndpointLocked(nodeId, ep)
    }

    private fun refreshPeers() {
        val pinned = peerStore.all()
        val list = synchronized(lock) {
            val out = pinned.map { p ->
                val ep = bestEndpointLocked(p.nodeId)
                Peer(p.nodeId, p.name, pinned = true, connected = ep != null, transport = ep?.transport, holds = p.holds)
            }
            val unpinned = pendingPairing.values.filter { pv -> out.none { it.nodeId == pv.hello.nodeId } }.map { pv ->
                Peer(pv.hello.nodeId, pv.hello.name, pinned = false, connected = key(pv.endpoint) in connectedEndpoints,
                    transport = pv.endpoint.transport, holds = pv.hello.holds)
            }
            out + unpinned
        }
        peersState.value = list
    }

    companion object {
        private const val TAG = "MeshRouter"
        /** Largest envelope the router parses; RESULT bodies are a few KB (Nearby itself caps bytes payloads at 32 KB). */
        const val MAX_ENVELOPE = 64 * 1024
        /** The replay guard's window: a clock further off than this makes a peer's JOBs fail. */
        const val CLOCK_WINDOW_MS = 10 * 60_000L
        /** The same (peer, reason) authentication failure is reported once per this interval. */
        const val AUTH_FAILURE_REPEAT_MS = 60_000L
    }
}
