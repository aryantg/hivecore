package com.hivecore.trust

import com.hivecore.core.ApprovalResult
import com.hivecore.core.PendingApproval
import com.hivecore.core.Verify
import com.hivecore.core.fakes.FakeApprovalGate
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.mesh.Canonical
import com.hivecore.mesh.ConsentResBody
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
import java.security.Signature
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalStoreTest {
    private class FakeNotifier : ApprovalNotifying {
        val notified = mutableListOf<PendingApproval>()
        val cancelled = mutableListOf<String>()
        override fun notify(pending: PendingApproval) { notified += pending }
        override fun cancel(jobId: String) { cancelled += jobId }
    }

    private val notifier = FakeNotifier()
    private var now = 1_000_000L
    private fun store(timeoutMs: Long = 60_000, signer: FakeSigner? = null, auto: () -> Boolean = { false }) =
        ApprovalStore(keys = null, nodeSigner = signer, notifier = notifier, timeoutMs = timeoutMs, debugAutoApprove = auto, clock = { now })

    @Test fun requestIsPendingThenApprovedResolves() = runTest {
        val s = store()
        val d = async { s.request("job-1", "Sales", "Average commission in the West", "hash-1") }
        runCurrent()
        val p = s.pending.value
        assertEquals(1, p.size)
        assertEquals(PendingApproval("job-1", "Sales", "Average commission in the West", "hash-1", now + 60_000), p[0])
        assertEquals(p[0], s.pendingFor("job-1"))
        assertEquals(listOf(p[0]), notifier.notified)
        assertTrue(notifier.cancelled.isEmpty())

        val sig = byteArrayOf(1, 2, 3)
        assertTrue(s.resolve("job-1", ApprovalResult.Approved(sig, "approval")))
        val r = d.await()
        assertTrue(r is ApprovalResult.Approved)
        r as ApprovalResult.Approved
        assertArrayEquals(sig, r.sig)
        assertEquals("approval", r.signedBy)
        assertTrue(s.pending.value.isEmpty())
        assertNull(s.pendingFor("job-1"))
        assertEquals(listOf("job-1"), notifier.cancelled)
    }

    @Test fun declinedResolves() = runTest {
        val s = store()
        val d = async { s.request("job-2", "Accounts", "Count employees by region", "h2") }
        runCurrent()
        assertTrue(s.resolve("job-2", ApprovalResult.Declined))
        assertEquals(ApprovalResult.Declined, d.await())
        assertTrue(s.pending.value.isEmpty())
        assertEquals(listOf("job-2"), notifier.cancelled)
    }

    @Test fun timesOutWithShortTimeout() = runTest {
        val s = store(timeoutMs = 250)
        val d = async { s.request("job-3", "Sales", "desc", "h3") }
        runCurrent()
        assertEquals(now + 250, s.pending.value.single().deadlineAt)
        assertEquals(ApprovalResult.TimedOut, d.await())   // virtual time: no real wait
        assertTrue(s.pending.value.isEmpty())
        assertEquals(listOf("job-3"), notifier.cancelled)
        assertFalse(s.resolve("job-3", ApprovalResult.Declined))   // nothing left to resolve
    }

    @Test fun debugAutoApproveSignsCanonicalWithNodeKey() = runTest {
        val node = FakeSigner()
        val s = store(signer = node, auto = { true })
        val r = s.request("job-4", "Sales", "desc", "h4")
        assertTrue(r is ApprovalResult.Approved)
        r as ApprovalResult.Approved
        assertEquals(ConsentResBody.SIGNED_BY_NODE, r.signedBy)
        assertTrue(Verify.verify(node.publicKeyB64, Canonical.consent("job-4", "h4").toByteArray(Charsets.UTF_8), r.sig))
        assertFalse(Verify.verify(node.publicKeyB64, Canonical.consent("job-4", "other").toByteArray(Charsets.UTF_8), r.sig))
        assertTrue(s.pending.value.isEmpty())
        assertTrue(notifier.notified.isEmpty())
    }

    @Test fun autoApproveWithoutNodeSignerFallsBackToOwnerPrompt() = runTest {
        val s = store(signer = null, auto = { true })
        val d = async { s.request("job-5", "Sales", "desc", "h5") }
        runCurrent()
        assertEquals(1, s.pending.value.size)
        s.resolve("job-5", ApprovalResult.Declined)
        assertEquals(ApprovalResult.Declined, d.await())
    }

    @Test fun canonicalBytesMatchMeshAndFakeGate() {
        val bytes = ApprovalStore.canonicalBytes("j", "abc")
        assertArrayEquals("j|abc|true".toByteArray(Charsets.UTF_8), bytes)
        assertArrayEquals(Canonical.consent("j", "abc").toByteArray(Charsets.UTF_8), bytes)
        assertArrayEquals(FakeApprovalGate.canonical("j", "abc").toByteArray(Charsets.UTF_8), bytes)
    }

    /** Mirrors the activity's CryptoObject path with a plain JCA key: initSign -> update(canonical) -> sign -> Verify. */
    @Test fun activitySigningPathVerifiesWithApprovalPubkey() {
        val approvalKey = FakeSigner.generate()
        val pubB64 = Base64.getEncoder().encodeToString(approvalKey.public.encoded)
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(approvalKey.private) }
        val sig = signature.run { update(ApprovalStore.canonicalBytes("job-7", "h7")); sign() }
        val body = ConsentResBody("job-7", "h7", true, "node-x", 1L, ConsentResBody.SIGNED_BY_APPROVAL, Base64.getEncoder().encodeToString(sig))
        val received = Base64.getDecoder().decode(body.approvalSig!!)
        assertTrue(Verify.verify(pubB64, Canonical.consent(body.jobId, body.intentHash).toByteArray(Charsets.UTF_8), received))
        assertFalse(Verify.verify(FakeSigner().publicKeyB64, ApprovalStore.canonicalBytes("job-7", "h7"), received))
    }

    @Test fun cancelAllDeclinesEveryWaiter() = runTest {
        val s = store()
        val a = async { s.request("job-a", "Sales", "a", "ha") }
        val b = async { s.request("job-b", "Accounts", "b", "hb") }
        runCurrent()
        assertEquals(listOf("job-a", "job-b"), s.pending.value.map { it.jobId })
        s.cancelAll()
        assertEquals(ApprovalResult.Declined, a.await())
        assertEquals(ApprovalResult.Declined, b.await())
        assertTrue(s.pending.value.isEmpty())
        assertEquals(setOf("job-a", "job-b"), notifier.cancelled.toSet())
    }

    @Test fun repeatedJobIdSupersedesTheEarlierWait() = runTest {
        val s = store()
        val first = async { s.request("job-r", "Sales", "first", "h") }
        runCurrent()
        val second = async { s.request("job-r", "Sales", "second", "h") }
        runCurrent()
        assertEquals(ApprovalResult.Declined, first.await())
        assertEquals(listOf("second"), s.pending.value.map { it.description })
        s.resolve("job-r", ApprovalResult.Approved(byteArrayOf(9), "approval"))
        assertTrue(second.await() is ApprovalResult.Approved)
        assertTrue(s.pending.value.isEmpty())
    }

    @Test fun publicKeyIsNullWithoutKeys() {
        assertNull(store().publicKeyB64)
        assertFalse(store().resolve("nothing", ApprovalResult.Declined))
    }
}
