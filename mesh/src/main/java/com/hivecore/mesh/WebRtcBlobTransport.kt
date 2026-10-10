package com.hivecore.mesh

import android.content.Context
import com.hivecore.core.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bulk transfer over libwebrtc data channels (spec 5.4, 18.3 `WebRtcBlobTransport`), the Android binding of
 * [DataChannelTransport]: everything above the peer seam (pieces, stall fallback, signalling) is shared with the
 * JVM tests; this file only turns [DataChannelPeer] into a `PeerConnection` with one ordered, reliable channel.
 *
 * @param signal   sends a `SIGNAL` body to a peer inside the sealed envelope (the router's `send(to, SIGNAL)`)
 * @param turn     the relay's current TURN credentials (`RelayTransport.turn.value`), null until issued
 * @param fallback relay delivery for the pieces of a stalled channel (`relay.send(endpointOf(to), bytes)`)
 */
class WebRtcBlobTransport(
    context: Context,
    selfId: String,
    signal: suspend (to: String, body: SignalBody) -> Result<Unit>,
    scope: CoroutineScope,
    turn: () -> TurnConfig?,
    fallback: (suspend (to: String, bytes: ByteArray) -> Result<Unit>)?,
    stun: List<String> = DEFAULT_STUN,
) : DataChannelTransport(selfId, signal, WebRtcPeers(context), scope, turn, stun, fallback)

/** [DataChannelPeerFactory] over Stream's prebuilt libwebrtc. One factory per process. */
class WebRtcPeers(context: Context) : DataChannelPeerFactory {
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun create(ice: IceConfig, initiator: Boolean): DataChannelPeer {
        val servers = ArrayList<PeerConnection.IceServer>()
        ice.stun.forEach { servers += PeerConnection.IceServer.builder(it).createIceServer() }
        ice.turn?.let { t -> t.urls.forEach { servers += PeerConnection.IceServer.builder(it).setUsername(t.username).setPassword(t.credential).createIceServer() } }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // STUN first: ICE prefers host and server-reflexive candidates by priority; relay candidates win only when
            // nothing else connects, which is the "TURN as fallback" of spec 5.4.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        return WebRtcPeer(factory, config, initiator)
    }
}

private class WebRtcPeer(factory: PeerConnectionFactory, config: PeerConnection.RTCConfiguration, private val initiator: Boolean) : DataChannelPeer {
    companion object { private const val TAG = "WebRtc"; private const val LABEL = "blobs" }

    override var onIceCandidate: (IceCandidateInfo) -> Unit = {}
    override var onOpen: () -> Unit = {}
    override var onMessage: (ByteArray) -> Unit = {}
    override var onClosed: (String) -> Unit = {}

    @Volatile private var channel: DataChannel? = null
    @Volatile override var open: Boolean = false
        private set

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) { onIceCandidate(IceCandidateInfo(c.sdp, c.sdpMid, c.sdpMLineIndex)) }
        override fun onDataChannel(dc: DataChannel) { if (!initiator) attach(dc) }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) closedBy("ice $state")
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onRenegotiationNeeded() {}
    }

    private val pc: PeerConnection = factory.createPeerConnection(config, observer) ?: error("createPeerConnection returned null")

    private fun attach(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> { open = true; onOpen() }
                    DataChannel.State.CLOSED -> closedBy("channel closed")
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                onMessage(bytes)
            }
        })
    }

    private fun closedBy(reason: String) {
        if (!open && channel == null) return
        open = false
        onClosed(reason)
    }

    override suspend fun createOffer(): String {
        attach(pc.createDataChannel(LABEL, DataChannel.Init().apply { ordered = true }))
        val offer = create { obs -> pc.createOffer(obs, MediaConstraints()) }
        set { obs -> pc.setLocalDescription(obs, offer) }
        return offer.description
    }

    override suspend fun acceptOffer(sdp: String): String {
        set { obs -> pc.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.OFFER, sdp)) }
        val answer = create { obs -> pc.createAnswer(obs, MediaConstraints()) }
        set { obs -> pc.setLocalDescription(obs, answer) }
        return answer.description
    }

    override suspend fun acceptAnswer(sdp: String) {
        set { obs -> pc.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    }

    override fun addIceCandidate(c: IceCandidateInfo) {
        pc.addIceCandidate(IceCandidate(c.sdpMid ?: "0", c.sdpMLineIndex ?: 0, c.candidate))
    }

    override fun send(bytes: ByteArray): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    override fun close() {
        open = false
        runCatching { channel?.close() }
        runCatching { pc.close() }
        runCatching { pc.dispose() }.onFailure { SLog.w(TAG, "dispose: ${it.message}") }
    }

    private suspend fun create(call: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
            override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "create failed")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        })
    }

    private suspend fun set(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "set failed")) }
        })
    }
}
