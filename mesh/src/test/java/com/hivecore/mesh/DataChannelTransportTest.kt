package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.LinkKind
import com.hivecore.core.fakes.FakeDataChannelNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Bulk transfer (spec 5.4) over [FakeDataChannelNetwork]: signalling, piece ordering, stall fallback. */
class DataChannelTransportTest {
    private val scopes = ArrayList<CoroutineScope>()
    private val nodes = HashMap<String, DataChannelTransport>()
    private val signals = ArrayList<SignalBody>()
    private val fallbackCalls = ArrayList<Triple<Long, String, Int>>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    inner class Node(val ts: TestScope, val net: FakeDataChannelNetwork, val id: String, turn: TurnConfig? = null, fallbackKbps: Int = 512) {
        val clock: () -> Long = { ts.testScheduler.currentTime }
        val transport = DataChannelTransport(id,
            // Delivered on the scheduler, as the router would: both sides can have an offer in flight at once.
            signal = { to, body -> signals += body; scopes.first().launch { nodes[to]?.handleSignal(body) }; Result.success(Unit) },
            peers = FakeDataChannelPeers(net, id), scope = ts.scope(), turn = { turn },
            fallback = { to, bytes -> fallbackCalls += Triple(clock(), to, bytes.size); Result.success(Unit) },
            clock = clock, fallbackKbps = fallbackKbps)
        val got = ArrayList<Inbound>()
        init { nodes[id] = transport; scopes.last().launch { transport.inbound.collect { got += it } } }
        suspend fun start() { transport.start(id); ts.runCurrent() }
        fun ep() = DataChannelTransport.endpointOf(id)
    }

    private fun dcTest(block: suspend TestScope.() -> Unit) = runTest { try { block() } finally { scopes.forEach { it.cancel() } } }

    @Test fun offerAnswerAndCandidatesOpenAChannelThatCarriesBytes() = dcTest {
        val net = FakeDataChannelNetwork()
        val turn = TurnConfig(listOf("turn:turn.test:3478"), "1:a", "cred")
        val a = Node(this, net, "aaaa", turn = turn); val b = Node(this, net, "bbbb")
        a.start(); b.start()
        val payload = ByteArray(1000) { it.toByte() }
        assertTrue(a.transport.send(b.ep(), payload).isSuccess)
        runCurrent()
        assertEquals(listOf("offer", "answer", "candidate", "candidate"), signals.map { it.kind }.sorted().let { listOf("offer", "answer") + it.filter { k -> k == "candidate" } })
        assertEquals(1, signals.count { it.kind == "offer" && it.from == "aaaa" && it.to == "bbbb" && it.sdp != null })
        assertEquals(1, signals.count { it.kind == "answer" && it.from == "bbbb" && it.sdp != null })
        assertTrue(signals.filter { it.kind == "candidate" }.all { it.candidate!!.startsWith("cand:") && it.sdpMid == "0" })
        assertEquals(1, b.got.size)
        assertArrayEquals(payload, b.got[0].bytes)
        assertEquals(a.ep(), b.got[0].endpoint)
        assertEquals(listOf(b.ep()), a.transport.connected.value)
        assertEquals(listOf(a.ep()), b.transport.connected.value)
        assertEquals(1, net.opened)
        assertEquals(1, net.withTurn)   // the relay's TURN credentials reached the ICE config
        assertEquals(LinkKind.WAN_DIRECT, a.transport.kind)
        assertEquals(0, fallbackCalls.size)
        // The same channel carries the reply.
        b.transport.send(a.ep(), "back".toByteArray()); runCurrent()
        assertEquals("back", String(a.got.single().bytes))
        assertEquals(1, net.opened)
    }

    @Test fun largeMessagesTravelAsOrderedPiecesAndReassemble() = dcTest {
        val net = FakeDataChannelNetwork()
        val a = Node(this, net, "aaaa"); val b = Node(this, net, "bbbb")
        a.start(); b.start()
        val big = Random(7).nextBytes(50_000)
        assertTrue(a.transport.send(b.ep(), big).isSuccess)
        runCurrent()
        val pieces = net.peers.first { it.nodeId == "aaaa" }.sent
        assertEquals(4, pieces.size)   // 50_000 / (16_384 - 8) → 4 pieces
        assertTrue(pieces.all { it.size <= DataChannelTransport.PIECE_BYTES })
        assertArrayEquals(big, b.got.single().bytes)
        // Several messages back to back keep their order.
        val msgs = (1..5).map { Random(it).nextBytes(it * 9_000) }
        msgs.forEach { assertTrue(a.transport.send(b.ep(), it).isSuccess) }
        runCurrent()
        assertEquals(6, b.got.size)
        msgs.forEachIndexed { i, m -> assertArrayEquals(m, b.got[i + 1].bytes) }
        assertTrue(a.transport.send(b.ep(), ByteArray(DataChannelTransport.MAX_MESSAGE_BYTES + 1)).isFailure)
    }

    @Test fun stallFallsBackToTheRelayAtACappedRate() = dcTest {
        val net = FakeDataChannelNetwork()
        val a = Node(this, net, "aaaa", fallbackKbps = 128); val b = Node(this, net, "bbbb")
        a.start(); b.start()
        net.block("aaaa", "bbbb")
        val piece = ByteArray(16_384)
        assertTrue(a.transport.send(b.ep(), piece).isSuccess)
        assertEquals(listOf(Triple(15_000L, "bbbb", 16_384)), fallbackCalls)   // 15 s stall, then the relay
        assertEquals(setOf("bbbb"), a.transport.stalled.value)
        runCurrent()
        assertTrue(signals.any { it.kind == "close" && it.from == "aaaa" })
        // While stalled, bytes go straight to the fallback, spaced by bytes·8 / kbps = 1024 ms.
        a.transport.send(b.ep(), piece)
        a.transport.send(b.ep(), piece)
        assertEquals(listOf(15_000L, 16_024L, 17_048L), fallbackCalls.map { it.first })
        assertEquals(3, a.transport.fallbackCount)
        assertEquals(1, signals.count { it.kind == "offer" })
        // After the retry window a channel is tried again and, unblocked, opens.
        net.unblock("aaaa", "bbbb")
        advanceTimeBy(DataChannelTransport.RETRY_AFTER_MS); runCurrent()
        assertTrue(a.transport.send(b.ep(), "direct".toByteArray()).isSuccess)
        runCurrent()
        assertEquals("direct", String(b.got.single().bytes))
        assertEquals(emptySet<String>(), a.transport.stalled.value)
        assertEquals(3, fallbackCalls.size)
    }

    @Test fun aClosedChannelIsReopenedOnTheNextSend() = dcTest {
        val net = FakeDataChannelNetwork()
        val a = Node(this, net, "aaaa"); val b = Node(this, net, "bbbb")
        a.start(); b.start()
        a.transport.send(b.ep(), "one".toByteArray()); runCurrent()
        b.transport.close("aaaa"); runCurrent()
        assertEquals(emptyList<Any>(), a.transport.connected.value)
        assertEquals(emptyList<Any>(), b.transport.connected.value)
        a.transport.send(b.ep(), "two".toByteArray()); runCurrent()
        assertEquals(listOf("one", "two"), b.got.map { String(it.bytes) })
        assertEquals(2, net.opened)
        assertEquals(0, fallbackCalls.size)
    }

    @Test fun simultaneousOffersEndInOneChannel() = dcTest {
        val net = FakeDataChannelNetwork()
        val a = Node(this, net, "aaaa"); val b = Node(this, net, "bbbb")
        a.start(); b.start()
        a.transport.open("bbbb"); b.transport.open("aaaa")
        runCurrent()
        assertEquals(listOf(b.ep()), a.transport.connected.value)
        assertEquals(listOf(a.ep()), b.transport.connected.value)
        assertEquals(2, signals.count { it.kind == "offer" })
        assertEquals(1, signals.count { it.kind == "answer" })
        a.transport.send(b.ep(), "x".toByteArray()); b.transport.send(a.ep(), "y".toByteArray()); runCurrent()
        assertEquals("x", String(b.got.single().bytes)); assertEquals("y", String(a.got.single().bytes))
    }

    @Test fun measuresTheLinkWithAnEchoOverTheChannel() = dcTest {
        val net = FakeDataChannelNetwork()
        val a = Node(this, net, "aaaa"); val b = Node(this, net, "bbbb")
        a.start(); b.start()
        assertEquals(LinkKind.WAN_DIRECT, a.transport.measureLink(b.ep()).kind)   // no channel yet: planning figure
        a.transport.send(b.ep(), "warm".toByteArray()); runCurrent()
        val link = a.transport.measureLink(b.ep())
        assertEquals(LinkKind.WAN_DIRECT, link.kind)
        assertEquals(1, link.rttMs)
        assertTrue(link.kbps > 0)
        assertEquals(1, b.got.size)   // the echo never surfaces as inbound
    }
}
