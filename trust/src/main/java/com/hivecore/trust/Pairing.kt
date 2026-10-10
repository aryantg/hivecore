package com.hivecore.trust

import com.hivecore.mesh.RosterBody
import kotlinx.serialization.Serializable

// Spec section 6.2 / 6.4 / 18.3: pairing by QR or `swarm://join?g=<groupId>&k=<pubkey>&t=<token>` with a 6-digit SAS,
// and the admin-signed roster. Interfaces only; the mesh+trust track implements them.

/** What the inviter shows (QR) or sends (link). [token] is valid for 10 minutes. */
@Serializable data class Invite(val groupId: String, val pubkey: String, val token: String, val issuedTs: Long) {
    fun link(): String = "swarm://join?g=$groupId&k=$pubkey&t=$token"
    companion object {
        const val TOKEN_TTL_MS = 10 * 60_000L
        fun parse(link: String): Invite? {
            if (!link.startsWith("swarm://join?")) return null
            val q = link.substringAfter('?').split('&').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }.toMap()
            val g = q["g"] ?: return null; val k = q["k"] ?: return null; val t = q["t"] ?: return null
            return Invite(g, k, t, 0L)
        }
    }
}

/** The 6 digits both phones show, derived from the handshake hash; confirmed by both users before any key is stored. */
data class SasPrompt(val peerNodeId: String, val peerName: String, val digits: String, val handshakeId: String)

interface Pairing {
    fun startInvite(groupId: String): Invite
    /** Connects (LAN, Nearby or relay), runs the handshake and returns the SAS to show; nothing is pinned yet. */
    suspend fun join(invite: Invite): Result<SasPrompt>
    /** Both keys are pinned only when [matches] is true; false (or an expired token) aborts and forgets the handshake. */
    suspend fun confirm(prompt: SasPrompt, matches: Boolean): Result<Unit>
}

typealias Roster = RosterBody

interface RosterStore {
    fun current(groupId: String): Roster?
    /** Applies a roster when its version is higher than the held one and `adminSig` verifies against the admin's pinned key. */
    fun apply(roster: Roster): Boolean
    fun isMember(nodeId: String): Boolean
    fun isRevoked(nodeId: String): Boolean
}
