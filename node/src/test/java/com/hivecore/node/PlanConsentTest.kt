package com.hivecore.node

import com.hivecore.mesh.PlanConsentVerdict
import com.hivecore.mesh.PlanConsent
import com.hivecore.core.Compiled
import com.hivecore.core.Filter
import com.hivecore.core.Intent
import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.node.data.Fixtures
import com.hivecore.node.data.RuleIntentCompiler
import com.hivecore.mesh.ConsentResBody
import com.hivecore.mesh.PlanConsentReqBody
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PlanConsentTest {
    companion object {
        lateinit var queries: List<Compiled.Query>
        @BeforeClass @JvmStatic fun seed() {
            val hr = Fixtures.store(NodeRole.HR).schema()
            val c = RuleIntentCompiler()
            queries = listOf(
                Intent("employees", "avg", "commission", filters = listOf(Filter("region", "eq", JsonPrimitive("West")))),
                Intent("employees", "avg", "commission", groupBy = listOf("region")),
                Intent("employees", "count", filters = listOf(Filter("region", "eq", JsonPrimitive("East")))),
            ).map { c.compile(it, hr) as Compiled.Query }
        }
    }

    private val owner = "node-hr"
    private val approvalKey = FakeSigner()
    private val nodeKey = FakeSigner()
    private val req: PlanConsentReqBody get() = PlanConsent.request("plan-1", "phash", owner, queries)

    private fun signed(signer: FakeSigner = approvalKey, signedBy: String = ConsentResBody.SIGNED_BY_APPROVAL) =
        PlanConsent.approved(req, signer.sign(PlanConsent.canonicalBytes("plan-1", "phash", req.intentHashes)), signedBy, 1L)

    private fun verify(res: com.hivecore.mesh.PlanConsentResBody, expected: PlanConsentReqBody? = req, debug: Boolean = false, from: String = owner) =
        PlanConsent.verify(res, from, expected, nodeKey.publicKeyB64, approvalKey.publicKeyB64, debug)

    @Test fun canonicalBytesSortHashes() {
        val hs = req.intentHashes
        assertEquals(3, hs.distinct().size)
        val expected = "plan-1|phash|" + hs.sorted().joinToString(",") + "|true"
        assertEquals(expected, String(PlanConsent.canonicalBytes("plan-1", "phash", hs), Charsets.UTF_8))
        assertEquals(expected, String(PlanConsent.canonicalBytes("plan-1", "phash", hs.reversed()), Charsets.UTF_8))
    }

    @Test fun descriptionListsEveryFigure() {
        val d = PlanConsent.description("Sales", queries)
        assertTrue(d, d.startsWith("Sales' phone asks for 3 figures from your employees table:\n1. Average of commission, where region = West"))
        assertTrue(d, d.contains("\n2. Average of commission by region"))
        assertTrue(d, d.contains("\n3. Count, where region = East"))
        assertTrue(d, d.endsWith("One fingerprint approves all 3 figures; nothing else."))
        assertTrue(PlanConsent.description("HR", queries.take(1)).startsWith("HR's phone asks for 1 figure from your employees table:"))
    }

    @Test fun approvalSignatureVerifies() {
        assertEquals(PlanConsentVerdict.Approved, verify(signed()))
        // the hash order in the response does not matter: the canonical form sorts
        assertEquals(PlanConsentVerdict.Approved, verify(signed().copy(intentHashes = req.intentHashes.reversed())))
    }

    @Test fun tamperedIntentListFails() {
        val res = signed()
        assertTrue(verify(res.copy(intentHashes = res.intentHashes.drop(1))) is PlanConsentVerdict.Invalid)
        // the request itself carried a different list (a figure swapped after approval)
        val other = req.copy(intentHashes = req.intentHashes.dropLast(1) + "f".repeat(64))
        assertTrue(verify(res, expected = other) is PlanConsentVerdict.Invalid)
        // a signature over a different list fails even if the request matches the new list
        val swapped = res.copy(intentHashes = other.intentHashes)
        assertEquals(PlanConsentVerdict.Invalid("approvalSig does not verify"), verify(swapped, expected = other))
        assertTrue(verify(res.copy(planHash = "other")) is PlanConsentVerdict.Invalid)
    }

    @Test fun envelopeAndRequestRules() {
        assertEquals(PlanConsentVerdict.Invalid("node != from"), verify(signed(), from = "someone-else"))
        assertTrue(verify(signed(), expected = null) is PlanConsentVerdict.Invalid)
        assertEquals(PlanConsentVerdict.Invalid("approved without approvalSig"), verify(signed().copy(approvalSig = null)))
        assertEquals(PlanConsentVerdict.Invalid("approvalSig does not verify"), verify(signed(signer = FakeSigner())))
        assertTrue(PlanConsent.verify(signed(), owner, req, nodeKey.publicKeyB64, null, false) is PlanConsentVerdict.Invalid)
        assertTrue(verify(signed().copy(signedBy = "other")) is PlanConsentVerdict.Invalid)
    }

    @Test fun nodeSignedOnlyInDebug() {
        val res = PlanConsent.debugNodeApprove(nodeKey, req, 1L)
        assertEquals(ConsentResBody.SIGNED_BY_NODE, res.signedBy)
        assertEquals(PlanConsentVerdict.Invalid("signedBy:node is debug-only"), verify(res, debug = false))
        assertEquals(PlanConsentVerdict.Approved, verify(res, debug = true))
        // a node-signed claim made with the approval key does not verify against the node key
        assertTrue(verify(signed(signedBy = ConsentResBody.SIGNED_BY_NODE), debug = true) is PlanConsentVerdict.Invalid)
    }

    @Test fun declineCarriesTheRefusal() {
        val r = PlanConsent.declined(req, 1L, "these figures together would reveal fewer than 5 people")
        assertEquals(PlanConsentVerdict.Declined("these figures together would reveal fewer than 5 people"), verify(r, expected = null))
    }
}
