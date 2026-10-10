package com.hivecore.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

// Plan 6 "core/Crypto.kt" — frozen after Wave 0. Pure JCA, so it runs unchanged on the JVM and on Android.

/** Signs with the node (or approval) key: SHA256withECDSA over the exact bytes, DER-encoded signature. */
interface Signer { val publicKeyB64: String; fun sign(bytes: ByteArray): ByteArray }          // SHA256withECDSA, DER

/** Verifies a DER ECDSA signature against a base64 X.509 (SubjectPublicKeyInfo) EC public key. Never throws. */
object Verify {
    fun verify(pubKeyB64: String, bytes: ByteArray, sig: ByteArray): Boolean = try {
        val der = Base64.getDecoder().decode(pubKeyB64)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(bytes)
            verify(sig)
        }
    } catch (_: Exception) {
        false
    }
}

object Hashing {
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))
}
