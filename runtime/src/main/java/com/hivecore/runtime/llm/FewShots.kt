package com.hivecore.runtime.llm

import android.content.Context
import com.hivecore.core.NodeRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One few-shot example: the user question and the exact JSON the assistant should answer with. */
data class FewShot(val question: String, val answerJson: String)

/**
 * Loads `assets/fewshots/<role>.json` (plan 8). File shape (also documented inside each file):
 * ```
 * {"_comment":[…], "role":"HR", "shots":[{"question":"…", "answer":{"intents":[…]}}, …]}
 * ```
 * `answer` is a JSON object (an IntentSet) and is re-encoded compactly so the prompt is deterministic.
 * The evening close may append up to 3 mined rows through [LlmIntentPlanner]'s provider; this loader only
 * knows the fixed three per role.
 */
object FewShots {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun assetPath(role: NodeRole): String = "fewshots/${role.name.lowercase()}.json"

    fun parse(text: String): List<FewShot> {
        val root = json.parseToJsonElement(text).jsonObject
        val shots = root["shots"]?.jsonArray ?: return emptyList()
        return shots.map { el ->
            val o = el.jsonObject
            val q = o["question"]?.jsonPrimitive?.content ?: error("few-shot without a question")
            val a = o["answer"] ?: error("few-shot '$q' without an answer")
            FewShot(q, json.encodeToString(JsonObject.serializer(), a.jsonObject))
        }
    }

    fun load(context: Context, role: NodeRole): List<FewShot> =
        context.assets.open(assetPath(role)).bufferedReader().use { parse(it.readText()) }
}
