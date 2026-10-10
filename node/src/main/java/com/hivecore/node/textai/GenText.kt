package com.hivecore.node.textai

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.runtime.llm.Gbnf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal

/**
 * GEN_TEXT (swarm plan §4A.3): structured generation only — a JSON-schema subset → prompt + GBNF (llama.cpp only;
 * never on the NPU) → strict parse + validation. The reply must be exactly one JSON object (surrounding whitespace
 * allowed); unknown keys, missing required keys, wrong types, enum or bound violations are rejected, never repaired
 * (R3). Free prose is impossible: every string has a maxLength (default [DEFAULT_MAX_LENGTH]).
 *
 * Schema subset: `{"type":"object","properties":{name: field…},"required":[names]}` where a field is
 * `{"type":"string","enum"?,"maxLength"?,"minLength"?,"pattern"?,"description"?}` | `{"type":"integer"|"number",
 * "minimum"?,"maximum"?}` | `{"type":"boolean"}` | `{"type":"array","items":<string|integer|number|boolean field>,
 * "maxItems"?,"minItems"?}`. Key order = properties order.
 */
class GenText(private val llm: LlmEngine) {

    sealed interface Outcome {
        data class Ok(val value: JsonObject, val raw: String) : Outcome
        data class Rejected(val reason: String, val raw: String) : Outcome
    }

    suspend fun generate(instruction: String, schema: Schema, context: String? = null, maxTokens: Int = 256): Result<Outcome> = runCatching {
        val params = GenParams(maxTokens = maxTokens, stopOnBalancedJson = true, grammar = grammarFor(schema, llm.backend.value))
        val raw = llm.generate(prompt(instruction, schema, context), params).getOrThrow().text
        when (val v = schema.parse(raw)) {
            is Validation.Ok -> Outcome.Ok(v.value, raw)
            is Validation.Bad -> Outcome.Rejected(v.reason, raw)
        }
    }

    data class Field(
        val name: String,
        val type: String,
        val enum: List<String>? = null,
        val minLength: Int? = null,
        val maxLength: Int = DEFAULT_MAX_LENGTH,
        val pattern: Regex? = null,
        val minimum: BigDecimal? = null,
        val maximum: BigDecimal? = null,
        val items: Field? = null,
        val minItems: Int? = null,
        val maxItems: Int = DEFAULT_MAX_ITEMS,
        val description: String? = null,
    )

    sealed interface Validation {
        data class Ok(val value: JsonObject) : Validation
        data class Bad(val reason: String) : Validation
    }

    class Schema(val fields: List<Field>, val required: Set<String>) {
        init {
            require(fields.isNotEmpty()) { "schema has no properties" }
            require(fields.map { it.name }.toSet().size == fields.size) { "duplicate property" }
            require(required.all { r -> fields.any { it.name == r } }) { "required names an unknown property" }
        }

        /** Strict parse + validation of a model reply; the output keeps the schema's key order (absent optional → null). */
        fun parse(text: String): Validation {
            val t = text.trim()
            if (!t.startsWith("{") || !t.endsWith("}")) return Validation.Bad("reply is not exactly one JSON object")
            val obj = runCatching { STRICT.parseToJsonElement(t) }.getOrNull() as? JsonObject
                ?: return Validation.Bad("invalid JSON")
            return validate(obj)
        }

        fun validate(obj: JsonObject): Validation {
            val names = fields.map { it.name }.toSet()
            obj.keys.firstOrNull { it !in names }?.let { return Validation.Bad("unknown key $it") }
            val out = LinkedHashMap<String, JsonElement>()
            for (f in fields) {
                val v = obj[f.name]
                if (v == null || v is JsonNull) {
                    if (f.name in required) return Validation.Bad("missing ${f.name}")
                    out[f.name] = JsonNull
                    continue
                }
                out[f.name] = check(f, v, f.name) ?: return Validation.Bad(lastError)
            }
            return Validation.Ok(JsonObject(out))
        }

        private var lastError = ""

        private fun check(f: Field, v: JsonElement, path: String): JsonElement? {
            fun bad(msg: String): JsonElement? { lastError = "$path: $msg"; return null }
            return when (f.type) {
                "string" -> {
                    val p = v as? JsonPrimitive ?: return bad("not a string")
                    if (!p.isString) return bad("not a string")
                    val s = p.content
                    if (f.enum != null && s !in f.enum) return bad("'$s' not in ${f.enum}")
                    if (s.length > f.maxLength) return bad("longer than ${f.maxLength}")
                    if (f.minLength != null && s.length < f.minLength) return bad("shorter than ${f.minLength}")
                    if (f.pattern != null && !f.pattern.matches(s)) return bad("does not match ${f.pattern.pattern}")
                    if (s.any { it < ' ' }) return bad("control character")
                    JsonPrimitive(s)
                }
                "integer", "number" -> {
                    val p = v as? JsonPrimitive ?: return bad("not a number")
                    if (p.isString) return bad("not a number")
                    val d = runCatching { BigDecimal(p.content) }.getOrNull() ?: return bad("not a number")
                    val n = d.stripTrailingZeros()
                    if (f.type == "integer" && (n.scale() > 0 || p.content.contains('.') || p.content.contains('e', true))) return bad("not an integer")
                    if (f.minimum != null && d < f.minimum) return bad("below ${f.minimum}")
                    if (f.maximum != null && d > f.maximum) return bad("above ${f.maximum}")
                    if (f.type == "integer") JsonPrimitive(n.toBigIntegerExact()) else JsonPrimitive(if (n.scale() < 0) n.setScale(0) else n)
                }
                "boolean" -> {
                    val p = v as? JsonPrimitive ?: return bad("not a boolean")
                    if (p.isString || p.booleanOrNull == null) return bad("not a boolean")
                    JsonPrimitive(p.booleanOrNull!!)
                }
                "array" -> {
                    val a = v as? JsonArray ?: return bad("not an array")
                    if (a.size > f.maxItems) return bad("more than ${f.maxItems} items")
                    if (f.minItems != null && a.size < f.minItems) return bad("fewer than ${f.minItems} items")
                    JsonArray(a.mapIndexed { i, e -> check(f.items!!, e, "$path[$i]") ?: return null })
                }
                else -> bad("unsupported type ${f.type}")
            }
        }

        companion object {
            private val STRICT = Json { isLenient = false; allowSpecialFloatingPointValues = false }

            fun from(json: JsonObject): Schema {
                require(((json["type"] as? JsonPrimitive)?.content ?: "object") == "object") { "schema type must be object" }
                val props = json["properties"] as? JsonObject ?: throw IllegalArgumentException("schema has no properties")
                val fields = props.entries.map { (k, v) -> field(k, v as? JsonObject ?: throw IllegalArgumentException("property $k is not an object"), top = true) }
                val req = (json["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: fields.map { it.name }.toSet()
                return Schema(fields, req)
            }

            private fun field(name: String, o: JsonObject, top: Boolean): Field {
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content
                fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
                fun d(k: String) = (o[k] as? JsonPrimitive)?.content?.let { BigDecimal(it) }
                val type = s("type") ?: if (o["enum"] != null) "string" else throw IllegalArgumentException("property $name has no type")
                require(type in setOf("string", "integer", "number", "boolean", "array")) { "property $name: unsupported type $type" }
                val items = if (type == "array") {
                    require(top) { "nested arrays are not supported" }
                    field("$name[]", o["items"] as? JsonObject ?: throw IllegalArgumentException("array $name has no items"), top = false)
                } else null
                return Field(name, type, (o["enum"] as? JsonArray)?.map { (it as JsonPrimitive).content }, i("minLength"),
                    i("maxLength") ?: DEFAULT_MAX_LENGTH, s("pattern")?.let { Regex(it) }, d("minimum"), d("maximum"), items,
                    i("minItems"), i("maxItems") ?: DEFAULT_MAX_ITEMS, s("description"))
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_LENGTH = 200
        const val DEFAULT_MAX_ITEMS = 10
        const val MAX_CONTEXT_CHARS = 4000

        fun prompt(instruction: String, schema: Schema, context: String?): List<ChatMsg> = listOf(
            ChatMsg("system", "You fill in a JSON object. Reply with exactly one JSON object on one line, with the listed keys in order, and nothing else."),
            ChatMsg("user", buildString {
                append("Task: ").append(instruction.trim()).append('\n')
                if (!context.isNullOrBlank()) {
                    val c = context.trim()
                    append("Source:\n").append(if (c.length <= MAX_CONTEXT_CHARS) c else c.take(MAX_CONTEXT_CHARS)).append('\n')
                }
                append("Keys:\n")
                for (f in schema.fields) append("- ").append(f.name).append(": ").append(describe(f))
                    .append(if (f.name in schema.required) "" else " (or null)").append('\n')
            }),
        )

        private fun describe(f: Field): String = buildString {
            when (f.type) {
                "string" -> if (f.enum != null) append("one of ").append(f.enum.joinToString(", ") { "\"$it\"" }) else append("string, at most ${f.maxLength} characters")
                "integer", "number" -> {
                    append(f.type)
                    if (f.minimum != null) append(", ≥ ").append(f.minimum.toPlainString())
                    if (f.maximum != null) append(", ≤ ").append(f.maximum.toPlainString())
                }
                "boolean" -> append("true or false")
                "array" -> append("list of at most ${f.maxItems} × (").append(describe(f.items!!)).append(')')
            }
            if (f.description != null) append(" — ").append(f.description)
        }

        /** GBNF for the schema (all keys emitted, in order); null on the NPU or an unknown backend. */
        fun grammarFor(schema: Schema, backend: LlmBackend?): String? =
            if (backend == null || backend == LlmBackend.NPU) null else grammar(schema)

        fun grammar(schema: Schema): String {
            val rules = ArrayList<String>()
            val body = schema.fields.mapIndexed { i, f ->
                val r = "f$i"
                val opt = f.name !in schema.required
                rules += "$r ::= " + value(f, "f${i}i", rules) + if (opt) " | \"null\"" else ""
                Gbnf.literal("\"" + Gbnf.jsonEscape(f.name) + "\"") + " ws \":\" ws " + r
            }
            return buildString {
                append("root ::= \"{\" ws ").append(body.joinToString(" ws \",\" ws ")).append(" ws \"}\"\n")
                for (r in rules) append(r).append('\n')
                append("string ::= \"\\\"\" char* \"\\\"\"\n")
                append("char ::= [^\"\\\\\\x00-\\x1f] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" hex hex hex hex)\n")
                append("hex ::= [0-9a-fA-F]\n")
                append("integer ::= \"-\"? (\"0\" | [1-9] [0-9]*)\n")
                append("number ::= integer (\".\" [0-9]+)?\n")
                append("boolean ::= \"true\" | \"false\"\n")
                append("ws ::= \" \"?")
            }
        }

        private fun value(f: Field, sub: String, rules: MutableList<String>): String = when (f.type) {
            "string" -> f.enum?.let { e -> "(" + e.joinToString(" | ") { Gbnf.literal("\"" + Gbnf.jsonEscape(it) + "\"") } + ")" } ?: "string"
            "integer" -> "integer"
            "number" -> "number"
            "boolean" -> "boolean"
            "array" -> {
                rules += "$sub ::= " + value(f.items!!, sub + "x", rules)
                "\"[\" ws ($sub (ws \",\" ws $sub)*)? ws \"]\""
            }
            else -> error("unsupported type ${f.type}")
        }
    }
}
