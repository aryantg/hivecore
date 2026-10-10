package com.hivecore.app.approval

import com.hivecore.core.ApprovalResult
import com.hivecore.core.PendingApproval
import com.hivecore.core.SLog
import com.hivecore.mesh.ConsentResBody
import com.hivecore.trust.ApprovalStore

/**
 * What the approval screen shows for one pending item (spec 7.6, 17): the requester, the header line, the numbered
 * figures of a plan (one fingerprint approves them all) or the single figure's description, the closing notes and
 * the seconds left. Pure Kotlin so the JVM tests drive it.
 */
data class ApprovalView(
    val jobId: String,
    val requester: String,
    val header: String,
    val figures: List<String>,
    val notes: List<String>,
    val planHash: String?,
    val secondsLeft: Long,
) {
    val isPlan: Boolean get() = planHash != null
    val expired: Boolean get() = secondsLeft <= 0
}

/**
 * The approval screen's logic over the [ApprovalStore], kept out of the activity so it unit-tests on the JVM: which
 * pending item to show, what to render for it, and how a biometric signature or a decline resolves it. The activity
 * owns only the BiometricPrompt and the Compose tree.
 *
 * [signer] produces the approval key's signature over the exact bytes the store names ([ApprovalStore.signingBytes]);
 * on a phone it is the `Signature` the `BiometricPrompt.CryptoObject` unlocked.
 */
class PlanApprovalController(
    private val store: ApprovalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The item to show: [jobId]'s when given and still pending, else the oldest pending one; null when nothing is pending. */
    fun current(jobId: String?): PendingApproval? {
        val pending = store.pending.value
        return if (jobId == null) pending.firstOrNull() else pending.firstOrNull { it.jobId == jobId }
    }

    fun view(item: PendingApproval): ApprovalView {
        val plan = store.planFor(item.jobId)
        val left = ((item.deadlineAt - clock()) / 1000L).coerceAtLeast(0L)
        return if (plan == null) ApprovalView(item.jobId, item.requester, "${item.requester} asks to use confidential data on this phone:",
            listOf(item.description), emptyList(), null, left)
        else ApprovalView(item.jobId, item.requester, plan.screen.header, plan.screen.figures, plan.screen.notes, plan.planHash, left)
    }

    /**
     * Approves [item] with [sign], which must return the approval key's signature over the bytes given. Returns the
     * failure the screen shows, or null when the store took the approval. A request that already ended is reported.
     */
    fun approve(item: PendingApproval, sign: (ByteArray) -> ByteArray): String? {
        val bytes = store.signingBytes(item.jobId) ?: return "That request already ended"
        val sig = try { sign(bytes) } catch (e: Exception) {
            SLog.e(TAG, "signing failed", e); return "Signing failed: ${e.message ?: e.javaClass.simpleName}"
        }
        val ok = store.resolve(item.jobId, ApprovalResult.Approved(sig, ConsentResBody.SIGNED_BY_APPROVAL))
        SLog.d(TAG, "approved ${item.jobId.take(8)} (${sig.size} B) resolved=$ok")
        return if (ok) null else "That request already ended"
    }

    /** Declines [item]; false when nothing was pending for it any more. */
    fun decline(item: PendingApproval, why: String): Boolean {
        val ok = store.resolve(item.jobId, ApprovalResult.Declined)
        SLog.d(TAG, "declined ${item.jobId.take(8)} ($why) resolved=$ok")
        return ok
    }

    private companion object { const val TAG = "PlanApproval" }
}
