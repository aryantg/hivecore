package com.hivecore.trust

import com.hivecore.mesh.RosterBody
import kotlinx.serialization.Serializable

// Spec section 6.2 / 6.4 / 18.3: pairing by QR or `swarm://join?g=<groupId>&k=<pubkey>&t=<token>` with a 6-digit SAS,
// and the admin-signed roster. Interfaces only; the mesh+trust track implements them.

/** What the inviter shows (QR) or sends (link). [token] is valid for 10 minutes. */
@Serializable data class Invite(val groupId: String, val pubkey: String, val token: String, val issuedTs: Long) {
    /** The remote-invite link (spec 6.2). The key is base64 and the token base64url: both are percent-encoded. */
    fun link(): String = "swarm://join?g=${enc(groupId)}&k=${enc(pubkey)}&t=${enc(token)}"
    /** What the QR carries (spec 6.2 `{groupId, pubkey, token}`, plus the issue time so the scanner refuses a stale code itself). */
    fun qrPayload(): String = com.hivecore.mesh.MeshJson.encode(this)

    companion object {
        const val TOKEN_TTL_MS = 10 * 60_000L
        fun parse(link: String): Invite? {
            if (!link.startsWith("swarm://join?")) return null
            val q = link.substringAfter('?').split('&').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to dec(kv.substring(it + 1)) } }.toMap()
            val g = q["g"] ?: return null; val k = q["k"] ?: return null; val t = q["t"] ?: return null
            return Invite(g, k, t, 0L)
        }
        /** A scanned QR ([qrPayload]) or a pasted link ([link]); null when it is neither. */
        fun fromText(text: String): Invite? {
            val s = text.trim()
            if (s.startsWith("{")) return runCatching { com.hivecore.mesh.MeshJson.decode<Invite>(s) }.getOrNull()
            return parse(s)
        }
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/** The 6 digits both phones show, derived from the handshake hash; confirmed by both users before any key is stored. */
data class SasPrompt(val peerNodeId: String, val peerName: String, val digits: String, val handshakeId: String)

interface Pairing {
    /** Issues a 10-minute single-use token; show [Invite.qrPayload] as a QR or send [Invite.link]. */
    fun startInvite(groupId: String): Invite
    /** Connects (LAN, Nearby or relay), runs the handshake and returns the SAS to show; nothing is pinned yet. */
    suspend fun join(invite: Invite): Result<SasPrompt>
    /** Both keys are pinned only when [matches] is true; false (or an expired token) aborts and forgets the handshake. */
    suspend fun confirm(prompt: SasPrompt, matches: Boolean): Result<Unit>
}

typealias Roster = RosterBody

/**
 * The roster store lives in `:mesh` (`com.hivecore.mesh.RosterStore`, implemented by `RosterStoreImpl` over `SqlDb`)
 * because `MeshRouter` applies ROSTER / REVOKE itself; this alias keeps the `:trust` name the other tracks code against.
 */
typealias RosterStore = com.hivecore.mesh.RosterStore
