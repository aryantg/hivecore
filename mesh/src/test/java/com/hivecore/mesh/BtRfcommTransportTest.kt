package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.PeerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.util.Collections
import kotlin.random.Random

/**
 * Real-time tests over piped links: the transport's loops run on Dispatchers.IO exactly as on the phone, only the
 * sockets are fake. Intervals are shortened so a full reconnect takes well under a second.
 */
class BtRfcommTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val world = FakeBtWorld()
    private val transports = ArrayList<BtRfcommTransport>()

    private fun node(address: String, name: String, connectIntervalMs: Long = 150, backoffMinMs: Long = 100, backoffMaxMs: Long = 400): BtRfcommTransport =
        BtRfcommTransport(world.node(address, name), scope, name, connectIntervalMs, backoffMinMs, backoffMaxMs).also { transports += it }

    private fun peer(address: String): PeerEndpoint = PeerEndpoint("bt", address, world.find(address)!!.name)

    private fun collect(t: BtRfcommTransport): MutableList<Inbound> {
        val list = Collections.synchronizedList(ArrayList<Inbound>())
        scope.launch { t.inbound.collect { list.add(it) } }
        return list
    }

    private suspend fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private suspend fun awaitLinked(vararg ts: BtRfcommTransport, peers: Int) {
        ts.forEach { t -> await("${t.localName} connected to $peers") { t.connected.value.size == peers } }
    }

    @After fun tearDown() = runBlocking {
        transports.forEach { it.stop() }
        scope.cancel()
    }

    @Test fun twoNodesExchangeFramedEnvelopesBothWays() = runBlocking {
        world.bond("AA", "BB")
        val a = node("AA", "HR phone"); val b = node("BB", "Sales phone")
        val gotA = collect(a); val gotB = collect(b)
        a.start("HR|aaaa0000"); b.start("SALES|bbbb0000")
        awaitLinked(a, b, peers = 1)
        assertEquals(PeerEndpoint("bt", "BB", "Sales phone"), a.connected.value.single())
        assertEquals(PeerEndpoint("bt", "AA", "HR phone"), b.connected.value.single())
        assertTrue(a.status.value, a.status.value.matches(Regex("Bluetooth · [12] links?")))

        val big = Random(7).nextBytes(BtRfcommTransport.MAX_FRAME)   // several pipe buffers: exercises readFully across chunks
        assertTrue(a.send(peer("BB"), "JOB-1 from A".toByteArray()).isSuccess)
        assertTrue(b.send(peer("AA"), "RESULT from B".toByteArray()).isSuccess)
        assertTrue(a.send(peer("BB"), big).isSuccess)
        assertTrue(b.send(peer("AA"), ByteArray(0)).isSuccess)   // empty frame is legal

        await("B received 2") { gotB.size == 2 }
        await("A received 2") { gotA.size == 2 }
        assertEquals("JOB-1 from A", String(gotB[0].bytes))
        assertEquals("AA", gotB[0].endpoint.endpointId)
        assertEquals("HR phone", gotB[0].endpoint.displayName)
        assertTrue(big.contentEquals(gotB[1].bytes))
        assertEquals("RESULT from B", String(gotA[0].bytes))
        assertEquals(0, gotA[1].bytes.size)
        assertEquals("BB", gotA[0].endpoint.endpointId)
    }

    @Test fun threeNodeBondGraphDeliversToEveryPeer() = runBlocking {
        world.bond("AA", "BB"); world.bond("BB", "CC"); world.bond("AA", "CC")
        val a = node("AA", "HR"); val b = node("BB", "Sales"); val c = node("CC", "Accounts")
        val got = mapOf("AA" to collect(a), "BB" to collect(b), "CC" to collect(c))
        a.start("HR|a"); b.start("SALES|b"); c.start("ACCOUNTS|c")
        awaitLinked(a, b, c, peers = 2)
        assertEquals(setOf("BB", "CC"), a.connected.value.map { it.endpointId }.toSet())
        assertEquals(setOf("AA", "CC"), b.connected.value.map { it.endpointId }.toSet())
        assertEquals(setOf("AA", "BB"), c.connected.value.map { it.endpointId }.toSet())

        val all = listOf("AA" to a, "BB" to b, "CC" to c)
        for ((from, t) in all) for ((to, _) in all) if (from != to) {
            assertTrue("$from -> $to", t.send(peer(to), "$from->$to".toByteArray()).isSuccess)
        }
        for ((id, list) in got) await("$id got 2") { list.size == 2 }
        for ((id, list) in got) {
            assertEquals(setOf("${all.map { it.first }.filter { it != id }[0]}->$id", "${all.map { it.first }.filter { it != id }[1]}->$id"), list.map { String(it.bytes) }.toSet())
            assertEquals(list.map { String(it.bytes).substringBefore("->") }.toSet(), list.map { it.endpoint.endpointId }.toSet())
        }
        // Both phones may connect to each other at once; the transport never reports a peer twice.
        assertEquals(a.connected.value.size, a.connected.value.distinctBy { it.endpointId }.size)
    }

    @Test fun oversizeFrameClosesOnlyThatLink() = runBlocking {
        world.bond("AA", "BB"); world.bond("AA", "CC")
        val a = node("AA", "HR", connectIntervalMs = 400, backoffMinMs = 300, backoffMaxMs = 600)
        val b = node("BB", "Sales", connectIntervalMs = 400, backoffMinMs = 300, backoffMaxMs = 600)
        val c = node("CC", "Accounts", connectIntervalMs = 400, backoffMinMs = 300, backoffMaxMs = 600)
        val gotA = collect(a); val gotB = collect(b)
        a.start("HR|a"); b.start("SALES|b"); c.start("ACCOUNTS|c")
        awaitLinked(a, peers = 2)

        // The transport refuses to send more than 1 MB itself ...
        assertTrue(a.send(peer("BB"), ByteArray(BtRfcommTransport.MAX_FRAME + 1)).isFailure)
        assertTrue(a.send(peer("BB"), ByteArray(0)).isSuccess)

        // ... and a peer announcing a 2 MB frame gets that link closed, nothing else.
        // (Both phones scan at once, so there may be one link per direction; poison every one of them.)
        val cToA = world.find("CC")!!.liveLinksTo("AA")
        assertTrue(cToA.isNotEmpty())
        cToA.forEach { DataOutputStream(it.output).apply { writeInt(2 * 1024 * 1024); flush() } }
        await("A closed C's links") { cToA.all { it.closed } }
        await("A dropped C") { a.connected.value.none { it.endpointId == "CC" } }
        assertTrue(a.connected.value.any { it.endpointId == "BB" })

        assertTrue(a.send(peer("BB"), "still fine".toByteArray()).isSuccess)
        assertTrue(b.send(peer("AA"), "and back".toByteArray()).isSuccess)
        await("B got 2") { gotB.size == 2 }
        await("A got 1") { gotA.size == 1 }
        assertEquals("still fine", String(gotB[1].bytes))
        assertEquals("and back", String(gotA[0].bytes))

        // Reconnect brings C back on a fresh link.
        awaitLinked(a, peers = 2)
        assertTrue(a.send(peer("CC"), "hello again".toByteArray()).isSuccess)
    }

    @Test fun brokenPipeTriggersReconnectAndNextSendSucceeds() = runBlocking {
        world.bond("AA", "BB")
        val a = node("AA", "HR", connectIntervalMs = 2_000, backoffMinMs = 400, backoffMaxMs = 800)
        val b = node("BB", "Sales", connectIntervalMs = 2_000, backoffMinMs = 400, backoffMaxMs = 800)
        val gotB = collect(b)
        a.start("HR|a"); b.start("SALES|b")
        awaitLinked(a, b, peers = 1)
        val before = world.find("AA")!!.links.size
        assertTrue(a.send(peer("BB"), "one".toByteArray()).isSuccess)
        await("B got one") { gotB.size == 1 }

        // Break every live link (as if B walked out of range for a moment).
        world.find("AA")!!.links.filter { !it.closed }.forEach { it.close() }
        await("A noticed the drop", timeoutMs = 300) { a.connected.value.isEmpty() }
        await("B noticed the drop", timeoutMs = 300) { b.connected.value.isEmpty() }
        assertTrue(a.status.value.contains("0 links"))
        assertTrue(a.send(peer("BB"), "lost".toByteArray()).isFailure)

        // The backoff (400 ms) reconnects long before the regular 2 s scan.
        awaitLinked(a, b, peers = 1)
        assertTrue(world.find("AA")!!.links.size > before)
        assertTrue(a.send(peer("BB"), "two".toByteArray()).isSuccess)
        await("B got two") { gotB.size == 2 }
        assertEquals("two", String(gotB[1].bytes))
        assertTrue(a.status.value, a.status.value.matches(Regex("Bluetooth · [12] links?")))
    }

    @Test fun connectedFollowsStartAndStopAndRadio() = runBlocking {
        world.bond("AA", "BB")
        val a = node("AA", "HR"); val b = node("BB", "Sales")
        a.start("HR|a")
        delay(300)
        assertTrue(a.connected.value.isEmpty())
        assertEquals("Bluetooth · 0 links · 1 bonded", a.status.value)
        assertTrue(world.find("AA")!!.connectAttempts.get() >= 1)   // tried B, B was not listening: normal

        b.start("SALES|b")
        awaitLinked(a, b, peers = 1)

        b.stop()
        assertEquals("stopped", b.status.value)
        assertTrue(b.connected.value.isEmpty())
        await("A sees B gone") { a.connected.value.isEmpty() }
        assertTrue(a.send(peer("BB"), "x".toByteArray()).isFailure)

        // Radio off (airplane mode): status says so and nothing is attempted; radio on: scanning resumes.
        val radio = world.find("AA")!!
        radio.reachable = false
        await("A reports Bluetooth off") { a.status.value == "Bluetooth off" }
        val attempts = radio.connectAttempts.get()
        delay(400)
        assertEquals(attempts, radio.connectAttempts.get())
        radio.reachable = true
        await("A scanning again") { a.status.value == "Bluetooth · 0 links · 1 bonded" }
        await("A tried B again") { radio.connectAttempts.get() > attempts }
        a.stop()
        assertEquals("stopped", a.status.value)

        // The Settings transport switch stops and starts the same transport: it must come back on the same endpoint.
        a.start("HR|a"); b.start("SALES|b")
        awaitLinked(a, b, peers = 1)
        val gotB = collect(b)
        assertTrue(a.send(peer("BB"), "after restart".toByteArray()).isSuccess)
        await("B got it") { gotB.size == 1 }
    }

    @Test fun pairingIsRouterLevelOverBluetooth() = runBlocking {
        world.bond("AA", "BB")
        val a = node("AA", "HR"); val b = node("BB", "Sales")
        val raised = ArrayList<Any>()
        val job: Job = scope.launch { a.pairingRequests.collect { raised.add(it) } }
        a.setPairingMode(true)
        a.start("HR|a"); b.start("SALES|b")
        awaitLinked(a, b, peers = 1)
        assertTrue(a.send(peer("BB"), "hello".toByteArray()).isSuccess)
        delay(100)
        assertTrue(raised.isEmpty())
        a.acceptPairing(com.hivecore.core.PairingRequest(peer("BB"), "123456"), true)   // no-op, must not throw
        job.cancel()
    }
}
