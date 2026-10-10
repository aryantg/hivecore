package com.hivecore.mesh

import com.hivecore.core.AnswerChip
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeOutcome
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.Transport
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.core.fakes.FakeTransport
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MeshRouterTest {
    private var clock = 1_700_000_000_000L
    private val scopes = ArrayList<CoroutineScope>()

    /** A foreground scope on the test scheduler (backgroundScope events are skipped by advanceUntilIdle). */
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    /** Drains every task at the current virtual time without advancing the clock (the pairing window must not expire). */
    private fun TestScope.settle() = runCurrent()

    /** One phone: its own signer, approval key, PeerStore on an in-memory db, FakeTransport on the shared bus. */
    inner class Node(bus: FakeBus, val id: String, val name: String, scope: CoroutineScope, debug: Boolean = false,
                     var holds: List<String> = listOf("$id-table")) {
        val signer = FakeSigner()
        val approval = FakeSigner()
        val db = JdbcSqlDb.inMemory()
        val store = PeerStore(db)
        val transport: FakeTransport = bus.node(id, name)
        val router = MeshRouter(
            transports = mapOf("fake" to transport), signer = signer, peerStore = store,
            localHello = { HelloBody("", name, "", approvalPubkey = approval.publicKeyB64, holds = holds, caps = listOf("sql", "npu")) },
            scope = scope, debugAllowNodeSignedApprovals = debug, now = { clock }
        )
        val nodeId get() = router.nodeId
        val got = ArrayList<Verified>()
        val pairings = ArrayList<PairingRequest>()

        init {
            scope.launch { router.verified.collect { got += it } }
            scope.launch { router.pairing.collect { pairings += it } }
        }

        fun pin(other: Node) = store.pin(other.nodeId, other.name, other.signer.publicKeyB64, other.approval.publicKeyB64, other.holds, clock)
        fun gotTypes() = got.map { it.type }
        fun got(type: MsgType) = got.filter { it.type == type }

        /** Sends raw bytes to [to] straight through the transport (bypassing the router's checks). */
        suspend fun raw(to: Node, bytes: ByteArray) = transport.send(to.transport.endpoint, bytes)
        suspend fun raw(to: PeerEndpoint, bytes: ByteArray) = transport.send(to, bytes)
    }

    private fun TestScope.node(bus: FakeBus, id: String, name: String, debug: Boolean = false) = Node(bus, id, name, scope(), debug)

    private suspend fun TestScope.startAll(vararg nodes: Node) {
        testScheduler.runCurrent()           // subscribe the verified/pairing collectors
        for (n in nodes) n.router.start()
        settle()
    }

    private fun job(from: Node, id: String = "job-1", nonce: String = "nonce-$id", ts: Long = clock) =
        MeshJson.encode(JobBody(id, nonce, ts, from.nodeId, from.name, "Average commission in the West region"))

    // ------------------------------------------------------------------ mesh of three

    @Test fun threeNodesExchangeHelloBroadcastJobAndUnicastResult() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales"); val accounts = node(bus, "accounts", "Accounts")
        val all = listOf(hr, sales, accounts)
        for (a in all) for (b in all) if (a !== b) a.pin(b)
        startAll(hr, sales, accounts)

        for (n in all) {
            assertEquals(n.id, setOf(sales, hr, accounts).filter { it !== n }.map { it.nodeId }.toSet(), n.router.connectedNodeIds().toSet())
            assertEquals(n.id, 2, n.got(MsgType.HELLO).size)
            assertEquals(2, n.router.peers.value.count { it.pinned && it.connected })
        }
        val fromSales = hr.got(MsgType.HELLO).first { it.from == sales.nodeId }
        val hello = MeshJson.decode<HelloBody>(fromSales.body)
        assertEquals(listOf("sales-table"), hello.holds)
        assertEquals(listOf("sql", "npu"), hr.router.lastHello(sales.nodeId)!!.caps)
        assertEquals(sales.approval.publicKeyB64, hr.store.get(sales.nodeId)!!.approvalPubkey)
        assertEquals(listOf("sales-table"), hr.router.peers.value.first { it.nodeId == sales.nodeId }.holds)

        val sentTo = hr.router.broadcast(MsgType.JOB, job(hr))
        settle()
        assertEquals(setOf(sales.nodeId, accounts.nodeId), sentTo.toSet())
        assertEquals(1, sales.got(MsgType.JOB).size); assertEquals(1, accounts.got(MsgType.JOB).size)
        assertEquals(0, hr.got(MsgType.JOB).size)
        assertEquals(hr.nodeId, sales.got(MsgType.JOB).single().from)
        assertEquals("job-1", MeshJson.decode<JobBody>(sales.got(MsgType.JOB).single().body).jobId)

        val result = MeshJson.encode(ResultBody.of("job-1", sales.nodeId, listOf(NodeOutcome.Tables(emptyList(), AnswerChip.LLM, "ih")), 1204, backend = "npu"))
        assertTrue(sales.router.send(hr.nodeId, MsgType.RESULT, result).isSuccess)
        settle()
        assertEquals(1, hr.got(MsgType.RESULT).size)
        assertEquals(sales.nodeId, hr.got(MsgType.RESULT).single().from)
        assertEquals(0, accounts.got(MsgType.RESULT).size)

        // Unicast to a node that is not connected fails cleanly.
        bus.setOffline("accounts", true)
        settle()
        assertTrue(hr.router.send(accounts.nodeId, MsgType.JOB, job(hr, "job-2")).isFailure)
        assertEquals(listOf(sales.nodeId), hr.router.connectedNodeIds())
        assertFalse(hr.router.peers.value.first { it.nodeId == accounts.nodeId }.connected)
    }

    // ------------------------------------------------------------------ verification

    @Test fun unknownSignerIsDroppedAndTamperedOrMisSignedBodiesAreDropped() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        sales.pin(hr)                         // hr does NOT pin sales
        startAll(hr, sales)
        assertEquals(1, sales.got(MsgType.HELLO).size)   // sales knows hr
        assertEquals(0, hr.got.size)                     // hr dropped the unknown HELLO
        assertTrue(hr.router.peers.value.isEmpty())

        sales.raw(hr, Wire.encode(MsgType.JOB, sales.nodeId, job(sales), sales.signer))
        settle()
        assertEquals(0, hr.got.size)                     // unknown signer

        hr.pin(sales)
        // Signed with the wrong key for `from`.
        sales.raw(hr, Wire.encode(MsgType.JOB, sales.nodeId, job(sales), FakeSigner()))
        // Body tampered after signing.
        val good = String(Wire.encode(MsgType.JOB, sales.nodeId, job(sales, "job-t"), sales.signer))
        sales.raw(hr, good.replace("West", "East").toByteArray())
        // Body claims a different requester than the envelope.
        sales.raw(hr, Wire.encode(MsgType.JOB, sales.nodeId, job(hr, "job-x"), sales.signer))
        // Garbage.
        sales.raw(hr, "{not an envelope".toByteArray())
        sales.raw(hr, Wire.encode("GOSSIP", sales.nodeId, "{}", sales.signer))
        settle()
        assertEquals(0, hr.got.size)

        sales.raw(hr, Wire.encode(MsgType.JOB, sales.nodeId, job(sales, "job-ok"), sales.signer))
        settle()
        assertEquals(listOf(MsgType.JOB), hr.gotTypes())
    }

    @Test fun replayedAndStaleJobsAreRejected() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)

        val bytes = Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-1", "n1"), hr.signer)
        hr.raw(sales, bytes); hr.raw(sales, bytes)
        settle()
        assertEquals(1, sales.got(MsgType.JOB).size)
        // Same nonce in a fresh envelope is still a replay; a new nonce is fine.
        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-9", "n1"), hr.signer))
        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-2", "n2"), hr.signer))
        settle()
        assertEquals(listOf("job-1", "job-2"), sales.got(MsgType.JOB).map { MeshJson.decode<JobBody>(it.body).jobId })

        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-old", "n3", ts = clock - 11 * 60_000L), hr.signer))
        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-future", "n4", ts = clock + 11 * 60_000L), hr.signer))
        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-edge", "n5", ts = clock - 9 * 60_000L), hr.signer))
        settle()
        assertEquals(listOf("job-1", "job-2", "job-edge"), sales.got(MsgType.JOB).map { MeshJson.decode<JobBody>(it.body).jobId })
    }

    @Test fun consentResApprovalSignatureRules() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        val jobId = "job-1"; val ih = "intent-hash-1"
        val canonical = Canonical.consent(jobId, ih).toByteArray()
        fun res(approved: Boolean, signedBy: String, sig: ByteArray?, hash: String = ih, job: String = jobId) =
            MeshJson.encode(ConsentResBody(job, hash, approved, sales.nodeId, clock, signedBy, sig?.let { Base64.getEncoder().encodeToString(it) }))
        suspend fun sendRes(body: String) { sales.router.send(hr.nodeId, MsgType.CONSENT_RES, body); settle() }

        // Approval before any CONSENT_REQ for the job: dropped.
        sendRes(res(true, "approval", sales.approval.sign(canonical)))
        assertEquals(0, hr.got(MsgType.CONSENT_RES).size)

        sales.router.send(hr.nodeId, MsgType.CONSENT_REQ, MeshJson.encode(ConsentReqBody(jobId, sales.nodeId, ih)))
        settle()
        assertEquals(1, hr.got(MsgType.CONSENT_REQ).size)

        sendRes(res(true, "approval", sales.signer.sign(canonical)))            // node key, claims approval -> dropped
        sendRes(res(true, "node", sales.signer.sign(canonical)))                // node-signed, debug flag off -> dropped
        sendRes(res(true, "approval", null))                                     // approved without a sig -> dropped
        sendRes(res(true, "approval", sales.approval.sign(canonical), hash = "other"))   // intentHash differs -> dropped
        sendRes(res(true, "approval", sales.approval.sign(Canonical.consent(jobId, "other").toByteArray())))  // wrong canonical -> dropped
        sendRes(res(true, "owner", sales.approval.sign(canonical)))              // unknown signedBy -> dropped
        assertEquals(0, hr.got(MsgType.CONSENT_RES).size)

        sendRes(res(true, "approval", sales.approval.sign(canonical)))          // the real thing
        sendRes(res(false, "approval", null))                                    // a decline needs no approval sig
        assertEquals(2, hr.got(MsgType.CONSENT_RES).size)
        assertTrue(MeshJson.decode<ConsentResBody>(hr.got(MsgType.CONSENT_RES)[0].body).approved)
        assertFalse(MeshJson.decode<ConsentResBody>(hr.got(MsgType.CONSENT_RES)[1].body).approved)

        // Without a pinned approvalPubkey an "approval" signature cannot be checked -> dropped.
        hr.store.updateFromHello(sales.nodeId, "Sales", null, emptyList(), clock, "fake", "sales")
        sendRes(res(true, "approval", sales.approval.sign(canonical)))
        assertEquals(2, hr.got(MsgType.CONSENT_RES).size)
    }

    @Test fun nodeSignedApprovalIsAcceptedOnlyWithTheDebugFlag() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR", debug = true); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        val canonical = Canonical.consent("job-1", "ih").toByteArray()
        sales.router.send(hr.nodeId, MsgType.CONSENT_REQ, MeshJson.encode(ConsentReqBody("job-1", sales.nodeId, "ih")))
        sales.router.send(hr.nodeId, MsgType.CONSENT_RES, MeshJson.encode(ConsentResBody("job-1", "ih", true, sales.nodeId, clock, "node",
            Base64.getEncoder().encodeToString(sales.signer.sign(canonical)))))
        // Node-signed but with the approval key: not the node key -> dropped even in debug.
        sales.router.send(hr.nodeId, MsgType.CONSENT_RES, MeshJson.encode(ConsentResBody("job-1", "ih", true, sales.nodeId, clock, "node",
            Base64.getEncoder().encodeToString(sales.approval.sign(canonical)))))
        settle()
        assertEquals(1, hr.got(MsgType.CONSENT_RES).size)
    }

    // ------------------------------------------------------------------ pairing

    @Test fun pairingModePinsOnFirstHelloWithEqualDigitsOnBothSides() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.router.setPairingMode(true); sales.router.setPairingMode(true)
        startAll(hr, sales)
        assertTrue(hr.transport.pairingMode); assertTrue(sales.transport.pairingMode)

        assertEquals(1, hr.pairings.size); assertEquals(1, sales.pairings.size)
        val expected = Wire.pairingDigits(hr.signer.publicKeyB64, sales.signer.publicKeyB64)
        assertEquals(expected, hr.pairings[0].digits)
        assertEquals(expected, sales.pairings[0].digits)
        assertEquals("sales", hr.pairings[0].endpoint.endpointId)
        assertNull(hr.store.get(sales.nodeId))
        val unpinned = hr.router.peers.value.single()
        assertEquals(sales.nodeId, unpinned.nodeId); assertFalse(unpinned.pinned); assertTrue(unpinned.connected)
        assertTrue(hr.router.connectedNodeIds().isEmpty())

        hr.router.accept(hr.pairings[0], true)
        sales.router.accept(sales.pairings[0], true)
        settle()
        assertEquals(sales.signer.publicKeyB64, hr.store.get(sales.nodeId)!!.pubkey)
        assertEquals(hr.signer.publicKeyB64, sales.store.get(hr.nodeId)!!.pubkey)
        assertEquals(hr.approval.publicKeyB64, sales.store.get(hr.nodeId)!!.approvalPubkey)
        assertEquals(listOf(sales.nodeId), hr.router.connectedNodeIds())
        assertEquals(listOf(hr.nodeId), sales.router.connectedNodeIds())
        assertTrue(hr.router.peers.value.single().pinned)

        hr.router.broadcast(MsgType.JOB, job(hr))
        settle()
        assertEquals(1, sales.got(MsgType.JOB).size)

        // The window closes by itself.
        advanceTimeBy(121_000); settle()
        assertFalse(hr.router.pairingMode()); assertFalse(hr.transport.pairingMode)
    }

    @Test fun pendingPairingsListTheUndecidedRequestsAndClearOnDecisionOrWindowEnd() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.router.setPairingMode(true); sales.router.setPairingMode(true)
        assertTrue(hr.router.pairingModeFlow.value)
        startAll(hr, sales)
        assertEquals(hr.pairings, hr.router.pendingPairings.value)
        assertEquals(1, sales.router.pendingPairings.value.size)
        hr.router.accept(hr.router.pendingPairings.value.single(), true)
        assertTrue(hr.router.pendingPairings.value.isEmpty())
        // A transport-raised request is listed too, and the end of the pairing window clears what is left.
        val req = PairingRequest(PeerEndpoint("fake", "acct", "Accounts|deadbeef"), "1234")
        hr.transport.raisePairing(req); settle()
        assertEquals(listOf(req), hr.router.pendingPairings.value)
        advanceTimeBy(121_000); settle()
        assertFalse(hr.router.pairingModeFlow.value)
        assertTrue(hr.router.pendingPairings.value.isEmpty())
        assertTrue(sales.router.pendingPairings.value.isEmpty())
    }

    @Test fun decliningAPairingDoesNotPin() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.router.setPairingMode(true); sales.router.setPairingMode(true)
        startAll(hr, sales)
        hr.router.accept(hr.pairings.single(), false)
        settle()
        assertNull(hr.store.get(sales.nodeId))
        assertTrue(hr.router.peers.value.isEmpty())
        // Pairing mode off: the next HELLO from the unpinned node is dropped and no new request is raised.
        hr.router.setPairingMode(false)
        sales.router.rebroadcastHello()
        settle()
        assertEquals(1, hr.pairings.size)
        assertNull(hr.store.get(sales.nodeId))
    }

    @Test fun transportPairingPassesThroughAndItsFirstHelloPins() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        startAll(hr, sales)
        assertNull(hr.store.get(sales.nodeId))
        // Nearby raises its own request (with its own digits) on the connection.
        val req = PairingRequest(sales.transport.endpoint, "4321")
        hr.transport.raisePairing(req)
        settle()
        assertEquals(listOf(req), hr.pairings)
        hr.router.accept(req, true)
        assertEquals(req to true, hr.transport.pairingDecisions.single())
        // The HELLO that follows the accepted connection pins without a second prompt.
        sales.router.rebroadcastHello()
        settle()
        assertEquals(sales.signer.publicKeyB64, hr.store.get(sales.nodeId)!!.pubkey)
        assertEquals(1, hr.pairings.size)
    }

    // ------------------------------------------------------------------ pinned-node updates

    @Test fun helloFromPinnedNodeUpdatesHoldsAndApprovalKeyButNeverTheNodeKey() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        sales.holds = listOf("sales", "q4.xlsx")
        sales.router.rebroadcastHello()
        settle()
        assertEquals(listOf("sales", "q4.xlsx"), hr.store.get(sales.nodeId)!!.holds)
        assertEquals(listOf("sales", "q4.xlsx"), hr.router.peers.value.single().holds)
        assertEquals(2, hr.got(MsgType.HELLO).size)

        // A HELLO carrying a different node key for the same nodeId cannot exist (nodeId is the key hash), and a
        // HELLO whose key does not hash to `from` is dropped.
        val impostor = FakeSigner()
        val body = MeshJson.encode(HelloBody(sales.nodeId, "Sales", impostor.publicKeyB64))
        sales.raw(hr, Wire.encode(MsgType.HELLO, sales.nodeId, body, impostor))
        settle()
        assertEquals(2, hr.got(MsgType.HELLO).size)
        assertEquals(sales.signer.publicKeyB64, hr.store.get(sales.nodeId)!!.pubkey)

        hr.router.forget(sales.nodeId)
        assertNull(hr.store.get(sales.nodeId))
        assertTrue(hr.router.peers.value.isEmpty())
        assertTrue(hr.router.send(sales.nodeId, MsgType.JOB, job(hr)).isFailure)
    }

    // ------------------------------------------------------------------ security review #8, #13, #14, #15, #18

    @Test fun pairingWithMismatchedDigitsDoesNotPinOrFallThrough() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.router.setPairingMode(true); sales.router.setPairingMode(true)
        startAll(hr, sales)
        val real = hr.pairings.single()
        hr.router.accept(PairingRequest(real.endpoint, "000000"), true)
        settle()
        assertTrue(hr.transport.pairingDecisions.isEmpty())        // not forwarded as a transport pairing
        sales.router.rebroadcastHello()                             // the next HELLO must not pin either
        settle()
        assertNull(hr.store.get(sales.nodeId))
        // The digits the owner compared still work.
        hr.router.accept(real, true)
        settle()
        assertEquals(sales.signer.publicKeyB64, hr.store.get(sales.nodeId)!!.pubkey)
    }

    @Test fun anApprovedConsentResIsAcceptedOncePerConsentReq() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        val approved = MeshJson.encode(ConsentResBody("job-1", "ih", true, sales.nodeId, clock, "approval",
            Base64.getEncoder().encodeToString(sales.approval.sign(Canonical.consent("job-1", "ih").toByteArray()))))
        sales.router.send(hr.nodeId, MsgType.CONSENT_REQ, MeshJson.encode(ConsentReqBody("job-1", sales.nodeId, "ih")))
        sales.router.send(hr.nodeId, MsgType.CONSENT_RES, approved)
        settle()
        assertEquals(1, hr.got(MsgType.CONSENT_RES).size)
        // The same signed approval again (a replay, or a second message for the same request): dropped.
        sales.router.send(hr.nodeId, MsgType.CONSENT_RES, approved)
        sales.raw(hr, Wire.encode(MsgType.CONSENT_RES, sales.nodeId, approved, sales.signer))
        settle()
        assertEquals(1, hr.got(MsgType.CONSENT_RES).size)
        // A new CONSENT_REQ opens the next one.
        sales.router.send(hr.nodeId, MsgType.CONSENT_REQ, MeshJson.encode(ConsentReqBody("job-1", sales.nodeId, "ih")))
        sales.router.send(hr.nodeId, MsgType.CONSENT_RES, approved)
        settle()
        assertEquals(2, hr.got(MsgType.CONSENT_RES).size)
    }

    @Test fun anOlderHelloCannotRollBackTheApprovalKey() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        val revoked = FakeSigner()
        fun hello(ts: Long, approvalKey: String) = Wire.encode(MsgType.HELLO, sales.nodeId,
            MeshJson.encode(HelloBody(sales.nodeId, "Sales", sales.signer.publicKeyB64, approvalPubkey = approvalKey, ts = ts)), sales.signer)
        sales.raw(hr, hello(clock - 60_000, revoked.publicKeyB64))      // captured before the re-enrolment
        settle()
        assertEquals(sales.approval.publicKeyB64, hr.store.get(sales.nodeId)!!.approvalPubkey)
        assertEquals(1, hr.got(MsgType.HELLO).size)
        val fresh = FakeSigner()
        sales.raw(hr, hello(clock + 1, fresh.publicKeyB64))             // a newer HELLO still updates it
        settle()
        assertEquals(fresh.publicKeyB64, hr.store.get(sales.nodeId)!!.approvalPubkey)
        sales.raw(hr, hello(clock, revoked.publicKeyB64))
        settle()
        assertEquals(fresh.publicKeyB64, hr.store.get(sales.nodeId)!!.approvalPubkey)
    }

    @Test fun aResultWhoseHashDoesNotMatchItsOutcomesIsDropped() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        val good = ResultBody.of("job-1", sales.nodeId, listOf(NodeOutcome.Refused("x")), 0)
        sales.router.send(hr.nodeId, MsgType.RESULT, MeshJson.encode(good.copy(hash = "0".repeat(64))))
        settle()
        assertTrue(hr.got(MsgType.RESULT).isEmpty())
        sales.router.send(hr.nodeId, MsgType.RESULT, MeshJson.encode(good))
        settle()
        assertEquals(1, hr.got(MsgType.RESULT).size)
    }

    /** A transport that records [Disconnectable.disconnect] calls. */
    private class Closing(val t: FakeTransport) : Transport by t, Disconnectable {
        val closed = ArrayList<PeerEndpoint>()
        override fun disconnect(endpoint: PeerEndpoint) { closed += endpoint }
    }

    @Test fun anUnpinnedHelloOutsidePairingClosesThatLinkAndOversizeEnvelopesAreDropped() = runTest {
        val bus = FakeBus()
        val sales = node(bus, "sales", "Sales")
        val hrT = Closing(bus.node("hr", "HR"))
        val hrSigner = FakeSigner()
        val hrStore = PeerStore(JdbcSqlDb.inMemory())
        val hr = MeshRouter(mapOf("fake" to hrT), hrSigner, hrStore, { HelloBody("", "HR", "") }, scope(), now = { clock })
        val got = ArrayList<Verified>()
        val s = scope(); s.launch { hr.verified.collect { got += it } }
        testScheduler.runCurrent()
        hr.start(); sales.router.start()
        settle()
        assertEquals(listOf(sales.transport.endpoint.endpointId), hrT.closed.map { it.endpointId })
        assertNull(hrStore.get(sales.nodeId))

        // Pinned now; a 70 KB envelope is dropped before it is parsed, a normal one passes.
        hrStore.pin(sales.nodeId, "Sales", sales.signer.publicKeyB64, null, emptyList(), clock)
        val huge = MeshJson.encode(JobBody("job-big", "n-big", clock, sales.nodeId, "Sales", "x".repeat(70 * 1024)))
        sales.raw(PeerEndpoint("fake", "hr", "HR"), Wire.encode(MsgType.JOB, sales.nodeId, huge, sales.signer))
        sales.raw(PeerEndpoint("fake", "hr", "HR"), Wire.encode(MsgType.JOB, sales.nodeId, job(sales), sales.signer))
        settle()
        assertEquals(listOf("job-1"), got.filter { it.type == MsgType.JOB }.map { MeshJson.decode<JobBody>(it.body).jobId })
    }

    @Test fun aPeerWhoseClockIsOffIsReportedAndClearedWhenItAgrees() = runTest {
        val bus = FakeBus()
        val hr = node(bus, "hr", "HR"); val sales = node(bus, "sales", "Sales")
        hr.pin(sales); sales.pin(hr)
        startAll(hr, sales)
        assertTrue(sales.router.clockSkew.value.isEmpty())
        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-old", "n1", ts = clock - 12 * 60_000L), hr.signer))
        settle()
        assertTrue(sales.got(MsgType.JOB).isEmpty())
        assertEquals(-12 * 60_000L, sales.router.clockSkew.value[hr.nodeId])
        hr.raw(sales, Wire.encode(MsgType.JOB, hr.nodeId, job(hr, "job-ok", "n2"), hr.signer))
        settle()
        assertTrue(sales.router.clockSkew.value.isEmpty())
        assertEquals(1, sales.got(MsgType.JOB).size)
    }

    @Test fun transportSwitchAndBondedForwarding() = runTest {
        val bus1 = FakeBus(); val bus2 = FakeBus()
        val connects = ArrayList<String>()
        val signer = FakeSigner()
        val t1 = bus1.node("hr"); val t2 = bus2.node("hr")
        val router = MeshRouter(mapOf("nearby" to t1, "bt" to t2), signer, PeerStore(JdbcSqlDb.inMemory()),
            { HelloBody("", "HR", "") }, scope(), initialTransport = "nearby",
            bondedProvider = { listOf("AA:BB" to "iQOO 15 Sales") }, connectBondedHandler = { connects += it }, now = { clock })
        assertEquals("nearby", router.transportName.value)
        router.start()
        assertTrue(t1.started); assertFalse(t2.started)
        assertEquals("HR|" + router.nodeId.take(8), t1.localName)
        assertEquals(listOf("AA:BB" to "iQOO 15 Sales"), router.bonded.value)
        router.selectTransport("bt")
        assertFalse(t1.started); assertTrue(t2.started)
        assertEquals("bt", router.transportName.value)
        router.selectTransport("wifi")   // unknown: ignored
        assertEquals("bt", router.transportName.value); assertTrue(t2.started)
        router.connectBonded("AA:BB")
        assertEquals(listOf("AA:BB"), connects)
        router.stop()
        assertFalse(t2.started)
    }
}
