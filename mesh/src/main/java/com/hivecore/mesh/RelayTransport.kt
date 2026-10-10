package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.core.Transport
import com.hivecore.core.fakes.FakeRelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ------------------------------------------------------------------ socket seam

/** One WebSocket to the relay, as [RelayTransport] sees it. */
interface RelaySocket {
    fun send(text: String): Boolean
    fun close()
}

interface RelaySocketListener {
    fun onText(text: String)
    fun onClosed(reason: String)
}

/** Opens sockets: OkHttp on the phone ([OkHttpRelaySockets]), the in-process fake in tests ([FakeRelaySockets]). */
fun interface RelaySocketFactory {
    /** Connects (or throws, which counts as a failed attempt); frames arrive on [listener] until [RelaySocketListener.onClosed]. */
    fun connect(url: String, listener: RelaySocketListener): RelaySocket
}

/** OkHttp WebSockets. No read timeout (the relay pings every 25 s); OkHttp's own ping stays off, the protocol has its own. */
class OkHttpRelaySockets(
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(15, TimeUnit.SECONDS).build(),
) : RelaySocketFactory {
    override fun connect(url: String, listener: RelaySocketListener): RelaySocket {
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onText(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(reason.ifEmpty { "closed $code" })
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onClosed(t.message ?: t.javaClass.simpleName)
        })
        return object : RelaySocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close() { ws.close(1000, "bye") }
        }
    }
}

/** [RelaySocketFactory] over [FakeRelay]: WAN scenarios on the JVM without a network. */
class FakeRelaySockets(private val relay: FakeRelay) : RelaySocketFactory {
    override fun connect(url: String, listener: RelaySocketListener): RelaySocket {
        val conn = relay.connect(object : FakeRelay.Listener {
            override fun onText(text: String) = listener.onText(text)
            override fun onClosed(reason: String) = listener.onClosed(reason)
        })
        return object : RelaySocket {
            override fun send(text: String): Boolean { conn.send(text); return true }
            override fun close() = conn.close()
        }
    }
}

/** A joiner's invite (spec 6.2, remote): authenticate as a guest of [inviterNodeId] with the raw [token]. */
data class GuestInvite(val inviterNodeId: String, val token: String)

// ------------------------------------------------------------------ transport

/**
 * WAN relay transport (spec 5.3, 5.5, 14, 16.6; ladder rank 5). Connects to `relayd`, answers the challenge with the
 * node key, authenticates against the roster version the trust layer reports, and maps `msg` frames to the
 * endpoint-based [Transport]: a peer is `PeerEndpoint("relay", nodeId, …)`. Sits below [SecureTransport], which
 * seals every envelope before it reaches the relay.
 *
 * - Reconnects with backoff 1 s → 30 s; [relayDown] is the flag the work queue reads ("WAN attempts wait up to
 *   60 s, then fault": [awaitOnline]).
 * - [onPresence] feeds the profile layer like LAN discovery; [members] is the latest presence map.
 * - [wake] asks the relay for an FCM push to a sleeping member.
 * - [measureLink] is a 2 KB echo (a `msg` to self through the relay) → kbps and rttMs.
 * - [clock] keeps the offset from `serverTs` so the replay guard holds across time zones.
 * - An admin passes [rosterUpload]; it is sent before every auth so a restarted relay learns the roster again.
 * - A joiner passes [guest] ([RelayPairingChannel]); the relay then exposes only the inviter.
 */
class RelayTransport(
    private val url: String,
    private val signer: Signer,
    private val groupId: String,
    private val rosterVersion: () -> Long,
    private val scope: CoroutineScope,
    private val sockets: RelaySocketFactory = OkHttpRelaySockets(),
    val clock: RelayClock = RelayClock(),
    private val pushToken: () -> String? = { null },
    private val rosterUpload: () -> RosterBody? = { null },
    private val guest: GuestInvite? = null,
    private val backoffMinMs: Long = RelayProtocol.RECONNECT_MIN_MS,
    private val backoffMaxMs: Long = RelayProtocol.RECONNECT_MAX_MS,
    private val pingEveryMs: Long = RelayProtocol.PING_EVERY_MS,
    private val localClock: () -> Long = { System.currentTimeMillis() },
) : Transport {
    companion object {
        const val NAME = "relay"
        private const val TAG = "Relay"
        private const val ECHO_TIMEOUT_MS = 10_000L
        private val ECHO_MARKER = "ECHO".toByteArray(Charsets.US_ASCII)
        fun endpointOf(nodeId: String) = PeerEndpoint(NAME, nodeId, "relay:${nodeId.take(8)}")
    }

    val selfId: String = Wire.nodeIdOf(signer.publicKeyB64) ?: error("signer public key is not base64")

    override val name: String = NAME
    override val kind: LinkKind get() = LinkKind.WAN_RELAY

    private val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 1)
    override val pairingRequests: SharedFlow<PairingRequest> = pairingFlow
    private val statusState = MutableStateFlow("stopped")
    override val status: StateFlow<String> = statusState

    private val authenticatedState = MutableStateFlow(false)
    /** True between `auth_ok` and the socket dropping. */
    val authenticated: StateFlow<Boolean> = authenticatedState
    private val relayDownState = MutableStateFlow(false)
    /** True from a failed connect or a dropped socket until the next `auth_ok` (spec 14 "Relay down"). */
    val relayDown: StateFlow<Boolean> = relayDownState
    /** Local time the current outage began, or null while connected. */
    @Volatile var outageSince: Long? = null
        private set
    private val membersState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Roster members → online, from `auth_ok` and every `presence` since. */
    val members: StateFlow<Map<String, Boolean>> = membersState
    private val turnState = MutableStateFlow<TurnCredentials?>(null)
    /** TURN credentials issued at the last `auth_ok`, for [WebRtcBlobTransport]. */
    val turn: StateFlow<TurnCredentials?> = turnState
    private val dropsFlow = MutableSharedFlow<RelayFrame>(replay = 0, extraBufferCapacity = 64)
    /** Every `drop` the relay sent us (expired hold, not a member, rate limited …). */
    val drops: SharedFlow<RelayFrame> = dropsFlow
    /** Reason of the last `auth_fail`, or null. */
    @Volatile var lastAuthFailure: String? = null
        private set
    /** Relay time of the last `presence` or `auth_ok`, per member: when the state last changed. */
    private val since = HashMap<String, Long>()

    private val presenceHandlers = java.util.concurrent.CopyOnWriteArrayList<(nodeId: String, online: Boolean, since: Long) -> Unit>()
    private val lock = Any()
    @Volatile private var socket: RelaySocket? = null
    @Volatile private var fatal = false
    private var loop: Job? = null
    private var pinger: Job? = null
    private val ids = AtomicLong(1)
    private val echoes = HashMap<Long, CompletableDeferred<Long>>()
    private var rosterReply: CompletableDeferred<RelayFrame>? = null
    private var inviteReply: CompletableDeferred<RelayFrame>? = null

    // ------------------------------------------------------------------ lifecycle

    override suspend fun start(localName: String) {
        synchronized(lock) {
            if (loop != null) return
            fatal = false
            statusState.value = "connecting"
            loop = scope.launch { runLoop() }
        }
    }

    override suspend fun stop() {
        val s: RelaySocket?
        synchronized(lock) {
            loop?.cancel(); loop = null
            pinger?.cancel(); pinger = null
            s = socket; socket = null
        }
        s?.close()
        markDown("stopped", down = false)
        statusState.value = "stopped"
    }

    private suspend fun runLoop() {
        var backoff = backoffMinMs
        while (scope.isActive && !fatal) {
            val closed = CompletableDeferred<String>()
            var authedThisAttempt = false
            // Frames can arrive before connect() returns (the fake relay sends the challenge synchronously); they wait
            // until the socket is known so the challenge can be answered on it.
            val early = ArrayList<String>()
            var ready = false
            val s = try {
                sockets.connect(url, object : RelaySocketListener {
                    override fun onText(text: String) {
                        synchronized(early) { if (!ready) { early += text; return } }
                        onFrame(text)
                        if (authenticatedState.value) authedThisAttempt = true
                    }
                    override fun onClosed(reason: String) { closed.complete(reason) }
                })
            } catch (e: Exception) {
                SLog.w(TAG, "connect failed: ${e.message}")
                null
            }
            if (s != null) {
                synchronized(lock) { socket = s }
                val replay = synchronized(early) { ready = true; early.toList().also { early.clear() } }
                replay.forEach { onFrame(it); if (authenticatedState.value) authedThisAttempt = true }
                val reason = closed.await()
                SLog.d(TAG, "socket closed: $reason")
                synchronized(lock) { if (socket === s) socket = null }
                markDown(reason, down = true)
                if (reason == RelayProtocol.REASON_REPLACED) fatal = true // another socket of this node took over
            } else {
                markDown("connect failed", down = true)
            }
            if (fatal) break
            backoff = if (authedThisAttempt) backoffMinMs else minOf(backoff * 2, backoffMaxMs)
            statusState.value = "reconnecting in ${backoff / 1000} s"
            delay(backoff)
        }
        synchronized(lock) { loop = null }
    }

    private fun markDown(reason: String, down: Boolean) {
        synchronized(lock) { pinger?.cancel(); pinger = null }
        authenticatedState.value = false
        connectedState.value = emptyList()
        if (down && outageSince == null) outageSince = localClock()
        relayDownState.value = down
        statusState.value = reason
        synchronized(echoes) { echoes.values.forEach { it.cancel() }; echoes.clear() }
    }

    /** Waits for `auth_ok` up to [timeoutMs] (default: the 60 s outage budget). False means the WAN attempt faults. */
    suspend fun awaitOnline(timeoutMs: Long = RelayProtocol.OUTAGE_WAIT_MS): Boolean =
        authenticatedState.value || withTimeoutOrNull(timeoutMs) { authenticatedState.first { it } } != null

    // ------------------------------------------------------------------ frames in

    private fun onFrame(text: String) {
        val f = RelayFrames.decode(text) ?: run { SLog.w(TAG, "bad frame: ${text.take(80)}"); return }
        when (f.type) {
            RelayProtocol.CHALLENGE -> onChallenge(f)
            RelayProtocol.AUTH_OK -> onAuthOk(f)
            RelayProtocol.AUTH_FAIL -> {
                lastAuthFailure = f.reason
                statusState.value = "auth failed: ${f.reason}"
                if (f.reason == RelayProtocol.REASON_REVOKED) fatal = true
            }
            RelayProtocol.MSG -> onMsg(f)
            RelayProtocol.PRESENCE -> {
                val id = f.nodeId ?: return
                val online = f.online ?: return
                val at = f.since ?: clock.now()
                synchronized(since) { since[id] = at }
                membersState.value = membersState.value + (id to online)
                publishConnected()
                presenceHandlers.forEach { h -> runCatching { h(id, online, at) }.onFailure { SLog.w(TAG, "presence handler: ${it.message}") } }
            }
            RelayProtocol.PONG -> { val ts = f.ts; val st = f.serverTs; if (ts != null && st != null) clock.syncRoundTrip(st, ts, localClock()) }
            RelayProtocol.PING -> { f.serverTs?.let { clock.syncOneWay(it) }; socket?.send(RelayFrames.encode(RelayFrame(RelayProtocol.PONG, ts = f.ts))) }
            RelayProtocol.DROP -> { dropsFlow.tryEmit(f); SLog.d(TAG, "drop to ${f.to?.take(8)}: ${f.reason}") }
            RelayProtocol.SENT -> {}
            RelayProtocol.ROSTER_OK, RelayProtocol.ROSTER_FAIL -> synchronized(lock) { rosterReply?.complete(f); rosterReply = null }
            RelayProtocol.INVITE_OK, RelayProtocol.INVITE_FAIL -> synchronized(lock) { inviteReply?.complete(f); inviteReply = null }
            else -> SLog.d(TAG, "ignored frame ${f.type}")
        }
    }

    private fun onChallenge(f: RelayFrame) {
        val nonce = f.nonce ?: return
        f.serverTs?.let { clock.syncOneWay(it) }
        val s = socket ?: return
        rosterUpload()?.let { s.send(RelayFrames.encode(RelayFrames.roster(it))) }
        val sig = Base64.getEncoder().encodeToString(signer.sign(RelayProtocol.authBytes(nonce)))
        val auth = RelayFrame(RelayProtocol.AUTH, nodeId = selfId, groupId = groupId, rosterVersion = rosterVersion(), sig = sig,
            pushToken = pushToken(), invite = guest?.token, pubkey = guest?.let { signer.publicKeyB64 }, inviter = guest?.inviterNodeId)
        s.send(RelayFrames.encode(auth))
    }

    private fun onAuthOk(f: RelayFrame) {
        f.serverTs?.let { clock.syncOneWay(it) }
        turnState.value = f.turn
        val now = clock.now()
        val map = LinkedHashMap<String, Boolean>()
        f.members?.forEach { m -> map[m.nodeId] = m.online; synchronized(since) { since.putIfAbsent(m.nodeId, now) } }
        membersState.value = map
        outageSince = null
        relayDownState.value = false
        lastAuthFailure = null
        statusState.value = "online (${map.count { it.value }} of ${map.size} members online)"
        connectedState.value = onlinePeers()   // before `authenticated`, so a waiter sees the peers at once
        authenticatedState.value = true
        synchronized(lock) {
            pinger?.cancel()
            pinger = scope.launch {
                while (isActive) {
                    delay(pingEveryMs)
                    socket?.send(RelayFrames.encode(RelayFrames.ping(localClock())))
                }
            }
        }
    }

    private fun onlinePeers(): List<PeerEndpoint> = membersState.value.filter { it.value && it.key != selfId }.keys.map { endpointOf(it) }

    private fun publishConnected() {
        if (!authenticatedState.value) return
        connectedState.value = onlinePeers()
    }

    private fun onMsg(f: RelayFrame) {
        val from = f.from ?: return
        val bytes = try { Base64.getDecoder().decode(f.cipher ?: return) } catch (_: Exception) { return }
        if (from == selfId && isEcho(bytes)) {
            val id = ByteBuffer.wrap(bytes, ECHO_MARKER.size, 8).long
            synchronized(echoes) { echoes.remove(id) }?.complete(localClock())
            return
        }
        inboundFlow.tryEmit(Inbound(endpointOf(from), bytes))
    }

    // ------------------------------------------------------------------ frames out

    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        if (bytes.size > RelayProtocol.MAX_CIPHER_BYTES) return Result.failure(IllegalArgumentException("${bytes.size} bytes exceed the relay cap"))
        val s = socket
        if (s == null || !authenticatedState.value) return Result.failure(IllegalStateException("relay not connected"))
        val frame = RelayFrames.msg(endpoint.endpointId, selfId, Base64.getEncoder().encodeToString(bytes), id = ids.getAndIncrement())
        return if (s.send(RelayFrames.encode(frame))) Result.success(Unit) else Result.failure(IllegalStateException("relay socket refused the frame"))
    }

    /** Presence as the relay reports it: `(nodeId, online, since)`; feeds the profile beacon like LAN discovery. */
    fun onPresence(handler: (nodeId: String, online: Boolean, since: Long) -> Unit) { presenceHandlers += handler }

    /** When [nodeId] last changed state (relay time), or null if never seen. */
    fun since(nodeId: String): Long? = synchronized(since) { since[nodeId] }

    /** Asks the relay to wake a sleeping member through FCM (spec 5.5). False if we are not connected. */
    fun wake(peer: String): Boolean {
        val s = socket ?: return false
        if (!authenticatedState.value) return false
        return s.send(RelayFrames.encode(RelayFrames.wake(peer)))
    }

    /** Uploads an admin-signed roster now (also sent automatically before every auth via `rosterUpload`). */
    suspend fun uploadRoster(body: RosterBody, timeoutMs: Long = 10_000): Result<Long> {
        val s = socket ?: return Result.failure(IllegalStateException("relay not connected"))
        val d = CompletableDeferred<RelayFrame>()
        synchronized(lock) { rosterReply = d }
        s.send(RelayFrames.encode(RelayFrames.roster(body)))
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: return Result.failure(IllegalStateException("no roster reply"))
        return if (r.type == RelayProtocol.ROSTER_OK) Result.success(r.version ?: body.version) else Result.failure(IllegalStateException(r.reason ?: "roster_fail"))
    }

    /** Opens an invite with the relay (inviter side of a remote pairing): a joiner holding [token] may then reach us. */
    suspend fun openInvite(token: String, timeoutMs: Long = 10_000): Result<Int> {
        val s = socket ?: return Result.failure(IllegalStateException("relay not connected"))
        if (!authenticatedState.value) return Result.failure(IllegalStateException("relay not authenticated"))
        val d = CompletableDeferred<RelayFrame>()
        synchronized(lock) { inviteReply = d }
        s.send(RelayFrames.encode(RelayFrames.invite(token)))
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: return Result.failure(IllegalStateException("no invite reply"))
        return if (r.type == RelayProtocol.INVITE_OK) Result.success(r.ttlS ?: 0) else Result.failure(IllegalStateException(r.reason ?: "invite_fail"))
    }

    // ------------------------------------------------------------------ link measurement

    private fun isEcho(bytes: ByteArray): Boolean =
        bytes.size >= ECHO_MARKER.size + 8 && ECHO_MARKER.indices.all { bytes[it] == ECHO_MARKER[it] }

    /**
     * 2 KB echo through the relay (spec 5.6): a `msg` to self, timed. The relay path is the same for every peer, so
     * the measurement does not depend on [endpoint]; metered is overlaid by [LinkProbe].
     */
    override suspend fun measureLink(endpoint: PeerEndpoint): Link {
        val s = socket ?: return Link.defaultFor(kind)
        if (!authenticatedState.value) return Link.defaultFor(kind)
        val id = ids.getAndIncrement()
        val payload = ByteArray(RelayProtocol.LINK_ECHO_BYTES)
        System.arraycopy(ECHO_MARKER, 0, payload, 0, ECHO_MARKER.size)
        ByteBuffer.wrap(payload, ECHO_MARKER.size, 8).putLong(id)
        val d = CompletableDeferred<Long>()
        synchronized(echoes) { echoes[id] = d }
        val t0 = localClock()
        s.send(RelayFrames.encode(RelayFrames.msg(selfId, selfId, Base64.getEncoder().encodeToString(payload))))
        val t1 = withTimeoutOrNull(ECHO_TIMEOUT_MS) { d.await() }
        synchronized(echoes) { echoes.remove(id) }
        if (t1 == null) return Link.defaultFor(kind)
        val rtt = maxOf(1L, t1 - t0)
        val kbps = (2L * payload.size * 8 / rtt).coerceIn(1L, 1_000_000L).toInt()
        return Link(kind, kbps, rtt.toInt(), metered = false)
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) {}
    override fun setPairingMode(on: Boolean) {}
}
