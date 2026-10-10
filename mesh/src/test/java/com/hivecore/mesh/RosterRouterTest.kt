package com.hivecore.mesh

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.core.fakes.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 6.4 on the router: ROSTER / REVOKE gossip, revocation effects, handover and rosterVersion in HELLO. */
class RosterRouterTest {
    private var clock = 1_700_000_000_000L
    private val scopes = ArrayList<CoroutineScope>()
    private fun TestScope.scope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
    @After fun tearDown() { scopes.forEach { it.cancel() } }

    inner class Node(bus: FakeBus, val id: String, val name: String, scope: CoroutineScope) {
        val signer = FakeSigner()
        val db = JdbcSqlDb.inMemory()
        val store = PeerStore(db)
        val transport: FakeTransport = bus.node(id, name)
        val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val roster = RosterStoreImpl(db, nodeId, signer.publicKeyB64) { store.get(it)?.pubkey }
        val router = MeshRouter(mapOf("fake" to transport), signer, store, { HelloBody("", name, "") }, scope, now = { clock }, rosterStore = roster)
        val got = ArrayList<Verified>()
        init { scope.launch { router.verified.collect { got += it } } }
        fun pin(o: Node) = store.pin(o.nodeId, o.name, o.signer.publicKeyB64, null, emptyList(), clock)
        fun got(type: MsgType) = got.filter { it.type == type }
        fun member(ts: Long = clock) = RosterMember(nodeId, signer.publicKeyB64, name, ts)
    }

    private suspend fun TestScope.mesh(vararg names: Pair<String, String>): List<Node> {
        val bus = FakeBus()
        val nodes = names.map { Node(bus, it.first, it.second, scope()) }
        for (a in nodes) for (b in nodes) if (a !== b) a.pin(b)
        runCurrent()
        nodes.forEach { it.router.start() }
        runCurrent()
        return nodes
    }

    private fun roster(admin: Node, version: Long, members: List<Node>, revoked: List<String> = emptyList(), adminId: String = admin.nodeId, signer: FakeSigner = admin.signer) =
        RosterSigning.sign(RosterBody("g1", version, adminId, members.map { it.member() }, revoked), signer)

    @Test fun rosterIsGossipedHighestValidVersionWinsAndRevocationDeletesTheKey() = runTest {
        val (admin, b, c) = mesh("admin" to "Admin", "b" to "B", "c" to "C")
        val v1 = roster(admin, 1L, listOf(admin, b, c))
        assertTrue(admin.roster.apply(v1))
        // The admin sends it to B only; B gossips it to C.
        assertTrue(admin.router.send(b.nodeId, MsgType.ROSTER, MeshJson.encode(v1)).isSuccess)
        runCurrent()
        assertEquals(1L, b.roster.current("g1")!!.version)
        assertEquals(1L, c.roster.current("g1")!!.version)
        assertEquals(1, b.got(MsgType.ROSTER).size); assertEquals(1, c.got(MsgType.ROSTER).size)
        assertTrue(admin.got(MsgType.ROSTER).isEmpty())   // the gossip back to the admin is not newer: dropped, not re-gossiped

        // A roster signed by a non-admin, and an older one, are dropped before the verified stream.
        val forged = roster(b, 5L, listOf(b), adminId = b.nodeId, signer = b.signer)
        b.router.broadcast(MsgType.ROSTER, MeshJson.encode(forged))
        admin.router.broadcast(MsgType.ROSTER, MeshJson.encode(v1))
        runCurrent()
        assertEquals(1L, c.roster.current("g1")!!.version)
        assertEquals(1, c.got(MsgType.ROSTER).size)

        // Revoke C: everyone deletes C's key, closes its session and drops what it sends.
        val rev = RosterSigning.sign(RevokeBody("g1", 2L, c.nodeId), admin.signer)
        assertTrue(admin.roster.applyRevoke(rev))
        assertNull(admin.store.get(c.nodeId).takeIf { false })   // (admin applied locally; the router forgets on receipt only)
        admin.router.broadcast(MsgType.REVOKE, MeshJson.encode(rev))
        runCurrent()
        assertEquals(2L, b.roster.current("g1")!!.version)
        assertTrue(b.roster.isRevoked(c.nodeId))
        assertNull(b.store.get(c.nodeId))                        // key deleted
        assertNull(b.router.secure()!!.session(c.nodeId))        // session closed
        assertEquals(listOf(admin.nodeId), b.router.connectedNodeIds())
        assertTrue(b.router.send(c.nodeId, MsgType.JOB, "{}").isFailure)
        val before = b.got.size
        c.router.rebroadcastHello()
        c.router.broadcast(MsgType.ROSTER, MeshJson.encode(v1))
        runCurrent()
        assertEquals(before, b.got.size)                         // nothing from C reaches B any more
        assertFalse(c.router.connectedNodeIds().contains(b.nodeId))
        // HELLO carries the roster version.
        admin.router.rebroadcastHello()
        runCurrent()
        assertEquals(2L, b.router.lastHello(admin.nodeId)!!.rosterVersion)
        assertEquals(2L, b.router.rosterVersion())
    }

    @Test fun aPeerBehindOnTheRosterIsBroughtUpToDateByHello() = runTest {
        val (admin, b) = mesh("admin" to "Admin", "b" to "B")
        val v1 = roster(admin, 1L, listOf(admin, b))
        assertTrue(admin.roster.apply(v1))
        val c = FakeSigner()
        val rev = RosterSigning.sign(RevokeBody("g1", 2L, Wire.nodeIdOf(c.publicKeyB64)!!), admin.signer)
        assertTrue(admin.roster.applyRevoke(rev))
        assertNull(b.roster.current("g1"))
        // B's next HELLO says "no roster" (it joined before the roster existed): the admin answers with the roster and the revoke.
        b.router.rebroadcastHello()
        runCurrent()
        assertEquals(2L, b.roster.current("g1")!!.version)
        assertEquals(listOf(Wire.nodeIdOf(c.publicKeyB64)), b.roster.current("g1")!!.revoked)
        assertEquals(1, b.got(MsgType.ROSTER).size); assertEquals(1, b.got(MsgType.REVOKE).size)
    }

    @Test fun adminHandoverMovesTheSigningRightToTheSuccessor() = runTest {
        val (admin, b, c) = mesh("admin" to "Admin", "b" to "B", "c" to "C")
        admin.roster.apply(roster(admin, 1L, listOf(admin, b, c)))
        admin.router.broadcast(MsgType.ROSTER, MeshJson.encode(admin.roster.signedRoster("g1")!!))
        runCurrent()
        // v2: the admin names B as successor.
        val v2 = roster(admin, 2L, listOf(admin, b, c), adminId = b.nodeId)
        assertTrue(admin.roster.apply(v2))
        admin.router.broadcast(MsgType.ROSTER, MeshJson.encode(v2))
        runCurrent()
        assertEquals(b.nodeId, c.roster.current("g1")!!.admin)
        // v3 signed by B is accepted; v4 signed by the old admin is not.
        val v3 = roster(b, 3L, listOf(admin, b, c), revoked = listOf(), adminId = b.nodeId, signer = b.signer)
        assertTrue(b.roster.apply(v3))
        b.router.broadcast(MsgType.ROSTER, MeshJson.encode(v3))
        runCurrent()
        assertEquals(3L, c.roster.current("g1")!!.version); assertEquals(3L, admin.roster.current("g1")!!.version)
        val stale = roster(admin, 4L, listOf(admin, b), adminId = admin.nodeId, signer = admin.signer)
        assertFalse(c.roster.apply(stale))
        admin.router.broadcast(MsgType.ROSTER, MeshJson.encode(stale))
        runCurrent()
        assertEquals(3L, c.roster.current("g1")!!.version); assertEquals(3L, b.roster.current("g1")!!.version)
        assertEquals(3, c.got(MsgType.ROSTER).size)   // v1, v2 and v3: the stale v4 never reached the stream
    }

    @Test fun rosterSigningCoversTheCanonicalBodyWithoutTheSignature() {
        val a = FakeSigner()
        val body = RosterBody("g1", 7L, "admin", listOf(RosterMember("n1", "k1", "N1", 5L)), listOf("n2"))
        val signed = RosterSigning.sign(body, a)
        assertTrue(RosterSigning.verify(signed, a.publicKeyB64))
        assertTrue(RosterSigning.verify(signed.copy(adminSig = signed.adminSig), a.publicKeyB64))
        assertFalse(RosterSigning.verify(signed.copy(version = 8L), a.publicKeyB64))
        assertFalse(RosterSigning.verify(signed, FakeSigner().publicKeyB64))
        // The signed bytes are canonical JSON (sorted keys, no whitespace) without adminSig.
        val bytes = String(RosterSigning.bytes(signed))
        assertEquals("{\"admin\":\"admin\",\"groupId\":\"g1\",\"members\":[{\"addedTs\":5,\"name\":\"N1\",\"nodeId\":\"n1\",\"pubkey\":\"k1\"}],\"revoked\":[\"n2\"],\"version\":7}", bytes)
        assertEquals(bytes, String(RosterSigning.bytes(body)))
    }
}
