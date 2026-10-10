package com.hivecore.trust

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.mesh.RosterBody
import com.hivecore.mesh.RosterMember
import com.hivecore.mesh.RosterSigning
import com.hivecore.mesh.RosterStoreImpl
import com.hivecore.mesh.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 6.4: the roster store over SQL and the admin's signed changes. */
class RosterAdminTest {
    private var clock = 1_700_000_000_000L

    inner class Phone(val name: String) {
        val signer = FakeSigner()
        val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val pinned = HashMap<String, String>()
        val store = RosterStoreImpl(JdbcSqlDb.inMemory(), nodeId, signer.publicKeyB64) { pinned[it] }
        val admin = RosterAdmin(signer, store) { clock }
        fun pin(o: Phone) { pinned[o.nodeId] = o.signer.publicKeyB64 }
    }

    @Test fun createAddRevokeAndHandoverAreSignedVersionBumpsThatOtherPhonesApplyInOrder() {
        val a = Phone("Admin"); val b = Phone("B"); val c = Phone("C")
        b.pin(a); c.pin(a); a.pin(b); a.pin(c)
        val v1 = a.admin.create("g1", "Admin")!!
        assertEquals(1L, v1.version); assertEquals(a.nodeId, v1.admin)
        assertTrue(RosterSigning.verify(v1, a.signer.publicKeyB64))
        assertTrue(a.store.isMember(a.nodeId))
        assertNull(a.admin.create("g1", "Admin"))   // exists

        assertTrue(b.store.apply(v1)); assertTrue(c.store.apply(v1))
        assertFalse(b.store.apply(v1))               // not newer
        val v2 = a.admin.add("g1", b.nodeId, b.signer.publicKeyB64, "B")!!
        val v3 = a.admin.add("g1", c.nodeId, c.signer.publicKeyB64, "C")!!
        assertEquals(listOf(2L, 3L), listOf(v2.version, v3.version))
        assertTrue(c.store.apply(v3))                // skipping v2 is fine: highest valid version wins
        assertFalse(c.store.apply(v2))
        assertTrue(b.store.apply(v2)); assertTrue(b.store.apply(v3))
        assertEquals(3, b.store.current("g1")!!.members.size)
        assertTrue(b.store.isMember(c.nodeId))
        assertNull(a.admin.add("g1", c.nodeId, FakeSigner().publicKeyB64, "C"))   // key does not hash to the id

        // Revoke C: a REVOKE bump; the effective roster drops C and lists it as revoked.
        val rev = a.admin.revoke("g1", c.nodeId)!!
        assertEquals(4L, rev.version)
        assertTrue(b.store.applyRevoke(rev))
        assertFalse(b.store.applyRevoke(rev))
        val cur = b.store.current("g1")!!
        assertEquals(4L, cur.version)
        assertEquals(listOf(c.nodeId), cur.revoked)
        assertFalse(cur.members.any { it.nodeId == c.nodeId })
        assertTrue(b.store.isRevoked(c.nodeId)); assertFalse(b.store.isMember(c.nodeId))
        assertEquals(3L, b.store.signedRoster("g1")!!.version)                 // the signed base stays
        assertEquals(listOf(rev), b.store.revokesAfter("g1", 3L))
        assertNull(a.admin.revoke("g1", a.nodeId))                              // the admin cannot revoke itself
        assertNull(b.admin.revoke("g1", a.nodeId))                              // B is not the admin

        // Handover: the admin signs a roster naming B; afterwards only B's signature counts.
        val v5 = a.admin.handover("g1", b.nodeId)!!
        assertEquals(5L, v5.version); assertEquals(b.nodeId, v5.admin)
        assertTrue(b.store.apply(v5))
        assertEquals(b.nodeId, b.store.current("g1")!!.admin)
        assertTrue(b.store.revokesAfter("g1", 5L).isEmpty())
        assertTrue(b.store.isRevoked(c.nodeId))                                 // the full roster carried the revocation on
        assertNull(a.admin.add("g1", c.nodeId, c.signer.publicKeyB64, "C"))     // A is no longer admin
        val v6 = b.admin.add("g1", c.nodeId, c.signer.publicKeyB64, "C again")!!
        assertTrue(a.store.apply(v6))
        assertTrue(a.store.isMember(c.nodeId)); assertFalse(a.store.isRevoked(c.nodeId))
        // A roster the old admin signs after the handover is rejected everywhere.
        val forged = RosterSigning.sign(v6.copy(version = 7L, admin = a.nodeId, adminSig = ""), a.signer)
        assertFalse(a.store.apply(forged)); assertFalse(b.store.apply(forged))
        assertNull(b.admin.handover("g1", FakeSigner().let { Wire.nodeIdOf(it.publicKeyB64)!! }))   // not a member
    }

    @Test fun theFirstRosterIsTrustedOnlyFromThisPhoneOrAPinnedAdmin() {
        val a = Phone("Admin"); val b = Phone("B"); val stranger = Phone("X")
        val v1 = a.admin.create("g1", "Admin")!!
        assertFalse(b.store.apply(v1))             // A is not pinned on B
        b.pin(a)
        assertTrue(b.store.apply(v1))
        val s1 = RosterSigning.sign(RosterBody("g2", 1L, stranger.nodeId, listOf(RosterMember(stranger.nodeId, stranger.signer.publicKeyB64, "X", clock))), stranger.signer)
        assertFalse(b.store.apply(s1))
        assertNull(b.store.current("g2"))
        // A member whose key does not hash to its id, a tampered body and a bad signature are all rejected.
        val badMember = RosterSigning.sign(v1.copy(version = 2L, members = v1.members + RosterMember(b.nodeId, stranger.signer.publicKeyB64, "B", clock), adminSig = ""), a.signer)
        assertFalse(b.store.apply(badMember))
        val v2 = a.admin.add("g1", b.nodeId, b.signer.publicKeyB64, "B")!!
        assertFalse(b.store.apply(v2.copy(members = v2.members.map { it.copy(name = "Mallory") })))
        assertFalse(b.store.apply(v2.copy(adminSig = "")))
        assertFalse(b.store.apply(v2.copy(adminSig = "AAAA")))
        assertTrue(b.store.apply(v2))
        assertEquals(2L, b.store.latest()!!.version)
        // A REVOKE needs a held roster and the admin's signature.
        val rev = RosterSigning.sign(com.hivecore.mesh.RevokeBody("g1", 3L, b.nodeId), b.signer)
        assertFalse(b.store.applyRevoke(rev))
        assertFalse(stranger.store.applyRevoke(RosterSigning.sign(com.hivecore.mesh.RevokeBody("g1", 3L, b.nodeId), a.signer)))
    }

    @Test fun theStoreSurvivesAReopen() {
        val db = JdbcSqlDb.inMemory()
        val a = Phone("Admin")
        val store1 = RosterStoreImpl(db, a.nodeId, a.signer.publicKeyB64)
        val admin = RosterAdmin(a.signer, store1) { clock }
        admin.create("g1", "Admin")
        val b = FakeSigner()
        admin.add("g1", Wire.nodeIdOf(b.publicKeyB64)!!, b.publicKeyB64, "B")
        admin.revoke("g1", Wire.nodeIdOf(b.publicKeyB64)!!)
        val store2 = RosterStoreImpl(db, a.nodeId, a.signer.publicKeyB64)
        assertEquals(3L, store2.current("g1")!!.version)
        assertTrue(store2.isRevoked(Wire.nodeIdOf(b.publicKeyB64)!!))
        assertNotNull(store2.signedRoster("g1"))
    }
}
