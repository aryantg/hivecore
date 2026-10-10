package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProbeTest {
    /** A transport whose peers and echo result the test controls. */
    private class ProbeTransport : Transport {
        override val name = "probe"
        override val kind = LinkKind.LAN
        override val connected = MutableStateFlow<List<PeerEndpoint>>(emptyList())
        override val inbound: SharedFlow<Inbound> = MutableSharedFlow()
        override val pairingRequests: SharedFlow<PairingRequest> = MutableSharedFlow()
        override val status: StateFlow<String> = MutableStateFlow("")
        var measurements = 0
        var next = Link(LinkKind.LAN, 5_000, 7)
        override suspend fun start(localName: String) {}
        override suspend fun stop() {}
        override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray) = Result.success(Unit)
        override fun acceptPairing(req: PairingRequest, accept: Boolean) {}
        override fun setPairingMode(on: Boolean) {}
        override suspend fun measureLink(endpoint: PeerEndpoint): Link { measurements++; return next }
    }

    @Test fun measuresAtSessionStartAndEveryFiveMinutesWithMeteredOverlay() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val t = ProbeTransport()
        var metered = false
        val reported = ArrayList<Pair<PeerEndpoint, Link>>()
        val probe = LinkProbe(t, scope, { metered }, onLink = { ep, l -> reported += ep to l })
        probe.start(); runCurrent()
        assertEquals(0, t.measurements)

        val b = PeerEndpoint("probe", "b", "b")
        t.connected.value = listOf(b); runCurrent()
        assertEquals(1, t.measurements)
        assertEquals(Link(LinkKind.LAN, 5_000, 7, metered = false), probe.link(b))
        assertEquals(1, reported.size)

        metered = true
        t.next = Link(LinkKind.LAN, 9_000, 3)
        advanceTimeBy(5 * 60_000 - 1); runCurrent()
        assertEquals(1, t.measurements)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, t.measurements)
        assertEquals(Link(LinkKind.LAN, 9_000, 3, metered = true), probe.link(b))
        assertTrue(reported.last().second.metered)

        // A second peer gets its own session-start measurement; a vanished peer stops being probed and is forgotten.
        val c = PeerEndpoint("probe", "c", "c")
        t.connected.value = listOf(c); runCurrent()
        assertEquals(3, t.measurements)
        assertNull(probe.link(b))
        advanceTimeBy(5 * 60_000 + 1); runCurrent()
        assertEquals(4, t.measurements)
        assertEquals(setOf("probe/c"), probe.links.value.keys)
        probe.stop()
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(4, t.measurements)
        scope.cancel()
    }

    @Test fun echoMaths() {
        val l = LinkProbe.fromEcho(LinkKind.WAN_RELAY, 2048, 100, metered = true)
        assertEquals(Link(LinkKind.WAN_RELAY, 327, 100, true), l)
        assertEquals(1, LinkProbe.fromEcho(LinkKind.LAN, 2048, 0, false).rttMs)
        assertFalse(l.carriesBlobs)
        assertTrue(LinkProbe.fromEcho(LinkKind.WAN_DIRECT, 2048, 40, false).carriesBlobs)
    }
}
