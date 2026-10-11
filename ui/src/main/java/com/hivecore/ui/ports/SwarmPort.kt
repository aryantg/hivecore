package com.hivecore.ui.ports

import com.hivecore.core.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Spec section 17 (Swarm) and section 6 (pairing, roster): what the Swarm screen needs beyond `core.WorkerPolicy`
// and the peer profiles. The mesh+trust track implements [SwarmPort] over `trust.Pairing` and `trust.RosterStore`;
// [FakeSwarmPort] drives the screen and the JVM tests until then.

/** A roster member (spec section 6.4). */
data class MemberView(val nodeId: String, val name: String, val admin: Boolean = false)

/** The admin-signed roster as this phone holds it. */
data class RosterView(val groupId: String, val version: Long, val adminId: String, val members: List<MemberView>) {
    fun nameOf(nodeId: String): String = members.firstOrNull { it.nodeId == nodeId }?.name ?: nodeId.take(8)
    val isEmpty: Boolean get() = members.isEmpty()
}

/** A join waiting for the admin's approval. */
data class JoinRequestView(val nodeId: String, val name: String, val ts: Long)

/** What the inviter shows as a QR code or sends as a link (`swarm://join?g=…&k=…&t=…`, spec section 6.2). */
data class InviteView(val link: String, val expiresTs: Long)

/** The 6 digits both phones show for one handshake; confirmed on both before any key is pinned (spec section 6.2). */
data class SasView(val handshakeId: String, val peerName: String, val digits: String)

/** A pair request another phone on this Wi-Fi sent ("Ping nearby"); Approve joins its invite, Deny tells it so. */
data class PairPingView(val id: String, val fromName: String, val ts: Long)

/** What a pinged phone answered to this phone's ping. */
data class PingReplyView(val fromName: String, val approved: Boolean, val ts: Long)

interface SwarmPort {
    val selfId: String
    val roster: StateFlow<RosterView>
    /** The latest profile per peer (never this phone's; that comes from `WorkerPolicy.profile`). */
    val peers: StateFlow<List<Profile>>
    /** Admin only: joins waiting for approval. */
    val pendingJoins: StateFlow<List<JoinRequestView>>
    /** Handshakes waiting for this phone's Confirm (as inviter or joiner), oldest first. */
    val sasPrompts: StateFlow<List<SasView>>
    /** Warnings the mesh raised (a session authentication failure, unauthorised tasks), newest first; shown on the screen. */
    val warnings: StateFlow<List<String>> get() = NO_WARNINGS

    /** Show QR / send invite link: a fresh invite token (10 minutes). */
    suspend fun createInvite(): Result<InviteView>
    /** Scan QR / open an invite link: connects, runs the handshake, and the SAS appears in [sasPrompts]. */
    suspend fun join(link: String): Result<SasView>
    /** Confirm (true) or reject (false) the digits of one handshake. */
    suspend fun confirmSas(handshakeId: String, matches: Boolean): Result<Unit>

    // ---- "Ping nearby": pair with every phone on this Wi-Fi without a QR scan ----
    /** Pair requests other phones sent to this one and nobody answered yet, oldest first. */
    val pairPings: StateFlow<List<PairPingView>> get() = NO_PINGS
    /** Answers to this phone's pings, newest first (this session only). */
    val pingReplies: StateFlow<List<PingReplyView>> get() = NO_REPLIES
    /** Mints a fresh invite and broadcasts it to every phone on the Wi-Fi; they see Approve / Deny. Unsupported on this build by default. */
    suspend fun pingNearby(): Result<InviteView> = Result.failure(UnsupportedOperationException("pinging needs the LAN mesh"))
    /** Approve (joins the invite the ping carries; the SAS then appears in [sasPrompts]) or deny one request. */
    suspend fun answerPing(id: String, approve: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException("pinging needs the LAN mesh"))

    // ---- admin actions (spec section 6.4) ----
    suspend fun approve(nodeId: String): Result<Unit>
    suspend fun revoke(nodeId: String): Result<Unit>
    suspend fun handOver(nodeId: String): Result<Unit>

    companion object {
        val NO_WARNINGS: StateFlow<List<String>> = MutableStateFlow(emptyList())
        val NO_PINGS: StateFlow<List<PairPingView>> = MutableStateFlow(emptyList())
        val NO_REPLIES: StateFlow<List<PingReplyView>> = MutableStateFlow(emptyList())
    }
}
