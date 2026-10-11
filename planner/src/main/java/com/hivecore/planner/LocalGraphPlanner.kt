package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.LlmEngine
import com.hivecore.core.Op
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.SLog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The on-device graph planner (spec amendment "open paths", tier T1G): the phone's own language model writes a small
 * spec 7.3 graph over the ops the swarm runs, from the question and the catalogs (names and flags only, which never
 * leave the swarm). The same compiler checks it and one repair round follows, as for the cloud planner, so nothing
 * the model invents runs unchecked: the owners still answer every AGG/DOC step through their guarded query path.
 * The op list is the analysis subset, described in a sentence each, which a 4B model can compose reliably.
 */
class LocalGraphPlanner(
    private val llm: LlmEngine,
    private val compiler: PlanCompilerImpl,
    private val selfId: String? = null,
    private val modelHash: () -> String = { "" },
    private val maxTokens: Int = 700,
) : T2Planner {

    override suspend fun plan(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>): Result<PlannedQuestion> = runCatching {
        val base = messages(question, catalogs, opRegistry)
        val first = propose(base)
        val c1 = compile(first, catalogs, capabilities, opRegistry)
        if (c1.isSuccess) return@runCatching c1.getOrThrow()
        val errors = errorList(c1)
        SLog.d(TAG, "graph rejected: ${errors.joinToString("; ")}; one repair round")
        val second = propose(base + listOf(ChatMsg("assistant", Canon.canonical(first)), ChatMsg("user", repairNote(errors))))
        val c2 = compile(second, catalogs, capabilities, opRegistry)
        if (c2.isFailure) throw IllegalStateException("the on-device planner's graph was rejected twice: ${errorList(c2).joinToString("; ")}")
        c2.getOrThrow()
    }

    override suspend fun repair(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>,
                                previous: JsonElement, errors: List<String>): Result<PlannedQuestion> = runCatching {
        val g = propose(messages(question, catalogs, opRegistry) + listOf(ChatMsg("assistant", Canon.canonical(previous)), ChatMsg("user", repairNote(errors))))
        val c = compile(g, catalogs, capabilities, opRegistry)
        if (c.isFailure) throw IllegalStateException("the repaired graph was rejected: ${errorList(c).joinToString("; ")}")
        c.getOrThrow()
    }

    private suspend fun propose(messages: List<ChatMsg>): JsonElement {
        val raw = llm.generate(messages, GenParams(maxTokens = maxTokens, stopOnBalancedJson = true)).getOrThrow().text
        val json = extractObject(raw) ?: throw IllegalStateException("the model did not return a JSON graph")
        return Canon.json.parseToJsonElement(json)
    }

    private fun compile(graphJson: JsonElement, catalogs: List<Catalog>, capabilities: Set<String>, ops: Set<Op>): Result<PlannedQuestion> {
        val g = GraphJson.parse(graphJson).getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "graph does not parse")) }
        if (g.dag.tasks.isEmpty()) return Result.failure(IllegalArgumentException("the model found no step over the listed data"))
        val c = compiler.validateT2(g.dag, g.confidence, catalogs, modelHash(), capabilities, ops, selfId)
        if (!c.ok) return Result.failure(IllegalArgumentException(c.check.errors.joinToString("; ")))
        return Result.success(PlannedQuestion(g.dag, g.confidence ?: DEFAULT_CONFIDENCE, g.clarification))
    }

    private fun errorList(r: Result<*>): List<String> = r.exceptionOrNull()?.message?.split("; ")?.filter { it.isNotBlank() } ?: listOf("unknown error")

    private fun repairNote(errors: List<String>) =
        "The compiler rejected that graph:\n" + errors.joinToString("\n") { "- $it" } + "\nReturn a corrected graph in the same format, JSON only."

    /** The system rule, one worked example, then the question with the catalogs (names and flags only). */
    fun messages(question: String, catalogs: List<Catalog>, ops: Set<Op>): List<ChatMsg> {
        val available = OP_LINES.filterKeys { it in ops || it == Op.RENDER }
        val system = buildString {
            append(SYSTEM_HEAD)
            for ((op, line) in available) append("- ").append(op.name).append(": ").append(line).append('\n')
            append(SYSTEM_RULES)
        }
        val payload = buildJsonObject {
            put("question", question.trim())
            putJsonArray("phones") {
                for (c in catalogs.sortedBy { it.node }) add(buildJsonObject {
                    put("id", c.node); put("name", c.nodeName)
                    putJsonArray("tables") { for (t in c.tables) add(buildJsonObject {
                        put("name", t.name)
                        putJsonArray("columns") { for (col in t.columns) add(buildJsonObject {
                            put("name", col.name); put("type", col.type.name.lowercase())
                            if (col.groupable) put("groupable", true)
                            if (col.confidential) put("confidential", true)
                        }) }
                    }) }
                    putJsonArray("docs") { for (d in c.docs) add(JsonPrimitive(d)) }
                })
            }
        }
        return listOf(ChatMsg("system", system), ChatMsg("user", EXAMPLE_Q), ChatMsg("assistant", EXAMPLE_A), ChatMsg("user", Canon.canonical(payload)))
    }

    companion object {
        const val TAG = "LocalGraphPlanner"
        const val DEFAULT_CONFIDENCE = 0.7

        /** One line per op the model may use, in the words of the analysis it does. */
        val OP_LINES: Map<Op, String> = linkedMapOf(
            Op.AGG to "one aggregate a phone works out from one of its tables; params.q = the question in plain words using that table's column names; owner = that phone's id; no needs",
            Op.DOC to "an answer read from a phone's documents; params.q = the question; owner = that phone's id; no needs",
            Op.RATIO to "first input divided by the second, per matching group; needs two table steps; params.num and params.den name each input's result column: sum_<column>, avg_<column>, or n for a count",
            Op.DIFF to "first input minus the second, per matching group; needs two table steps; params.num and params.den as for RATIO",
            Op.SHARE to "each group's share of the total in percent; needs one table step; params.num names its result column (sum_<column> or n)",
            Op.TREND to "the change between consecutive rows of a monthly table; params.measure = the column; needs one table step",
            Op.RANK to "the top or bottom groups; params.measure, params.k (a number), params.order 'desc' or 'asc'; needs one table step",
            Op.THRESHOLD to "groups above or below a value or the average; params.measure, params.cmp 'gt' or 'lt', params.value (a number or 'avg'); needs one table step",
            Op.RECONCILE to "whether two tables' counts per group agree; needs two table steps",
            Op.RENDER to "the answer step; params.kind 'chart' draws the first input table (params.style 'bar' or 'line'); otherwise it says its inputs",
        )

        val SYSTEM_HEAD = """
            You plan a question over the tables and documents held on a few phones as a small graph of steps. Reply with one JSON object only, no prose.
            Steps you may use:
        """.trimIndent() + "\n"

        val SYSTEM_RULES = """
            Rules: AGG and DOC steps have no needs and run on the phone that holds the table (owner = its id); every other step lists in needs the ids of the steps it reads, in order; ask one phone for one aggregate per AGG step and never for rows; a confidential column may only be averaged or counted; at most 6 steps; the last step is the answer and is named in output; if the question is not about the listed data reply {"nodes":[]}.
            Format: {"goal":"<one line>","nodes":[{"id":"a","op":"AGG","owner":"<phone id>","params":{"q":"<question for that phone>"}},{"id":"r","op":"RATIO","needs":["a","b"],"params":{"num":"sum_revenue","den":"n"}}],"output":"r","confidence":<0 to 1>}
        """.trimIndent()

        val EXAMPLE_Q: String = Canon.canonical(buildJsonObject {
            put("question", "Revenue per rep by region this year")
            putJsonArray("phones") {
                add(buildJsonObject {
                    put("id", "node-sales"); put("name", "Sales")
                    putJsonArray("tables") { add(buildJsonObject { put("name", "sales"); putJsonArray("columns") {
                        add(buildJsonObject { put("name", "region"); put("type", "text"); put("groupable", true) })
                        add(buildJsonObject { put("name", "month"); put("type", "text"); put("groupable", true) })
                        add(buildJsonObject { put("name", "revenue"); put("type", "number") }) } }) }
                    putJsonArray("docs") {}
                })
                add(buildJsonObject {
                    put("id", "node-hr"); put("name", "HR")
                    putJsonArray("tables") { add(buildJsonObject { put("name", "employees"); putJsonArray("columns") {
                        add(buildJsonObject { put("name", "region"); put("type", "text"); put("groupable", true) })
                        add(buildJsonObject { put("name", "role"); put("type", "text"); put("groupable", true) })
                        add(buildJsonObject { put("name", "salary"); put("type", "number"); put("confidential", true) }) } }) }
                    putJsonArray("docs") { add(JsonPrimitive("leave_policy.md")) }
                })
            }
        })

        val EXAMPLE_A: String = Canon.canonical(buildJsonObject {
            put("goal", "revenue per rep by region this year")
            putJsonArray("nodes") {
                add(buildJsonObject { put("id", "a"); put("op", "AGG"); put("owner", "node-sales"); putJsonObject("params") { put("q", "revenue by region this year") } })
                add(buildJsonObject { put("id", "b"); put("op", "AGG"); put("owner", "node-hr"); putJsonObject("params") { put("q", "number of reps by region") } })
                add(buildJsonObject { put("id", "r"); put("op", "RATIO"); putJsonArray("needs") { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }; putJsonObject("params") { put("key", "region"); put("num", "sum_revenue"); put("den", "n") } })
            }
            put("output", "r")
            put("confidence", 0.9)
        })

        /** The first balanced JSON object in [text] (a model may wrap it in prose or a code fence). */
        fun extractObject(text: String): String? {
            val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
            var depth = 0; var inString = false; var escape = false
            for (i in start until text.length) {
                val ch = text[i]
                when {
                    escape -> escape = false
                    inString && ch == '\\' -> escape = true
                    ch == '"' -> inString = !inString
                    inString -> Unit
                    ch == '{' -> depth++
                    ch == '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                }
            }
            return null
        }
    }
}
