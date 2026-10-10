package com.hivecore.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// Plan 6 "core/Transport.kt" — frozen after Wave 0.
data class PeerEndpoint(val transport: String, val endpointId: String, val displayName: String)
data class Inbound(val endpoint: PeerEndpoint, val bytes: ByteArray)
data class PairingRequest(val endpoint: PeerEndpoint, val digits: String)
interface Transport {
    val name: String; val connected: StateFlow<List<PeerEndpoint>>; val inbound: SharedFlow<Inbound>
    val pairingRequests: SharedFlow<PairingRequest>; val status: StateFlow<String>
    suspend fun start(localName: String); suspend fun stop()
    suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit>
    fun acceptPairing(req: PairingRequest, accept: Boolean)
    fun setPairingMode(on: Boolean)

    // ---- v2 plan §3.5 / §13.1 (defaults keep the Wave 0 transports source-compatible) ----
    /** Where this transport sits on the ladder (§3.1). LAN/Nearby/BT transports override; relay and WebRTC report WAN kinds. */
    val kind: LinkKind get() = LinkKind.LAN
    /**
     * Measured link to one peer: a 2 KB echo at session start, refreshed every 5 min by the session layer. The default
     * is the ladder's planning figure for [kind]; transports that can measure override it.
     */
    suspend fun measureLink(endpoint: PeerEndpoint): Link = Link.defaultFor(kind)
}
