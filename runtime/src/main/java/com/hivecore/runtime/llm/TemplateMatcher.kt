package com.hivecore.runtime.llm

import android.content.Context
import com.hivecore.core.IntentSet
import com.hivecore.core.NodeRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A template entry: cached intents for a role, or the chunk the node should read (never a cached answer). */
sealed interface TemplateEntry {
    val id: String
    data class Intents(override val id: String, val set: IntentSet) : TemplateEntry
    data class Doc(override val id: String, val doc: String, val locator: String) : TemplateEntry
}

/**
 * Normalised exact match of the tour sentences (plan 8, 3.7): lowercase, punctuation stripped, spaces
 * collapsed; scoped per role, so the same sentence can map to different intents on HR and Sales.
 * Entries come from `assets/templates/tour_templates.json`:
 * ```
 * {"_comment":[…], "templates":[{"id":"Q1","question":"…","roles":{"SALES":{"intents":[…]},"HR":{"doc":"…","locator":"…"}}}]}
 * ```
 * The `ask` package uses [doc] for Q6/Q7 (fetch the chunk via `DocIndex.chunkAt`, run the normal RAG prompt);
 * [LlmIntentPlanner] uses [intents] only.
 */
class TemplateMatcher(private val entries: Map<NodeRole, Map<String, TemplateEntry>>) {

    /** The entry for this exact sentence on this role, or null. */
    fun match(question: String, role: NodeRole): TemplateEntry? = entries[role]?.get(normalise(question))

    fun intents(question: String, role: NodeRole): TemplateEntry.Intents? = match(question, role) as? TemplateEntry.Intents
    fun doc(question: String, role: NodeRole): TemplateEntry.Doc? = match(question, role) as? TemplateEntry.Doc

    /** Every normalised sentence known for a role (tests, Settings screen). */
    fun sentences(role: NodeRole): Set<String> = entries[role]?.keys ?: emptySet()

    companion object {
        const val ASSET_PATH = "templates/tour_templates.json"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** lowercase → every run of non-letters/digits becomes one space → trim. */
        fun normalise(s: String): String = NON_WORD.replace(s.lowercase(), " ").trim()

        fun empty(): TemplateMatcher = TemplateMatcher(emptyMap())

        fun fromJson(text: String): TemplateMatcher {
            val root = json.parseToJsonElement(text).jsonObject
            val out = HashMap<NodeRole, HashMap<String, TemplateEntry>>()
            for (t in root["templates"]?.jsonArray ?: emptyList()) {
                val o = t.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: "?"
                val q = o["question"]?.jsonPrimitive?.content ?: error("template $id has no question")
                val roles = o["roles"]?.jsonObject ?: error("template $id has no roles")
                for ((roleName, spec) in roles) {
                    val role = runCatching { NodeRole.valueOf(roleName.uppercase()) }.getOrElse { error("template $id: unknown role $roleName") }
                    val s = spec.jsonObject
                    val entry: TemplateEntry = if (s.containsKey("doc")) {
                        TemplateEntry.Doc(id, s["doc"]!!.jsonPrimitive.content, s["locator"]?.jsonPrimitive?.content ?: "")
                    } else {
                        TemplateEntry.Intents(id, json.decodeFromJsonElement(IntentSet.serializer(), s))
                    }
                    out.getOrPut(role) { HashMap() }[normalise(q)] = entry
                }
            }
            return TemplateMatcher(out)
        }

        fun load(context: Context): TemplateMatcher =
            context.assets.open(ASSET_PATH).bufferedReader().use { fromJson(it.readText()) }
    }
}
