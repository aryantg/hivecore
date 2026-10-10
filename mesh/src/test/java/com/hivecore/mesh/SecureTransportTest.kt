package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.LinkKind
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureTransportTest {
    private var clock = 1_700_000_000_000L
    private val scopes = ArrayList<CoroutineScope>()

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    inner class Phone(bus: FakeBus, val id: String, scope: CoroutineScope, rekeyBytes: Long = SecureTransport.REKEY_BYTES,
                      val signer: FakeSigner = FakeSigner()) {
        val store = PeerStore(JdbcSqlDb.inMemory())
        val raw: FakeTransport = bus.node(id, id)
        val failures = ArrayList<Pair<String, String>>()
        var admit: (String, String) -> Boolean = { _, _ -> false }
        val secure = SecureTransport(raw, signer, { store.get(it)?.pubkey }, { clock }, scope,
            allowUnpinned = { n, k -> admit(n, k) }, onAuthFailure = { p, r -> failures += p to r }, measureEveryMs = 0, rekeyBytes = rekeyBytes)
        val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val got = ArrayList<Inbound>()
        val controls = ArrayList<String>()
        init {
            scope.launch { secure.inbound.collect { got += it } }
            secure.addListener(object : SessionListener {
                override fun onControl(session: Session, bytes: ByteArray) { controls += String(bytes) }
            })
        }
        fun pin(o: Phone) = store.pin(o.nodeId, o.id, o.signer.publicKeyB64, null, emptyList(), clock)
        fun endpointOf(o: Phone) = o.raw.endpoint
    }

    private suspend fun TestScope.start(vararg ps: Phone) {
        runCurrent()
        for (p in ps) p.secure.start(p.id)
        runCurrent()
    }

    private fun envelope(p: Phone, text: String) = Wire.encode(MsgType.JOB, p.nodeId, "{\"q\":\"$text\"}", p.signer)

    @Test fun pinnedPeersHandshakeAndCarryOnlyCiphertext() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.pin(b); b.pin(a)
        start(a, b)

        assertEquals(listOf("b"), a.secure.connected.value.map { it.endpointId })
        assertEquals(listOf("a"), b.secure.connected.value.map { it.endpointId })
        val sa = a.secure.session(b.nodeId)!!; val sb = b.secure.session(a.nodeId)!!
        assertArrayEquals(sa.handshakeHash, sb.handshakeHash)
        assertTrue(sa.initiator != sb.initiator)   // the simultaneous open resolved to one initiator

        val env = envelope(a, "average commission West")
        assertTrue(a.secure.send(a.endpointOf(b), env).isSuccess)
        runCurrent()
        assertEquals(1, b.got.size)
        assertArrayEquals(env, b.got.single().bytes)
        assertEquals("a", b.got.single().endpoint.endpointId)

        // C8: nothing on the carrier is a plaintext envelope or contains the body text.
        for ((_, bytes) in a.raw.sent + b.raw.sent) {
            assertTrue(bytes[0] in setOf(SecureTransport.TAG_INIT, SecureTransport.TAG_RESP, SecureTransport.TAG_FIN, Session.TAG_SEALED))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("commission"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(a.nodeId))
        }
        assertTrue(a.raw.sent.any { it.second[0] == Session.TAG_SEALED })
    }

    @Test fun meshRouterRunsUnchangedOverSessions() = runTest {
        val bus = FakeBus()
        val phones = listOf("hr", "sales", "accounts").map { Phone(bus, it, scope()) }
        for (x in phones) for (y in phones) if (x !== y) x.pin(y)
        val routers = phones.map { p ->
            MeshRouter(mapOf("fake" to p.secure), p.signer, p.store,
                localHello = { HelloBody("", p.id, "", caps = listOf("sql")) }, scope = scope(), now = { clock })
        }
        val got = routers.map { r -> ArrayList<Verified>().also { l -> scope().launch { r.verified.collect { l += it } } } }
        runCurrent()
        routers.forEach { it.start() }
        runCurrent()
        for ((i, r) in routers.withIndex()) {
            assertEquals(2, r.connectedNodeIds().size)
            assertEquals(2, got[i].count { it.type == MsgType.HELLO })
        }
        val body = MeshJson.encode(JobBody("job-1", "n-1", clock, routers[0].nodeId, "hr", "headcount by region"))
        assertEquals(2, routers[0].broadcast(MsgType.JOB, body).size)
        runCurrent()
        assertEquals(1, got[1].count { it.type == MsgType.JOB })
        assertEquals(1, got[2].count { it.type == MsgType.JOB })
        for (p in phones) for ((_, bytes) in p.raw.sent) {
            assertFalse(bytes[0] == '{'.code.toByte())
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("headcount"))
        }
    }

    @Test fun unpairedPeerIsRefusedAndReported() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.pin(b)   // b never paired with a
        start(a, b)
        assertTrue(a.secure.connected.value.isEmpty())
        assertTrue(b.secure.connected.value.isEmpty())
        assertNull(b.secure.session(a.nodeId))
        assertTrue(b.failures.any { it.first == a.nodeId && it.second.contains("not paired") })
        assertTrue(a.failures.any { it.second.contains("refused") })
        assertTrue(a.secure.send(a.endpointOf(b), envelope(a, "x")).isFailure)
    }

    @Test fun keyDifferentFromThePinIsRefused() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        b.pin(a)
        // a pinned an impostor key under b's nodeId
        a.store.pin(b.nodeId, "b", FakeSigner().publicKeyB64, null, emptyList(), clock)
        start(a, b)
        assertTrue(a.secure.connected.value.isEmpty())
        assertTrue(a.failures.any { it.first == b.nodeId && it.second.contains("differs") })
    }

    @Test fun prePinPeerCompletesHandshakeButCarriesControlOnly() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.admit = { n, _ -> n == b.nodeId }; b.admit = { n, _ -> n == a.nodeId }
        start(a, b)
        val sa = a.secure.session(b.nodeId)
        val sb = b.secure.session(a.nodeId)
        assertNotNull(sa); assertNotNull(sb)
        assertArrayEquals(sa!!.handshakeHash, sb!!.handshakeHash)   // the SAS source
        assertTrue(a.secure.connected.value.isEmpty())               // the router does not see a pairing session
        assertTrue(a.secure.send(a.endpointOf(b), envelope(a, "x")).isFailure)
        assertTrue(a.secure.sendControl(sa, "hello pairing".toByteArray()).isSuccess)
        runCurrent()
        assertEquals(listOf("hello pairing"), b.controls)

        // Pinning on confirmation exposes the session without a new handshake.
        a.pin(b); b.pin(a); a.secure.refresh(); b.secure.refresh()
        assertEquals(listOf("b"), a.secure.connected.value.map { it.endpointId })
        assertTrue(a.secure.send(a.endpointOf(b), envelope(a, "now paired")).isSuccess)
        runCurrent()
        assertEquals(1, b.got.size)
    }

    @Test fun plaintextAndTamperedAndReplayedFramesAreDropped() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.pin(b); b.pin(a)
        start(a, b)
        // A legacy plaintext envelope straight on the carrier never reaches the router.
        a.raw.send(a.endpointOf(b), envelope(a, "legacy"))
        runCurrent()
        assertTrue(b.got.isEmpty())
        assertEquals(1, b.secure.refusedFrames)

        assertTrue(a.secure.send(a.endpointOf(b), envelope(a, "one")).isSuccess)
        runCurrent()
        assertEquals(1, b.got.size)
        val sealed = a.raw.sent.last().second
        a.raw.send(a.endpointOf(b), sealed)                       // replay
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        a.raw.send(a.endpointOf(b), tampered)                     // bit flip
        runCurrent()
        assertEquals(1, b.got.size)
        assertTrue(a.secure.send(a.endpointOf(b), envelope(a, "two")).isSuccess)
        runCurrent()
        assertEquals(2, b.got.size)
    }

    @Test fun sessionRekeysByVolumeAndTimeAndPeerFollows() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope(), rekeyBytes = 600); val b = Phone(bus, "b", scope(), rekeyBytes = 600)
        a.pin(b); b.pin(a)
        start(a, b)
        val sa = a.secure.session(b.nodeId)!!
        val e0 = sa.epoch
        repeat(10) { a.secure.send(a.endpointOf(b), envelope(a, "msg $it")) }
        runCurrent()
        assertTrue("epoch advanced", sa.epoch > e0)
        assertEquals(10, b.got.size)
        val e1 = sa.epoch
        clock += SecureTransport.REKEY_INTERVAL_MS
        a.secure.send(a.endpointOf(b), envelope(a, "after a day"))
        runCurrent()
        assertTrue(sa.epoch > e1)
        assertEquals(11, b.got.size)
    }

    @Test fun linkIsMeasuredByEchoAtSessionStart() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.pin(b); b.pin(a)
        start(a, b)
        runCurrent()
        val link = a.secure.link(b.nodeId)
        assertEquals(LinkKind.LAN, link.kind)
        assertEquals(1, link.rttMs)   // the fake bus is instant; rtt floors at 1 ms
        assertTrue(link.kbps > 0)
        assertEquals(link, a.secure.measureLink(a.endpointOf(b)))
    }

    @Test fun closingARevokedPeerDropsItsSession() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.pin(b); b.pin(a)
        start(a, b)
        assertEquals(1, a.secure.connected.value.size)
        a.store.forget(b.nodeId)
        a.secure.refresh()
        runCurrent()
        assertTrue(a.secure.connected.value.isEmpty())
        assertNull(a.secure.session(b.nodeId))
        assertNull(b.secure.session(a.nodeId))   // the authenticated CLOSE reached b
    }

    @Test fun openWaitsForTheHandshake() = runTest {
        val bus = FakeBus()
        val a = Phone(bus, "a", scope()); val b = Phone(bus, "b", scope())
        a.pin(b); b.pin(a)
        start(a, b)
        val s = a.secure.open(a.endpointOf(b))
        assertEquals(b.nodeId, s.peerNodeId)
        assertEquals(b.signer.publicKeyB64, s.peerPubkey)
    }
}
