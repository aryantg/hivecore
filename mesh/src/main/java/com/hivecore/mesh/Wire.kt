package com.hivecore.mesh

import com.hivecore.core.Hashing
import com.hivecore.core.Signer
import com.hivecore.core.Verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * One decoded envelope. `body` is the exact JSON string that was signed; `sig` is the DER ECDSA signature over
 * `body.toByteArray(UTF_8)`. `type` is kept as the raw string so unknown types can be logged before being dropped.
 */
class Envelope(val v: Int, val type: String, val from: String, val body: String, val sig: ByteArray) {
    val msgType: MsgType? get() = MsgType.entries.firstOrNull { it.name == type }
    override fun toString(): String = "Envelope(v=$v type=$type from=${from.take(8)} body=${body.length}B)"
}

/**
 * Plan 10 envelope: `{"v":1,"type":"JOB","from":"<nodeId>","body":"<json string>","sig":"<base64 DER>"}`.
 * The signature covers the body string's UTF-8 bytes. A verifier never re-serialises: it checks the bytes of the
 * string it received, so whitespace or key order in the outer envelope cannot break verification.
 */
object Wire {
    const val VERSION = 1
    private val parser = Json { ignoreUnknownKeys = true }

    fun encode(type: MsgType, from: String, bodyJson: String, signer: Signer): ByteArray =
        encode(type.name, from, bodyJson, signer)

    fun encode(type: String, from: String, bodyJson: String, signer: Signer): ByteArray {
        val sig = signer.sign(bodyJson.toByteArray(Charsets.UTF_8))
        val obj = buildJsonObject {
            put("v", VERSION)
            put("type", type)
            put("from", from)
            put("body", bodyJson)
            put("sig", Base64.getEncoder().encodeToString(sig))
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses an envelope. Throws [IllegalArgumentException] on malformed input (the router logs and drops). */
    fun decode(bytes: ByteArray): Envelope {
        val obj = try {
            parser.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("envelope is not a JSON object: ${e.message}")
        }
        val v = (obj["v"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw IllegalArgumentException("envelope has no v")
        if (v != VERSION) throw IllegalArgumentException("unsupported envelope version $v")
        val type = str(obj["type"], "type")
        val from = str(obj["from"], "from")
        val body = str(obj["body"], "body")
        val sigB64 = str(obj["sig"], "sig")
        val sig = try { Base64.getDecoder().decode(sigB64) } catch (_: Exception) { throw IllegalArgumentException("sig is not base64") }
        return Envelope(v, type, from, body, sig)
    }

    private fun str(el: JsonElement?, name: String): String {
        val p = el as? JsonPrimitive ?: throw IllegalArgumentException("envelope field $name missing")
        if (!p.isString) throw IllegalArgumentException("envelope field $name is not a string")
        return p.content
    }

    /** Verifies `sig` over the received body bytes with the given X.509 SPKI public key (base64). Never throws. */
    fun verify(env: Envelope, pubKeyB64: String): Boolean = verifyBody(env.body, env.sig, pubKeyB64)

    fun verifyBody(body: String, sig: ByteArray, pubKeyB64: String): Boolean =
        Verify.verify(pubKeyB64, body.toByteArray(Charsets.UTF_8), sig)

    /** `nodeId = sha256Hex(pubkeyDer)`; null when the key is not valid base64. */
    fun nodeIdOf(pubKeyB64: String): String? = try {
        Hashing.sha256Hex(Base64.getDecoder().decode(pubKeyB64))
    } catch (_: Exception) {
        null
    }

    /**
     * Pairing digits for a transport without its own (RFCOMM): the first 6 decimal digits of
     * `sha256Hex(min(pkA,pkB) + max(pkA,pkB))`. Both phones hold both HELLO keys, so both compute the same value.
     * (Hex characters a-f are skipped so the code is typeable as a number; the raw hex is the fallback.)
     */
    fun pairingDigits(pkA: String, pkB: String): String {
        val lo = if (pkA <= pkB) pkA else pkB
        val hi = if (pkA <= pkB) pkB else pkA
        val hex = Hashing.sha256Hex(lo + hi)
        val digits = hex.filter { it in '0'..'9' }
        return if (digits.length >= 6) digits.substring(0, 6) else hex.substring(0, 6)
    }
}
