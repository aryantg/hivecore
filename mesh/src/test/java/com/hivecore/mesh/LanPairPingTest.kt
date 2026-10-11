package com.hivecore.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A pings, B hears it once (three rounds, one card), B approves, A hears the approval; B never sees its own ping; a denial reads as one. */
class LanPairPingTest {
    private fun await(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    private fun freePort(lo: InetAddress): Int = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(lo, 0)) }.use { it.localPort }

    @Test fun requestApproveDeny() = runBlocking {
        val lo = InetAddress.getLoopbackAddress()
        val portA = freePort(lo); val portB = freePort(lo)
        val now = java.util.concurrent.atomic.AtomicLong(5_000_000L)   // read on the listener threads: atomic, not a captured var
        val a = LanPairPing(portA, sendPort = portB, targets = { listOf(lo) }, sweep = false, rounds = 3, roundGapMs = 50, clock = { now.get() })
        val b = LanPairPing(portB, sendPort = portA, targets = { listOf(lo) }, sweep = false, rounds = 1, clock = { now.get() })
        val replies = CopyOnWriteArrayList<PairPingReply>()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch { a.replies.collect { replies += it } }
        try {
            a.start("Sales|aaaaaaaa"); b.start("HR|bbbbbbbb")
            assertTrue(a.running && b.running)
            Thread.sleep(100) // the collector above must be subscribed before the first reply

            // 1. A pings: B shows exactly one request although three rounds went out; A never shows its own
            val sent = a.ping("""{"groupId":"g1","pubkey":"k","token":"t","issuedTs":1}""").getOrThrow()
            assertEquals(1, sent.sentTo)
            assertTrue("B hears the ping", await(3_000) { b.requests.value.isNotEmpty() })
            Thread.sleep(250) // the other two rounds
            val req = b.requests.value.single()
            assertEquals(sent.id, req.id); assertEquals("Sales|aaaaaaaa", req.fromName); assertEquals(lo.hostAddress, req.fromHost)
            assertEquals("""{"groupId":"g1","pubkey":"k","token":"t","issuedTs":1}""", req.payload)
            assertTrue(a.requests.value.isEmpty())

            // 2. B approves: the card goes, A hears who approved
            b.reply(req, approve = true).getOrThrow()
            assertTrue(b.requests.value.isEmpty())
            assertTrue("A hears the approval", await(3_000) { replies.any { it.id == sent.id } })
            assertEquals(listOf(true), replies.map { it.approved }); assertEquals("HR|bbbbbbbb", replies.single().fromName)

            // 3. a second ping denied reads as a denial; a reply to an unknown id is ignored
            val second = a.ping("payload-2").getOrThrow()
            assertTrue(await(3_000) { b.requests.value.any { it.id == second.id } })
            b.reply(b.requests.value.single { it.id == second.id }, approve = false).getOrThrow()
            b.reply(PairPing("nobody", "x", lo.hostAddress, "", now.get()), approve = true)
            assertTrue(await(3_000) { replies.size == 2 })
            assertFalse(replies.last().approved)

            // 4. an unanswered request expires after the ttl
            val third = a.ping("payload-3").getOrThrow()
            assertTrue(await(3_000) { b.requests.value.any { it.id == third.id } })
            Thread.sleep(250) // let the sender's repeat rounds land first; a jumped clock would otherwise re-admit them as new
            now.addAndGet(LanPairPing.TTL_MS + 1_000)
            assertTrue("expired", await(3_000) { b.requests.value.isEmpty() })
        } finally { a.stop(); b.stop(); scope.cancel() }
    }

    @Test fun pingFailsWhenNotStarted() {
        val p = LanPairPing(freePort(InetAddress.getLoopbackAddress()), targets = { emptyList() }, sweep = false)
        assertTrue(p.ping("x").isFailure)
        assertFalse(p.running)
    }

    private fun CoroutineScope.cancel() = coroutineContext[Job]?.cancel()
}
