package com.hivecore.trust

import com.hivecore.mesh.PlanConsent
import com.hivecore.mesh.PlanConsentScreen
import com.hivecore.mesh.PlanPending
import com.hivecore.core.ApprovalGate
import com.hivecore.core.ApprovalResult
import com.hivecore.core.PendingApproval
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.mesh.Canonical
import com.hivecore.mesh.ConsentResBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** What [ApprovalStore] tells about a new or finished request; [ApprovalNotifier] is the Android implementation. */
interface ApprovalNotifying {
    fun notify(pending: PendingApproval)
    fun cancel(jobId: String)
}

/**
 * Debug-only switch behind the `APPROVE_AUTO` adb hook (plan 16.3). The hook flips [autoApprove]; `Graph` wires it
 * into [ApprovalStore] as `debugAutoApprove = { BuildConfig.DEBUG && ApprovalDebug.autoApprove }` so a release build
 * can never auto-approve, whatever this flag says. Verifiers accept `signedBy:"node"` only in debug builds as well.
 */
object ApprovalDebug {
    @Volatile var autoApprove: Boolean = false
}

/**
 * The owner's consent gate (plan 12). [request] publishes a [PendingApproval] on [pending], notifies the owner and
 * suspends until [resolve] or the timeout; the Ask screen shows the pending cards as the fallback when the
 * notification does not surface. Pure Kotlin (no Android imports) so it unit-tests on the JVM.
 *
 * @param keys             the biometric key; null = no approval key (HELLO caps omit "approval")
 * @param nodeSigner       the node key, used only by the debug auto-approve path (`signedBy:"node"`)
 * @param notifier         posts / cancels the full-screen notification
 * @param timeoutMs        how long a request waits for the owner (plan: 60 s; the asker extends its deadline to 65 s)
 * @param debugAutoApprove when true the request resolves at once with a node-signed approval; debug builds only
 * @param clock            wall clock for `deadlineAt` (injectable for tests)
 */
class ApprovalStore(
    private val keys: ApprovalKeys? = null,
    private val nodeSigner: Signer? = null,
    private val notifier: ApprovalNotifying? = null,
    private val timeoutMs: Long = 60_000L,
    private val debugAutoApprove: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis
) : ApprovalGate {
    private val lock = Any()
    private val waiters = HashMap<String, CompletableDeferred<ApprovalResult>>()
    private val plans = HashMap<String, PlanPending>()
    private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())

    /** Requests waiting for the owner, oldest first. */
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    /** Read live (not cached) so a re-created key after a fingerprint change shows up in the next HELLO. */
    override val publicKeyB64: String? get() = keys?.publicKeyB64()

    fun pendingFor(jobId: String): PendingApproval? = _pending.value.firstOrNull { it.jobId == jobId }

    override suspend fun request(jobId: String, requester: String, description: String, intentHash: String): ApprovalResult {
        if (debugAutoApprove()) {
            val s = nodeSigner
            if (s != null) {
                SLog.w(TAG, "DEBUG auto-approve for job ${jobId.take(8)} from $requester (signedBy=node)")
                return ApprovalResult.Approved(s.sign(canonicalBytes(jobId, intentHash)), ConsentResBody.SIGNED_BY_NODE)
            }
            SLog.w(TAG, "auto-approve requested but no node signer; falling back to the owner prompt")
        }
        return await(PendingApproval(jobId, requester, description, intentHash, clock() + timeoutMs), plan = null)
    }

    /**
     * Swarm plan §7: one screen and one fingerprint for every figure a plan asks of this phone. The pending item is
     * keyed by [planId] (its `intentHash` field carries [planHash]); [planFor] gives the activity the figures and
     * [signingBytes] the exact bytes to sign: `Canonical.planConsent(planId, planHash, intentHashes)`.
     */
    override suspend fun requestPlan(planId: String, requester: String, description: String, planHash: String,
                                     intentHashes: List<String>): ApprovalResult {
        val plan = PlanPending(planId, planHash, intentHashes, PlanConsentScreen.parse(description))
        if (debugAutoApprove()) {
            val s = nodeSigner
            if (s != null) {
                SLog.w(TAG, "DEBUG auto-approve for plan ${planId.take(8)} from $requester (signedBy=node)")
                return ApprovalResult.Approved(s.sign(plan.canonicalBytes()), ConsentResBody.SIGNED_BY_NODE)
            }
            SLog.w(TAG, "auto-approve requested but no node signer; falling back to the owner prompt")
        }
        return await(PendingApproval(planId, requester, description, planHash, clock() + timeoutMs), plan)
    }

    /** The plan behind pending item [jobId], or null when it is a single-figure request. */
    fun planFor(jobId: String): PlanPending? = synchronized(lock) { plans[jobId] }

    /**
     * The exact bytes the approval key must sign for pending item [jobId]: the plan form for a plan, else
     * `jobId|intentHash|true`. Null when nothing is pending for that id.
     */
    fun signingBytes(jobId: String): ByteArray? {
        synchronized(lock) { plans[jobId] }?.let { return it.canonicalBytes() }
        return pendingFor(jobId)?.let { canonicalBytes(it.jobId, it.intentHash) }
    }

    private suspend fun await(item: PendingApproval, plan: PlanPending?): ApprovalResult {
        val jobId = item.jobId
        val deferred = CompletableDeferred<ApprovalResult>()
        synchronized(lock) {
            waiters[jobId]?.complete(ApprovalResult.Declined)   // a repeated jobId supersedes the earlier wait
            waiters[jobId] = deferred
            if (plan != null) plans[jobId] = plan else plans.remove(jobId)
            _pending.value = _pending.value.filter { it.jobId != jobId } + item
        }
        SLog.d(TAG, "pending approval job=${jobId.take(8)} from=${item.requester}${if (plan != null) " (plan, ${plan.intentHashes.size} figures)" else ""}: " +
            "${item.description} (timeout $timeoutMs ms)")
        try { notifier?.notify(item) } catch (e: Exception) { SLog.e(TAG, "notify failed", e) }
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ApprovalResult.TimedOut
        } finally {
            // Only tear down when this is still the live wait for the job (a repeated jobId may have replaced it).
            val mine = synchronized(lock) {
                if (waiters[jobId] === deferred) {
                    waiters.remove(jobId)
                    plans.remove(jobId)
                    _pending.value = _pending.value.filter { it.jobId != jobId }
                    true
                } else false
            }
            if (mine) try { notifier?.cancel(jobId) } catch (e: Exception) { SLog.e(TAG, "cancel failed", e) }
        }
        SLog.d(TAG, "approval job=${jobId.take(8)} -> ${describe(result)}")
        return result
    }

    /** Completes a waiting [request]. Returns false when nothing was waiting for that job (already resolved or timed out). */
    fun resolve(jobId: String, result: ApprovalResult): Boolean {
        val d = synchronized(lock) { waiters[jobId] } ?: run {
            SLog.w(TAG, "resolve(${jobId.take(8)}): nothing pending"); return false
        }
        return d.complete(result)
    }

    /** Resolves every waiting request with [result] (default: declined), e.g. when the node service stops. */
    fun cancelAll(result: ApprovalResult = ApprovalResult.Declined) {
        val all = synchronized(lock) { waiters.values.toList() }
        SLog.d(TAG, "cancelAll: ${all.size} pending -> ${describe(result)}")
        all.forEach { it.complete(result) }
    }

    private fun describe(r: ApprovalResult) = when (r) {
        is ApprovalResult.Approved -> "approved(signedBy=${r.signedBy})"
        ApprovalResult.Declined -> "declined"
        ApprovalResult.TimedOut -> "timed out"
    }

    companion object {
        private const val TAG = "ApprovalStore"

        /** The exact bytes the approval key signs: `jobId|intentHash|true` in UTF-8 (plan 10 CONSENT_RES). */
        fun canonicalBytes(jobId: String, intentHash: String): ByteArray =
            Canonical.consent(jobId, intentHash).toByteArray(Charsets.UTF_8)
    }
}
