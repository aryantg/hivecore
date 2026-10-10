package com.hivecore.core.fakes

/** One ICE candidate as the fake signals it. */
data class FakeIceCandidate(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)

/**
 * In-process WebRTC stand-in (spec 5.4, 18.2) so bulk-transfer scenarios run on the JVM: peers exchange an offer, an
 * answer and ICE candidates through whatever signalling the test wires, and a channel opens once both sides hold
 * the remote description and at least one remote candidate. Delivery is synchronous, ordered and reliable.
 *
 * Failure knobs: [block] keeps a pair from ever connecting (ICE failure → the 15 s stall), [stall] silently loses a
 * node's outbound messages (a channel that dies mid-transfer), [unstall] restores it.
 */
class FakeDataChannelNetwork(val clock: () -> Long = { System.currentTimeMillis() }) {
    private val offers = HashMap<String, FakeDataChannelPeer>()
    private val blocked = HashSet<String>()
    private val stalled = HashSet<String>()
    private var seq = 0
    /** Every peer created, for assertions on how many connections a scenario needed. */
    val peers = ArrayList<FakeDataChannelPeer>()
    /** Channels that reached the open state. */
    var opened: Int = 0
        private set
    /** TURN was configured for this many peers (the relay credentials reached the transport). */
    var withTurn: Int = 0
        private set

    @Synchronized
    fun peer(nodeId: String, turnConfigured: Boolean = false): FakeDataChannelPeer =
        FakeDataChannelPeer(this, nodeId, ++seq).also { peers += it; if (turnConfigured) withTurn++ }

    @Synchronized fun block(a: String, b: String) { blocked += "$a|$b"; blocked += "$b|$a" }
    @Synchronized fun unblock(a: String, b: String) { blocked -= "$a|$b"; blocked -= "$b|$a" }
    @Synchronized fun stall(nodeId: String) { stalled += nodeId }
    @Synchronized fun unstall(nodeId: String) { stalled -= nodeId }

    @Synchronized internal fun isBlocked(a: String, b: String) = "$a|$b" in blocked
    @Synchronized internal fun isStalled(nodeId: String) = nodeId in stalled
    @Synchronized internal fun registerOffer(sdp: String, p: FakeDataChannelPeer) { offers[sdp] = p }
    @Synchronized internal fun offerer(sdp: String): FakeDataChannelPeer? = offers.remove(sdp)
    @Synchronized internal fun countOpen() { opened++ }
}

class FakeDataChannelPeer internal constructor(private val net: FakeDataChannelNetwork, val nodeId: String, val id: Int) {
    var onIceCandidate: (FakeIceCandidate) -> Unit = {}
    var onOpen: () -> Unit = {}
    var onMessage: (ByteArray) -> Unit = {}
    var onClosed: (String) -> Unit = {}

    @Volatile var remote: FakeDataChannelPeer? = null
        private set
    @Volatile var open: Boolean = false
        private set
    @Volatile var closed: Boolean = false
        private set
    @Volatile private var remoteSet = false
    private var localCandidates = 0
    @Volatile private var remoteCandidates = 0
    val sent = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
    val received = java.util.Collections.synchronizedList(ArrayList<ByteArray>())

    fun createOffer(): String {
        check(!closed) { "peer closed" }
        val sdp = "offer:$nodeId:$id"
        net.registerOffer(sdp, this)
        gather()
        return sdp
    }

    fun acceptOffer(sdp: String): String {
        check(!closed) { "peer closed" }
        val other = net.offerer(sdp) ?: throw IllegalArgumentException("unknown offer $sdp")
        remote = other
        other.remote = this
        remoteSet = true
        gather()
        return "answer:$nodeId:$id"
    }

    fun acceptAnswer(sdp: String) {
        check(!closed) { "peer closed" }
        val r = remote ?: throw IllegalStateException("answer before any offer was accepted")
        require(sdp == "answer:${r.nodeId}:${r.id}") { "answer $sdp is not from ${r.nodeId}" }
        remoteSet = true
        tryOpen()
    }

    fun addIceCandidate(c: FakeIceCandidate) {
        require(c.candidate.startsWith("cand:")) { "not a fake candidate: ${c.candidate}" }
        remoteCandidates++
        tryOpen()
    }

    private fun gather() {
        localCandidates++
        onIceCandidate(FakeIceCandidate("cand:$nodeId:$localCandidates", "0", 0))
    }

    private fun tryOpen() {
        val r = remote ?: return
        if (open || closed || r.closed) return
        if (!remoteSet || !r.remoteSet || remoteCandidates == 0 || r.remoteCandidates == 0) return
        if (net.isBlocked(nodeId, r.nodeId)) return
        open = true; r.open = true
        net.countOpen()
        onOpen(); r.onOpen()
    }

    /** True if the channel accepted the bytes (a stalled node's bytes are accepted and lost, as a dead path would). */
    fun send(bytes: ByteArray): Boolean {
        val r = remote ?: return false
        if (!open) return false
        sent += bytes.copyOf()
        if (net.isStalled(nodeId)) return true
        r.received += bytes.copyOf()
        r.onMessage(bytes.copyOf())
        return true
    }

    fun close() {
        if (closed) return
        closed = true; open = false
        remote?.let { r -> if (!r.closed) { r.closed = true; r.open = false; r.onClosed("peer closed") } }
        onClosed("closed")
    }
}
