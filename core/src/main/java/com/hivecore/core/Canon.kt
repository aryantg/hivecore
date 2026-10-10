package com.hivecore.core

import com.hivecore.core.AggTable
import com.hivecore.core.Dag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Canonical JSON and hashing for swarm plans and task outputs (swarm plan §5.2, §6.3, §6.4).
 *
 * Canonical form: object keys sorted (UTF-16 order), no whitespace, arrays in order, strings JSON-escaped, numbers
 * as their shortest exact decimal (`5`, `5.0` and `5.00` all print `5`; `1.50` prints `1.5`; no exponent). Two phones
 * that hold the same value therefore produce the same bytes and the same hash, whichever JSON library wrote it.
 */
object Canon {
    /** Encoder with defaults written out, so a field left at its default still enters the hash. */
    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun canonical(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    fun canonical(dag: Dag): String = canonical(json.encodeToJsonElement(Dag.serializer(), dag))

    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f]) }
        return sb.toString()
    }

    fun hash(e: JsonElement): String = sha256Hex(canonical(e))

    /** `sha256(canonical(dag) + patternsVersion + modelHash)` (§5.2). */
    fun planHash(dag: Dag, modelHash: String): String = sha256Hex(canonical(dag) + dag.patternsVersion + modelHash)

    fun equal(a: JsonElement, b: JsonElement): Boolean = canonical(a) == canonical(b)

    // ------------------------------------------------------------------ AggTable helpers

    fun decodeTable(e: JsonElement): AggTable = json.decodeFromJsonElement(AggTable.serializer(), e)

    /**
     * An [AggTable] as JSON; [notes] (division by zero, missing keys …) ride along as an extra `notes` array, present
     * only when non-empty. Decoders with `ignoreUnknownKeys` (every Json in this app, and [decodeTable]) read it as a
     * plain AggTable; [notesOf] reads the notes back.
     */
    fun encodeTable(t: AggTable, notes: List<String> = emptyList()): JsonElement {
        val base = json.encodeToJsonElement(AggTable.serializer(), t) as JsonObject
        if (notes.isEmpty()) return base
        return JsonObject(base + ("notes" to JsonArray(notes.map { JsonPrimitive(it) })))
    }

    fun notesOf(e: JsonElement): List<String> =
        ((e as? JsonObject)?.get("notes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    // ------------------------------------------------------------------ numbers

    /** A JSON number as an exact decimal, or null for strings that are not numbers, JsonNull and non-primitives. */
    fun decimal(e: JsonElement?): BigDecimal? {
        if (e == null || e is JsonNull || e !is JsonPrimitive) return null
        if (e.isString) return null
        return e.content.toBigDecimalOrNull()
    }

    /** Like [decimal] but also accepts numeric strings ("1200"), for tables whose values arrived as text. */
    fun decimalLoose(e: JsonElement?): BigDecimal? {
        if (e == null || e is JsonNull || e !is JsonPrimitive) return null
        return e.content.trim().toBigDecimalOrNull()
    }

    /** Long when the value is an exact integer that fits, else a Double rounded half-up to 4 decimals. */
    fun number(v: BigDecimal): JsonPrimitive {
        val s = v.stripTrailingZeros()
        if (s.scale() <= 0) {
            try { return JsonPrimitive(s.longValueExact()) } catch (_: ArithmeticException) { }
        }
        return JsonPrimitive(v.setScale(4, RoundingMode.HALF_UP).toDouble())
    }

    /** Exact-enough division for [number]: 20 significant digits, then rounded by [number]. */
    fun div(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, MathContext(20, RoundingMode.HALF_UP))

    // ------------------------------------------------------------------ writer

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    out.append(JsonPrimitive(k).toString()).append(':')
                    write(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, x -> if (i > 0) out.append(','); write(x, out) }
                out.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> out.append(e.toString())
                e.content == "true" || e.content == "false" -> out.append(e.content)
                else -> {
                    val d = e.content.toBigDecimalOrNull()
                    if (d == null) out.append(e.content)
                    else out.append(if (d.signum() == 0) "0" else d.stripTrailingZeros().toPlainString())
                }
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
