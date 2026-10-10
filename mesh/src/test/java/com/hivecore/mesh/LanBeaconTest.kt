package com.hivecore.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** A beacon announces itself, lists a peer it hears, ignores its own name and forgets a peer that goes quiet. */
class LanBeaconTest {
    private fun await(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    @Test fun announcesHearsAndForgets() {
        val lo = InetAddress.getLoopbackAddress()
        val listener = DatagramSocket(null).apply { reuseAddress = true; soTimeout = 3_000; bind(InetSocketAddress(lo, 0)) }
        val beaconSock = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(lo, 0)) }
        val beaconPort = beaconSock.localPort; beaconSock.close()
        var now = 1_000_000L
        val beacon = LanBeacon(beaconPort, sendPort = listener.localPort, intervalMs = 100, staleMs = 400, targets = { listOf(lo) }, clock = { now }, sweep = false)
        try {
            beacon.start("A|aaaaaaaa", 5001)
            // 1. it announces: the listener on the send port hears "HC1 <name> <port>"
            val p = DatagramPacket(ByteArray(256), 256)
            listener.receive(p)
            assertEquals(listOf("HC1", "A|aaaaaaaa", "5001"), String(p.data, 0, p.length, Charsets.UTF_8).split('\t'))
            // 2. it hears a peer, and never itself
            fun send(line: String) { val b = line.toByteArray(Charsets.UTF_8); listener.send(DatagramPacket(b, b.size, lo, beaconPort)) }
            send("HC1\tB|bbbbbbbb\t5002")
            send("HC1\tA|aaaaaaaa\t5001")
            send("junk\tX\t1")
            assertTrue("hears B", await(3_000) { beacon.peers.value.any { it.serviceName == "B|bbbbbbbb" } })
            val b = beacon.peers.value.single()
            assertEquals(5002, b.port); assertEquals(lo.hostAddress, b.host)
            // 3. a quiet peer is pruned after the stale window
            now += 10_000
            assertTrue("pruned", await(3_000) { beacon.peers.value.isEmpty() })
        } finally { beacon.stop(); listener.close() }
    }
}
