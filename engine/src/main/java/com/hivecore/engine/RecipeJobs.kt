package com.hivecore.engine

import com.hivecore.core.Op
import com.hivecore.core.Recipe
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Split jobs for recipes that take no input files (workload/Recipes `input`): a prompt recipe binds `{item}` to the
 * prompt and fans out over seeds (GEN_IMAGE/GEN_VARIANTS) or runs one item; an items recipe (`input: none`, the EVAL
 * sweep) shards the given items under the stage's key. The splitter runs every stage on the same input, so a stage
 * that consumes an earlier stage's output (`inputs` non-empty) is not run here and is reported as skipped.
 */
object RecipeJobs {
    class Staged(val jobs: List<SplitJob>, val skipped: List<String>)

    fun promptJobs(jobId: String, r: Recipe, prompt: String, owner: String, private: Boolean): Staged {
        val (runnable, skipped) = r.stages.partition { it.inputs.isEmpty() }
        val jobs = runnable.map { st ->
            val params = bind(st.params, prompt) as JsonObject
            val input: SplitInput = when (st.op) {
                Op.GEN_IMAGE, Op.GEN_VARIANTS -> SplitInput.Seeds(
                    n = (params["n"] as? JsonPrimitive)?.intOrNull ?: 1,
                    base = (params["seed"] as? JsonPrimitive)?.longOrNull ?: 0L, private = private)
                else -> SplitInput.Items(listOf(JsonPrimitive(prompt)), key = "items", private = private)
            }
            SplitJob("$jobId/${st.id}", st.op, JsonObject(params - "n"), st.modelId, input, owner = owner)
        }
        return Staged(jobs, skipped.map { it.id })
    }

    fun itemJobs(jobId: String, r: Recipe, items: List<JsonElement>, owner: String, private: Boolean): Staged {
        val (runnable, skipped) = r.stages.partition { it.inputs.isEmpty() }
        val jobs = runnable.map { st ->
            val key = if (st.op == Op.EVAL) "cases" else "items"
            val params = JsonObject(st.params.filterKeys { it != key && it != "item" })
            SplitJob("$jobId/${st.id}", st.op, params, st.modelId, SplitInput.Items(items, key = key, private = private), owner = owner,
                unitMs = if (st.op == Op.BENCH) com.hivecore.engine.split.SplitConstants.UNIT_MS_BENCH else null)
        }
        return Staged(jobs, skipped.map { it.id })
    }

    /** Replaces `{item}` in every string of [e] with [value]. */
    fun bind(e: JsonElement, value: String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { bind(it.value, value) })
        is JsonArray -> JsonArray(e.map { bind(it, value) })
        is JsonPrimitive -> if (e.isString && "{item}" in e.content) JsonPrimitive(e.content.replace("{item}", value)) else e
        else -> e
    }
}
