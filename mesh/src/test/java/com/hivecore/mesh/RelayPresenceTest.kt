package com.hivecore.mesh

import com.hivecore.core.fakes.FakeRelay
import com.hivecore.core.fakes.FakeSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presence, sleep and wake (spec 5.5, 14) over the fake relay on a virtual clock. */
class RelayPresenceTest {
    private val base = 1_791_633_600_000L
    private val scopes = ArrayList<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    inner class Phone(val ts: TestScope, relay: FakeRelay, val name: String) {
        val signer = FakeSigner()
        val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val member = FakeRelay.Member(nodeId, signer.publicKeyB64, name)
        val clock: () -> Long = { base + ts.testScheduler.currentTime }
        val scope = ts.scope()
        val transport = RelayTransport("ws://relay.test/ws", signer, "g1", { 1L }, scope, FakeRelaySockets(relay),
            clock = RelayClock(clock), pushToken = { "tok-$name" }, localClock = clock)
        val presence = RelayPresence(transport, scope, clock)
        suspend fun start() { transport.start(name); ts.runCurrent() }
        suspend fun stop() { transport.stop(); ts.runCurrent() }
    }

    private fun presenceTest(block: suspend TestScope.(FakeRelay, Phone, Phone) -> Unit) = runTest {
        val relay = FakeRelay(clock = { base + testScheduler.currentTime })
        val a = Phone(this, relay, "a"); val b = Phone(this, relay, "b")
        relay.roster("g1", 1, a.nodeId, listOf(a.member, b.member))
        try { block(relay, a, b) } finally { scopes.forEach { it.cancel() } }
    }

    @Test fun onlineMemberNeedsNoWake() = presenceTest { relay, a, b ->
        a.start(); b.start()
        assertTrue(a.presence.isOnline(b.nodeId))
        assertTrue(a.presence.ensureAwake(b.nodeId))
        assertTrue(relay.wakes.isEmpty())
        assertFalse(a.presence.isAbsent(b.nodeId))
    }

    @Test fun wakeThenReturnWithinTheGrace() = presenceTest { relay, a, b ->
        a.start(); b.start(); b.stop()
        assertFalse(a.presence.isOnline(b.nodeId))
        assertTrue(a.presence.isAbsent(b.nodeId))   // offline and never woken: nothing to wait for
        val waiting = async { a.presence.ensureAwake(b.nodeId) }
        runCurrent()
        assertEquals(listOf(FakeRelay.Wake("g1", b.nodeId, a.nodeId, "tok-b")), relay.wakes)
        assertTrue(a.presence.isWaking(b.nodeId))
        assertFalse(a.presence.isAbsent(b.nodeId))   // inside the 20 s grace
        advanceTimeBy(5_000); runCurrent()
        b.start()                                   // the FCM push got through
        assertTrue(waiting.await())
        assertTrue(a.presence.isOnline(b.nodeId))
        assertFalse(a.presence.isWaking(b.nodeId))
    }

    @Test fun absentAfterTwentySecondsWithoutAnAnswer() = presenceTest { relay, a, b ->
        a.start(); b.start(); b.stop()
        val waiting = async { a.presence.ensureAwake(b.nodeId) }
        advanceTimeBy(RelayProtocol.WAKE_GRACE_MS - 1); runCurrent()
        assertTrue(waiting.isActive)
        assertFalse(a.presence.isAbsent(b.nodeId))
        advanceTimeBy(2); runCurrent()
        assertFalse(waiting.await())
        assertTrue(a.presence.isAbsent(b.nodeId))
        assertEquals(1, relay.wakes.size)
    }

    @Test fun relayDownWaitsSixtySecondsThenFaults() = presenceTest { relay, a, b ->
        a.start(); b.start()
        relay.goDown(); runCurrent()
        assertTrue(a.presence.relayDown.value)
        val attempt = async { a.presence.ensureAwake(b.nodeId) }
        advanceTimeBy(RelayProtocol.OUTAGE_WAIT_MS + 1); runCurrent()
        assertFalse(attempt.await())
        assertTrue(relay.wakes.isEmpty())
        // Back within the budget: the attempt proceeds.
        val second = async { a.presence.awaitRelay() }
        relay.comeBack()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(second.await())
        assertFalse(a.presence.relayDown.value)
        assertTrue(a.presence.ensureAwake(b.nodeId))
    }
}
