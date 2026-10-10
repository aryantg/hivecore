package com.hivecore.node.devai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One golden test case (swarm plan §4A.3 EVAL, §11): a question, the expected outcome kind and, when the set gives
 * structured expectations, the numbers the answer text must contain (compared after the renderer's formatting is
 * parsed back, at two decimals). [id] is stable per source file and position, so shards and merges are ordered by it.
 */
@Serializable data class EvalCase(
    val id: String,
    val q: String,
    /** "answer" | "refusal" | "nonode" */
    val expect: String,
    val pattern: String? = null,
    val nodes: List<String> = emptyList(),
    val values: List<Double> = emptyList(),
    val note: String? = null,
) {
    init { require(expect in KINDS) { "expect must be one of $KINDS, got '$expect'" } }

    companion object { val KINDS = listOf("answer", "refusal", "nonode") }
}

/**
 * Loaders for `tools/questions_50.json` (`{"_about", "questions": [{q, expect, nodes, note, pattern?, expected?}]}`)
 * and `tools/questions_composite.json` (`[{q, expect, pattern, nodes, expected}]`). Bulk entries ("Bulk: …", the
 * eval sweep and OCR inbox) are workloads, not questions, and are skipped unless [includeBulk].
 */
object EvalCases {
    private val json = Json { ignoreUnknownKeys = true }

    /** Either file format, detected from the top-level shape. [prefix] names the set in the ids ("q50", "comp"). */
    fun parse(text: String, prefix: String, includeBulk: Boolean = false): List<EvalCase> {
        val root = json.parseToJsonElement(text)
        val rows: List<JsonObject> = when (root) {
            is JsonObject -> root["questions"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            is JsonArray -> root.map { it.jsonObject }
            else -> emptyList()
        }
        return rows.mapIndexedNotNull { i, o ->
            val q = str(o["q"]) ?: return@mapIndexedNotNull null
            val pattern = str(o["pattern"])
            if (!includeBulk && isBulk(q, pattern)) return@mapIndexedNotNull null
            val explicit = (o["values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            EvalCase(
                id = "%s:%02d".format(prefix, i + 1),
                q = q,
                expect = str(o["expect"]) ?: "answer",
                pattern = pattern,
                nodes = (o["nodes"] as? JsonArray)?.mapNotNull { str(it) } ?: emptyList(),
                values = explicit ?: o["expected"]?.let { valuesOf(pattern, it) } ?: emptyList(),
                note = str(o["note"]),
            )
        }
    }

    fun questions50(text: String): List<EvalCase> = parse(text, "q50")
    fun composite(text: String): List<EvalCase> = parse(text, "comp")

    fun isBulk(q: String, pattern: String?): Boolean =
        q.startsWith("Bulk:", ignoreCase = true) || pattern == "eval_sweep" || pattern?.startsWith("bulk_") == true

    /**
     * The numbers an answer must show, by the shape of `expected`: a per_unit object → its `ratio` values; a ranked
     * list [{key, value}] → the values; `top` (+ `commission_avg`, `company_avg`) → those; flag_vs_average
     * (`by` + `overall`) → the per-group values and the overall; compare_two (`a`, `b`, `diff`) → the three;
     * growth_over_period ({group: [{month, value, …}]}) → every `value`. Anything else (doc extracts, reconcile
     * lists, refusal reasons) grades on the outcome kind alone.
     */
    fun valuesOf(pattern: String?, expected: JsonElement): List<Double> {
        fun nums(e: JsonElement?): List<Double> = when (e) {
            is JsonPrimitive -> listOfNotNull(e.takeIf { !it.isString }?.doubleOrNull)
            is JsonObject -> e.values.flatMap { nums(it) }
            is JsonArray -> e.flatMap { nums(it) }
            else -> emptyList()
        }
        return when (expected) {
            is JsonArray -> expected.mapNotNull { (it as? JsonObject)?.get("value")?.let { v -> nums(v).firstOrNull() } }
            is JsonObject -> when {
                "ratio" in expected -> nums(expected["ratio"])
                "top" in expected -> nums((expected["top"] as? JsonObject)?.get("value")) + nums(expected["commission_avg"]) + nums(expected["company_avg"])
                "by" in expected && "overall" in expected -> nums(expected["by"]) + nums(expected["overall"])
                "a" in expected && "b" in expected && "diff" in expected -> nums(expected["a"]) + nums(expected["b"]) + nums(expected["diff"])
                pattern == "growth_over_period" -> expected.values.flatMap { g ->
                    (g as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("value")?.let { v -> nums(v).firstOrNull() } } ?: emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    fun toJson(c: EvalCase): JsonElement = json.encodeToJsonElement(EvalCase.serializer(), c)
    fun fromJson(e: JsonElement): EvalCase = json.decodeFromJsonElement(EvalCase.serializer(), e)

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
