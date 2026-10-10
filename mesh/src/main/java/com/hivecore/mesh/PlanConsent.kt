package com.hivecore.mesh

import com.hivecore.core.Compiled
import com.hivecore.core.Hashing
import com.hivecore.core.Signer
import com.hivecore.core.Verify
import com.hivecore.mesh.Canonical
import com.hivecore.mesh.ConsentResBody
import com.hivecore.mesh.PinnedPeer
import com.hivecore.mesh.PlanConsentReqBody
import com.hivecore.mesh.PlanConsentResBody
import java.util.Base64

/** Outcome of checking a PLAN_CONSENT_RES on the commander. */
sealed interface PlanConsentVerdict {
    /** Approved with a valid signature over [Canonical.planConsent]. */
    data object Approved : PlanConsentVerdict
    /** The owner declined, or refused the plan outright ([reason] = the differencing guard's text, if any). */
    data class Declined(val reason: String?) : PlanConsentVerdict
    /** The message must be dropped: [why] says which rule failed. */
    data class Invalid(val why: String) : PlanConsentVerdict
}

/**
 * The one approval screen of a plan, split out of [PlanConsent.description]: the header ("Sales' phone asks for 3
 * figures from your employees table:"), one entry per numbered figure, and the closing notes.
 */
data class PlanConsentScreen(val header: String, val figures: List<String>, val notes: List<String>) {
    companion object {
        private val NUMBERED = Regex("""^\d+\.\s+(.*)$""")

        fun parse(description: String): PlanConsentScreen {
            val lines = description.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val header = lines.firstOrNull().orEmpty()
            val figures = ArrayList<String>()
            val notes = ArrayList<String>()
            for (l in lines.drop(1)) {
                val m = NUMBERED.matchEntire(l)
                if (m != null) figures += m.groupValues[1] else notes += l
            }
            return PlanConsentScreen(header, figures, notes)
        }
    }
}

/** A plan consent waiting for the owner ([ApprovalStore.requestPlan]). */
data class PlanPending(val planId: String, val planHash: String, val intentHashes: List<String>, val screen: PlanConsentScreen) {
    fun canonicalBytes(): ByteArray = PlanConsent.canonicalBytes(planId, planHash, intentHashes)
}

/**
 * Plan-scoped consent helpers (swarm plan §7, §8.1): one approval screen and one fingerprint for every figure a plan
 * asks of this owner. The approval key signs [Canonical.planConsent] = `planId|planHash|sorted(intentHashes)|true`.
 * Verification mirrors MeshRouter's CONSENT_RES rules: sender = node, approval only for a matching request, signature
 * required, `signedBy:"approval"` checked against the pinned approval key, `signedBy:"node"` only in debug builds.
 */
object PlanConsent {

    /** The hash of one figure: sha256 of its canonical signature (the single-intent form of NodeAnswerer's hash). */
    fun intentHash(q: Compiled.Query): String = Hashing.sha256Hex(q.signature)

    fun intentHashes(queries: List<Compiled.Query>): List<String> = queries.map { intentHash(it) }

    /** The exact bytes the approval key signs for the plan. */
    fun canonicalBytes(planId: String, planHash: String, intentHashes: List<String>): ByteArray =
        Canonical.planConsent(planId, planHash, intentHashes).toByteArray(Charsets.UTF_8)

    /**
     * The one-screen text: "Sales' phone asks for 3 figures from your employees table:" followed by one numbered line
     * per figure (the compiler's description), and the note that one fingerprint approves them all.
     */
    fun description(requesterName: String, queries: List<Compiled.Query>): String = buildString {
        val name = requesterName.trim()
        val owner = when {
            name.isEmpty() -> "A phone"
            name.endsWith("s") || name.endsWith("S") -> "$name' phone"
            else -> "$name's phone"
        }
        val tables = queries.map { it.intent.table }.distinct()
        val n = queries.size
        append(owner).append(" asks for ").append(n).append(if (n == 1) " figure" else " figures").append(" from your ")
        append(when (tables.size) {
            0 -> "data"
            1 -> "${tables[0]} table"
            else -> tables.dropLast(1).joinToString(", ") + " and " + tables.last() + " tables"
        })
        append(":")
        queries.forEachIndexed { i, q -> append("\n").append(i + 1).append(". ").append(q.description) }
        append("\n").append(if (n == 1) "Your fingerprint approves this figure only." else "One fingerprint approves all $n figures; nothing else.")
    }

    /** A request body for [queries] of plan [planId]/[planHash] from owner [node]. */
    fun request(planId: String, planHash: String, node: String, queries: List<Compiled.Query>): PlanConsentReqBody =
        PlanConsentReqBody(planId, planHash, node, intentHashes(queries))

    /** An approved response; [sig] from the approval key (or the node key, debug auto-approve). */
    fun approved(req: PlanConsentReqBody, sig: ByteArray, signedBy: String, ts: Long): PlanConsentResBody =
        PlanConsentResBody(req.planId, req.planHash, req.intentHashes, true, req.node, ts, signedBy, Base64.getEncoder().encodeToString(sig))

    /** A declined or refused response ([refusal] = the differencing guard's reason, null for a plain decline). */
    fun declined(req: PlanConsentReqBody, ts: Long, refusal: String? = null): PlanConsentResBody =
        PlanConsentResBody(req.planId, req.planHash, req.intentHashes, false, req.node, ts, refusal = refusal)

    /** Debug auto-approve: the node key signs the plan bytes (`signedBy:"node"`, accepted only by debug commanders). */
    fun debugNodeApprove(signer: Signer, req: PlanConsentReqBody, ts: Long): PlanConsentResBody =
        approved(req, signer.sign(canonicalBytes(req.planId, req.planHash, req.intentHashes)), ConsentResBody.SIGNED_BY_NODE, ts)

    fun verify(res: PlanConsentResBody, from: String, expected: PlanConsentReqBody?, peer: PinnedPeer, debugAllowNodeSigned: Boolean): PlanConsentVerdict =
        verify(res, from, expected, peer.pubkey, peer.approvalPubkey, debugAllowNodeSigned)

    /**
     * Checks [res] received from envelope sender [from] against the request this commander recorded ([expected]) and
     * the sender's pinned keys. Never throws.
     */
    fun verify(res: PlanConsentResBody, from: String, expected: PlanConsentReqBody?, nodePubkey: String, approvalPubkey: String?,
               debugAllowNodeSigned: Boolean): PlanConsentVerdict {
        if (res.node != from) return PlanConsentVerdict.Invalid("node != from")
        if (!res.approved) return PlanConsentVerdict.Declined(res.refusal)
        val sig = res.approvalSig?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return PlanConsentVerdict.Invalid("approved without approvalSig")
        if (expected == null) return PlanConsentVerdict.Invalid("approval without a PLAN_CONSENT_REQ for plan ${res.planId}")
        if (expected.node != res.node) return PlanConsentVerdict.Invalid("approval node differs from PLAN_CONSENT_REQ")
        if (expected.planId != res.planId) return PlanConsentVerdict.Invalid("approval planId differs from PLAN_CONSENT_REQ")
        if (expected.planHash != res.planHash) return PlanConsentVerdict.Invalid("approval planHash differs from PLAN_CONSENT_REQ")
        if (res.intentHashes.isEmpty()) return PlanConsentVerdict.Invalid("approval lists no intents")
        if (res.intentHashes.sorted() != expected.intentHashes.sorted())
            return PlanConsentVerdict.Invalid("approval intentHashes differ from PLAN_CONSENT_REQ")
        val key = when (res.signedBy) {
            ConsentResBody.SIGNED_BY_APPROVAL -> approvalPubkey ?: return PlanConsentVerdict.Invalid("approval signed but no approvalPubkey pinned")
            ConsentResBody.SIGNED_BY_NODE -> {
                if (!debugAllowNodeSigned) return PlanConsentVerdict.Invalid("signedBy:node is debug-only")
                nodePubkey
            }
            else -> return PlanConsentVerdict.Invalid("unknown signedBy '${res.signedBy}'")
        }
        if (!Verify.verify(key, canonicalBytes(res.planId, res.planHash, res.intentHashes), sig))
            return PlanConsentVerdict.Invalid("approvalSig does not verify")
        return PlanConsentVerdict.Approved
    }
}
