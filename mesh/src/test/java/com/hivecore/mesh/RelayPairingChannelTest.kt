package com.hivecore.mesh

import com.hivecore.core.fakes.FakeRelay
import com.hivecore.core.fakes.FakeSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote pairing leg (spec 6.2): a joiner holding the invite link reaches the inviter through the relay as a guest and
 * runs the secure handshake before any key is pinned; both sides end with the same handshake hash (the SAS source).
 */
class RelayPairingChannelTest {
    private val base = 1_791_633_600_000L
    private val scopes = ArrayList<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @Test fun joinerHandshakesWithTheInviterThroughTheRelayBeforeAnyPin() = runTest {
        val clock: () -> Long = { base + testScheduler.currentTime }
        val relay = FakeRelay(clock = clock)
        val inviterSigner = FakeSigner(); val joinerSigner = FakeSigner()
        val inviterId = Wire.nodeIdOf(inviterSigner.publicKeyB64)!!
        val joinerId = Wire.nodeIdOf(joinerSigner.publicKeyB64)!!
        relay.roster("g1", 1, inviterId, listOf(FakeRelay.Member(inviterId, inviterSigner.publicKeyB64, "inviter")))
        try {
            // Inviter: ordinary member transport, an invite open with the relay, pairing admitted while it is open.
            val inviterRelay = RelayTransport("ws://relay.test/ws", inviterSigner, "g1", { 1L }, scope(), FakeRelaySockets(relay),
                clock = RelayClock(clock), localClock = clock)
            var inviteOpen = false
            val inviterSecure = SecureTransport(inviterRelay, inviterSigner, { null }, clock, scope(),
                allowUnpinned = { _, _ -> inviteOpen }, measureEveryMs = 0)
            val inviterSessions = ArrayList<Session>()
            inviterSecure.addListener(object : SessionListener { override fun onOpen(session: Session) { inviterSessions += session } })
            inviterSecure.start("inviter"); runCurrent()
            assertTrue(inviterRelay.authenticated.value)
            val token = "tok-from-the-link"
            assertEquals(600, RelayPairingChannel.host(inviterRelay, token).getOrThrow())
            inviteOpen = true

            // Joiner: parsed `g`, `k`, `t` from the link; guest transport; handshake accepting only the key in the link.
            val link = "swarm://join?g=g1&k=${inviterSigner.publicKeyB64}&t=$token"
            val q = link.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val joinerRelay = RelayPairingChannel.joiner("ws://relay.test/ws", joinerSigner, q["g"]!!, q["k"]!!, q["t"]!!, scope(),
                FakeRelaySockets(relay), RelayClock(clock), clock)
            val joinerSecure = SecureTransport(joinerRelay, joinerSigner, { null }, clock, scope(),
                allowUnpinned = RelayPairingChannel.allowOnly(q["k"]!!), measureEveryMs = 0)
            joinerSecure.start("joiner"); runCurrent()
            assertTrue(joinerRelay.authenticated.value)
            assertEquals(listOf(RelayPairingChannel.inviterEndpoint(q["k"]!!)), joinerRelay.connected.value)

            val opening = async { joinerSecure.open(RelayPairingChannel.inviterEndpoint(q["k"]!!)) }
            runCurrent()
            val joinerSession = opening.await()
            assertEquals(inviterId, joinerSession.peerNodeId)
            assertEquals(inviterSigner.publicKeyB64, joinerSession.peerPubkey)
            val inviterSession = inviterSessions.single()
            assertEquals(joinerId, inviterSession.peerNodeId)
            assertArrayEquals(joinerSession.handshakeHash, inviterSession.handshakeHash)   // the same 6-digit SAS on both phones
            assertTrue(joinerSession.handshakeHash.size >= 32)

            // Nothing is pinned: a data envelope is still refused on both sides until the trust layer confirms the SAS.
            assertTrue(joinerSecure.send(joinerSession.endpoint, "data".toByteArray()).isFailure)
            assertFalse(relay.online("nobody"))
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    @Test fun wrongKeyInTheLinkIsRefusedByTheJoiner() = runTest {
        val clock: () -> Long = { base + testScheduler.currentTime }
        val relay = FakeRelay(clock = clock)
        val inviterSigner = FakeSigner(); val joinerSigner = FakeSigner(); val attacker = FakeSigner()
        val inviterId = Wire.nodeIdOf(inviterSigner.publicKeyB64)!!
        relay.roster("g1", 1, inviterId, listOf(FakeRelay.Member(inviterId, inviterSigner.publicKeyB64, "inviter")))
        try {
            val inviterRelay = RelayTransport("ws://relay.test/ws", inviterSigner, "g1", { 1L }, scope(), FakeRelaySockets(relay),
                clock = RelayClock(clock), localClock = clock)
            val inviterSecure = SecureTransport(inviterRelay, inviterSigner, { null }, clock, scope(), allowUnpinned = { _, _ -> true }, measureEveryMs = 0)
            inviterSecure.start("inviter"); runCurrent()
            RelayPairingChannel.host(inviterRelay, "tok").getOrThrow()

            // The link carries a substituted key (a relay or network attacker): the joiner's handshake must refuse the inviter.
            val failures = ArrayList<String>()
            val joinerRelay = RelayPairingChannel.joiner("ws://relay.test/ws", joinerSigner, "g1", inviterSigner.publicKeyB64, "tok", scope(),
                FakeRelaySockets(relay), RelayClock(clock), clock)
            val joinerSecure = SecureTransport(joinerRelay, joinerSigner, { null }, clock, scope(),
                allowUnpinned = RelayPairingChannel.allowOnly(attacker.publicKeyB64), onAuthFailure = { _, r -> failures += r }, measureEveryMs = 0)
            joinerSecure.start("joiner"); runCurrent()
            val opening = async { runCatching { joinerSecure.open(RelayTransport.endpointOf(inviterId)) } }
            runCurrent()
            assertTrue(opening.await().isFailure)
            assertTrue(failures.isNotEmpty())
            assertTrue(joinerSecure.sessions.value.isEmpty())
        } finally {
            scopes.forEach { it.cancel() }
        }
    }
}
