package com.hivecore.trust

import com.hivecore.mesh.PlanConsentVerdict
import com.hivecore.mesh.PlanConsentScreen
import com.hivecore.mesh.PlanConsent
import com.hivecore.core.ApprovalResult
import com.hivecore.core.PendingApproval
import com.hivecore.core.Verify
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.mesh.Canonical
import com.hivecore.mesh.ConsentResBody
import com.hivecore.mesh.PlanConsentReqBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApprovalStore.requestPlan]: one pending item per plan, the plan-scoped canonical bytes, and the signing path the
 * activity follows (BiometricPrompt is device-only; a [FakeSigner] stands in for the CryptoObject's approval key).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalStorePlanTest {
    private class Notifier : ApprovalNotifying {
        val notified = mutableListOf<PendingApproval>()
        val cancelled = mutableListOf<String>()
        override fun notify(pending: PendingApproval) { notified += pending }
        override fun cancel(jobId: String) { cancelled += jobId }
    }

    private val notifier = Notifier()
    private val now = 5_000_000L
    private val hashes = listOf("bbb", "aaa", "ccc")
    private val description = "Sales' phone asks for 3 figures from your employees table:\n" +
        "1. Count of employees by region\n2. Average commission in West (confidential: commission)\n" +
        "3. Average commission in West, joined 2024 or later (confidential: commission)\n" +
        "One fingerprint approves all 3 figures; nothing else."

    private fun store(node: FakeSigner? = null, auto: Boolean = false) =
        ApprovalStore(keys = null, nodeSigner = node, notifier = notifier, timeoutMs = 60_000, debugAutoApprove = { auto }, clock = { now })

    @Test fun planIsOnePendingScreenWithEveryFigureAndSignsPlanBytes() = runTest {
        val s = store()
        val d = async { s.requestPlan("plan-1", "Sales", description, "ph", hashes) }
        runCurrent()

        val item = s.pending.value.single()
        assertEquals(PendingApproval("plan-1", "Sales", description, "ph", now + 60_000), item)
        assertEquals(listOf(item), notifier.notified)
        val plan = s.planFor("plan-1")!!
        assertEquals(hashes, plan.intentHashes)
        assertEquals("Sales' phone asks for 3 figures from your employees table:", plan.screen.header)
        assertEquals(listOf("Count of employees by region", "Average commission in West (confidential: commission)",
            "Average commission in West, joined 2024 or later (confidential: commission)"), plan.screen.figures)
        assertEquals(listOf("One fingerprint approves all 3 figures; nothing else."), plan.screen.notes)

        // The bytes the activity signs are the plan form, not jobId|intentHash|true.
        val bytes = s.signingBytes("plan-1")!!
        assertArrayEquals(Canonical.planConsent("plan-1", "ph", hashes).toByteArray(Charsets.UTF_8), bytes)
        assertArrayEquals(PlanConsent.canonicalBytes("plan-1", "ph", hashes), bytes)

        // Activity path: the approval key signs those bytes inside the prompt, then resolves the pending item.
        val approvalKey = FakeSigner()
        assertTrue(s.resolve("plan-1", ApprovalResult.Approved(approvalKey.sign(bytes), ConsentResBody.SIGNED_BY_APPROVAL)))
        val r = d.await() as ApprovalResult.Approved
        assertEquals(ConsentResBody.SIGNED_BY_APPROVAL, r.signedBy)
        assertTrue(s.pending.value.isEmpty())
        assertNull(s.planFor("plan-1"))
        assertNull(s.signingBytes("plan-1"))
        assertEquals(listOf("plan-1"), notifier.cancelled)

        // The commander accepts it against the request (intent order does not matter) and the pinned approval key.
        val req = PlanConsentReqBody("plan-1", "ph", "owner", hashes.sorted())
        val res = PlanConsent.approved(req.copy(intentHashes = hashes), r.sig, r.signedBy, now)
        assertEquals(PlanConsentVerdict.Approved, PlanConsent.verify(res, "owner", req, FakeSigner().publicKeyB64, approvalKey.publicKeyB64, false))
        assertTrue(PlanConsent.verify(res, "owner", req, approvalKey.publicKeyB64, FakeSigner().publicKeyB64, false) is PlanConsentVerdict.Invalid)
    }

    @Test fun singleFigureRequestKeepsItsOwnBytes() = runTest {
        val s = store()
        val d = async { s.request("job-1", "Sales", "Average commission", "ih") }
        runCurrent()
        assertNull(s.planFor("job-1"))
        assertArrayEquals(ApprovalStore.canonicalBytes("job-1", "ih"), s.signingBytes("job-1"))
        s.resolve("job-1", ApprovalResult.Declined)
        assertEquals(ApprovalResult.Declined, d.await())
    }

    @Test fun declinedAndTimedOutPlansClearTheirState() = runTest {
        val s = store()
        val d = async { s.requestPlan("plan-2", "HR", description, "ph2", hashes) }
        runCurrent()
        s.resolve("plan-2", ApprovalResult.Declined)
        assertEquals(ApprovalResult.Declined, d.await())
        assertNull(s.planFor("plan-2"))

        val t = async { s.requestPlan("plan-3", "HR", description, "ph3", hashes) }
        assertEquals(ApprovalResult.TimedOut, t.await())
        assertNull(s.planFor("plan-3"))
        assertTrue(s.pending.value.isEmpty())
    }

    @Test fun debugAutoApproveSignsThePlanBytesWithTheNodeKey() = runTest {
        val node = FakeSigner()
        val r = store(node = node, auto = true).requestPlan("plan-4", "Sales", description, "ph4", hashes) as ApprovalResult.Approved
        assertEquals(ConsentResBody.SIGNED_BY_NODE, r.signedBy)
        assertTrue(Verify.verify(node.publicKeyB64, PlanConsent.canonicalBytes("plan-4", "ph4", hashes), r.sig))
        assertTrue(notifier.notified.isEmpty())
    }

    @Test fun descriptionBuilderRoundTripsThroughTheScreenParser() {
        val screen = PlanConsentScreen.parse("HR's phone asks for 1 figure from your employees table:\n1. Count of employees\n" +
            "Your fingerprint approves this figure only.\nAlso reads payroll.pdf (confidential).")
        assertEquals(listOf("Count of employees"), screen.figures)
        assertEquals(listOf("Your fingerprint approves this figure only.", "Also reads payroll.pdf (confidential)."), screen.notes)
    }
}
