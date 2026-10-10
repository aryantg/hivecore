package com.hivecore.trust

import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.mesh.RevokeBody
import com.hivecore.mesh.RosterBody
import com.hivecore.mesh.RosterMember
import com.hivecore.mesh.RosterSigning
import com.hivecore.mesh.Wire

/**
 * Spec 6.4, the admin phone's side: creates the group roster, adds a member after pairing, revokes, and hands the
 * group over to a successor. Every change is a new version signed with this phone's node key, applied to the local
 * [RosterStore] first and returned for the router to broadcast as ROSTER / REVOKE (the router gossips it on). Returns
 * null when this phone is not the group's admin.
 */
class RosterAdmin(private val signer: Signer, private val store: RosterStore, private val clock: () -> Long = { System.currentTimeMillis() }) {
    val nodeId: String = Wire.nodeIdOf(signer.publicKeyB64) ?: error("signer public key is not base64")

    /** A new group with this phone as its only member and admin (version 1). */
    fun create(groupId: String, selfName: String): RosterBody? {
        if (store.current(groupId) != null) { SLog.w(TAG, "group $groupId exists"); return null }
        val r = RosterSigning.sign(RosterBody(groupId, 1L, nodeId, listOf(RosterMember(nodeId, signer.publicKeyB64, selfName, clock()))), signer)
        return r.takeIf { store.apply(it) }
    }

    /** Adds (or renames) a member; the pairing's `onPaired` calls this. */
    fun add(groupId: String, memberId: String, pubkey: String, name: String): RosterBody? {
        if (Wire.nodeIdOf(pubkey) != memberId) { SLog.w(TAG, "add: key does not hash to ${memberId.take(8)}"); return null }
        return bump(groupId) { cur ->
            cur.copy(members = cur.members.filter { it.nodeId != memberId } + RosterMember(memberId, pubkey, name, clock()),
                revoked = cur.revoked.filter { it != memberId })
        }
    }

    /** Revokes a member: a REVOKE bump; the member's key is deleted on every phone that applies it. */
    fun revoke(groupId: String, memberId: String): RevokeBody? {
        val cur = admin(groupId) ?: return null
        if (memberId == nodeId) { SLog.w(TAG, "the admin cannot revoke itself; hand over first"); return null }
        val r = RosterSigning.sign(RevokeBody(groupId, cur.version + 1, memberId), signer)
        return r.takeIf { store.applyRevoke(it) }
    }

    /** Hands the group to [successor] (a member): a roster naming it as admin, signed by this phone. */
    fun handover(groupId: String, successor: String): RosterBody? = bump(groupId) { cur ->
        if (cur.members.none { it.nodeId == successor }) { SLog.w(TAG, "handover: ${successor.take(8)} is not a member"); return null }
        cur.copy(admin = successor)
    }

    private fun admin(groupId: String): RosterBody? {
        val cur = store.current(groupId) ?: run { SLog.w(TAG, "no roster for $groupId"); return null }
        if (cur.admin != nodeId) { SLog.w(TAG, "this phone is not the admin of $groupId"); return null }
        return cur
    }

    private inline fun bump(groupId: String, mutate: (RosterBody) -> RosterBody): RosterBody? {
        val cur = admin(groupId) ?: return null
        val next = RosterSigning.sign(mutate(cur).copy(version = cur.version + 1, adminSig = ""), signer)
        return next.takeIf { store.apply(it) }
    }

    companion object { private const val TAG = "RosterAdmin" }
}
