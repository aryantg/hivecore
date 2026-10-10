package com.hivecore.ui.ports

import com.hivecore.core.Profile
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

interface SwarmPort {
    val selfId: String
    val roster: StateFlow<RosterView>
    /** The latest profile per peer (never this phone's; that comes from `WorkerPolicy.profile`). */
    val peers: StateFlow<List<Profile>>
    /** Admin only: joins waiting for approval. */
    val pendingJoins: StateFlow<List<JoinRequestView>>
    /** Handshakes waiting for this phone's Confirm (as inviter or joiner), oldest first. */
    val sasPrompts: StateFlow<List<SasView>>

    /** Show QR / send invite link: a fresh invite token (10 minutes). */
    suspend fun createInvite(): Result<InviteView>
    /** Scan QR / open an invite link: connects, runs the handshake, and the SAS appears in [sasPrompts]. */
    suspend fun join(link: String): Result<SasView>
    /** Confirm (true) or reject (false) the digits of one handshake. */
    suspend fun confirmSas(handshakeId: String, matches: Boolean): Result<Unit>

    // ---- admin actions (spec section 6.4) ----
    suspend fun approve(nodeId: String): Result<Unit>
    suspend fun revoke(nodeId: String): Result<Unit>
    suspend fun handOver(nodeId: String): Result<Unit>
}
