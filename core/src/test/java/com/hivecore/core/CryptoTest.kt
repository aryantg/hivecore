package com.hivecore.core

import com.hivecore.core.fakes.FakeSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test fun verifyRoundTripWithFakeSigner() {
        val signer = FakeSigner()
        val body = """{"jobId":"j1","nonce":"abc","question":"Average commission in the West"}""".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(body)
        assertTrue(Verify.verify(signer.publicKeyB64, body, sig))
    }

    @Test fun tamperedBytesFail() {
        val signer = FakeSigner()
        val body = "hello".toByteArray()
        val sig = signer.sign(body)
        assertFalse(Verify.verify(signer.publicKeyB64, "hellp".toByteArray(), sig))
        val broken = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertFalse(Verify.verify(signer.publicKeyB64, body, broken))
    }

    @Test fun wrongKeyFailsAndGarbageDoesNotThrow() {
        val a = FakeSigner(); val b = FakeSigner()
        val body = "x".toByteArray()
        assertFalse(Verify.verify(b.publicKeyB64, body, a.sign(body)))
        assertFalse(Verify.verify("not base64!!", body, a.sign(body)))
        assertFalse(Verify.verify(a.publicKeyB64, body, byteArrayOf(1, 2, 3)))
    }

    @Test fun signaturesAreDerAndKeysDiffer() {
        val a = FakeSigner(); val b = FakeSigner()
        assertNotEquals(a.publicKeyB64, b.publicKeyB64)
        val sig = a.sign("x".toByteArray())
        assertEquals(0x30, sig[0].toInt() and 0xff)   // DER SEQUENCE
        assertTrue(sig.size in 68..72)
    }

    @Test fun sha256KnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(ByteArray(0)))
        assertEquals(Hashing.sha256Hex("abc"), Hashing.sha256Hex("abc".toByteArray(Charsets.UTF_8)))
        assertEquals(64, Hashing.sha256Hex("anything").length)
    }
}
