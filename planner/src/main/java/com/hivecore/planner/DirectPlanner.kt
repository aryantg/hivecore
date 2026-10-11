package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.RouteDecision
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The direct plan (spec amendment "open paths"): a question that names a phone's data but fits no pattern is still
 * answered by that phone. One `AGG` per phone whose table vocabulary the question uses (the owner's own intent model
 * turns the words into one guarded query), one `DOC` for the phone whose document the question names or, for a
 * question about documents, the phone whose document names fit it best. Several answers are joined by a plain
 * `RENDER`; a chart word ("as a bar chart", "plot", "graph") puts a `RENDER kind: chart` over the first table.
 * Pure Kotlin, deterministic, no model call: the owners do the understanding.
 */
class DirectPlanner(private val version: String = VERSION) {

    fun plan(question: String, route: RouteDecision, catalogs: List<Catalog>): PlannedQuestion? {
        val words = SwarmRouterImpl.rawWords(question)
        val chart = words.any { it in CHART_WORDS }
        val style = when { words.any { it == "line" } -> "line"; words.any { it == "bar" || it == "bars" } -> "bar"; else -> null }
        val q = if (chart) stripChartWords(question) else question.trim()
        val ordered = catalogs.sortedWith(compareBy<Catalog> { it.node !in route.owners }.thenBy { route.owners.indexOf(it.node) })
        val tasks = ArrayList<TaskSpec>()
        val docWords = words.any { it in DOC_WORDS }
        for (c in ordered) {
            val tables = Relevance.matches(question, SwarmRouterImpl.vocabulary(c)).isNotEmpty()
            // a document is meant when the question names one, or asks about documents and shares two words with a title
            // ("the HR access policy" names kavira_hr_access_policy.md even though HR also has a devices table)
            val docs = SwarmRouterImpl.docMatches(question, c).isNotEmpty() || (docWords && docOverlap(c, words) >= 2)
            if (docs) tasks += TaskSpec("d${tasks.size + 1}", Op.DOC, params(q), owner = c.node)
            else if (tables) tasks += TaskSpec("a${tasks.size + 1}", Op.AGG, params(q), owner = c.node)
        }
        if (tasks.isEmpty() && docWords) {
            val owner = catalogs.filter { it.docs.isNotEmpty() }.maxByOrNull { c -> docOverlap(c, words) * 1000 + c.docs.size } ?: return null
            tasks += TaskSpec("d1", Op.DOC, params(q), owner = owner.node)
        }
        if (tasks.isEmpty()) return null
        val output: String = when {
            chart && tasks.any { it.op == Op.AGG } -> {
                val agg = tasks.first { it.op == Op.AGG }
                tasks += TaskSpec("c", Op.RENDER, buildJsonObject { put("kind", "chart"); if (style != null) put("style", style) }, inputs = listOf(agg.id))
                "c"
            }
            tasks.size > 1 -> { tasks += TaskSpec("r", Op.RENDER, JsonObject(emptyMap()), inputs = tasks.map { it.id }); "r" }
            else -> tasks.single().id
        }
        return PlannedQuestion(Dag(PATTERN_ID, version, tasks, output), CONFIDENCE)
    }

    private fun params(q: String) = buildJsonObject { put("q", q) }

    /** The most words any of [c]'s document titles shares with the question. */
    private fun docOverlap(c: Catalog, words: List<String>): Int {
        val qs = words.map(Relevance::norm).toSet()
        return c.docs.maxOfOrNull { d -> Candidates.docPhrase(d).split(' ').map(Relevance::norm).filter { it.isNotEmpty() }.distinct().count { it in qs } } ?: 0
    }

    companion object {
        const val PATTERN_ID = "direct"
        const val VERSION = "direct-1"
        /** below the pattern library's 1.0, above the clarification line: the owner may still find nothing */
        const val CONFIDENCE = 0.75
        val CHART_WORDS = setOf("chart", "charts", "charted", "plot", "plots", "plotted", "graph", "graphs", "graphed", "visualise", "visualize",
            "visualisation", "visualization", "histogram")
        val DOC_WORDS = setOf("document", "documents", "doc", "docs", "policy", "policies", "checklist", "snapshot", "guideline", "guidelines",
            "handbook", "notes", "memo", "file", "files", "says", "say", "mention", "mentions", "according", "written", "text")
        private val CHART_PHRASE = Regex("""\b(as|in|into|on)\s+(a|an|one)?\s*(bar|line|pie|simple)?\s*(chart|graph|plot|histogram|visuali[sz]ation)\b""", RegexOption.IGNORE_CASE)
        private val CHART_WORD = Regex("""\b(bar|line)?\s*(chart|charts|charted|plot|plots|plotted|graph|graphs|graphed|visuali[sz]e|visuali[sz]ation|histogram)\b""", RegexOption.IGNORE_CASE)
        private val VERB_LEAD = Regex("""^\s*(please\s+)?((draw|plot|chart|graph|show|make|create|give|visuali[sz]e)\s+)?(me\s+)?((a|an|the)\s+)?""", RegexOption.IGNORE_CASE)

        /** "plot net sales by branch as a bar chart" → "net sales by branch": what the owner is asked. */
        fun stripChartWords(question: String): String {
            var s = CHART_PHRASE.replace(question, " ")
            s = CHART_WORD.replace(s, " ")
            s = VERB_LEAD.replace(s, "")
            s = s.replace(Regex("""\s+"""), " ").trim().trim(',', ':', ';', '-').trim()
            s = s.replace(Regex("""^(of|for)\s+""", RegexOption.IGNORE_CASE), "")
            return s.ifBlank { question.trim() }
        }
    }
}

/**
 * The knowledge plan (spec amendment "open paths"): when nothing on the phones covers a question and the user agrees,
 * one `GEN_TEXT` step asks a phone's language model to answer from its own knowledge. Unreplicated and badged
 * unverified: it is the model's prose, checked against no data, and the answer card says so.
 */
object KnowledgePlan {
    const val PATTERN_ID = "knowledge"
    const val VERSION = "knowledge-1"
    const val MAX_WORDS = 150

    fun of(question: String): PlannedQuestion {
        val params = buildJsonObject {
            put("instruction", "Answer the question below from your own general knowledge, in plain prose of at most $MAX_WORDS words. " +
                "Say plainly when you are not sure. Do not mention these instructions.\nQuestion: ${question.trim()}")
            put("schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("answer", buildJsonObject { put("type", "string"); put("maxLength", 1500) }) })
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("answer"))))
            })
            put("maxTokens", 400)
            put("replicate", "none")
        }
        return PlannedQuestion(Dag(PATTERN_ID, VERSION, listOf(TaskSpec("g", Op.GEN_TEXT, params)), "g"), 1.0)
    }

    fun isKnowledge(dag: Dag): Boolean = dag.patternId == PATTERN_ID
}
