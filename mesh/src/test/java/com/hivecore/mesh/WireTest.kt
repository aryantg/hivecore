package com.hivecore.mesh

import com.hivecore.core.AnswerChip
import com.hivecore.core.NodeOutcome
import com.hivecore.core.fakes.FakeSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireTest {
    private val signer = FakeSigner()
    private val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
    private val body = MeshJson.encode(JobBody("job-1", "nonce-1", 1_700_000_000_000L, nodeId, "HR", "How many employees in each region"))

    @Test fun signVerifyRoundTrip() {
        val bytes = Wire.encode(MsgType.JOB, nodeId, body, signer)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("{\"v\":1,\"type\":\"JOB\",\"from\":\"$nodeId\",\"body\":\""))
        val env = Wire.decode(bytes)
        assertEquals(1, env.v)
        assertEquals("JOB", env.type)
        assertEquals(MsgType.JOB, env.msgType)
        assertEquals(nodeId, env.from)
        assertEquals(body, env.body)
        assertTrue(Wire.verify(env, signer.publicKeyB64))
        assertFalse(Wire.verify(env, FakeSigner().publicKeyB64))
        val job = MeshJson.decode<JobBody>(env.body)
        assertEquals("job-1", job.jobId)
    }

    @Test fun tamperedBodyFails() {
        val bytes = Wire.encode(MsgType.JOB, nodeId, body, signer)
        val tampered = String(bytes, Charsets.UTF_8).replace("How many", "How few").toByteArray()
        val env = Wire.decode(tampered)
        assertEquals(body.replace("How many", "How few"), env.body)
        assertFalse(Wire.verify(env, signer.publicKeyB64))
    }

    @Test fun reserialisedEnvelopeStillVerifies() {
        val bytes = Wire.encode(MsgType.RESULT, nodeId, body, signer)
        val original = Wire.decode(bytes)
        // A relay (or a different JSON library) re-emits the envelope with different whitespace and key order.
        val sigB64 = java.util.Base64.getEncoder().encodeToString(original.sig)
        val escapedBody = kotlinx.serialization.json.JsonPrimitive(original.body).toString()
        val loose = """
            {
              "sig" : "$sigB64",
              "body" : $escapedBody,
              "from" : "$nodeId",
              "type" : "RESULT",
              "v" : 1,
              "extra" : {"ignored": true}
            }
        """.trimIndent().toByteArray()
        val env = Wire.decode(loose)
        assertEquals(original.body, env.body)
        assertTrue(Wire.verify(env, signer.publicKeyB64))
    }

    @Test fun bodyWithUnicodeAndEscapesSurvives() {
        val b = MeshJson.encode(JobBody("j", "n", 1L, nodeId, "Ventas", "¿Cuántos \"empleados\" en \\ cada región? — tab\t"))
        val env = Wire.decode(Wire.encode(MsgType.JOB, nodeId, b, signer))
        assertEquals(b, env.body)
        assertTrue(Wire.verify(env, signer.publicKeyB64))
    }

    @Test fun malformedEnvelopesThrow() {
        for (bad in listOf("", "not json", "[1,2]", "{\"v\":2,\"type\":\"JOB\",\"from\":\"a\",\"body\":\"{}\",\"sig\":\"AA==\"}",
            "{\"v\":1,\"type\":\"JOB\",\"from\":\"a\",\"body\":{},\"sig\":\"AA==\"}",
            "{\"v\":1,\"type\":\"JOB\",\"from\":\"a\",\"body\":\"{}\"}",
            "{\"v\":1,\"type\":\"JOB\",\"from\":\"a\",\"body\":\"{}\",\"sig\":\"***\"}")) {
            try { Wire.decode(bad.toByteArray()); fail("expected failure for: $bad") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unknownTypeIsDecodedButHasNoMsgType() {
        val env = Wire.decode(Wire.encode("GOSSIP", nodeId, "{}", signer))
        assertEquals("GOSSIP", env.type)
        assertNull(env.msgType)
    }

    @Test fun nodeIdIsSha256OfSpki() {
        val der = signer.keyPair.public.encoded
        assertEquals(com.hivecore.core.Hashing.sha256Hex(der), nodeId)
        assertEquals(64, nodeId.length)
        assertNull(Wire.nodeIdOf("***not base64***"))
    }

    @Test fun pairingDigitsAreSymmetricSixDigits() {
        val a = FakeSigner().publicKeyB64; val b = FakeSigner().publicKeyB64
        val d1 = Wire.pairingDigits(a, b); val d2 = Wire.pairingDigits(b, a)
        assertEquals(d1, d2)
        assertEquals(6, d1.length)
        assertTrue(d1.all { it in '0'..'9' })
        assertNotNull(Wire.pairingDigits(a, a))
    }

    @Test fun canonicalConsentString() {
        assertEquals("job-7|abc123|true", Canonical.consent("job-7", "abc123"))
    }

    @Test fun resultBodyHashCoversOutcomes() {
        val outcomes = listOf<NodeOutcome>(NodeOutcome.Refused("filters on name are not allowed on confidential data"))
        val r = ResultBody.of("j", nodeId, outcomes, 61, ResultTimings(12, 3), "npu")
        assertTrue(r.hashMatches())
        val json = MeshJson.encode(r)
        assertTrue(json.contains("\"type\":\"refused\""))
        val back = MeshJson.decode<ResultBody>(json)
        assertEquals(r, back)
        assertTrue(back.hashMatches())
        assertFalse(back.copy(outcomes = listOf(NodeOutcome.Declined(true))).hashMatches())
        // Every outcome variant round-trips through the polymorphic serializer.
        val all = listOf(
            NodeOutcome.Tables(emptyList(), AnswerChip.TEMPLATE, "ih"), NodeOutcome.Doc(com.hivecore.core.DocAnswer("t", "d", "slide 3", 2), AnswerChip.LLM),
            NodeOutcome.Refused("r"), NodeOutcome.Declined(false), NodeOutcome.NotRelevant(listOf("sales")), NodeOutcome.Timeout)
        assertEquals(all, MeshJson.decode<ResultBody>(MeshJson.encode(ResultBody.of("j", nodeId, all, 0))).outcomes)
    }

    @Test fun helloOmitsNullApprovalKeyAndIgnoresUnknownFields() {
        val h = HelloBody(nodeId, "HR", signer.publicKeyB64, null, listOf("employees"), listOf("sql", "npu"), 5L)
        val json = MeshJson.encode(h)
        assertFalse(json.contains("approvalPubkey"))
        val back = MeshJson.decode<HelloBody>(json.dropLast(1) + ",\"future\":42}")
        assertEquals(h, back)
        val consent = MeshJson.decode<ConsentResBody>("""{"jobId":"j","intentHash":"h","approved":false,"node":"n","ts":1}""")
        assertEquals("approval", consent.signedBy)
        assertNull(consent.approvalSig)
    }
}
