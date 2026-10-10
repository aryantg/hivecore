package com.hivecore.mesh

import com.hivecore.mesh.PlanConsent
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeSigner
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
import org.junit.Test

/** MeshRouter's PLAN_CONSENT_REQ/RES rules (swarm plan §7, §8.1) on the commander's side. */
class PlanConsentRouterTest {
    private val clock = 1_700_000_000_000L
    private val scopes = ArrayList<CoroutineScope>()
    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private inner class Node(bus: FakeBus, val id: String, scope: CoroutineScope, debug: Boolean) {
        val signer = FakeSigner()
        val approval = FakeSigner()
        val store = PeerStore(JdbcSqlDb.inMemory())
        val router = MeshRouter(mapOf("fake" to bus.node(id, id)), signer, store,
            { HelloBody("", id, "", approvalPubkey = approval.publicKeyB64) }, scope, debugAllowNodeSignedApprovals = debug, now = { clock })
        val got = ArrayList<Verified>()
        init { scope.launch { router.verified.collect { got += it } } }
        fun pin(o: Node) = store.pin(o.router.nodeId, o.id, o.signer.publicKeyB64, o.approval.publicKeyB64, emptyList(), clock)
        fun res() = got.filter { it.type == MsgType.PLAN_CONSENT_RES }.map { MeshJson.decode<PlanConsentResBody>(it.body) }
    }

    private suspend fun TestScope.pair(debug: Boolean = false): Pair<Node, Node> {
        val bus = FakeBus()
        val s = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val sales = Node(bus, "sales", s, debug); val hr = Node(bus, "hr", s, debug)
        sales.pin(hr); hr.pin(sales)
        runCurrent()
        sales.router.start(); hr.router.start()
        runCurrent()
        return sales to hr
    }

    private fun req(hr: Node, planId: String = "plan-1") = PlanConsentReqBody(planId, "ph", hr.router.nodeId, listOf("i1", "i2"))

    private suspend fun TestScope.send(hr: Node, to: Node, type: MsgType, body: Any) {
        val json = when (body) { is PlanConsentReqBody -> MeshJson.encode(body); is PlanConsentResBody -> MeshJson.encode(body); else -> error("") }
        hr.router.send(to.router.nodeId, type, json)
        runCurrent()
    }

    @Test fun approvalMustMatchTheRequestAndVerifyOnce() = runTest {
        val (sales, hr) = pair()
        val r = req(hr)
        val good = PlanConsent.approved(r, hr.approval.sign(PlanConsent.canonicalBytes(r.planId, r.planHash, r.intentHashes)), "approval", clock)

        send(hr, sales, MsgType.PLAN_CONSENT_RES, good)                      // no request yet
        assertEquals(0, sales.res().size)

        send(hr, sales, MsgType.PLAN_CONSENT_REQ, r)
        send(hr, sales, MsgType.PLAN_CONSENT_RES, good.copy(intentHashes = listOf("i1", "i3")))   // tampered figures
        send(hr, sales, MsgType.PLAN_CONSENT_RES, good.copy(planHash = "other"))                  // tampered plan
        val forged = PlanConsent.approved(r, FakeSigner().sign(PlanConsent.canonicalBytes(r.planId, r.planHash, r.intentHashes)), "approval", clock)
        send(hr, sales, MsgType.PLAN_CONSENT_RES, forged)                                         // wrong key
        send(hr, sales, MsgType.PLAN_CONSENT_RES, good.copy(approvalSig = null))                  // unsigned
        assertEquals(0, sales.res().size)

        send(hr, sales, MsgType.PLAN_CONSENT_RES, good.copy(intentHashes = listOf("i2", "i1")))   // order does not matter
        assertEquals(1, sales.res().size)
        send(hr, sales, MsgType.PLAN_CONSENT_RES, good)                                           // one answer per request
        assertEquals(1, sales.res().size)
    }

    @Test fun declineAndRefusalNeedNoSignature() = runTest {
        val (sales, hr) = pair()
        send(hr, sales, MsgType.PLAN_CONSENT_RES, PlanConsent.declined(req(hr), clock, "these figures together would reveal fewer than 5 people"))
        assertEquals(1, sales.res().size)
        assertFalse(sales.res().single().approved)
    }

    @Test fun nodeSignedApprovalOnlyInDebug() = runTest {
        for (debug in listOf(false, true)) {
            val (sales, hr) = pair(debug)
            val r = req(hr)
            send(hr, sales, MsgType.PLAN_CONSENT_REQ, r)
            send(hr, sales, MsgType.PLAN_CONSENT_RES, PlanConsent.debugNodeApprove(hr.signer, r, clock))
            assertEquals("debug=$debug", if (debug) 1 else 0, sales.res().size)
        }
    }

    @Test fun senderMustBeTheNamedNode() = runTest {
        val (sales, hr) = pair()
        val r = req(hr).copy(node = sales.router.nodeId)
        send(hr, sales, MsgType.PLAN_CONSENT_REQ, r)
        send(hr, sales, MsgType.PLAN_CONSENT_RES, PlanConsent.declined(r, clock))
        assertEquals(0, sales.got.count { it.type == MsgType.PLAN_CONSENT_REQ || it.type == MsgType.PLAN_CONSENT_RES })
    }
}
