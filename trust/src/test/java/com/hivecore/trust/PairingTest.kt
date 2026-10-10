package com.hivecore.trust

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.core.fakes.FakeTransport
import com.hivecore.mesh.HelloBody
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.Session
import com.hivecore.mesh.Verified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Spec 6.2 pairing over two MeshRouters on a FakeBus (sessions on, nothing pinned beforehand). */
class PairingTest {
    private var clock = 1_700_000_000_000L
    private val scopes = ArrayList<CoroutineScope>()
    private fun TestScope.scope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
    @After fun tearDown() { scopes.forEach { it.cancel() } }

    inner class Phone(val bus: FakeBus, val id: String, val name: String, scope: CoroutineScope) {
        val signer = FakeSigner()
        val store = PeerStore(JdbcSqlDb.inMemory())
        val transport: FakeTransport = bus.node(id, name)
        val warnings = ArrayList<Pair<String, String>>()
        val router = MeshRouter(mapOf("fake" to transport), signer, store, { HelloBody("", name, "") }, scope, now = { clock },
            onAuthFailure = { p, r -> warnings += p to r })
        val nodeId get() = router.nodeId
        val pairing = PairingImpl(router, signer, store, { name }, { _, _ -> inviterEndpoint }, scope, { clock }, SecureRandom())
        var inviterEndpoint: PeerEndpoint? = null
        val prompts = ArrayList<SasPrompt>()
        val events = ArrayList<PairingEvent>()
        val got = ArrayList<Verified>()
        val paired = ArrayList<String>()
        init {
            scope.launch { pairing.prompts.collect { prompts += it } }
            scope.launch { pairing.events.collect { events += it } }
            scope.launch { router.verified.collect { got += it } }
            pairing.onPaired = { n, _, _, g -> paired += "$n@$g" }
        }
    }

    private suspend fun TestScope.phones(): Pair<Phone, Phone> {
        val bus = FakeBus()
        val inviter = Phone(bus, "inviter", "HR", scope()); val joiner = Phone(bus, "joiner", "Sales", scope())
        joiner.inviterEndpoint = inviter.transport.endpoint
        runCurrent()
        inviter.router.start(); joiner.router.start()
        runCurrent()
        // Unpaired: the automatic handshake was refused on both sides and nothing crossed.
        assertTrue(inviter.got.isEmpty()); assertTrue(joiner.got.isEmpty())
        return inviter to joiner
    }

    private fun TestScope.join(joiner: Phone, invite: Invite): () -> Result<SasPrompt>? {
        var r: Result<SasPrompt>? = null
        scopes.last().launch { r = joiner.pairing.join(invite) }
        runCurrent()
        return { r }
    }

    @Test fun qrAndLinkRoundTrip() {
        val inv = Invite("g1", FakeSigner().publicKeyB64, "tok_-123", 42L)
        assertEquals(inv, Invite.fromText(inv.qrPayload()))
        val parsed = Invite.parse(inv.link())!!
        assertEquals(inv.copy(issuedTs = 0L), parsed)
        assertEquals(parsed, Invite.fromText("  " + inv.link() + "\n"))
        assertTrue(inv.link().startsWith("swarm://join?g=g1&k="))
        assertNull(Invite.parse("https://example.invalid/join?g=g1"))
        assertNull(Invite.fromText("not an invite"))
    }

    @Test fun bothPhonesShowTheSameSasAndKeysArePinnedOnlyWhenBothConfirm() = runTest {
        val (inviter, joiner) = phones()
        val invite = inviter.pairing.startInvite("g1")
        assertEquals(inviter.signer.publicKeyB64, invite.pubkey)
        val result = join(joiner, Invite.parse(invite.link())!!)
        val pj = result()!!.getOrThrow()
        assertEquals(1, inviter.prompts.size)
        val pi = inviter.prompts.single()
        assertEquals(pj, joiner.prompts.single())
        assertEquals(6, pi.digits.length); assertTrue(pi.digits.all { it.isDigit() })
        assertEquals(pi.digits, pj.digits)                 // the SAS is the same on both phones
        assertEquals(pi.handshakeId, pj.handshakeId)
        assertEquals(inviter.nodeId, pj.peerNodeId); assertEquals("HR", pj.peerName)
        assertEquals(joiner.nodeId, pi.peerNodeId); assertEquals("Sales", pi.peerName)
        assertEquals(com.hivecore.mesh.SecureTransport.sas(inviter.router.secure()!!.session(joiner.nodeId)!!.handshakeHash), pi.digits)
        // Nothing pinned, nothing exposed to the routers, no envelope crossed.
        assertNull(inviter.store.get(joiner.nodeId)); assertNull(joiner.store.get(inviter.nodeId))
        assertTrue(inviter.router.connectedNodeIds().isEmpty()); assertTrue(inviter.got.isEmpty())

        assertTrue(inviter.pairing.confirm(pi, true).isSuccess)
        runCurrent()
        assertNull(inviter.store.get(joiner.nodeId)); assertNull(joiner.store.get(inviter.nodeId))   // one side is not enough
        assertTrue(joiner.pairing.confirm(pj, true).isSuccess)
        runCurrent()
        assertEquals(joiner.signer.publicKeyB64, inviter.store.get(joiner.nodeId)!!.pubkey)
        assertEquals(inviter.signer.publicKeyB64, joiner.store.get(inviter.nodeId)!!.pubkey)
        assertEquals("Sales", inviter.store.get(joiner.nodeId)!!.name)
        assertEquals(listOf("${joiner.nodeId}@g1"), inviter.paired)
        assertEquals(listOf("${inviter.nodeId}@g1"), joiner.paired)
        assertTrue(inviter.events.single() is PairingEvent.Paired)
        // The session is exposed without a new handshake: HELLOs cross and the routers see each other.
        assertEquals(listOf(joiner.nodeId), inviter.router.connectedNodeIds())
        assertEquals(listOf(inviter.nodeId), joiner.router.connectedNodeIds())
        assertEquals(1, inviter.got.count { it.type == MsgType.HELLO }); assertEquals(1, joiner.got.count { it.type == MsgType.HELLO })
        assertTrue(inviter.pairing.pendingPrompts().isEmpty())
        // The token is single-use.
        assertTrue(inviter.transport.sent.all { it.second[0] != '{'.code.toByte() })
        assertTrue(inviter.transport.sent.any { it.second[0] == Session.TAG_SEALED })
    }

    @Test fun aMismatchedSasAbortsBeforeAnyKeyIsStored() = runTest {
        val (inviter, joiner) = phones()
        val invite = inviter.pairing.startInvite("g1")
        val pj = join(joiner, invite)()!!.getOrThrow()
        val pi = inviter.prompts.single()
        assertTrue(inviter.pairing.confirm(pi, true).isSuccess)          // HR read the digits as matching
        runCurrent()
        assertTrue(joiner.pairing.confirm(pj, false).isSuccess)          // Sales saw different digits
        runCurrent()
        assertNull(inviter.store.get(joiner.nodeId)); assertNull(joiner.store.get(inviter.nodeId))
        assertTrue(inviter.events.single() is PairingEvent.Aborted)
        assertTrue(joiner.events.single() is PairingEvent.Aborted)
        assertTrue(inviter.paired.isEmpty()); assertTrue(joiner.paired.isEmpty())
        assertNull(inviter.router.secure()!!.session(joiner.nodeId))     // the session is gone
        assertTrue(inviter.router.connectedNodeIds().isEmpty())
        // A late confirm on the aborted pairing is refused.
        assertTrue(inviter.pairing.confirm(pi, true).isFailure)
    }

    @Test fun anExpiredTokenIsRefusedByTheInviterAndByTheScanner() = runTest {
        val (inviter, joiner) = phones()
        val invite = inviter.pairing.startInvite("g1")
        clock += Invite.TOKEN_TTL_MS + 1
        // The scanner refuses a stale QR itself (the QR carries the issue time) without connecting.
        val local = join(joiner, Invite.fromText(invite.qrPayload())!!)()!!
        assertTrue(local.isFailure); assertTrue(local.exceptionOrNull()!!.message!!.contains("expired"))
        assertNull(inviter.router.secure()!!.session(joiner.nodeId))
        // A link carries no issue time: the joiner connects and the inviter refuses the token after the handshake.
        val r = join(joiner, Invite.parse(invite.link())!!)()!!
        assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message!!.contains("expired"))
        assertTrue(inviter.prompts.isEmpty()); assertTrue(joiner.prompts.isEmpty())
        assertNull(inviter.store.get(joiner.nodeId)); assertNull(joiner.store.get(inviter.nodeId))
        assertTrue(inviter.events.single() is PairingEvent.Aborted)
        assertNull(inviter.router.secure()!!.session(joiner.nodeId))
    }

    @Test fun aWrongOrReusedTokenIsRefused() = runTest {
        val (inviter, joiner) = phones()
        val invite = inviter.pairing.startInvite("g1")
        val r = join(joiner, invite.copy(token = "forged"))()!!
        assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!.contains("unknown"))
        assertNull(inviter.store.get(joiner.nodeId)); assertTrue(inviter.prompts.isEmpty())
        // A token for another group is refused too.
        val r2 = join(joiner, invite.copy(groupId = "g2"))()!!
        assertTrue(r2.isFailure); assertTrue(r2.exceptionOrNull()!!.message!!.contains("another swarm"))
        // The real one works once; a second joiner with the same token is refused.
        val pj = join(joiner, invite)()!!.getOrThrow()
        inviter.pairing.confirm(inviter.prompts.single(), true); joiner.pairing.confirm(pj, true)
        runCurrent()
        assertNotNull(inviter.store.get(joiner.nodeId))
        val third = Phone(joiner.bus, "third", "Accounts", scope()).also { it.inviterEndpoint = inviter.transport.endpoint }
        runCurrent(); third.router.start(); runCurrent()
        val r3 = join(third, invite)()!!
        assertTrue(r3.isFailure); assertTrue(r3.exceptionOrNull()!!.message!!.contains("already used"))
        assertNull(inviter.store.get(third.nodeId))
    }

    @Test fun aPhoneThatIsNotTheInviterCannotAnswerTheInvite() = runTest {
        val (inviter, joiner) = phones()
        val invite = inviter.pairing.startInvite("g1")
        // The link's key was swapped by an attacker: the handshake with the real inviter fails the key check.
        val r = join(joiner, invite.copy(pubkey = FakeSigner().publicKeyB64))()!!
        assertTrue(r.isFailure)
        assertNull(joiner.store.get(inviter.nodeId)); assertTrue(joiner.prompts.isEmpty())
        assertFalse(joiner.warnings.isEmpty())   // the joiner was told the key did not match
    }

    @Test fun anExpiredInviteCannotBeConfirmedLate() = runTest {
        val (inviter, joiner) = phones()
        val invite = inviter.pairing.startInvite("g1")
        val pj = join(joiner, invite)()!!.getOrThrow()
        joiner.pairing.confirm(pj, true); runCurrent()
        clock += Invite.TOKEN_TTL_MS + 1          // the inviter's user came back to the prompt after ten minutes
        assertTrue(inviter.pairing.confirm(inviter.prompts.single(), true).isFailure)
        runCurrent()
        assertNull(inviter.store.get(joiner.nodeId)); assertNull(joiner.store.get(inviter.nodeId))
    }
}
