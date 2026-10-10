package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.LinkKind
import com.hivecore.core.fakes.FakeRelay
import com.hivecore.core.fakes.FakeSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** RelayTransport over the in-process [FakeRelay] on the test scheduler's virtual clock. */
class RelayTransportTest {
    private val base = 1_791_633_600_000L // 2026-10-10 12:00 UTC
    private var relayOffset = 0L
    private val scopes = ArrayList<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    /** runTest ends with advanceUntilIdle, which would run the transports' ping loops forever: cancel them first. */
    private fun relayTest(block: suspend TestScope.() -> Unit) = runTest { try { block() } finally { scopes.forEach { it.cancel() } } }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    private fun TestScope.local(): () -> Long = { base + testScheduler.currentTime }
    private fun TestScope.relay(): FakeRelay = FakeRelay(clock = { base + testScheduler.currentTime + relayOffset })

    inner class Phone(val ts: TestScope, val relay: FakeRelay, val name: String, val group: String = "g1",
                      val signer: FakeSigner = FakeSigner(), guest: GuestInvite? = null, rosterUpload: () -> RosterBody? = { null }) {
        val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val member = FakeRelay.Member(nodeId, signer.publicKeyB64, name)
        val scope = ts.scope()
        val transport = RelayTransport("ws://relay.test/ws", signer, group, { 1L }, scope, FakeRelaySockets(relay),
            clock = RelayClock(ts.local()), pushToken = { "tok-$name" }, rosterUpload = rosterUpload, guest = guest, localClock = ts.local())
        val got = ArrayList<Inbound>()
        val presence = ArrayList<Triple<String, Boolean, Long>>()
        val drops = ArrayList<RelayFrame>()
        init {
            scope.launch { transport.inbound.collect { got += it } }
            scope.launch { transport.drops.collect { drops += it } }
            transport.onPresence { id, on, since -> presence += Triple(id, on, since) }
        }
        suspend fun start() { transport.start(name); ts.runCurrent() }
        suspend fun stop() { transport.stop(); ts.runCurrent() }
        fun ep() = RelayTransport.endpointOf(nodeId)
    }

    private fun roster(relay: FakeRelay, vararg phones: Phone, revoked: Set<String> = emptySet()) =
        relay.roster("g1", 1, phones[0].nodeId, phones.map { it.member }, revoked)

    @Test fun authenticatesListsMembersAndReportsPresence() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b)
        a.start()
        assertTrue(a.transport.authenticated.value)
        assertEquals(mapOf(a.nodeId to true, b.nodeId to false), a.transport.members.value)
        assertEquals(emptyList<Any>(), a.transport.connected.value)
        assertEquals("tok-a", relay.tokens[a.nodeId])

        b.start()
        assertEquals(listOf(b.ep()), a.transport.connected.value)
        assertEquals(listOf(a.ep()), b.transport.connected.value)
        assertEquals(listOf(Triple(b.nodeId, true, base)), a.presence)
        assertEquals(LinkKind.WAN_RELAY, a.transport.kind)
        assertTrue(a.transport.status.value.startsWith("online"))

        b.stop()
        assertEquals(emptyList<Any>(), a.transport.connected.value)
        assertEquals(Triple(b.nodeId, false, base), a.presence.last())
        assertFalse(relay.online(b.nodeId))
    }

    @Test fun forwardsSealedBytesBetweenMembers() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b)
        a.start(); b.start()
        val payload = ByteArray(1000) { it.toByte() }
        assertTrue(a.transport.send(b.ep(), payload).isSuccess)
        runCurrent()
        assertEquals(1, b.got.size)
        assertEquals(a.ep(), b.got[0].endpoint)
        assertArrayEquals(payload, b.got[0].bytes)
        // The relay saw only base64 ciphertext, never the bytes as text.
        val msg = relay.received.last { it["type"].toString() == "\"msg\"" }
        assertEquals(Base64.getEncoder().encodeToString(payload), msg["cipher"].toString().trim('"'))
        // Over the cap: refused locally.
        assertTrue(a.transport.send(b.ep(), ByteArray(RelayProtocol.MAX_CIPHER_BYTES + 1)).isFailure)
    }

    @Test fun holdsForAnOfflineMemberThenDelivers() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b)
        a.start()
        assertTrue(a.transport.send(b.ep(), "held".toByteArray()).isSuccess)
        assertEquals(1, relay.heldCount(b.nodeId))
        advanceTimeBy(2 * 60_000); relay.sweep()
        b.start()
        assertEquals(listOf("held"), b.got.map { String(it.bytes) })
        assertEquals(0, relay.heldCount(b.nodeId))
    }

    @Test fun dropsAfterFiveMinutesAndTellsTheSender() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b)
        a.start()
        a.transport.send(b.ep(), "late".toByteArray())
        advanceTimeBy(RelayProtocol.HOLD_MS - 1_000); relay.sweep(); runCurrent()
        assertTrue(a.drops.isEmpty())
        advanceTimeBy(2_000); relay.sweep(); runCurrent()
        assertEquals(1, a.drops.size)
        assertEquals(RelayProtocol.REASON_EXPIRED, a.drops[0].reason)
        assertEquals(b.nodeId, a.drops[0].to)
        b.start()
        assertTrue(b.got.isEmpty())
    }

    @Test fun reconnectsWithBackoffAndFlagsTheOutage() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a")
        roster(relay, a)
        a.start()
        assertFalse(a.transport.relayDown.value)

        relay.goDown(); runCurrent()
        assertTrue(a.transport.relayDown.value)
        assertFalse(a.transport.authenticated.value)
        assertEquals(base, a.transport.outageSince)
        // Attempts at 1 s, then +2 s, +4 s, +8 s, +16 s, +30 s, +30 s ... while the relay is down.
        val waiting = async { a.transport.awaitOnline() }
        advanceTimeBy(59_000); runCurrent()
        assertTrue(waiting.isActive)
        assertEquals("reconnecting in 30 s", a.transport.status.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(false, waiting.await())   // 60 s: the WAN attempt faults

        relay.comeBack()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(a.transport.authenticated.value)
        assertFalse(a.transport.relayDown.value)
        assertEquals(null, a.transport.outageSince)
        assertTrue(a.transport.awaitOnline())
        // The next drop retries after 1 s again.
        relay.killAll(); runCurrent()
        assertTrue(a.transport.relayDown.value)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(a.transport.authenticated.value)
    }

    @Test fun keepsTheRelayClockOffset() = relayTest {
        relayOffset = 5 * 3_600_000L + 250   // the relay lives five hours and a quarter second ahead
        val relay = relay()
        val a = Phone(this, relay, "a")
        roster(relay, a)
        a.start()
        assertTrue(a.transport.clock.synced)
        assertEquals(relayOffset, a.transport.clock.offsetMs)
        assertEquals(base + relayOffset, a.transport.clock.now())
        // After the first ping the sample is round-trip corrected (RTT 0 on the fake: same offset).
        advanceTimeBy(RelayProtocol.PING_EVERY_MS + 1); runCurrent()
        assertEquals(0L, a.transport.clock.sampleRttMs)
        assertEquals(relayOffset, a.transport.clock.offsetMs)
        val guard = ReplayGuard(now = a.transport.clock::now)
        assertEquals(null, guard.check("x", "n1", base + relayOffset + 60_000))
        assertNotNull(guard.check("x", "n2", base + 60_000))   // local time would be 5 h off
    }

    @Test fun wakeGoesThroughTheRelay() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b)
        a.start(); b.start(); b.stop()
        assertTrue(a.transport.wake(b.nodeId))
        assertEquals(1, relay.wakes.size)
        assertEquals(FakeRelay.Wake("g1", b.nodeId, a.nodeId, "tok-b"), relay.wakes[0])
    }

    @Test fun measuresTheLinkWithAnEcho() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a")
        roster(relay, a)
        a.start()
        val link = a.transport.measureLink(a.ep())
        assertEquals(LinkKind.WAN_RELAY, link.kind)
        assertEquals(1, link.rttMs)
        assertTrue(link.kbps > 0)
        assertTrue(a.got.isEmpty())   // the echo never reaches the router
    }

    @Test fun revokedNodeStopsRetrying() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b, revoked = setOf(b.nodeId))
        b.start()
        assertEquals(RelayProtocol.REASON_REVOKED, b.transport.lastAuthFailure)
        assertFalse(b.transport.authenticated.value)
        val sockets = relay.socketCount()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(sockets, relay.socketCount())   // no reconnect loop for a revoked key
    }

    @Test fun adminUploadsTheRosterBeforeAuth() = relayTest {
        val relay = relay()   // knows no group yet
        val signer = FakeSigner()
        val adminId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val body = RosterBody("g1", 2, adminId, listOf(RosterMember(adminId, signer.publicKeyB64, "admin", base)))
        val signed = body.copy(adminSig = Base64.getEncoder().encodeToString(signer.sign(RelayFrames.rosterSigningBytes(body))))
        val a = Phone(this, relay, "a", signer = signer, rosterUpload = { signed })
        a.start()
        assertTrue(a.transport.authenticated.value)
        assertEquals(2L, relay.rosterVersion("g1"))
        // A tampered roster is refused.
        val bad = signed.copy(members = signed.members + RosterMember("x", FakeSigner().publicKeyB64, "x", base))
        assertTrue(a.transport.uploadRoster(bad).isFailure)
        assertEquals(2L, relay.rosterVersion("g1"))
    }

    @Test fun guestReachesOnlyTheInviter() = relayTest {
        val relay = relay()
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        roster(relay, a, b)
        a.start(); b.start()
        val joiner = Phone(this, relay, "j", guest = GuestInvite(a.nodeId, "tok-1"))
        joiner.start()
        assertEquals(RelayProtocol.REASON_BAD_INVITE, joiner.transport.lastAuthFailure)   // not open yet
        joiner.stop()

        assertEquals(600, a.transport.openInvite("tok-1").getOrThrow())
        joiner.start()
        assertTrue(joiner.transport.authenticated.value)
        assertEquals(listOf(a.ep()), joiner.transport.connected.value)
        assertTrue(b.presence.none { it.first == joiner.nodeId })
        joiner.transport.send(a.ep(), "hello".toByteArray()); runCurrent()
        assertEquals("hello", String(a.got.single().bytes))
        assertEquals(joiner.ep(), a.got.single().endpoint)
        a.transport.send(joiner.ep(), "welcome".toByteArray()); runCurrent()
        assertEquals("welcome", String(joiner.got.single().bytes))
        joiner.transport.send(b.ep(), "psst".toByteArray()); runCurrent()
        assertEquals(RelayProtocol.REASON_GUEST_ONLY, joiner.drops.single().reason)
        assertTrue(b.got.isEmpty())
    }
}
