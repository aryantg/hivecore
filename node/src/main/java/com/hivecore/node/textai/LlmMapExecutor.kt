package com.hivecore.node.textai

import com.hivecore.core.Canon
import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.JsonSchema
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.SLog
import com.hivecore.core.TaskSpec
import com.hivecore.engine.JsonSchemaValidator
import com.hivecore.engine.SchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * `LLM_MAP` (spec 7.5): the generic per-item LLM op for anything without a bespoke executor, so T2 can express a
 * task the pattern library has never seen without the runtime learning a new op.
 *
 * `params = {template, schema, maxTokens}`: for every item (the [TextInputs] sources: `params.text`/`texts`/`units`,
 * earlier task outputs, released text blobs) the template is filled (`{item}` = the item's text; without the
 * placeholder the item is appended) and the model must reply with exactly one JSON value satisfying `schema` (the
 * [JsonSchema] draft 2020-12 subset). On llama.cpp the reply is constrained by a GBNF grammar built from the schema
 * when it fits the [GenText] subset; on the qairt NPU (where any grammar breaks generation) and for richer schemas the
 * reply is parsed and validated only. A reply that does not parse or validate fails the task ("rejected: …"); nothing
 * is ever repaired, so replicas and the gate compare exactly what a model produced.
 *
 * Output: `{"items": [{"item": <source key>, "output": <value>}, …]}` in item order, which concatenates across shards
 * (SHARD merge = CONCAT) and votes across fan-out replicas (FANOUT merge = VOTE) like every other LLM op.
 */
class LlmMapExecutor(
    private val llm: LlmEngine,
    private val validator: SchemaValidator = JsonSchemaValidator,
) : OpExecutor {
    override val ops: Set<Op> = setOf(Op.LLM_MAP)

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = runCatching {
        val p = task.params
        val template = (p[TEMPLATE] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("LLM_MAP needs a template")
        val schema = p[SchemaValidator.PARAM] as? JsonObject ?: throw IllegalArgumentException("LLM_MAP needs a schema")
        JsonSchema.schemaError(schema)?.let { throw IllegalArgumentException("LLM_MAP schema: $it") }
        val maxTokens = ((p[MAX_TOKENS] as? JsonPrimitive)?.intOrNull ?: DEFAULT_MAX_TOKENS).coerceIn(1, MAX_TOKENS_CAP)
        val items = TextInputs.units(p, inputs, blobs)
        if (items.isEmpty()) throw IllegalArgumentException("LLM_MAP has no items")
        if (items.size > MAX_ITEMS) throw IllegalArgumentException("LLM_MAP has ${items.size} items (at most $MAX_ITEMS per task)")
        val grammar = grammarFor(schema, llm.backend.value)
        val out = items.map { u ->
            val raw = llm.generate(messages(template, u.text, schema), GenParams(maxTokens = maxTokens, stopOnBalancedJson = true, grammar = grammar))
                .getOrThrow().text
            val value = parse(raw) ?: run {
                SLog.w(TAG, "item ${u.key}: not one JSON value: ${raw.take(300).replace('\n', ' ')}")
                throw IllegalStateException("rejected: item ${u.key}: the reply is not exactly one JSON value")
            }
            validator.validate(value, schema)?.let {
                SLog.w(TAG, "item ${u.key}: $it in ${raw.take(300).replace('\n', ' ')}")
                throw IllegalStateException("rejected: item ${u.key}: $it")
            }
            JsonObject(linkedMapOf("item" to JsonPrimitive(u.key), "output" to value))
        }
        JsonObject(mapOf("items" to JsonArray(out)))
    }

    companion object {
        private const val TAG = "LlmMap"
        const val TEMPLATE = "template"
        const val MAX_TOKENS = "maxTokens"
        const val DEFAULT_MAX_TOKENS = 256
        const val MAX_TOKENS_CAP = 1500
        const val MAX_ITEMS = 64
        const val MAX_ITEM_CHARS = 4000
        private const val PLACEHOLDER = "{item}"
        private val STRICT = Json { isLenient = false; allowSpecialFloatingPointValues = false }

        fun messages(template: String, item: String, schema: JsonObject): List<ChatMsg> {
            val text = if (item.length <= MAX_ITEM_CHARS) item else item.take(MAX_ITEM_CHARS)
            val filled = if (PLACEHOLDER in template) template.replace(PLACEHOLDER, text) else "${template.trim()}\n\nItem:\n$text"
            return listOf(
                ChatMsg("system", "Reply with exactly one JSON value that satisfies this JSON Schema, on one line, and nothing else.\n" +
                    "Schema: " + Canon.canonical(schema)),
                ChatMsg("user", filled),
            )
        }

        /** The reply as one JSON value (surrounding whitespace allowed), or null. Never repaired. */
        fun parse(raw: String): JsonElement? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            return runCatching { STRICT.parseToJsonElement(t) }.getOrNull()
        }

        /**
         * GBNF from [schema] on llama.cpp (CPU/GPU) when the schema fits [GenText.Schema]'s subset (an object of
         * scalar or scalar-array properties); null on the NPU, an unknown backend, or a richer schema.
         */
        fun grammarFor(schema: JsonObject, backend: LlmBackend?): String? {
            if (backend == null || backend == LlmBackend.NPU) return null
            if (schema["additionalProperties"] != null || schema["anyOf"] != null || schema["oneOf"] != null || schema["allOf"] != null) return null
            val s = runCatching { GenText.Schema.from(schema) }.getOrNull() ?: return null
            // the GenText grammar lets an optional key be null, which a non-nullable schema type would then reject
            if (s.required.size != s.fields.size) return null
            return runCatching { GenText.grammar(s) }.getOrNull()
        }
    }
}
