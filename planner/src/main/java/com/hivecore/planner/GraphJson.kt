package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A conditional edge's `when` (spec 7.3): `always`, `if_empty`, `if_confidence_below:<x>`, `if_count_above:<n>`.
 * The producer's output decides whether the consumer runs; the engine evaluates [holds] against that output.
 */
sealed class EdgeCondition {
    data object Always : EdgeCondition()
    data object IfEmpty : EdgeCondition()
    data class IfConfidenceBelow(val threshold: Double) : EdgeCondition()
    data class IfCountAbove(val count: Int) : EdgeCondition()

    /** The spec spelling. */
    fun encode(): String = when (this) {
        Always -> "always"; IfEmpty -> "if_empty"
        is IfConfidenceBelow -> "if_confidence_below:" + Canon.canonical(JsonPrimitive(threshold))
        is IfCountAbove -> "if_count_above:$count"
    }

    /** True when the consumer should run given the producer's [output]. */
    fun holds(output: JsonElement): Boolean = when (this) {
        Always -> true
        IfEmpty -> when (output) {
            is JsonNull -> true
            is JsonArray -> output.isEmpty()
            is JsonObject -> output.isEmpty() || listOf("items", "rows", "results", "picks").any { k -> (output[k] as? JsonArray)?.isEmpty() == true }
            is JsonPrimitive -> output.isString && output.content.isBlank()
        }
        is IfConfidenceBelow -> ((output as? JsonObject)?.get("confidence") as? JsonPrimitive)?.doubleOrNull?.let { it < threshold } ?: false
        is IfCountAbove -> countOf(output)?.let { it > count } ?: false
    }

    private fun countOf(e: JsonElement): Int? = when (e) {
        is JsonArray -> e.size
        is JsonObject -> ((e["count"] as? JsonPrimitive)?.intOrNull) ?: listOf("items", "rows", "results", "picks").firstNotNullOfOrNull { (e[it] as? JsonArray)?.size }
        else -> null
    }

    companion object {
        /** Null when [s] is not one of the four spellings (a bad threshold counts as unknown too). */
        fun parse(s: String): EdgeCondition? {
            val t = s.trim().lowercase()
            return when {
                t == "always" -> Always
                t == "if_empty" -> IfEmpty
                t.startsWith("if_confidence_below:") -> t.substringAfter(':').toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { IfConfidenceBelow(it) }
                t.startsWith("if_count_above:") -> t.substringAfter(':').toIntOrNull()?.takeIf { it >= 0 }?.let { IfCountAbove(it) }
                else -> null
            }
        }
    }
}

/** A parsed spec 7.3 graph: the [Dag] plus the fields the planner tiers read. */
data class Graph(val dag: Dag, val goal: String?, val confidence: Double?, val clarification: List<String>)

/**
 * Spec 7.3 graph JSON <-> [Dag] codec. One node = one [TaskSpec]; its `cap`, `input`, `axis`, `replicas`, `schema`,
 * `privacy`, `template`, `maxTokens`, `q` and anything under `params` live in `TaskSpec.params` under the [T2Params]
 * keys; `needs` and the edges become `inputs`; a conditional edge is kept as `params.when = {producerId: "<when>"}`
 * (only for conditions other than `always`); `owner` is the TaskSpec owner. The graph's `privacy` map gives each
 * node reading a named plan input its lineage. Nodes are re-ordered topologically (stable, so a cycle keeps the
 * written order for the compiler to report); `output` is the named node, else the last node nobody consumes.
 *
 * Cloud strict-schema mode cannot describe free-form objects or maps, so [parse] also accepts `schema` and `params`
 * as JSON strings, `privacy` as `[{input, privacy}]` and `clarification` as one string; [encode] always writes the
 * spec's object forms. There is no `exec` field: a node that carries one is kept so the compiler rejects it.
 */
object GraphJson {

    /** Keys of a node JSON object that are not op params. */
    private val NODE_KEYS = setOf("id", "op", "needs", "owner", "params")

    fun parse(e: JsonElement): Result<Graph> = runCatching {
        val g = e as? JsonObject ?: throw IllegalArgumentException("the graph is not a JSON object")
        val nodesJson = g["nodes"] as? JsonArray ?: throw IllegalArgumentException("the graph has no nodes array")
        if (nodesJson.isEmpty()) throw IllegalArgumentException("the graph has no nodes")
        val privacy = privacyMap(g["privacy"])
        val edges = (g["edges"] as? JsonArray ?: JsonArray(emptyList())).mapIndexed { i, x ->
            val o = x as? JsonObject ?: throw IllegalArgumentException("edge ${i + 1} is not an object")
            val from = str(o["from"]) ?: throw IllegalArgumentException("edge ${i + 1} has no from")
            val to = str(o["to"]) ?: throw IllegalArgumentException("edge ${i + 1} has no to")
            val w = str(o["when"]) ?: "always"
            val cond = EdgeCondition.parse(w) ?: throw IllegalArgumentException("edge $from -> $to: unknown condition '$w'")
            Triple(from, to, cond)
        }
        val tasks = nodesJson.mapIndexed { i, x ->
            val o = x as? JsonObject ?: throw IllegalArgumentException("node ${i + 1} is not an object")
            val id = str(o["id"])?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("node ${i + 1} has no id")
            val opName = str(o["op"]) ?: throw IllegalArgumentException("node '$id' has no op")
            val op = Op.entries.firstOrNull { it.name == opName.trim().uppercase() } ?: throw IllegalArgumentException("node '$id': unknown op '$opName'")
            val params = LinkedHashMap<String, JsonElement>()
            asObject(o["params"], "node '$id' params")?.let { params.putAll(it) }
            for ((k, v) in o) {
                if (k in NODE_KEYS || v is JsonNull) continue
                when (k) {
                    T2Params.SCHEMA -> params[k] = asObject(v, "node '$id' schema") ?: continue
                    T2Params.AXIS -> { val a = str(v) ?: continue; if (a != "none") params[k] = JsonPrimitive(a.lowercase()) }
                    else -> params[k] = v
                }
            }
            str(o["input"])?.let { input -> if (T2Params.PRIVACY !in params) privacy[input]?.let { params[T2Params.PRIVACY] = JsonPrimitive(it) } }
            val needs = LinkedHashSet<String>()
            (o["needs"] as? JsonArray)?.forEach { n -> str(n)?.let { needs += it } }
            val conditions = LinkedHashMap<String, String>()
            for ((from, to, cond) in edges) if (to == id) {
                needs += from
                if (cond != EdgeCondition.Always) conditions[from] = cond.encode()
            }
            if (conditions.isNotEmpty()) params[T2Params.WHEN] = JsonObject(conditions.mapValues { JsonPrimitive(it.value) })
            TaskSpec(id = id, op = op, params = JsonObject(params), inputs = needs.toList(), owner = str(o["owner"])?.takeIf { it.isNotBlank() })
        }
        val ordered = topological(tasks)
        val consumed = ordered.flatMap { it.inputs }.toSet()
        val output = str(g["output"]) ?: ordered.lastOrNull { it.id !in consumed }?.id ?: ordered.last().id
        val confidence = (g["confidence"] as? JsonPrimitive)?.doubleOrNull
        val clarification = when (val c = g["clarification"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> c.mapNotNull { str(it) }.filter { it.isNotBlank() }
            is JsonPrimitive -> listOfNotNull(c.content.takeIf { c.isString && it.isNotBlank() })
            else -> emptyList()
        }
        Graph(Dag(T2Params.PATTERN_ID, T2Params.VERSION, ordered, output), str(g["goal"]), confidence, clarification)
    }

    /** The spec 7.3 JSON of [dag] (object forms throughout), with the planner fields. */
    fun encode(dag: Dag, goal: String? = null, confidence: Double? = null, clarification: List<String> = emptyList()): JsonObject {
        val privacy = LinkedHashMap<String, JsonElement>()
        val nodes = dag.tasks.map { t ->
            val o = LinkedHashMap<String, JsonElement>()
            o["id"] = JsonPrimitive(t.id); o["op"] = JsonPrimitive(t.op.name)
            val input = str(t.params[T2Params.INPUT]); val priv = str(t.params[T2Params.PRIVACY])
            if (input != null && priv != null) privacy.putIfAbsent(input, JsonPrimitive(priv))
            for ((k, v) in t.params) if (k != T2Params.WHEN && !(k == T2Params.PRIVACY && input != null)) o[k] = v
            if (t.inputs.isNotEmpty()) o["needs"] = JsonArray(t.inputs.map { JsonPrimitive(it) })
            t.owner?.let { o["owner"] = JsonPrimitive(it) }
            JsonObject(o)
        }
        val edges = dag.tasks.flatMap { t ->
            val w = t.params[T2Params.WHEN] as? JsonObject
            t.inputs.map { from ->
                JsonObject(mapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(t.id), "when" to JsonPrimitive(str(w?.get(from)) ?: "always")))
            }
        }
        val out = LinkedHashMap<String, JsonElement>()
        out["goal"] = goal?.let { JsonPrimitive(it) } ?: JsonNull
        out["nodes"] = JsonArray(nodes)
        out["edges"] = JsonArray(edges)
        out["privacy"] = JsonObject(privacy)
        out["output"] = JsonPrimitive(dag.output)
        out["confidence"] = confidence?.let { JsonPrimitive(it) } ?: JsonNull
        out["clarification"] = if (clarification.isEmpty()) JsonNull else JsonArray(clarification.map { JsonPrimitive(it) })
        return JsonObject(out)
    }

    /** The conditional edges into [t] (producer id -> condition), empty for plain `always` inputs. */
    fun conditions(t: TaskSpec): Map<String, EdgeCondition> =
        (t.params[T2Params.WHEN] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { EdgeCondition.parse(it) }?.let { k to it } }?.toMap() ?: emptyMap()

    /** Stable topological order (Kahn, ties by written order); a cycle leaves the remaining nodes in written order. */
    fun topological(tasks: List<TaskSpec>): List<TaskSpec> {
        val ids = tasks.map { it.id }.toSet()
        val remaining = tasks.toMutableList()
        val placed = LinkedHashSet<String>()
        val out = ArrayList<TaskSpec>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { t -> t.inputs.all { it !in ids || it in placed } } ?: break
            remaining.remove(next); placed += next.id; out += next
        }
        return out + remaining
    }

    private fun privacyMap(e: JsonElement?): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        when (e) {
            is JsonObject -> for ((k, v) in e) str(v)?.let { m[k] = it.lowercase() }
            is JsonArray -> e.forEach { x -> (x as? JsonObject)?.let { o -> val i = str(o["input"]); val p = str(o["privacy"]); if (i != null && p != null) m[i] = p.lowercase() } }
            else -> {}
        }
        return m
    }

    /** An object, or an object encoded as a JSON string (strict cloud schemas cannot hold free-form objects). */
    private fun asObject(e: JsonElement?, what: String): JsonObject? = when (e) {
        null, is JsonNull -> null
        is JsonObject -> e
        is JsonPrimitive -> if (e.isString) {
            if (e.content.isBlank()) null
            else runCatching { Canon.json.parseToJsonElement(e.content) }.getOrNull() as? JsonObject ?: throw IllegalArgumentException("$what is not a JSON object")
        } else throw IllegalArgumentException("$what is not a JSON object")
        else -> throw IllegalArgumentException("$what is not a JSON object")
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.content?.takeIf { e !is JsonNull }
}
