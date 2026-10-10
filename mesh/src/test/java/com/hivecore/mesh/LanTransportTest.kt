package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.LinkKind
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.fakes.FakeSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Collections
import kotlin.random.Random

/**
 * Real loopback sockets, real Dispatchers.IO: only NSD is faked. Intervals are shortened so a reconnect takes well
 * under a second.
 */
class LanTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transports = ArrayList<LanTransport>()

    /** The NSD seam: the test publishes what discovery would have resolved. */
    class FakeLanDiscovery : LanDiscovery {
        val state = MutableStateFlow<List<LanPeer>>(emptyList())
        override val peers: StateFlow<List<LanPeer>> = state
        var advertised: Pair<String, Int>? = null
        override fun start(serviceName: String, port: Int) { advertised = serviceName to port }
        override fun stop() { advertised = null }
    }

    inner class Phone(val serviceName: String, fallbackMs: Long = 400) {
        val discovery = FakeLanDiscovery()
        val transport = LanTransport(SocketLanEndpoint(0, InetAddress.getLoopbackAddress()), discovery, scope, serviceName,
            connectIntervalMs = 150, backoffMinMs = 100, backoffMaxMs = 400, fallbackMs = fallbackMs, echoTimeoutMs = 1_000).also { transports += it }
        val got: MutableList<Inbound> = Collections.synchronizedList(ArrayList())
        init { scope.launch { transport.inbound.collect { got.add(it) } } }
        suspend fun start() = transport.start(serviceName)
        fun sees(o: Phone) { discovery.state.value = discovery.state.value + LanPeer(o.serviceName, "127.0.0.1", o.transport.port) }
        fun endpointOf(o: Phone) = PeerEndpoint(LanTransport.NAME, LanTransport.endpointId(o.serviceName, "127.0.0.1"), LanTransport.displayNameOf(o.serviceName))
    }

    private suspend fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private suspend fun awaitLinked(vararg ps: Phone, peers: Int) {
        ps.forEach { p -> await("${p.serviceName} connected to $peers") { p.transport.connected.value.size == peers } }
    }

    @After fun tearDown() = runBlocking {
        transports.forEach { it.stop() }
        scope.cancel()
    }

    @Test fun discoveredPeersConnectOnceAndExchangeFramedBytesBothWays() = runBlocking {
        val a = Phone("HR|aaaa0000"); val b = Phone("Sales|bbbb0000")
        a.start(); b.start()
        assertEquals("HR|aaaa0000" to a.transport.port, a.discovery.advertised)
        assertTrue(a.transport.port > 0)
        a.sees(b); b.sees(a)
        awaitLinked(a, b, peers = 1)
        assertEquals(a.endpointOf(b), a.transport.connected.value.single())
        assertEquals(b.endpointOf(a), b.transport.connected.value.single())
        assertEquals(LinkKind.LAN, a.transport.kind)
        assertTrue(a.transport.status.value, a.transport.status.value.startsWith("LAN · 1 link"))

        val big = Random(7).nextBytes(LanTransport.MAX_FRAME)   // many socket buffers: exercises readFully across chunks
        assertTrue(a.transport.send(a.endpointOf(b), "JOB-1 from A".toByteArray()).isSuccess)
        assertTrue(b.transport.send(b.endpointOf(a), "RESULT from B".toByteArray()).isSuccess)
        assertTrue(a.transport.send(a.endpointOf(b), big).isSuccess)
        assertTrue(b.transport.send(b.endpointOf(a), ByteArray(0)).isSuccess)   // an empty frame is legal
        await("B received 2") { b.got.size == 2 }
        await("A received 2") { a.got.size == 2 }
        assertEquals("JOB-1 from A", String(b.got[0].bytes))
        assertEquals(a.endpointOf(b).endpointId.substringBefore('@'), "bbbb0000")
        assertEquals("HR", b.got[0].endpoint.displayName)
        assertEquals("aaaa0000@127.0.0.1", b.got[0].endpoint.endpointId)
        assertTrue(big.contentEquals(b.got[1].bytes))
        assertEquals("RESULT from B", String(a.got[0].bytes))
        assertEquals(0, a.got[1].bytes.size)
        // The lower prefix connected; the higher one did not open a second link within the fallback window.
        delay(200)
        assertEquals(1, a.transport.connected.value.size)
        // Oversize frames are refused before they touch the socket.
        assertTrue(a.transport.send(a.endpointOf(b), ByteArray(LanTransport.MAX_FRAME + 1)).isFailure)
        // Unknown endpoint fails cleanly.
        assertTrue(a.transport.send(PeerEndpoint("lan", "cccc0000@127.0.0.1", "Nobody"), ByteArray(1)).isFailure)
    }

    @Test fun measureLinkEchoesTwoKilobytesAndReportsLan() = runBlocking {
        val a = Phone("HR|aaaa0000"); val b = Phone("Sales|bbbb0000")
        a.start(); b.start(); a.sees(b); b.sees(a)
        awaitLinked(a, b, peers = 1)
        val link = a.transport.measureLink(a.endpointOf(b))
        assertEquals(LinkKind.LAN, link.kind)
        assertTrue(link.rttMs >= 1)
        assertTrue(link.kbps > 0)
        assertFalse(link.metered)
        assertEquals(LinkKind.LAN, b.transport.measureLink(b.endpointOf(a)).kind)
        // Without a link: the ladder default, not an error.
        assertEquals(com.hivecore.core.Link.defaultFor(LinkKind.LAN), a.transport.measureLink(PeerEndpoint("lan", "x@127.0.0.1", "x")))
        // The echo never reaches the inbound stream.
        delay(100)
        assertTrue(a.got.isEmpty()); assertTrue(b.got.isEmpty())
    }

    @Test fun aDroppedLinkReconnectsWithBackoffAndDisconnectClosesIt() = runBlocking {
        val a = Phone("HR|aaaa0000"); val b = Phone("Sales|bbbb0000")
        a.start(); b.start(); a.sees(b); b.sees(a)
        awaitLinked(a, b, peers = 1)
        // The session layer refused the peer: the link goes down on both sides, then the connect loop retries.
        a.transport.disconnect(a.endpointOf(b))
        await("A dropped") { a.transport.connected.value.isEmpty() }
        await("B saw the drop") { b.transport.connected.value.isEmpty() }
        awaitLinked(a, b, peers = 1)
        assertTrue(a.transport.send(a.endpointOf(b), "again".toByteArray()).isSuccess)
        await("B got it") { b.got.size == 1 }
        assertEquals("again", String(b.got[0].bytes))
    }

    @Test fun theHigherPrefixConnectsAfterTheFallbackWhenDiscoveryIsOneSided() = runBlocking {
        val a = Phone("HR|aaaa0000", fallbackMs = 300); val b = Phone("Sales|bbbb0000", fallbackMs = 300)
        a.start(); b.start()
        b.sees(a)   // only B found A
        delay(150)
        assertTrue(b.transport.connected.value.isEmpty())   // still waiting for A to connect
        awaitLinked(a, b, peers = 1)                          // B gave up waiting and connected itself
        assertEquals(a.endpointOf(b), a.transport.connected.value.single())
    }

    @Test fun anOversizeHeaderClosesOnlyThatLink() = runBlocking {
        val a = Phone("HR|aaaa0000"); val b = Phone("Sales|bbbb0000")
        a.start(); b.start(); a.sees(b); b.sees(a)
        awaitLinked(a, b, peers = 1)
        // A rogue client sends a 1 GB length header: the server closes that link; the existing one carries on.
        val rogue = SocketLanEndpoint(0, InetAddress.getLoopbackAddress()).connect("127.0.0.1", b.transport.port)!!
        rogue.output.write(byteArrayOf(0x40, 0, 0, 0)); rogue.output.flush()
        await("rogue closed") { runCatching { rogue.input.read() }.getOrDefault(-1) == -1 }
        assertTrue(a.transport.send(a.endpointOf(b), "still here".toByteArray()).isSuccess)
        await("B got it") { b.got.size == 1 }
        rogue.close()
    }

    @Test fun sessionsRunOverLanAndTheWireCarriesCiphertextOnly() = runBlocking {
        val a = Phone("HR|aaaa0000"); val b = Phone("Sales|bbbb0000")
        val sa = FakeSigner(); val sb = FakeSigner()
        val ida = Wire.nodeIdOf(sa.publicKeyB64)!!; val idb = Wire.nodeIdOf(sb.publicKeyB64)!!
        val secA = SecureTransport(a.transport, sa, { if (it == idb) sb.publicKeyB64 else null }, scope = scope, measureEveryMs = 0)
        val secB = SecureTransport(b.transport, sb, { if (it == ida) sa.publicKeyB64 else null }, scope = scope, measureEveryMs = 0)
        val gotB: MutableList<Inbound> = Collections.synchronizedList(ArrayList())
        scope.launch { secB.inbound.collect { gotB.add(it) } }
        delay(50)
        secA.start("HR|aaaa0000"); secB.start("Sales|bbbb0000")
        a.sees(b); b.sees(a)
        await("session") { secA.session(idb) != null && secB.session(ida) != null }
        await("exposed") { secA.connected.value.size == 1 }
        val env = Wire.encode(MsgType.JOB, ida, "{\"q\":\"average commission West\"}", sa)
        assertTrue(secA.send(a.endpointOf(b), env).isSuccess)
        await("B got the envelope") { gotB.size == 1 }
        assertTrue(env.contentEquals(gotB[0].bytes))
        // What the raw transport handed up were session frames only: the plaintext envelope never crossed the socket.
        for (inb in b.got) {
            assertTrue(inb.bytes[0] == Session.TAG_SEALED || inb.bytes[0] == SecureTransport.TAG_INIT ||
                inb.bytes[0] == SecureTransport.TAG_RESP || inb.bytes[0] == SecureTransport.TAG_FIN)
            assertFalse(String(inb.bytes, Charsets.ISO_8859_1).contains("commission"))
        }
        assertEquals(LinkKind.LAN, secA.link(idb).kind)
        secA.stop(); secB.stop()
    }
}
