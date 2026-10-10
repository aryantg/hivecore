package com.hivecore.mesh

import com.hivecore.core.Canon
import com.hivecore.core.Verify
import com.hivecore.core.fakes.FakeRelay
import com.hivecore.core.fakes.FakeSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RelayFramesTest {
    // The same vector lives in relayd/relay/canon_test.go: both canonical writers must agree byte for byte.
    private val vectorIn = """{"version":5.0,"groupId":"g\"1","members":[{"nodeId":"n","addedTs":1700000000000,"name":"tab\tname","pubkey":"k"}],"admin":"n","revoked":[],"adminSig":"x","ratio":1.50,"neg":-0,"exp":1e3,"small":0.000100,"ctl":"a\u0001b","uni":"é"}"""
    private val vectorOut = """{"admin":"n","ctl":"a\u0001b","exp":1000,"groupId":"g\"1","members":[{"addedTs":1700000000000,"name":"tab\tname","nodeId":"n","pubkey":"k"}],"neg":0,"ratio":1.5,"revoked":[],"small":0.0001,"uni":"é","version":5}"""

    @Test fun canonicalAgreesWithTheRelayVector() {
        val obj = Json.parseToJsonElement(vectorIn) as JsonObject
        assertEquals(vectorOut, Canon.canonical(JsonObject(obj.filterKeys { it != "adminSig" })))
    }

    @Test fun frameCodecRoundTripsAndOmitsNulls() {
        val f = RelayFrames.msg("b", "a", "AAEC", id = 7, ttlS = 30)
        val text = RelayFrames.encode(f)
        assertEquals("""{"type":"msg","to":"b","from":"a","cipher":"AAEC","ttlS":30,"id":7}""", text)
        assertEquals(f, RelayFrames.decode(text))
        assertNull(RelayFrames.decode("not json"))
        // Frames from the Go relay carry fields this side does not know yet.
        val ok = RelayFrames.decode("""{"type":"auth_ok","serverTs":5,"members":[{"nodeId":"x","online":true}],"version":2,"future":1}""")!!
        assertEquals(listOf(RelayMember("x", true)), ok.members)
        assertEquals(2L, ok.version)
    }

    @Test fun authBytesAreDomainZeroNonce() {
        val nonce = ByteArray(32) { it.toByte() }
        val b = RelayProtocol.authBytes(Base64.getEncoder().encodeToString(nonce))
        assertArrayEquals("relayd-auth-v1".toByteArray() + byteArrayOf(0) + nonce, b)
        assertArrayEquals(FakeRelay.authBytes(nonce), b)
        assertEquals(FakeRelay.AUTH_DOMAIN, RelayProtocol.AUTH_DOMAIN)
        assertEquals(FakeRelay.HOLD_MS, RelayProtocol.HOLD_MS)
        assertEquals(FakeRelay.INVITE_TTL_MS, RelayProtocol.INVITE_TTL_MS)
    }

    @Test fun rosterSigningBytesExcludeAdminSigAndVerify() {
        val admin = FakeSigner()
        val id = Wire.nodeIdOf(admin.publicKeyB64)!!
        val body = RosterBody("g1", 3, id, listOf(RosterMember(id, admin.publicKeyB64, "admin", 1_700_000_000_000)))
        val bytes = RelayFrames.rosterSigningBytes(body)
        assertTrue(String(bytes).startsWith("""{"admin":"$id","groupId":"g1","members":[{"addedTs":1700000000000,"""))
        assertTrue("adminSig" !in String(bytes))
        val signed = body.copy(adminSig = Base64.getEncoder().encodeToString(admin.sign(bytes)))
        assertArrayEquals(bytes, RelayFrames.rosterSigningBytes(signed))
        assertTrue(Verify.verify(admin.publicKeyB64, bytes, Base64.getDecoder().decode(signed.adminSig)))
        val frame = RelayFrames.roster(signed)
        assertEquals(signed.adminSig, frame.adminSig)
        assertEquals("g1", (frame.roster as JsonObject)["groupId"].toString().trim('"'))
    }

    @Test fun tokenHashIsSha256Hex() {
        assertEquals(64, RelayProtocol.tokenHash("tok-123").length)
        assertEquals(RelayProtocol.tokenHash("tok-123"), RelayFrames.invite("tok-123").tokenHash)
    }
}
