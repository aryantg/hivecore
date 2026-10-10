package com.hivecore.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

/**
 * A small JSON Schema draft 2020-12 subset (v2 plan §6.2, §6.3, §7.3): the schemas T2 plans carry in `params.schema`,
 * that `LLM_MAP` validates every item against and that the Escalation Gate sends as the strict `response_format`.
 *
 * Supported keywords: `type` (one name or an array of names, so `["string","null"]` expresses nullable), `properties`,
 * `required`, `additionalProperties` (boolean or schema), `items` (one schema), `minItems`/`maxItems`, `enum`, `const`,
 * `minimum`/`maximum`, `exclusiveMinimum`/`exclusiveMaximum`, `minLength`/`maxLength`, `pattern`, `anyOf`/`oneOf`/
 * `allOf`. Annotations are accepted and ignored: `$schema`, `$id`, `title`, `description`, `default`, `examples`,
 * `format`, `$comment`. A schema with `$ref` (or `$dynamicRef`, `$defs`, …) or any other keyword is not valid here:
 * the validator would not enforce it, and a schema whose meaning is not fully checked must not gate a cloud answer.
 *
 * Numbers compare exactly (BigDecimal); `integer` accepts 3 and 3.0 but not 3.5. Pure Kotlin, no Android.
 */
object JsonSchema {

    val TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val ANNOTATIONS = setOf("\$schema", "\$id", "title", "description", "default", "examples", "format", "\$comment")
    private val KEYWORDS = setOf("type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
        "enum", "const", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern",
        "anyOf", "oneOf", "allOf") + ANNOTATIONS
    private val REFS = setOf("\$ref", "\$dynamicRef", "\$recursiveRef", "\$defs", "definitions", "\$anchor", "\$dynamicAnchor")

    /** Maximum nesting depth of a schema (a guard against hostile plans). */
    const val MAX_DEPTH = 16

    /** True when [schema] is a schema this subset fully understands (no `$ref`, no unknown keyword). */
    fun isValidSchema(schema: JsonElement?): Boolean = schemaError(schema) == null

    /** Null when [schema] is valid, else the first problem ("properties.total: unsupported keyword format2"). */
    fun schemaError(schema: JsonElement?): String? = when (schema) {
        null -> "no schema"
        is JsonObject -> checkSchema(schema, "", 0)
        // draft 2020-12 allows the boolean schemas true/false; they make no sense as an output contract
        else -> "schema is not an object"
    }

    private fun checkSchema(s: JsonObject, path: String, depth: Int): String? {
        fun at(k: String) = if (path.isEmpty()) k else "$path.$k"
        if (depth > MAX_DEPTH) return "${path.ifEmpty { "schema" }}: nested deeper than $MAX_DEPTH"
        for (k in s.keys) {
            if (k in REFS) return "${at(k)}: references are not allowed"
            if (k !in KEYWORDS) return "${at(k)}: unsupported keyword"
        }
        s["type"]?.let { t ->
            val names = when (t) {
                is JsonPrimitive -> if (t.isString) listOf(t.content) else return "${at("type")}: not a type name"
                is JsonArray -> t.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return "${at("type")}: not a type name" }
                else -> return "${at("type")}: not a type name"
            }
            if (names.isEmpty()) return "${at("type")}: empty"
            names.firstOrNull { it !in TYPES }?.let { return "${at("type")}: unknown type $it" }
            if (names.toSet().size != names.size) return "${at("type")}: duplicate type"
        }
        s["properties"]?.let { p ->
            val o = p as? JsonObject ?: return "${at("properties")}: not an object"
            for ((name, sub) in o) {
                val so = sub as? JsonObject ?: return "${at("properties")}.$name: not a schema object"
                checkSchema(so, "${at("properties")}.$name", depth + 1)?.let { return it }
            }
        }
        s["required"]?.let { r ->
            val a = r as? JsonArray ?: return "${at("required")}: not an array"
            val names = a.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return "${at("required")}: not a list of names" }
            if (names.toSet().size != names.size) return "${at("required")}: duplicate name"
        }
        s["additionalProperties"]?.let { ap ->
            when (ap) {
                is JsonObject -> checkSchema(ap, at("additionalProperties"), depth + 1)?.let { return it }
                is JsonPrimitive -> if (ap.isString || ap.booleanOrNull == null) return "${at("additionalProperties")}: not a boolean or schema"
                else -> return "${at("additionalProperties")}: not a boolean or schema"
            }
        }
        s["items"]?.let { i ->
            val io = i as? JsonObject ?: return "${at("items")}: not a schema object"
            checkSchema(io, at("items"), depth + 1)?.let { return it }
        }
        for (k in listOf("minItems", "maxItems", "minLength", "maxLength")) s[k]?.let { v ->
            val n = nonNegativeInt(v) ?: return "${at(k)}: not a non-negative integer"
            if (n < 0) return "${at(k)}: negative"
        }
        for ((lo, hi) in listOf("minItems" to "maxItems", "minLength" to "maxLength", "minimum" to "maximum")) {
            val a = s[lo]?.let { number(it) }; val b = s[hi]?.let { number(it) }
            if (a != null && b != null && a > b) return "${at(lo)}: greater than $hi"
        }
        for (k in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) s[k]?.let { v ->
            if (number(v) == null) return "${at(k)}: not a number"
        }
        s["pattern"]?.let { p ->
            val str = (p as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "${at("pattern")}: not a string"
            runCatching { Regex(str) }.onFailure { return "${at("pattern")}: invalid regular expression" }
        }
        s["enum"]?.let { e ->
            val a = e as? JsonArray ?: return "${at("enum")}: not an array"
            if (a.isEmpty()) return "${at("enum")}: empty"
        }
        for (k in listOf("anyOf", "oneOf", "allOf")) s[k]?.let { v ->
            val a = v as? JsonArray ?: return "${at(k)}: not an array"
            if (a.isEmpty()) return "${at(k)}: empty"
            a.forEachIndexed { i, sub ->
                val so = sub as? JsonObject ?: return "${at(k)}[$i]: not a schema object"
                checkSchema(so, "${at(k)}[$i]", depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** Null when [value] satisfies [schema], else the first error with its JSON path ("$.total: below 0"). */
    fun validate(value: JsonElement, schema: JsonObject): String? {
        schemaError(schema)?.let { return "invalid schema: $it" }
        return check(value, schema, "$")
    }

    private fun check(v: JsonElement, s: JsonObject, path: String): String? {
        s["type"]?.let { t ->
            val names = if (t is JsonArray) t.map { (it as JsonPrimitive).content } else listOf((t as JsonPrimitive).content)
            if (names.none { isType(v, it) }) return "$path: not ${names.joinToString(" or ")}"
        }
        s["const"]?.let { c -> if (!same(v, c)) return "$path: not the constant ${c}" }
        (s["enum"] as? JsonArray)?.let { e -> if (e.none { same(v, it) }) return "$path: not one of $e" }

        if (v is JsonPrimitive && v !is JsonNull && !v.isString && v.booleanOrNull == null) {
            val n = number(v)
            if (n != null) {
                s["minimum"]?.let { number(it) }?.let { if (n < it) return "$path: below ${it.toPlainString()}" }
                s["maximum"]?.let { number(it) }?.let { if (n > it) return "$path: above ${it.toPlainString()}" }
                s["exclusiveMinimum"]?.let { number(it) }?.let { if (n <= it) return "$path: not above ${it.toPlainString()}" }
                s["exclusiveMaximum"]?.let { number(it) }?.let { if (n >= it) return "$path: not below ${it.toPlainString()}" }
            }
        }
        if (v is JsonPrimitive && v.isString) {
            val str = v.content
            val len = str.codePointCount(0, str.length)
            s["minLength"]?.let { nonNegativeInt(it) }?.let { if (len < it) return "$path: shorter than $it" }
            s["maxLength"]?.let { nonNegativeInt(it) }?.let { if (len > it) return "$path: longer than $it" }
            (s["pattern"] as? JsonPrimitive)?.content?.let { p -> if (!Regex(p).containsMatchIn(str)) return "$path: does not match $p" }
        }
        if (v is JsonArray) {
            s["minItems"]?.let { nonNegativeInt(it) }?.let { if (v.size < it) return "$path: fewer than $it items" }
            s["maxItems"]?.let { nonNegativeInt(it) }?.let { if (v.size > it) return "$path: more than $it items" }
            (s["items"] as? JsonObject)?.let { item -> v.forEachIndexed { i, e -> check(e, item, "$path[$i]")?.let { return it } } }
        }
        if (v is JsonObject) {
            val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (s["required"] as? JsonArray)?.forEach { r ->
                val name = (r as JsonPrimitive).content
                if (name !in v) return "$path: missing $name"
            }
            for ((k, e) in v) {
                val ps = props[k] as? JsonObject
                if (ps != null) { check(e, ps, "$path.$k")?.let { return it }; continue }
                when (val ap = s["additionalProperties"]) {
                    is JsonObject -> check(e, ap, "$path.$k")?.let { return it }
                    is JsonPrimitive -> if (ap.booleanOrNull == false) return "$path: unknown key $k"
                    else -> {}
                }
            }
        }
        (s["allOf"] as? JsonArray)?.forEach { sub -> check(v, sub as JsonObject, path)?.let { return it } }
        (s["anyOf"] as? JsonArray)?.let { a ->
            if (a.none { check(v, it as JsonObject, path) == null }) return "$path: matches none of anyOf"
        }
        (s["oneOf"] as? JsonArray)?.let { a ->
            val n = a.count { check(v, it as JsonObject, path) == null }
            if (n != 1) return "$path: matches $n of oneOf (exactly 1 required)"
        }
        return null
    }

    private fun isType(v: JsonElement, t: String): Boolean = when (t) {
        "null" -> v is JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v !is JsonNull && v.isString
        "boolean" -> v is JsonPrimitive && v !is JsonNull && !v.isString && v.booleanOrNull != null
        "number" -> v is JsonPrimitive && v !is JsonNull && !v.isString && v.booleanOrNull == null && number(v) != null
        "integer" -> v is JsonPrimitive && v !is JsonNull && !v.isString && v.booleanOrNull == null &&
            number(v)?.let { it.stripTrailingZeros().scale() <= 0 } == true
        else -> false
    }

    /** JSON equality with numbers compared by value (1 == 1.0). */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        a is JsonPrimitive && b is JsonPrimitive -> {
            if (a.isString || b.isString) a.isString == b.isString && a.content == b.content
            else {
                val x = number(a); val y = number(b)
                if (x != null && y != null) x.compareTo(y) == 0 else a.content == b.content
            }
        }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a.getValue(it), b.getValue(it)) }
        else -> false
    }

    private fun number(e: JsonElement): BigDecimal? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.content.toBigDecimalOrNull()
    }

    private fun nonNegativeInt(e: JsonElement): Int? {
        val n = number(e) ?: return null
        if (n.stripTrailingZeros().scale() > 0 || n.signum() < 0) return null
        return runCatching { n.intValueExact() }.getOrNull()
    }
}
