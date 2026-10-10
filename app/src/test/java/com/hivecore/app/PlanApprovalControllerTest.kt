package com.hivecore.app

import com.hivecore.app.approval.PlanApprovalController
import com.hivecore.core.ApprovalResult
import com.hivecore.core.PendingApproval
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.mesh.ConsentResBody
import com.hivecore.mesh.PlanConsent
import com.hivecore.trust.ApprovalNotifying
import com.hivecore.trust.ApprovalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 7.6 / 17 on the JVM: the approval screen's logic over the store. One pending plan shows every figure, one
 * signature over the plan bytes approves it, a decline refuses it, and a request that already ended is reported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanApprovalControllerTest {
    private class Notifier : ApprovalNotifying {
        val notified = mutableListOf<PendingApproval>()
        val cancelled = mutableListOf<String>()
        override fun notify(pending: PendingApproval) { notified += pending }
        override fun cancel(jobId: String) { cancelled += jobId }
    }

    private val notifier = Notifier()
    private var now = 5_000_000L
    private val hashes = listOf("bbb", "aaa", "ccc")
    private val description = "Sales' phone asks for 3 figures from your employees table:\n" +
        "1. Count of employees by region\n2. Average commission in West (confidential: commission)\n" +
        "3. Average commission in West, joined 2024 or later (confidential: commission)\n" +
        "One fingerprint approves all 3 figures; nothing else."
    private val store = ApprovalStore(keys = null, nodeSigner = null, notifier = notifier, timeoutMs = 60_000, clock = { now })
    private val controller = PlanApprovalController(store) { now }

    @Test fun aPendingPlanShowsEveryFigureAndOneSignatureApprovesIt() = runTest {
        val d = async { store.requestPlan("plan-1", "Sales", description, "ph", hashes) }
        runCurrent()
        val item = controller.current(null)!!
        assertEquals(item, controller.current("plan-1"))
        assertNull(controller.current("other"))
        val v = controller.view(item)
        assertTrue(v.isPlan)
        assertEquals("Sales", v.requester)
        assertEquals("Sales' phone asks for 3 figures from your employees table:", v.header)
        assertEquals(3, v.figures.size)
        assertEquals(listOf("One fingerprint approves all 3 figures; nothing else."), v.notes)
        assertEquals("ph", v.planHash)
        assertEquals(60L, v.secondsLeft)
        now += 55_000
        assertEquals(5L, controller.view(item).secondsLeft)

        val key = FakeSigner()
        var signed: ByteArray? = null
        assertNull(controller.approve(item) { bytes -> signed = bytes; key.sign(bytes) })
        assertArrayEquals(PlanConsent.canonicalBytes("plan-1", "ph", hashes), signed)
        val r = d.await() as ApprovalResult.Approved
        assertEquals(ConsentResBody.SIGNED_BY_APPROVAL, r.signedBy)
        assertTrue(com.hivecore.core.Verify.verify(key.publicKeyB64, PlanConsent.canonicalBytes("plan-1", "ph", hashes), r.sig))
        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("plan-1"), notifier.cancelled)
    }

    @Test fun declineRefusesAndAnEndedRequestIsReported() = runTest {
        val d = async { store.request("job-1", "Sales", "Average commission in West", "h1") }
        runCurrent()
        val item = controller.current("job-1")!!
        val v = controller.view(item)
        assertFalse(v.isPlan)
        assertEquals(listOf("Average commission in West"), v.figures)
        assertTrue(controller.decline(item, "button"))
        assertEquals(ApprovalResult.Declined, d.await())
        assertFalse(controller.decline(item, "again"))
        assertEquals("That request already ended", controller.approve(item) { it })
        assertNull(controller.current(null))
    }

    @Test fun aSigningFailureKeepsTheRequestPending() = runTest {
        val d = async { store.request("job-2", "HR", "Headcount by region", "h2") }
        runCurrent()
        val item = controller.current("job-2")!!
        val why = controller.approve(item) { throw IllegalStateException("key locked") }
        assertTrue(why, why!!.contains("key locked"))
        assertEquals(1, store.pending.value.size)
        assertTrue(controller.decline(item, "cleanup"))
        d.await()
    }
}
