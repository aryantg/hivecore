package com.hivecore.core.fakes

import com.hivecore.core.ApprovalGate
import com.hivecore.core.ApprovalResult
import com.hivecore.core.Signer
import kotlinx.coroutines.delay

/**
 * Auto-resolves every request. [mode] APPROVE signs the canonical `jobId|intentHash|true` with [signer]
 * (signedBy "approval"); DECLINE / TIMEOUT return those results. A null [signer] means "no biometric enrolled":
 * [publicKeyB64] is null and requests are declined.
 */
class FakeApprovalGate(private val signer: Signer? = FakeSigner(), var mode: Mode = Mode.APPROVE, var delayMs: Long = 0) : ApprovalGate {
    enum class Mode { APPROVE, DECLINE, TIMEOUT }
    data class Request(val jobId: String, val requester: String, val description: String, val intentHash: String)

    val requests = mutableListOf<Request>()
    override val publicKeyB64: String? get() = signer?.publicKeyB64

    override suspend fun request(jobId: String, requester: String, description: String, intentHash: String): ApprovalResult {
        requests.add(Request(jobId, requester, description, intentHash))
        if (delayMs > 0) delay(delayMs)
        val s = signer ?: return ApprovalResult.Declined
        return when (mode) {
            Mode.APPROVE -> ApprovalResult.Approved(s.sign(canonical(jobId, intentHash).toByteArray(Charsets.UTF_8)), "approval")
            Mode.DECLINE -> ApprovalResult.Declined
            Mode.TIMEOUT -> ApprovalResult.TimedOut
        }
    }

    companion object {
        /** The bytes the biometric key signs (plan 10 CONSENT_RES). */
        fun canonical(jobId: String, intentHash: String): String = "$jobId|$intentHash|true"
    }
}
