package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Transport
import com.hivecore.core.fakes.FakeDataChannelNetwork
import com.hivecore.core.fakes.FakeDataChannelPeer
import com.hivecore.core.fakes.FakeIceCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

// ------------------------------------------------------------------ the peer seam

/** One ICE candidate as `SIGNAL {kind: candidate}` carries it (spec 16.1). */
data class IceCandidateInfo(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)

/** TURN as the relay issued it (spec 20.1); [from] maps the `auth_ok` credentials. */
data class TurnConfig(val urls: List<String>, val username: String, val credential: String) {
    companion object { fun from(c: TurnCredentials) = TurnConfig(c.urls, c.username, c.credential) }
}

/** ICE servers for one connection: STUN first, TURN as fallback (spec 5.4). */
data class IceConfig(val stun: List<String>, val turn: TurnConfig?)

/**
 * One peer connection carrying one ordered, reliable data channel. [WebRtcPeers] builds it over libwebrtc on the
 * phone; [FakeDataChannelPeers] over [FakeDataChannelNetwork] on the JVM. Callbacks fire on the library's thread.
 */
interface DataChannelPeer {
    var onIceCandidate: (IceCandidateInfo) -> Unit
    var onOpen: () -> Unit
    var onMessage: (ByteArray) -> Unit
    var onClosed: (reason: String) -> Unit
    /** Creates the channel and an offer; sets it as the local description. */
    suspend fun createOffer(): String
    /** Applies a remote offer and returns the answer (set as local description). */
    suspend fun acceptOffer(sdp: String): String
    suspend fun acceptAnswer(sdp: String)
    fun addIceCandidate(c: IceCandidateInfo)
    val open: Boolean
    fun send(bytes: ByteArray): Boolean
    fun close()
}

fun interface DataChannelPeerFactory { fun create(ice: IceConfig, initiator: Boolean): DataChannelPeer }

// ------------------------------------------------------------------ the transport

/**
 * Bulk transfer over data channels (spec 5.4; ladder rank 4, `WAN_DIRECT`). Signalling rides inside the sealed
 * envelope as `SIGNAL` bodies through [signal] (the integration wave wires it to the router's `send(to, SIGNAL)`
 * and feeds inbound `SIGNAL`s to [handleSignal]). ICE tries STUN first and TURN from the relay's credentials.
 *
 * Every message is cut into ordered pieces of at most [pieceBytes] (16 KB, one data channel message each) and
 * reassembled on the far side; the envelope cap keeps a message within five pieces. Blob reassembly and the
 * hash-before-write rule belong to `BlobStore`; this transport only delivers bytes.
 *
 * If no channel opens within [stallMs] (15 s) or a send fails, the peer is marked [stalled] for [retryAfterMs] and
 * bytes go through [fallback] (the relay) at a capped rate of [fallbackKbps]; the transfer rule reads [stalled]
 * to keep media at home.
 */
open class DataChannelTransport(
    val selfId: String,
    private val signal: suspend (to: String, body: SignalBody) -> Result<Unit>,
    private val peers: DataChannelPeerFactory,
    private val scope: CoroutineScope,
    private val turn: () -> TurnConfig? = { null },
    private val stun: List<String> = DEFAULT_STUN,
    private val fallback: (suspend (to: String, bytes: ByteArray) -> Result<Unit>)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val stallMs: Long = STALL_MS,
    private val fallbackKbps: Int = FALLBACK_KBPS,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    private val pieceBytes: Int = PIECE_BYTES,
) : Transport {
    companion object {
        const val NAME = "webrtc"
        private const val TAG = "DataChannel"
        const val STALL_MS = 15_000L
        const val FALLBACK_KBPS = 512
        const val RETRY_AFTER_MS = 60_000L
        const val PIECE_BYTES = 16 * 1024
        const val HEADER_BYTES = 8
        /** Envelope cap plus sealing overhead. */
        const val MAX_MESSAGE_BYTES = 64 * 1024 + 1024
        private const val ECHO_TIMEOUT_MS = 10_000L
        private const val CONTROL_TOTAL = 0
        private const val PING = 0
        private const val PONG = 1
        val DEFAULT_STUN = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
        fun endpointOf(nodeId: String) = PeerEndpoint(NAME, nodeId, "p2p:${nodeId.take(8)}")
    }

    private inner class Channel(val peerId: String, val sessionId: String, val initiator: Boolean, val peer: DataChannelPeer) {
        val opened = CompletableDeferred<Boolean>()
        @Volatile var remoteSet = false
        val pendingCandidates = ArrayList<IceCandidateInfo>()
        val nextId = AtomicInteger(1)
        // reassembly of the message in flight (ordered channel: pieces arrive in order or the message is lost)
        var curId = -1
        var curTotal = 0
        val parts = ByteArrayOutputStream()
        var partsSeen = 0
    }

    override val name: String = NAME
    override val kind: LinkKind get() = LinkKind.WAN_DIRECT
    private val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 1)
    override val pairingRequests: SharedFlow<PairingRequest> = pairingFlow
    private val statusState = MutableStateFlow("stopped")
    override val status: StateFlow<String> = statusState
    private val stalledState = MutableStateFlow<Set<String>>(emptySet())
    /** Peers whose channel failed or stalled recently: their bytes go through the fallback until [retryAfterMs] passes. */
    val stalled: StateFlow<Set<String>> = stalledState

    private val lock = Any()
    private val channels = HashMap<String, Channel>()
    /** Candidates that arrived before their offer (ICE gathers while the offer is still in flight), per session. */
    private val earlyCandidates = HashMap<String, ArrayList<IceCandidateInfo>>()
    private val stalledUntil = HashMap<String, Long>()
    private val echoes = HashMap<Int, CompletableDeferred<Long>>()
    private val echoIds = AtomicInteger(1)
    private val fallbackMutex = Mutex()
    private var fallbackNextAt = 0L
    private val random = SecureRandom()
    @Volatile private var started = false
    /** Fallback sends so far, for the debug screen and tests. */
    @Volatile var fallbackCount: Int = 0
        private set

    // ------------------------------------------------------------------ Transport

    override suspend fun start(localName: String) { started = true; statusState.value = "ready" }

    override suspend fun stop() {
        started = false
        val all = synchronized(lock) { channels.values.toList().also { channels.clear() } }
        all.forEach { ch -> ch.peer.close(); runCatching { signal(ch.peerId, SignalBody(ch.sessionId, "close", selfId, ch.peerId)) } }
        publish()
        statusState.value = "stopped"
    }

    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        if (bytes.size > MAX_MESSAGE_BYTES) return Result.failure(IllegalArgumentException("${bytes.size} bytes exceed the data channel message cap"))
        if (!started) return Result.failure(IllegalStateException("data channel transport not started"))
        val peerId = endpoint.endpointId
        val ch = ensureChannel(peerId)
        if (ch != null) {
            val opened = withTimeoutOrNull(stallMs) { ch.opened.await() } ?: false
            if (opened && ch.peer.open) {
                val id = ch.nextId.getAndIncrement()
                if (pieces(id, bytes).all { ch.peer.send(it) }) return Result.success(Unit)
                fail(ch, "send failed")
            } else {
                fail(ch, if (opened) "channel closed" else "no data channel within ${stallMs / 1000} s")
            }
        }
        return viaFallback(peerId, bytes)
    }

    /** Opens a channel to [peerId] ahead of the first send (the orchestrator does this when a plan names a remote node). */
    fun open(peerId: String) { scope.launch { ensureChannel(peerId) } }

    fun close(peerId: String) {
        val ch = synchronized(lock) { channels.remove(peerId) } ?: return
        ch.peer.close()
        ch.opened.complete(false)
        publish()
        scope.launch { signal(peerId, SignalBody(ch.sessionId, "close", selfId, peerId)) }
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) {}
    override fun setPairingMode(on: Boolean) {}

    /** 2 KB echo over the open channel (spec 5.6); the planning figure when there is none. */
    override suspend fun measureLink(endpoint: PeerEndpoint): Link {
        val ch = synchronized(lock) { channels[endpoint.endpointId] } ?: return Link.defaultFor(kind)
        if (!ch.peer.open) return Link.defaultFor(kind)
        val id = echoIds.getAndIncrement()
        val d = CompletableDeferred<Long>()
        synchronized(lock) { echoes[id] = d }
        val t0 = clock()
        ch.peer.send(control(id, PING, RelayProtocol.LINK_ECHO_BYTES))
        val t1 = withTimeoutOrNull(ECHO_TIMEOUT_MS) { d.await() }
        synchronized(lock) { echoes.remove(id) }
        if (t1 == null) return Link.defaultFor(kind)
        val rtt = maxOf(1L, t1 - t0)
        val kbps = (2L * RelayProtocol.LINK_ECHO_BYTES * 8 / rtt).coerceIn(1L, 1_000_000L).toInt()
        return Link(kind, kbps, rtt.toInt(), metered = false)
    }

    // ------------------------------------------------------------------ signalling

    /** Inbound `SIGNAL` from the router (already verified: `body.from == envelope.from`). */
    suspend fun handleSignal(body: SignalBody) {
        if (body.to != selfId) return
        val from = body.from
        when (body.kind) {
            "offer" -> {
                val sdp = body.sdp ?: return
                val existing = synchronized(lock) { channels[from] }
                if (existing != null && !existing.opened.isCompleted && existing.initiator && selfId < from) {
                    SLog.d(TAG, "glare with ${from.take(8)}: keeping our offer"); return
                }
                if (existing != null) { synchronized(lock) { channels.remove(from) }; existing.peer.close(); existing.opened.complete(false) }
                val ch = newChannel(from, body.sessionId, initiator = false)
                synchronized(lock) { earlyCandidates.remove(body.sessionId)?.let { ch.pendingCandidates += it } }
                val answer = try { ch.peer.acceptOffer(sdp) } catch (e: Exception) { fail(ch, "offer rejected: ${e.message}"); return }
                remoteReady(ch)
                signal(from, SignalBody(ch.sessionId, "answer", selfId, from, sdp = answer))
            }
            "answer" -> {
                val ch = channelFor(from, body.sessionId) ?: return
                try { ch.peer.acceptAnswer(body.sdp ?: return) } catch (e: Exception) { fail(ch, "answer rejected: ${e.message}"); return }
                remoteReady(ch)
            }
            "candidate" -> {
                val c = IceCandidateInfo(body.candidate ?: return, body.sdpMid, body.sdpMLineIndex)
                val ch = channelFor(from, body.sessionId) ?: run {
                    synchronized(lock) {
                        if (earlyCandidates.size < 64) earlyCandidates.getOrPut(body.sessionId) { ArrayList() }.let { if (it.size < 32) it += c }
                    }
                    return
                }
                val now = synchronized(lock) { if (ch.remoteSet) true else { ch.pendingCandidates += c; false } }
                if (now) ch.peer.addIceCandidate(c)
            }
            "close" -> {
                val ch = channelFor(from, body.sessionId) ?: return
                synchronized(lock) { channels.remove(from) }
                ch.peer.close()
                ch.opened.complete(false)
                publish()
            }
        }
    }

    private fun channelFor(peerId: String, sessionId: String): Channel? =
        synchronized(lock) { channels[peerId]?.takeIf { it.sessionId == sessionId } }

    private fun remoteReady(ch: Channel) {
        val flush = synchronized(lock) { ch.remoteSet = true; ch.pendingCandidates.toList().also { ch.pendingCandidates.clear() } }
        flush.forEach { ch.peer.addIceCandidate(it) }
    }

    private fun iceConfig() = IceConfig(stun, turn())

    private suspend fun ensureChannel(peerId: String): Channel? {
        val existing = synchronized(lock) {
            channels[peerId] ?: run {
                val until = stalledUntil[peerId]
                if (until != null && clock() < until) return null
                stalledUntil.remove(peerId)
                null
            }
        }
        if (existing != null) return existing
        val ch = newChannel(peerId, sessionId(), initiator = true)
        val offer = try { ch.peer.createOffer() } catch (e: Exception) { fail(ch, "offer failed: ${e.message}"); return null }
        signal(peerId, SignalBody(ch.sessionId, "offer", selfId, peerId, sdp = offer))
        return ch
    }

    private fun newChannel(peerId: String, sessionId: String, initiator: Boolean): Channel {
        val peer = peers.create(iceConfig(), initiator)
        val ch = Channel(peerId, sessionId, initiator, peer)
        peer.onIceCandidate = { c -> scope.launch { signal(peerId, SignalBody(sessionId, "candidate", selfId, peerId, candidate = c.candidate, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) } }
        peer.onOpen = {
            ch.opened.complete(true)
            synchronized(lock) { stalledUntil.remove(peerId) }
            stalledState.value = synchronized(lock) { stalledUntil.keys.toSet() }
            publish()
        }
        peer.onMessage = { bytes -> onPiece(ch, bytes) }
        peer.onClosed = { reason ->
            val current = synchronized(lock) { if (channels[peerId] === ch) { channels.remove(peerId); true } else false }
            ch.opened.complete(false)
            if (current) { SLog.d(TAG, "channel to ${peerId.take(8)} closed: $reason"); publish() }
        }
        synchronized(lock) { channels[peerId] = ch }
        return ch
    }

    private fun fail(ch: Channel, reason: String) {
        SLog.w(TAG, "channel to ${ch.peerId.take(8)}: $reason; falling back for ${retryAfterMs / 1000} s")
        synchronized(lock) {
            if (channels[ch.peerId] === ch) channels.remove(ch.peerId)
            stalledUntil[ch.peerId] = clock() + retryAfterMs
            stalledState.value = stalledUntil.keys.toSet()
        }
        ch.opened.complete(false)
        ch.peer.close()
        publish()
        scope.launch { signal(ch.peerId, SignalBody(ch.sessionId, "close", selfId, ch.peerId)) }
    }

    private suspend fun viaFallback(peerId: String, bytes: ByteArray): Result<Unit> {
        val fb = fallback ?: return Result.failure(IllegalStateException("no data channel to ${peerId.take(8)} and no fallback"))
        fallbackMutex.withLock {
            val now = clock()
            val at = maxOf(now, fallbackNextAt)
            fallbackNextAt = at + bytes.size * 8L / maxOf(1, fallbackKbps)
            if (at > now) delay(at - now)
        }
        fallbackCount++
        return fb(peerId, bytes)
    }

    private fun publish() {
        connectedState.value = synchronized(lock) { channels.values.filter { it.peer.open }.map { endpointOf(it.peerId) } }
    }

    private fun sessionId(): String = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ pieces

    /** `msgId(4) index(2) total(2) payload`; total 0 marks a control frame (echo). */
    private fun pieces(id: Int, bytes: ByteArray): List<ByteArray> {
        val max = pieceBytes - HEADER_BYTES
        val total = maxOf(1, (bytes.size + max - 1) / max)
        require(total <= 0xFFFF) { "too many pieces" }
        return (0 until total).map { i ->
            val from = i * max
            val len = minOf(max, bytes.size - from)
            ByteBuffer.allocate(HEADER_BYTES + len).putInt(id).putShort(i.toShort()).putShort(total.toShort()).put(bytes, from, len).array()
        }
    }

    private fun control(id: Int, kind: Int, size: Int): ByteArray =
        ByteBuffer.allocate(maxOf(HEADER_BYTES, size)).putInt(id).putShort(kind.toShort()).putShort(CONTROL_TOTAL.toShort()).array()

    private fun onPiece(ch: Channel, piece: ByteArray) {
        if (piece.size < HEADER_BYTES) return
        val buf = ByteBuffer.wrap(piece)
        val id = buf.int
        val index = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        if (total == CONTROL_TOTAL) {
            when (index) {
                PING -> ch.peer.send(control(id, PONG, piece.size))
                PONG -> synchronized(lock) { echoes.remove(id) }?.complete(clock())
            }
            return
        }
        synchronized(ch) {
            if (index == 0) { ch.curId = id; ch.curTotal = total; ch.parts.reset(); ch.partsSeen = 0 }
            else if (id != ch.curId || index != ch.partsSeen) { SLog.w(TAG, "piece $index of $id out of order; dropping message"); ch.curId = -1; return }
            ch.parts.write(piece, HEADER_BYTES, piece.size - HEADER_BYTES)
            ch.partsSeen++
            if (ch.partsSeen == ch.curTotal) {
                val msg = ch.parts.toByteArray()
                ch.curId = -1
                inboundFlow.tryEmit(Inbound(endpointOf(ch.peerId), msg))
            }
        }
    }
}

// ------------------------------------------------------------------ JVM fake peers

/** [DataChannelPeerFactory] over [FakeDataChannelNetwork] so WAN bulk scenarios run on the JVM. */
class FakeDataChannelPeers(private val net: FakeDataChannelNetwork, private val nodeId: String) : DataChannelPeerFactory {
    override fun create(ice: IceConfig, initiator: Boolean): DataChannelPeer = Adapter(net.peer(nodeId, ice.turn != null))

    private class Adapter(val fake: FakeDataChannelPeer) : DataChannelPeer {
        override var onIceCandidate: (IceCandidateInfo) -> Unit = {}
            set(v) { field = v; fake.onIceCandidate = { c: FakeIceCandidate -> v(IceCandidateInfo(c.candidate, c.sdpMid, c.sdpMLineIndex)) } }
        override var onOpen: () -> Unit = {}
            set(v) { field = v; fake.onOpen = v }
        override var onMessage: (ByteArray) -> Unit = {}
            set(v) { field = v; fake.onMessage = v }
        override var onClosed: (String) -> Unit = {}
            set(v) { field = v; fake.onClosed = v }
        override suspend fun createOffer(): String = fake.createOffer()
        override suspend fun acceptOffer(sdp: String): String = fake.acceptOffer(sdp)
        override suspend fun acceptAnswer(sdp: String) = fake.acceptAnswer(sdp)
        override fun addIceCandidate(c: IceCandidateInfo) = fake.addIceCandidate(FakeIceCandidate(c.candidate, c.sdpMid, c.sdpMLineIndex))
        override val open: Boolean get() = fake.open
        override fun send(bytes: ByteArray): Boolean = fake.send(bytes)
        override fun close() = fake.close()
    }
}
