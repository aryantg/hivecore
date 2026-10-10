package com.hivecore.runtime.llm

import com.hivecore.core.IntentSet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Thrown (inside the Result) by [IntentParser.parse]; the message is what the retry prompt quotes. */
class IntentParseException(message: String) : Exception(message)

/**
 * Model output → [IntentSet] (plan 8): find the first `{`, balance braces string-aware (so `{` inside a value
 * does not count), decode leniently, then normalise what small models get wrong: a bare intent object, a
 * single object instead of a list, `group_by` as a string, operator symbols, mixed-case names.
 * Every failure carries a short, specific message that is fed back to the model on the retry.
 */
object IntentParser {
    const val MAX_INTENTS = 3
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val METRICS = setOf("count", "sum", "avg", "min", "max")
    private val OPS = mapOf(
        "eq" to "eq", "=" to "eq", "==" to "eq", "equals" to "eq", "is" to "eq",
        "neq" to "neq", "!=" to "neq", "<>" to "neq", "ne" to "neq", "not" to "neq",
        "gt" to "gt", ">" to "gt", "gte" to "gte", ">=" to "gte", "ge" to "gte",
        "lt" to "lt", "<" to "lt", "lte" to "lte", "<=" to "lte", "le" to "lte",
        "in" to "in"
    )
    private val METRIC_ALIASES = mapOf(
        "average" to "avg", "mean" to "avg", "total" to "sum", "cnt" to "count", "number" to "count",
        "minimum" to "min", "maximum" to "max", "lowest" to "min", "highest" to "max"
    )

    fun parse(text: String): Result<IntentSet> = runCatching {
        val body = extractJson(text).getOrThrow()
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw IntentParseException("invalid JSON: ${firstLine(e.message) ?: "syntax error"}")
        }
        val obj = root as? JsonObject ?: throw IntentParseException("the output must be a JSON object")
        val normalised = normalise(obj)
        try {
            json.decodeFromJsonElement(IntentSet.serializer(), normalised)
        } catch (e: SerializationException) {
            throw IntentParseException("invalid intent: ${firstLine(e.message) ?: "wrong shape"}")
        }
    }

    /**
     * For an output cut off mid-way (the planner's time budget stopped the stream): the complete intent objects at
     * the start of `{"intents":[{...},{...` re-closed as `{"intents":[{...}]}`, or null when no intent is complete.
     * Braces inside JSON strings are ignored (escapes honoured).
     */
    fun salvagePrefix(text: String): String? {
        val key = text.indexOf("\"intents\"")
        if (key < 0) return null
        val open = text.indexOf('[', key)
        if (open < 0) return null
        val objects = ArrayList<String>()
        var depth = 0; var start = -1; var inString = false; var escape = false
        var i = open + 1
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { objects += text.substring(start, i + 1); start = -1 } }
                ']' -> if (depth == 0) break
            }
            i++
        }
        if (objects.isEmpty()) return null
        return objects.joinToString(",", prefix = "{\"intents\":[", postfix = "]}")
    }

    /**
     * Returns the substring from the first `{` to its matching `}`. Braces inside JSON strings are ignored
     * (escapes honoured). Errors: no `{` at all; or the text ends before the object closes (truncated output).
     */
    fun extractJson(text: String): Result<String> {
        val start = text.indexOf('{')
        if (start < 0) return Result.failure(IntentParseException("no JSON object found in the output"))
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return Result.success(text.substring(start, i + 1)) }
            }
            i++
        }
        return Result.failure(IntentParseException("the JSON object is truncated (unbalanced braces)"))
    }

    /** Canonical compact JSON of an IntentSet (logging, few-shot assistant turns). */
    fun encode(set: IntentSet): String = json.encodeToString(IntentSet.serializer(), set)

    /** Pure-structure fixes before decoding; throws [IntentParseException] with a specific message. */
    private fun normalise(obj: JsonObject): JsonObject {
        val notRelevant = obj["not_relevant"]?.let { truthy(it) } ?: false
        if (notRelevant) return buildJsonObject { put("not_relevant", JsonPrimitive(true)) }
        val raw: JsonElement? = when {
            obj.containsKey("intents") -> obj["intents"]
            obj.containsKey("intent") -> obj["intent"]
            obj.containsKey("table") -> obj           // a bare intent
            else -> null
        }
        val list: List<JsonElement> = when (raw) {
            null, is JsonNull -> emptyList()
            is JsonArray -> raw
            is JsonObject -> listOf(raw)
            else -> throw IntentParseException("intents must be an array of intent objects")
        }
        if (list.isEmpty()) {
            // {"skip":["revenue"],"intents":[]}: every measure asked is another department's, i.e. not relevant here.
            val skipped = (obj["skip"] as? JsonArray)?.isNotEmpty() == true
            if (skipped) return buildJsonObject { put("not_relevant", JsonPrimitive(true)) }
            throw IntentParseException("intents is empty; output {\"not_relevant\":true} if the question is not about these tables")
        }
        // An extra sum/avg/min/max with no column is the model's placeholder for a measure this node does not hold
        // (Wave 4, HR Q1: count by region + "sum" with no column for revenue). When another intent is complete it is
        // dropped instead of failing the whole answer; alone it is still an error the retry quotes.
        val kept = if (list.size < 2) list else list.filterNot { aggregateWithoutColumn(it) }.ifEmpty { list }
        // Exact duplicates (same table, metric, column, group_by and filters) are a known small-model tic; drop them.
        val intents = kept.mapIndexed { idx, el -> normaliseIntent(idx + 1, el) }.distinct().take(MAX_INTENTS)
        return buildJsonObject { put("intents", JsonArray(intents)) }
    }

    private fun aggregateWithoutColumn(el: JsonElement): Boolean {
        val o = el as? JsonObject ?: return false
        val metric = str(o["metric"] ?: o["agg"] ?: o["aggregate"])?.trim()?.lowercase()?.let { METRIC_ALIASES[it] ?: it } ?: return false
        val column = str(o["column"])?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "*" }
        return metric in METRICS && metric != "count" && column == null
    }

    private fun normaliseIntent(n: Int, el: JsonElement): JsonObject {
        val o = el as? JsonObject ?: throw IntentParseException("intent $n is not an object")
        val table = str(o["table"])?.trim()?.lowercase() ?: throw IntentParseException("intent $n has no table")
        if (table.isEmpty()) throw IntentParseException("intent $n has an empty table")
        var metric = str(o["metric"] ?: o["agg"] ?: o["aggregate"])?.trim()?.lowercase()
            ?: throw IntentParseException("intent $n has no metric")
        metric = METRIC_ALIASES[metric] ?: metric
        if (metric !in METRICS) throw IntentParseException("intent $n has unknown metric '$metric' (use count, sum, avg, min or max)")
        val column = str(o["column"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "null" && it != "*" }
        if (metric != "count" && column == null) throw IntentParseException("intent $n ($metric) needs a numeric column")
        val groupBy = when (val g = o["group_by"] ?: o["groupBy"] ?: o["group"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> g.map { str(it)?.trim()?.lowercase() ?: throw IntentParseException("intent $n has a non-text group_by entry") }
            is JsonPrimitive -> listOf(g.content.trim().lowercase())
            else -> throw IntentParseException("intent $n has an invalid group_by")
        }.filter { it.isNotEmpty() }
        val filters = when (val f = o["filters"] ?: o["filter"] ?: o["where"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> f.mapIndexed { i, fe -> normaliseFilter(n, i + 1, fe) }
            is JsonObject -> listOf(normaliseFilter(n, 1, f))
            else -> throw IntentParseException("intent $n has invalid filters (expected an array)")
        }
        return buildJsonObject {
            put("table", JsonPrimitive(table))
            put("metric", JsonPrimitive(metric))
            if (column != null) put("column", JsonPrimitive(column))
            put("group_by", JsonArray(groupBy.map { JsonPrimitive(it) }))
            put("filters", JsonArray(filters))
        }
    }

    private fun normaliseFilter(n: Int, i: Int, el: JsonElement): JsonObject {
        val f = el as? JsonObject ?: throw IntentParseException("intent $n filter $i is not an object")
        val column = str(f["column"] ?: f["field"] ?: f["col"])?.trim()?.lowercase()
            ?: throw IntentParseException("intent $n filter $i has no column")
        val opRaw = str(f["op"] ?: f["operator"])?.trim()?.lowercase() ?: throw IntentParseException("intent $n filter $i has no op")
        val op = OPS[opRaw] ?: throw IntentParseException("intent $n filter $i has unknown op '$opRaw' (use eq, neq, gt, gte, lt, lte or in)")
        val value = f["value"] ?: f["values"] ?: throw IntentParseException("intent $n filter $i has no value")
        if (value is JsonNull) throw IntentParseException("intent $n filter $i has a null value")
        if (op == "in" && value !is JsonArray) throw IntentParseException("intent $n filter $i: op in needs an array value")
        if (op != "in" && value is JsonArray) throw IntentParseException("intent $n filter $i: only op in may take an array value")
        if (value is JsonObject) throw IntentParseException("intent $n filter $i: value must be text or a number")
        return buildJsonObject { put("column", JsonPrimitive(column)); put("op", JsonPrimitive(op)); put("value", value) }
    }

    private fun str(e: JsonElement?): String? = when (e) {
        null, is JsonNull -> null
        is JsonPrimitive -> e.content
        else -> null
    }

    private fun truthy(e: JsonElement): Boolean =
        e is JsonPrimitive && e.content.trim().lowercase() in setOf("true", "1", "yes")

    private fun firstLine(msg: String?): String? = msg?.lineSequence()?.firstOrNull()?.take(160)
}
