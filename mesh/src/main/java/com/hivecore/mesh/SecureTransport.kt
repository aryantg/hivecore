package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.core.Transport
import com.hivecore.core.Verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * v2 plan §4.2 / rule C8: every link is end-to-end encrypted and mutually authenticated before any envelope moves.
 *
 * ## Handshake (Noise XX shape, signature-based static authentication)
 *
 * Android Keystore signing keys cannot do ECDH, so the static step of Noise XX (`es`, `se`) is replaced by each side
 * signing the running transcript hash with its pinned P-256 node key. Ephemeral keys are fresh P-256 pairs per
 * handshake (JCA `KeyAgreement("ECDH")`); keys come from HKDF-SHA256; frames are sealed with AES-256-GCM.
 *
 * ```
 *   h0 = SHA256(PROTOCOL_NAME)
 *   -> SESSION_INIT  e_I                                   h1 = SHA256(h0 || INIT frame)
 *   <- SESSION_RESP  e_R, AEAD(k1, {s_R, sig_R(h2)})       h2 = SHA256(h1 || e_R); ee = ECDH(e_I, e_R)
 *                                                          k1 | k2 = HKDF(salt = h2, ikm = ee, "sn hs", 64)
 *                                                          h3 = SHA256(h2 || RESP ciphertext)
 *   -> SESSION_FIN   AEAD(k2, {s_I, sig_I(h3)})            h4 = SHA256(h3 || FIN ciphertext)  (the handshake hash)
 *   transport keys:  k_I→R | k_R→I = HKDF(salt = h4, ikm = ee, "sn transport", 64)
 * ```
 *
 * `sig_R` is over `"SN-SESSION-RESP\0" || h2` and `sig_I` over `"SN-SESSION-INIT\0" || h3`; both cover both
 * ephemeral keys, so a man in the middle cannot forward one side's signature into its own handshake. The static keys
 * travel encrypted (identity hiding, as in XX). Each side then checks the peer's static key: it must hash to a nodeId
 * whose pinned key is exactly this key, or (no pin yet) the peer must be admitted for pairing (router pairing mode, or
 * [SecureTransport.allowUnpinned] for an SAS pairing in progress). The handshake hash `h4` feeds the 6-digit SAS.
 *
 * Simultaneous open: both sides send INIT when a link appears. A side holding its own outstanding INIT that receives
 * the peer's INIT compares the two ephemeral keys (unsigned bytes): the lower key stays initiator, the higher one
 * drops its INIT and answers as responder. Both compute the same order.
 *
 * ## Wire framing (first byte is the frame tag)
 *
 * | tag    | frame          | layout after the tag (u16 = big-endian length prefix)                                  |
 * | ------ | -------------- | -------------------------------------------------------------------------------------- |
 * | `0xA1` | SESSION_INIT   | proto(1) · u16 eI · eI (X.509 DER)                                                       |
 * | `0xA2` | SESSION_RESP   | proto(1) · u16 eR · eR · u16 ct · AES-GCM(k1, iv 0, aad h2, u16 s · s · u16 sig · sig)   |
 * | `0xA3` | SESSION_FIN    | proto(1) · u16 ct · AES-GCM(k2, iv 0, aad h3, u16 s · s · u16 sig · sig)                 |
 * | `0xAF` | SESSION_ABORT  | code(1); plaintext refusal, honoured only before the peer has sent an authenticated frame|
 * | `0xB0` | SEALED         | epoch(4) · seq(8) · AES-GCM(k_dir epoch, iv = epoch‖seq, aad = tag‖epoch‖seq, kind(1) · payload) |
 *
 * Handshake frames are plaintext framing only; everything after them is a SEALED frame. A legacy plaintext envelope
 * starts with `{` (0x7B), which is no tag above, so it is recognised and refused; any other leading byte is dropped.
 * Inside a SEALED frame `kind` is DATA (a v:2 envelope for the router), PING / PONG (the 2 KB link echo), CONTROL
 * (pairing messages for the trust layer) or CLOSE (authenticated teardown).
 *
 * ## Keys over time
 *
 * Each direction has its own key and a strictly increasing `seq` (the receiver drops `seq <= last`). The sender
 * ratchets its key (`k' = HKDF(k, "sn rekey")`, `epoch + 1`) after [REKEY_INTERVAL_MS] or [REKEY_BYTES]; the receiver
 * follows a higher epoch only after the frame authenticates under the ratcheted key. Old keys are discarded.
 */

/** Why a session could not be authenticated (one per [SecureTransport.onAuthFailure] call). */
data class AuthFailure(val peer: String, val endpoint: PeerEndpoint, val reason: String)

/** Receives session lifecycle and trust-layer control messages (pairing). Every method has an empty default. */
interface SessionListener {
    fun onOpen(session: Session) {}
    fun onClose(session: Session) {}
    fun onControl(session: Session, bytes: ByteArray) {}
}

/** §13.1, adapted to endpoint-addressed transports: a session is opened on an endpoint and found by nodeId. */
interface SessionManager {
    /** The session on [endpoint], running the handshake first when there is none. Throws when it fails or times out. */
    suspend fun open(endpoint: PeerEndpoint): Session
    /** The live session with node [peer], or null. */
    fun session(peer: String): Session?
    /** The measured link to [peer] (2 KB echo at session start and every 5 min); the transport's default when unmeasured. */
    fun link(peer: String): Link
}

/**
 * One established, mutually authenticated session. [seal] / [open] are the §13.1 data path (a SEALED frame of kind
 * DATA); [handshakeHash] is the transcript hash `h4` from which the pairing SAS is derived.
 */
class Session internal constructor(
    val endpoint: PeerEndpoint,
    val peerNodeId: String,
    val peerPubkey: String,
    val handshakeHash: ByteArray,
    val initiator: Boolean,
    sendKey: ByteArray,
    recvKey: ByteArray,
    val openedAt: Long,
    private val clock: () -> Long,
    defaultLink: Link,
    private val rekeyIntervalMs: Long,
    private val rekeyBytes: Long,
) {
    private var sendKey = sendKey
    private var sendEpoch = 0
    private var sendSeq = 0L
    private var sendEpochStart = openedAt
    private var sendEpochBytes = 0L
    private var recvKey = recvKey
    private var recvEpoch = 0
    private var recvSeq = -1L

    @Volatile var link: Link = defaultLink
        internal set
    /** True once a SEALED frame from the peer authenticated (an ABORT after that is ignored). */
    @Volatile var peerSpoke: Boolean = false
        private set
    @Volatile var closed: Boolean = false
        internal set
    internal val pings = LinkedHashMap<Long, Long>()
    /** Clock time of the last completed link echo (0 = none yet); the session layer re-measures when it is stale. */
    @Volatile internal var measuredAt: Long = 0L
    @Volatile internal var pingInFlight: Boolean = false

    /** Spec 6.2: the 6-digit short authentication string both phones show, derived from the handshake hash. */
    fun sas(): String = SecureTransport.sas(handshakeHash)

    /** Current send epoch (number of rekeys so far). */
    val epoch: Int get() = synchronized(this) { sendEpoch }

    fun seal(plain: ByteArray): ByteArray = sealKind(KIND_DATA, plain)

    /** Opens a SEALED frame of kind DATA. Throws [SecurityException] on any failure (bad tag, replay, auth). */
    fun open(cipher: ByteArray): ByteArray {
        val (kind, plain) = openFrame(cipher)
        if (kind != KIND_DATA) throw SecurityException("not a data frame (kind $kind)")
        return plain
    }

    internal fun sealKind(kind: Byte, payload: ByteArray): ByteArray = synchronized(this) {
        val now = clock()
        if (sendEpochBytes >= rekeyBytes || now - sendEpochStart >= rekeyIntervalMs) {
            sendKey = NoiseCrypto.ratchet(sendKey)
            sendEpoch++
            sendEpochStart = now
            sendEpochBytes = 0
        }
        val seq = ++sendSeq
        val header = header(sendEpoch, seq)
        val plain = ByteArray(payload.size + 1).also { it[0] = kind; System.arraycopy(payload, 0, it, 1, payload.size) }
        val ct = NoiseCrypto.seal(sendKey, iv(sendEpoch, seq), header, plain)
        sendEpochBytes += payload.size
        header + ct
    }

    internal fun openFrame(frame: ByteArray): Pair<Byte, ByteArray> = synchronized(this) {
        if (frame.size < HEADER + 16 + 1 || frame[0] != TAG_SEALED) throw SecurityException("not a sealed frame")
        val bb = ByteBuffer.wrap(frame)
        bb.get()
        val epoch = bb.int
        val seq = bb.long
        if (seq <= recvSeq) throw SecurityException("replayed or reordered frame (seq $seq <= $recvSeq)")
        if (epoch < recvEpoch) throw SecurityException("frame from a retired key epoch $epoch")
        if (epoch - recvEpoch > MAX_RATCHET_STEPS) throw SecurityException("key epoch $epoch is too far ahead")
        var key = recvKey
        repeat(epoch - recvEpoch) { key = NoiseCrypto.ratchet(key) }
        val header = frame.copyOfRange(0, HEADER)
        val plain = try {
            NoiseCrypto.open(key, iv(epoch, seq), header, frame.copyOfRange(HEADER, frame.size))
        } catch (e: Exception) {
            throw SecurityException("frame does not authenticate")
        }
        if (plain.isEmpty()) throw SecurityException("empty frame")
        recvKey = key; recvEpoch = epoch; recvSeq = seq
        peerSpoke = true
        plain[0] to plain.copyOfRange(1, plain.size)
    }

    override fun toString(): String = "Session(${peerNodeId.take(8)} via ${endpoint.displayName}, epoch=$sendEpoch)"

    companion object {
        const val TAG_SEALED: Byte = 0xB0.toByte()
        const val HEADER = 1 + 4 + 8
        /** Bytes a SEALED frame adds to its payload: header, kind byte and the 16-byte GCM tag. */
        const val OVERHEAD = HEADER + 1 + 16
        const val KIND_DATA: Byte = 0
        const val KIND_PING: Byte = 1
        const val KIND_PONG: Byte = 2
        const val KIND_CONTROL: Byte = 3
        const val KIND_CLOSE: Byte = 4
        /** How many rekeys a receiver follows in one step (a sender that rekeyed more often than this lost frames). */
        const val MAX_RATCHET_STEPS = 16

        private fun header(epoch: Int, seq: Long): ByteArray =
            ByteBuffer.allocate(HEADER).put(TAG_SEALED).putInt(epoch).putLong(seq).array()

        private fun iv(epoch: Int, seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(epoch).putLong(seq).array()
    }
}

/**
 * A [Transport] decorator that runs the handshake above on every link of [inner] and then carries only SEALED
 * frames. To [MeshRouter] it looks like the inner transport: same [name] and endpoints, but [connected] lists only
 * endpoints with an established session whose peer is pinned (or any session while pairing mode is on, so the
 * router's own HELLO pairing still works), and [inbound] carries decrypted envelopes only.
 *
 * @param inner          the carrier (Nearby, RFCOMM, LAN, relay, fake)
 * @param signer         this phone's node key; signs the handshake transcript
 * @param pinnedKeyOf    nodeId → pinned node key (base64 SPKI), null when not pinned (PeerStore)
 * @param clock          wall clock in ms (rekey timing, link echo)
 * @param scope          collectors and link measurement run here
 * @param allowUnpinned  admits an unpinned peer's handshake outside pairing mode (an SAS pairing in progress); such a
 *                       session carries only CONTROL frames until the peer is pinned
 * @param admitEndpoint  admits and exposes an unpinned peer on this endpoint: a transport-level pairing (Nearby digits)
 *                       the user accepted, whose first HELLO the router pins
 * @param onAuthFailure  (peer, reason) for the UI warning; peer is the claimed nodeId or `endpoint:<name>`
 * @param measureEveryMs after the echo at session start, a [link] or [measureLink] call older than this re-measures
 *                       in the background (no timer: a 5-minute loop would never let a virtual-time test go idle);
 *                       0 = only at session start
 */
class SecureTransport(
    val inner: Transport,
    private val signer: Signer,
    private val pinnedKeyOf: (String) -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope,
    var allowUnpinned: (nodeId: String, pubkey: String) -> Boolean = { _, _ -> false },
    var admitEndpoint: (endpoint: PeerEndpoint) -> Boolean = { false },
    onAuthFailure: (peer: String, reason: String) -> Unit = { _, _ -> },
    private val measureEveryMs: Long = LINK_MEASURE_INTERVAL_MS,
    private val rekeyIntervalMs: Long = REKEY_INTERVAL_MS,
    private val rekeyBytes: Long = REKEY_BYTES,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : Transport, SessionManager, Disconnectable {

    var onAuthFailure: (peer: String, reason: String) -> Unit = onAuthFailure
    val selfId: String = Wire.nodeIdOf(signer.publicKeyB64) ?: error("signer public key is not base64")

    override val name: String get() = inner.name
    override val kind: LinkKind get() = inner.kind
    override val pairingRequests: SharedFlow<PairingRequest> get() = inner.pairingRequests
    override val status: StateFlow<String> get() = inner.status

    private val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val failuresFlow = MutableSharedFlow<AuthFailure>(replay = 0, extraBufferCapacity = 64)
    /** Every authentication failure (also reported through [onAuthFailure]). */
    val authFailures: SharedFlow<AuthFailure> = failuresFlow
    private val sessionsState = MutableStateFlow<List<Session>>(emptyList())
    /** Every established session, pairing sessions included. */
    val sessions: StateFlow<List<Session>> = sessionsState

    private val lock = Any()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SessionListener>()
    // ---- guarded by `lock` ----
    private val byEndpoint = HashMap<String, Session>()
    private val pending = HashMap<String, Handshake>()
    private val waiters = HashMap<String, MutableList<CompletableDeferred<Session>>>()
    private var innerConnected: List<PeerEndpoint> = emptyList()
    private val collectors = ArrayList<Job>()
    private var pairingMode = false
    /** Frames refused because they were plaintext or unknown (C8 evidence for tests and the debug screen). */
    @Volatile var refusedFrames: Int = 0
        private set

    private class Handshake(val endpoint: PeerEndpoint, val initiator: Boolean, val e: KeyPair, val eDer: ByteArray, val startedAt: Long) {
        var h: ByteArray = ByteArray(0)
        var ee: ByteArray = ByteArray(0)
        var kFin: ByteArray = ByteArray(0)
        /** responder: the initiator's identity is still unknown; initiator: the verified responder */
        var peerNodeId: String? = null
        var peerPubkey: String? = null
    }

    private fun key(e: PeerEndpoint) = "${e.transport}/${e.endpointId}"

    fun addListener(l: SessionListener) { listeners += l }
    fun removeListener(l: SessionListener) { listeners -= l }

    // ------------------------------------------------------------------ Transport

    override suspend fun start(localName: String) {
        val inboundReady = CompletableDeferred<Unit>()
        synchronized(lock) {
            collectors.forEach { it.cancel() }
            collectors.clear()
            collectors += scope.launch { inner.inbound.onSubscription { inboundReady.complete(Unit) }.collect { onFrame(it) } }
        }
        inboundReady.await()
        inner.setPairingMode(synchronized(lock) { pairingMode })
        inner.start(localName)
        synchronized(lock) {
            collectors += scope.launch { inner.connected.collect { onInnerConnected(it) } }
            // A handshake that never completed (a lost or crossed frame) is dropped after the handshake timeout and
            // started again while the link is still there; otherwise the link stays dead for good.
            collectors += scope.launch {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    kotlinx.coroutines.delay(handshakeTimeoutMs / 3)
                    val stale = synchronized(lock) {
                        val now = clock()
                        pending.filterValues { now - it.startedAt > handshakeTimeoutMs }.keys.toList().also { ks -> ks.forEach { pending.remove(it) } }
                            .mapNotNull { k -> innerConnected.firstOrNull { key(it) == k && k !in byEndpoint } }
                    }
                    for (ep in stale) { SLog.d(TAG, "handshake with ${ep.displayName} timed out; starting again"); initiate(ep) }
                }
            }
        }
    }

    override suspend fun stop() {
        val closing: List<Session>
        synchronized(lock) {
            collectors.forEach { it.cancel() }
            collectors.clear()
            closing = byEndpoint.values.toList()
            byEndpoint.clear()
            pending.clear()
            innerConnected = emptyList()
            failWaiters(null, "transport stopped")
        }
        closing.forEach { retire(it) }
        publish()
        inner.stop()
    }

    /** Seals [bytes] (an envelope) for the session on [endpoint]. Fails when there is no session to a pinned peer. */
    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        val s = synchronized(lock) { byEndpoint[key(endpoint)] }
            ?: return Result.failure(IllegalStateException("no secure session with ${endpoint.displayName}"))
        if (!exposed(s)) return Result.failure(IllegalStateException("${s.peerNodeId.take(8)} is not paired"))
        return inner.send(endpoint, s.sealKind(Session.KIND_DATA, bytes))
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) = inner.acceptPairing(req, accept)

    override fun setPairingMode(on: Boolean) {
        synchronized(lock) { pairingMode = on }
        inner.setPairingMode(on)
        refresh()
    }

    override suspend fun measureLink(endpoint: PeerEndpoint): Link {
        val s = synchronized(lock) { byEndpoint[key(endpoint)] } ?: return inner.measureLink(endpoint)
        remeasureIfStale(s)
        return s.link
    }

    /** True while the carrier lists [endpoint], whether or not a session exists on it (the router keeps pairing state per link). */
    fun hasLink(endpoint: PeerEndpoint): Boolean = synchronized(lock) { innerConnected.any { key(it) == key(endpoint) } }

    /** Starts a handshake on [endpoint] when the carrier has the link but no session or handshake exists (a retry after a refusal). */
    fun retry(endpoint: PeerEndpoint) {
        val k = key(endpoint)
        val go = synchronized(lock) { innerConnected.any { key(it) == k } && k !in byEndpoint && k !in pending }
        if (go) scope.launch { initiate(endpoint) }
    }

    private fun remeasureIfStale(s: Session) {
        if (measureEveryMs <= 0 || s.closed) return
        val stale = synchronized(s) {
            if (s.pingInFlight || clock() - s.measuredAt < measureEveryMs) false else { s.pingInFlight = true; true }
        }
        if (stale) scope.launch { ping(s) }
    }

    override fun disconnect(endpoint: PeerEndpoint) {
        val s = synchronized(lock) { pending.remove(key(endpoint)); byEndpoint.remove(key(endpoint)) }
        if (s != null) { scope.launch { runCatching { inner.send(endpoint, s.sealKind(Session.KIND_CLOSE, ByteArray(0))) } }; retire(s) }
        publish()
        (inner as? Disconnectable)?.disconnect(endpoint)
    }

    // ------------------------------------------------------------------ SessionManager

    override suspend fun open(endpoint: PeerEndpoint): Session {
        val d = CompletableDeferred<Session>()
        val start: Boolean
        synchronized(lock) {
            byEndpoint[key(endpoint)]?.let { return it }
            waiters.getOrPut(key(endpoint)) { ArrayList() } += d
            start = key(endpoint) !in pending
        }
        if (start) initiate(endpoint)
        return withTimeout(handshakeTimeoutMs) { d.await() }
    }

    override fun session(peer: String): Session? = synchronized(lock) { byEndpoint.values.lastOrNull { it.peerNodeId == peer } }

    override fun link(peer: String): Link {
        val s = session(peer) ?: return Link.defaultFor(kind)
        remeasureIfStale(s)
        return s.link
    }

    /** Sends a CONTROL message (pairing) inside [session]. */
    suspend fun sendControl(session: Session, bytes: ByteArray): Result<Unit> {
        if (session.closed) return Result.failure(IllegalStateException("session closed"))
        return inner.send(session.endpoint, session.sealKind(Session.KIND_CONTROL, bytes))
    }

    /** Closes every session with [nodeId] (revocation, forget) and drops the link. */
    fun close(nodeId: String, reason: String = "closed") {
        val gone = synchronized(lock) { byEndpoint.values.filter { it.peerNodeId == nodeId } }
        for (s in gone) {
            SLog.d(TAG, "closing $s: $reason")
            disconnect(s.endpoint)
        }
    }

    /**
     * Re-evaluates every session against the pins (after a pin, a forget or a revocation): a peer that is neither
     * pinned nor admitted for pairing loses its session; one whose pinned key changed is an authentication failure.
     */
    fun refresh() {
        val sessions = synchronized(lock) { byEndpoint.values.toList() }
        for (s in sessions) {
            val pinned = pinnedKeyOf(s.peerNodeId)
            when {
                pinned != null && pinned != s.peerPubkey -> { fail(s.endpoint, s.peerNodeId, "node key differs from the pinned key"); disconnect(s.endpoint) }
                pinned == null && !admitted(s.endpoint, s.peerNodeId, s.peerPubkey) -> disconnect(s.endpoint)
            }
        }
        publish()
        // A link that lost its session to a refusal gets a new handshake now that the pins changed.
        val retry = synchronized(lock) { innerConnected.filter { key(it) !in byEndpoint && key(it) !in pending } }
        for (ep in retry) scope.launch { initiate(ep) }
    }

    // ------------------------------------------------------------------ inbound

    private suspend fun onInnerConnected(list: List<PeerEndpoint>) {
        val fresh = ArrayList<PeerEndpoint>()
        val gone = ArrayList<Session>()
        synchronized(lock) {
            val keys = list.map { key(it) }.toSet()
            val before = innerConnected.map { key(it) }.toSet()
            for (ep in list) if (key(ep) !in before && key(ep) !in byEndpoint && key(ep) !in pending) fresh += ep
            for (k in byEndpoint.keys.filter { it !in keys }) gone += byEndpoint.remove(k)!!
            pending.keys.removeAll { it !in keys }
            innerConnected = list
        }
        gone.forEach { retire(it) }
        publish()
        for (ep in fresh) initiate(ep)
    }

    private suspend fun onFrame(inb: Inbound) {
        val b = inb.bytes
        if (b.isEmpty()) return
        try {
            when (b[0]) {
                TAG_INIT -> onInit(inb.endpoint, b)
                TAG_RESP -> onResp(inb.endpoint, b)
                TAG_FIN -> onFin(inb.endpoint, b)
                TAG_ABORT -> onAbort(inb.endpoint, b)
                Session.TAG_SEALED -> onSealed(inb.endpoint, b)
                '{'.code.toByte() -> { refusedFrames++; SLog.w(TAG, "refused a plaintext envelope from ${inb.endpoint.displayName} (C8: sessions only)") }
                else -> { refusedFrames++; SLog.w(TAG, "dropped a frame with unknown tag 0x%02x from ${inb.endpoint.displayName}".format(b[0])) }
            }
        } catch (e: HandshakeFailure) {
            abort(inb.endpoint, e.peer, e.code, e.message ?: "handshake failed")
        } catch (e: Exception) {
            SLog.w(TAG, "dropped a frame from ${inb.endpoint.displayName}: ${e.message}")
        }
    }

    private suspend fun initiate(ep: PeerEndpoint) {
        // Phone gate finding: when the peer's INIT arrived first we are already its responder; starting our own
        // handshake then overwrote that state, its FIN found no responder, and both sides stayed pending for good.
        synchronized(lock) { if (pending[key(ep)]?.initiator == false || key(ep) in byEndpoint) return }
        val e = NoiseCrypto.ephemeral()
        val hs = Handshake(ep, initiator = true, e = e, eDer = e.public.encoded, startedAt = clock())
        val frame = Bytes().u8(TAG_INIT).u8(PROTO).blob(hs.eDer).bytes()
        hs.h = NoiseCrypto.sha256(H0, frame)
        synchronized(lock) { pending[key(ep)] = hs }
        val r = inner.send(ep, frame)
        if (r.isFailure) {
            synchronized(lock) { if (pending[key(ep)] === hs) pending.remove(key(ep)) }
            SLog.w(TAG, "SESSION_INIT to ${ep.displayName} not sent: ${r.exceptionOrNull()?.message}")
        }
    }

    private suspend fun onInit(ep: PeerEndpoint, frame: ByteArray) {
        val rd = Reader(frame, 1)
        if (rd.u8() != PROTO) throw HandshakeFailure(null, CODE_BAD, "unsupported session protocol")
        val eI = rd.blob(MAX_KEY); rd.end()
        val eIKey = NoiseCrypto.publicKey(eI) ?: throw HandshakeFailure(null, CODE_BAD, "ephemeral key does not parse")
        synchronized(lock) {
            val mine = pending[key(ep)]
            if (mine != null && mine.initiator && compare(mine.eDer, eI) < 0) return   // we stay initiator; they will answer ours
        }
        val e = NoiseCrypto.ephemeral()
        val hs = Handshake(ep, initiator = false, e = e, eDer = e.public.encoded, startedAt = clock())
        val h1 = NoiseCrypto.sha256(H0, frame)
        val h2 = NoiseCrypto.sha256(h1, hs.eDer)
        val ee = NoiseCrypto.ecdh(e, eIKey)
        val k = NoiseCrypto.hkdf(h2, ee, "sn hs", 64)
        val payload = identityPayload(SIG_RESP, h2)
        val ct = NoiseCrypto.seal(k.copyOfRange(0, 32), ZERO_IV, h2, payload)
        val resp = Bytes().u8(TAG_RESP).u8(PROTO).blob(hs.eDer).blob(ct).bytes()
        hs.h = NoiseCrypto.sha256(h2, ct)
        hs.ee = ee
        hs.kFin = k.copyOfRange(32, 64)
        synchronized(lock) { pending[key(ep)] = hs }
        inner.send(ep, resp).onFailure { SLog.w(TAG, "SESSION_RESP to ${ep.displayName} not sent: ${it.message}") }
    }

    private suspend fun onResp(ep: PeerEndpoint, frame: ByteArray) {
        val hs = synchronized(lock) { pending[key(ep)] }?.takeIf { it.initiator }
            ?: run { SLog.w(TAG, "SESSION_RESP from ${ep.displayName} without our INIT; ignored"); return }
        val rd = Reader(frame, 1)
        if (rd.u8() != PROTO) throw HandshakeFailure(null, CODE_BAD, "unsupported session protocol")
        val eR = rd.blob(MAX_KEY)
        val ct = rd.blob(MAX_PAYLOAD); rd.end()
        val eRKey = NoiseCrypto.publicKey(eR) ?: throw HandshakeFailure(null, CODE_BAD, "ephemeral key does not parse")
        val h2 = NoiseCrypto.sha256(hs.h, eR)
        val ee = NoiseCrypto.ecdh(hs.e, eRKey)
        val k = NoiseCrypto.hkdf(h2, ee, "sn hs", 64)
        val payload = try { NoiseCrypto.open(k.copyOfRange(0, 32), ZERO_IV, h2, ct) } catch (_: Exception) {
            throw HandshakeFailure(null, CODE_BAD, "SESSION_RESP does not decrypt")
        }
        val (peerId, peerKey) = verifyIdentity(ep, payload, SIG_RESP, h2)
        val h3 = NoiseCrypto.sha256(h2, ct)
        val finCt = NoiseCrypto.seal(k.copyOfRange(32, 64), ZERO_IV, h3, identityPayload(SIG_INIT, h3))
        val fin = Bytes().u8(TAG_FIN).u8(PROTO).blob(finCt).bytes()
        val h4 = NoiseCrypto.sha256(h3, finCt)
        val tk = NoiseCrypto.hkdf(h4, ee, "sn transport", 64)
        val s = Session(ep, peerId, peerKey, h4, initiator = true, sendKey = tk.copyOfRange(0, 32), recvKey = tk.copyOfRange(32, 64),
            openedAt = clock(), clock = clock, defaultLink = inner.measureLink(ep), rekeyIntervalMs = rekeyIntervalMs, rekeyBytes = rekeyBytes)
        synchronized(lock) { if (pending[key(ep)] === hs) pending.remove(key(ep)) }
        val r = inner.send(ep, fin)
        if (r.isFailure) { SLog.w(TAG, "SESSION_FIN to ${ep.displayName} not sent: ${r.exceptionOrNull()?.message}"); return }
        established(s)
    }

    private suspend fun onFin(ep: PeerEndpoint, frame: ByteArray) {
        val hs = synchronized(lock) { pending[key(ep)] }?.takeIf { !it.initiator }
            ?: run { SLog.w(TAG, "SESSION_FIN from ${ep.displayName} without our RESP; ignored"); return }
        val rd = Reader(frame, 1)
        if (rd.u8() != PROTO) throw HandshakeFailure(null, CODE_BAD, "unsupported session protocol")
        val ct = rd.blob(MAX_PAYLOAD); rd.end()
        val payload = try { NoiseCrypto.open(hs.kFin, ZERO_IV, hs.h, ct) } catch (_: Exception) {
            throw HandshakeFailure(null, CODE_BAD, "SESSION_FIN does not decrypt")
        }
        val (peerId, peerKey) = verifyIdentity(ep, payload, SIG_INIT, hs.h)
        val h4 = NoiseCrypto.sha256(hs.h, ct)
        val tk = NoiseCrypto.hkdf(h4, hs.ee, "sn transport", 64)
        val s = Session(ep, peerId, peerKey, h4, initiator = false, sendKey = tk.copyOfRange(32, 64), recvKey = tk.copyOfRange(0, 32),
            openedAt = clock(), clock = clock, defaultLink = inner.measureLink(ep), rekeyIntervalMs = rekeyIntervalMs, rekeyBytes = rekeyBytes)
        synchronized(lock) { if (pending[key(ep)] === hs) pending.remove(key(ep)) }
        established(s)
    }

    private fun onAbort(ep: PeerEndpoint, frame: ByteArray) {
        val code = if (frame.size >= 2) frame[1].toInt() else CODE_BAD
        val s: Session?
        val hadPending: Boolean
        synchronized(lock) {
            hadPending = pending.remove(key(ep)) != null
            s = byEndpoint[key(ep)]?.takeIf { !it.peerSpoke && it.initiator }
            if (s != null) byEndpoint.remove(key(ep))
            if (hadPending || s != null) failWaiters(key(ep), "peer refused the session")
        }
        if (s == null && !hadPending) return   // an ABORT on a session that already authenticated traffic is ignored
        s?.let { retire(it) }
        publish()
        report(ep, s?.peerNodeId, "peer refused the session (${CODE_NAMES[code] ?: "code $code"})")
        (inner as? Disconnectable)?.disconnect(ep)   // spec 6.3: an authentication failure drops the link on both ends
    }

    private suspend fun onSealed(ep: PeerEndpoint, frame: ByteArray) {
        val s = synchronized(lock) { byEndpoint[key(ep)] }
        if (s == null) {
            SLog.w(TAG, "sealed frame from ${ep.displayName} without a session; starting a handshake")
            if (synchronized(lock) { key(ep) !in pending }) initiate(ep)
            return
        }
        val (kind, payload) = try { s.openFrame(frame) } catch (e: SecurityException) {
            SLog.w(TAG, "dropped a sealed frame from ${s.peerNodeId.take(8)}: ${e.message}"); return
        }
        when (kind) {
            Session.KIND_DATA -> if (exposed(s)) inboundFlow.emit(Inbound(ep, payload))
                else SLog.w(TAG, "dropped data from unpaired ${s.peerNodeId.take(8)} (pairing session carries control only)")
            Session.KIND_PING -> inner.send(ep, s.sealKind(Session.KIND_PONG, payload))
            Session.KIND_PONG -> onPong(s, payload)
            Session.KIND_CONTROL -> listeners.forEach { runCatching { it.onControl(s, payload) }.onFailure { e -> SLog.w(TAG, "control listener: ${e.message}") } }
            Session.KIND_CLOSE -> {
                synchronized(lock) { if (byEndpoint[key(ep)] === s) byEndpoint.remove(key(ep)) }
                retire(s); publish()
            }
            else -> SLog.w(TAG, "unknown sealed kind $kind from ${s.peerNodeId.take(8)}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun identityPayload(label: ByteArray, h: ByteArray): ByteArray {
        val pub = Base64.getDecoder().decode(signer.publicKeyB64)
        val sig = signer.sign(label + h)
        return Bytes().blob(pub).blob(sig).bytes()
    }

    /** Checks the peer's static key and transcript signature, then the pin. Returns (nodeId, pubkeyB64). */
    private fun verifyIdentity(ep: PeerEndpoint, payload: ByteArray, label: ByteArray, h: ByteArray): Pair<String, String> {
        val rd = Reader(payload, 0)
        val pub = rd.blob(MAX_KEY)
        val sig = rd.blob(MAX_KEY); rd.end()
        val pubB64 = Base64.getEncoder().encodeToString(pub)
        val nodeId = Wire.nodeIdOf(pubB64) ?: throw HandshakeFailure(null, CODE_BAD, "static key is not valid")
        if (!Verify.verify(pubB64, label + h, sig)) throw HandshakeFailure(nodeId, CODE_SIG, "transcript signature does not verify")
        if (nodeId == selfId) throw HandshakeFailure(nodeId, CODE_BAD, "peer presented this phone's own key")
        val pinned = pinnedKeyOf(nodeId)
        if (pinned != null && pinned != pubB64) throw HandshakeFailure(nodeId, CODE_KEY, "node key differs from the pinned key")
        if (pinned == null && !admitted(ep, nodeId, pubB64)) throw HandshakeFailure(nodeId, CODE_UNPAIRED, "node is not paired")
        return nodeId to pubB64
    }

    private fun admitted(ep: PeerEndpoint, nodeId: String, pubkey: String): Boolean =
        synchronized(lock) { pairingMode } || runCatching { admitEndpoint(ep) }.getOrDefault(false) ||
            runCatching { allowUnpinned(nodeId, pubkey) }.getOrDefault(false)

    /** A session reaches the router only when its peer is pinned with this key, the router is pairing, or its endpoint was admitted. */
    private fun exposed(s: Session): Boolean = !s.closed &&
        (pinnedKeyOf(s.peerNodeId) == s.peerPubkey || synchronized(lock) { pairingMode } || runCatching { admitEndpoint(s.endpoint) }.getOrDefault(false))

    private fun established(s: Session) {
        val old: Session?
        val ws: List<CompletableDeferred<Session>>
        synchronized(lock) {
            old = byEndpoint.put(key(s.endpoint), s)
            ws = waiters.remove(key(s.endpoint)).orEmpty()
        }
        if (old != null && old !== s) retire(old)
        SLog.d(TAG, "session open with ${s.peerNodeId.take(8)} via ${s.endpoint.displayName} (${if (s.initiator) "initiator" else "responder"})")
        ws.forEach { it.complete(s) }
        listeners.forEach { runCatching { it.onOpen(s) } }
        publish()
        synchronized(s) { s.pingInFlight = true }
        scope.launch { ping(s) }
    }

    private suspend fun ping(s: Session) {
        val id = clock() xor System.nanoTime()
        val payload = ByteArray(LINK_ECHO_BYTES).also { ByteBuffer.wrap(it).putLong(id) }
        synchronized(s) { s.pings[id] = clock(); while (s.pings.size > 8) s.pings.remove(s.pings.keys.first()) }
        inner.send(s.endpoint, s.sealKind(Session.KIND_PING, payload))
    }

    private fun onPong(s: Session, payload: ByteArray) {
        if (payload.size < 8) return
        val id = ByteBuffer.wrap(payload).long
        val t0 = synchronized(s) { s.pings.remove(id) } ?: return
        val rtt = maxOf(1L, clock() - t0)
        val kbps = (2L * payload.size * 8 / rtt).coerceIn(1L, MAX_KBPS.toLong()).toInt()
        s.link = Link(inner.kind, kbps, rtt.toInt(), s.link.metered)
        synchronized(s) { s.measuredAt = clock(); s.pingInFlight = false }
    }

    private fun retire(s: Session) {
        if (s.closed) return
        s.closed = true
        listeners.forEach { runCatching { it.onClose(s) } }
    }

    private fun publish() {
        val list = synchronized(lock) {
            val live = byEndpoint.values.toList()
            sessionsState.value = live
            innerConnected.filter { ep -> byEndpoint[key(ep)]?.let { exposedLocked(it) } == true }
        }
        connectedState.value = list
    }

    private fun exposedLocked(s: Session): Boolean = !s.closed &&
        (pinnedKeyOf(s.peerNodeId) == s.peerPubkey || pairingMode || runCatching { admitEndpoint(s.endpoint) }.getOrDefault(false))

    private suspend fun abort(ep: PeerEndpoint, peer: String?, code: Int, reason: String) {
        synchronized(lock) { pending.remove(key(ep)); failWaiters(key(ep), reason) }
        runCatching { inner.send(ep, byteArrayOf(TAG_ABORT, code.toByte())) }
        report(ep, peer, reason)
        (inner as? Disconnectable)?.disconnect(ep)
    }

    private fun report(ep: PeerEndpoint, peer: String?, reason: String) {
        val who = peer ?: "endpoint:${ep.displayName}"
        SLog.w(TAG, "session with $who refused: $reason")
        failuresFlow.tryEmit(AuthFailure(who, ep, reason))
        runCatching { onAuthFailure(who, reason) }
    }

    private fun fail(ep: PeerEndpoint, peer: String, reason: String) = report(ep, peer, reason)

    /** Caller holds `lock`. Null key = every waiter. */
    private fun failWaiters(k: String?, reason: String) {
        val ks = if (k == null) waiters.keys.toList() else listOf(k)
        for (x in ks) waiters.remove(x)?.forEach { it.completeExceptionally(SecurityException(reason)) }
    }

    private class HandshakeFailure(val peer: String?, val code: Int, msg: String) : Exception(msg)

    private class Bytes {
        private val out = ByteArrayOutputStream()
        fun u8(v: Byte) = apply { out.write(v.toInt() and 0xff) }
        fun u8(v: Int) = apply { out.write(v and 0xff) }
        fun blob(b: ByteArray) = apply {
            require(b.size <= 0xffff) { "field too long" }
            out.write(b.size ushr 8); out.write(b.size and 0xff); out.write(b)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Reader(private val b: ByteArray, private var i: Int) {
        fun u8(): Int { if (i >= b.size) throw HandshakeFailure(null, CODE_BAD, "truncated frame"); return b[i++].toInt() and 0xff }
        fun blob(max: Int): ByteArray {
            val n = (u8() shl 8) or u8()
            if (n > max || i + n > b.size) throw HandshakeFailure(null, CODE_BAD, "field length $n out of range")
            return b.copyOfRange(i, i + n).also { i += n }
        }
        fun end() { if (i != b.size) throw HandshakeFailure(null, CODE_BAD, "trailing bytes in frame") }
    }

    companion object {
        private const val TAG = "SecureTransport"
        const val PROTOCOL_NAME = "SN-NOISE-XX-SIG_P256_AESGCM_SHA256-v1"
        const val PROTO = 1
        const val TAG_INIT: Byte = 0xA1.toByte()
        const val TAG_RESP: Byte = 0xA2.toByte()
        const val TAG_FIN: Byte = 0xA3.toByte()
        const val TAG_ABORT: Byte = 0xAF.toByte()

        /** §17.3 Noise rekey: 24 h or 1 GB per direction, whichever comes first. */
        const val REKEY_INTERVAL_MS = 24 * 60 * 60_000L
        const val REKEY_BYTES = 1L shl 30
        /** §17.3 link measurement: a 2 KB echo at session start and every 5 min. */
        const val LINK_ECHO_BYTES = 2048
        const val LINK_MEASURE_INTERVAL_MS = 5 * 60_000L
        /** How long [open] waits for a handshake. */
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val MAX_KBPS = 1_000_000
        private const val MAX_KEY = 512
        private const val MAX_PAYLOAD = 2048

        const val CODE_BAD = 1
        const val CODE_UNPAIRED = 2
        const val CODE_KEY = 3
        const val CODE_SIG = 4
        private val CODE_NAMES = mapOf(CODE_BAD to "bad handshake", CODE_UNPAIRED to "not paired", CODE_KEY to "key mismatch", CODE_SIG to "bad signature")

        private val H0 = MessageDigest.getInstance("SHA-256").digest(PROTOCOL_NAME.toByteArray(Charsets.UTF_8))
        private val SIG_RESP = "SN-SESSION-RESP\u0000".toByteArray(Charsets.UTF_8)
        private val SIG_INIT = "SN-SESSION-INIT\u0000".toByteArray(Charsets.UTF_8)
        private val ZERO_IV = ByteArray(12)

        /**
         * Spec 6.2 / 20.2: the 6-digit SAS from the handshake hash `h4`: the first 4 bytes of
         * `SHA256("SN-SAS\u0000" || h4)` as an unsigned integer, modulo 10^6, zero-padded. Both sides hold the same `h4`
         * only when they completed the same handshake, so a relay or network attacker who substituted keys shows
         * different digits on the two phones.
         */
        fun sas(handshakeHash: ByteArray): String {
            val d = NoiseCrypto.sha256(SAS_LABEL, handshakeHash)
            val v = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
            return "%06d".format(v % 1_000_000L)
        }
        private val SAS_LABEL = "SN-SAS\u0000".toByteArray(Charsets.UTF_8)

        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}

/** The handshake and record primitives: plain JCA, so the JVM tests run the exact code the phones run. */
object NoiseCrypto {
    fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKey(der: ByteArray): PublicKey? = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    } catch (_: Exception) {
        null
    }

    fun ecdh(mine: KeyPair, theirs: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(mine.private)
        doPhase(theirs, true)
        generateSecret()
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            t = hmac(prk, t + info.toByteArray(Charsets.UTF_8) + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }

    fun ratchet(key: ByteArray): ByteArray = hkdf(ByteArray(0), key, "sn rekey", 32)

    fun seal(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        updateAAD(aad)
        doFinal(plain)
    }

    fun open(key: ByteArray, iv: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        updateAAD(aad)
        doFinal(cipher)
    }
}
