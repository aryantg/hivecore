package com.hivecore.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.TreeMap
import kotlin.random.Random

/**
 * Phase 0 exit gate (spec section 19): `canonical()` and `sha256` agree with an independent reference implementation
 * on 1,000 fuzzed JSON values. The reference below is written from the spec's rule (section 6.6) without looking at
 * [Canon]: keys sorted, no whitespace, numbers as shortest exact decimals, strings JSON-escaped.
 */
class CanonFuzzTest {

    // ---------------------------------------------------------------- reference implementation

    private fun refCanonical(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonObject -> TreeMap(e).entries.joinToString(",", "{", "}") { (k, v) -> refString(k) + ":" + refCanonical(v) }
        is JsonArray -> e.joinToString(",", "[", "]") { refCanonical(it) }
        is JsonPrimitive -> when {
            e.isString -> refString(e.content)
            e.content == "true" || e.content == "false" -> e.content
            else -> refNumber(e.content)
        }
    }

    private fun refNumber(s: String): String {
        val d = BigDecimal(s)
        if (d.compareTo(BigDecimal.ZERO) == 0) return "0"
        return d.stripTrailingZeros().toPlainString()
    }

    private fun refString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun refSha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- generator

    private val alphabet = "abcXYZ019 _-.:/\"\\\n\té₹中😀"

    private fun randString(r: Random): String {
        val n = r.nextInt(0, 12)
        val sb = StringBuilder()
        repeat(n) {
            val c = alphabet[r.nextInt(alphabet.length)]
            // keep surrogate pairs intact
            if (c.isHighSurrogate()) { sb.append(c); sb.append(alphabet[alphabet.indexOf(c) + 1]) } else if (!c.isLowSurrogate()) sb.append(c)
        }
        return sb.toString()
    }

    private fun randNumber(r: Random): JsonPrimitive = when (r.nextInt(8)) {
        0 -> JsonPrimitive(r.nextLong(-1_000_000_000_000L, 1_000_000_000_000L))
        1 -> JsonPrimitive(r.nextInt(-1000, 1000))
        2 -> JsonPrimitive(r.nextDouble(-1e6, 1e6))
        3 -> JsonPrimitive(BigDecimal(r.nextLong(-99999, 99999)).movePointLeft(r.nextInt(0, 6)))
        4 -> JsonPrimitive(0)
        5 -> JsonPrimitive(-0.0)
        6 -> JsonPrimitive(r.nextInt(-9, 9).toDouble())
        else -> JsonPrimitive(r.nextInt(1, 999) / 100.0)
    }

    private fun randJson(r: Random, depth: Int): JsonElement = when (if (depth <= 0) r.nextInt(4) else r.nextInt(6)) {
        0 -> JsonNull
        1 -> JsonPrimitive(r.nextBoolean())
        2 -> randNumber(r)
        3 -> JsonPrimitive(randString(r))
        4 -> JsonArray(List(r.nextInt(0, 5)) { randJson(r, depth - 1) })
        else -> JsonObject(List(r.nextInt(0, 5)) { randString(r) to randJson(r, depth - 1) }.toMap())
    }

    // ---------------------------------------------------------------- tests

    @Test fun thousandFuzzedValuesAgreeWithTheReference() {
        val r = Random(20261010)
        var checked = 0
        repeat(1000) {
            val v = randJson(r, 4)
            val expected = refCanonical(v)
            val actual = Canon.canonical(v)
            assertEquals("value #$it: $v", expected, actual)
            assertEquals("hash #$it", refSha256(expected), Canon.sha256Hex(actual))
            assertEquals("hash #$it via Hashing", Hashing.sha256Hex(expected), Canon.hash(v))
            // canonical output re-parses to an element that canonicalises to the same bytes (fixpoint)
            assertEquals("fixpoint #$it", actual, Canon.canonical(Json.parseToJsonElement(actual)))
            checked++
        }
        assertEquals(1000, checked)
    }

    @Test fun numbersPrintAsShortestExactDecimals() {
        for (s in listOf("5", "5.0", "5.00", "5e0")) assertEquals("5", Canon.canonical(JsonPrimitive(s.toBigDecimal())))
        assertEquals("1.5", Canon.canonical(JsonPrimitive(1.50)))
        assertEquals("0", Canon.canonical(JsonPrimitive(-0.0)))
        assertEquals("0", Canon.canonical(JsonPrimitive(0.000)))
        assertEquals("1234567890123", Canon.canonical(JsonPrimitive(1234567890123L)))
        assertEquals("0.0001", Canon.canonical(JsonPrimitive(1e-4)))
        assertTrue(!Canon.canonical(JsonPrimitive(1e21)).contains('E'))
    }

    @Test fun keysAreSortedAndWhitespaceFree() {
        val v = Json.parseToJsonElement("""{ "b" : [1, 2.0, {"z": null, "a": "x"}], "a" : true }""")
        assertEquals("""{"a":true,"b":[1,2,{"a":"x","z":null}]}""", Canon.canonical(v))
    }

    @Test fun normalisedArithmeticNumbers() {
        assertEquals("5", Canon.number(BigDecimal("5.000")).content)
        assertEquals("3.3333", Canon.number(Canon.div(BigDecimal(10), BigDecimal(3))).content)
        assertEquals("2.5", Canon.number(BigDecimal("2.49995")).content)
    }
}
