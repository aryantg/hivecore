package com.hivecore.cloud

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Op
import com.hivecore.core.OpRegistry
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.SLog
import com.hivecore.planner.GraphJson
import com.hivecore.planner.PlanCompilerImpl
import com.hivecore.planner.PlanKeys
import com.hivecore.planner.T2Params
import com.hivecore.planner.T2Planner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The T2 planner failed twice (spec 7.4); the facade falls back to T1's best candidate. */
class T2PlanFailed(message: String, val errors: List<String>) : Exception(message)

/**
 * Spec 7.2-7.4: the T2 cloud planner. The payload is the question text, the swarm capability summary, the op
 * registry (name, class, capability, axes, shapes) and, only when [schemaSharing] is on, the catalogs as names and
 * flags (never values or row counts). The model answers in the spec 7.3 graph schema under a strict `response_format`
 * ([GRAPH_SCHEMA]), which [GraphJson] converts to a [com.hivecore.core.Dag]. The graph is compiled; a failure gets one
 * [repair] round carrying the graph and the compiler's error list; a second failure is a [T2PlanFailed] the facade
 * renders as T1's best candidate plus a clarification. The planner role's spend is booked under `plan:<key>`.
 */
class CloudPlannerImpl(
    private val cloud: CloudClient,
    private val config: ModelsConfig,
    private val compiler: PlanCompilerImpl,
    private val schemaSharing: () -> Boolean = { false },
    private val selfId: String? = null,
    private val modelHash: () -> String = { "" },
    private val caps: SpendCaps? = null,
) : T2Planner {

    override suspend fun plan(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>): Result<PlannedQuestion> = runCatching {
        val jobId = "plan:" + PlanKeys.key(question, catalogs, capabilities).take(16)
        val first = propose(jobId, baseMessages(question, catalogs, capabilities, opRegistry))
        val c1 = compile(first, catalogs, capabilities, opRegistry)
        if (c1.isSuccess) return@runCatching c1.getOrThrow()
        val errors1 = c1.exceptionOrNull()!!.message!!
        SLog.d(TAG, "T2 graph rejected: $errors1; one repair round")
        val second = repairOnce(jobId, question, catalogs, capabilities, opRegistry, first, errorList(c1))
        val c2 = compile(second, catalogs, capabilities, opRegistry)
        if (c2.isFailure) throw T2PlanFailed("the cloud planner's graph was rejected twice: ${errorList(c2).joinToString("; ")}", errorList(c2))
        c2.getOrThrow()
    }

    override suspend fun repair(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>,
                                previous: JsonElement, errors: List<String>): Result<PlannedQuestion> = runCatching {
        val jobId = "plan:" + PlanKeys.key(question, catalogs, capabilities).take(16)
        val g = repairOnce(jobId, question, catalogs, capabilities, opRegistry, previous, errors)
        val c = compile(g, catalogs, capabilities, opRegistry)
        if (c.isFailure) throw T2PlanFailed("the repaired graph was rejected: ${errorList(c).joinToString("; ")}", errorList(c))
        c.getOrThrow()
    }

    private suspend fun repairOnce(jobId: String, question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>,
                                   previous: JsonElement, errors: List<String>): JsonElement =
        propose(jobId, baseMessages(question, catalogs, capabilities, opRegistry) + listOf(
            Msg("assistant", Canon.canonical(previous)),
            Msg("user", "The compiler rejected that graph:\n" + errors.joinToString("\n") { "- $it" } + "\nReturn a corrected graph in the same format."),
        ))

    private suspend fun propose(jobId: String, messages: List<Msg>): JsonElement {
        val role = CloudRole.PLANNER
        val maxTokens = config.role(role).maxTokens
        caps?.let { c ->
            val est = config.estimateInr(config.primary(role), messages.sumOf { it.content.length.toLong() } / 4, maxTokens.toLong())
            c.check(role, jobId, est)?.let { throw T2PlanFailed(it, listOf(it)) }
        }
        val reply = cloud.complete(role, messages, GRAPH_SCHEMA, maxTokens).getOrThrow()
        caps?.record(jobId, reply.costUsd?.let { it * config.inrPerUsd } ?: config.estimateInr(reply.slug, reply.tokensIn.toLong(), reply.tokensOut.toLong()))
        return reply.output
    }

    /** Parse + compile; a failure's message is the `; `-joined error list. */
    private fun compile(graphJson: JsonElement, catalogs: List<Catalog>, capabilities: Set<String>, ops: Set<Op>): Result<PlannedQuestion> {
        val g = GraphJson.parse(graphJson).getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "graph does not parse")) }
        val c = compiler.validateT2(g.dag, g.confidence, catalogs, modelHash(), capabilities, ops, selfId)
        if (!c.ok) return Result.failure(IllegalArgumentException(c.check.errors.joinToString("; ")))
        return Result.success(PlannedQuestion(g.dag, g.confidence ?: 0.0, g.clarification))
    }

    private fun errorList(r: Result<*>): List<String> = r.exceptionOrNull()?.message?.split("; ")?.filter { it.isNotBlank() } ?: listOf("unknown error")

    /** The messages of spec 7.2 row T2: question, capability summary, op registry, catalog names and flags when shared. */
    fun baseMessages(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>): List<Msg> {
        val payload = buildJsonObject {
            put("question", question.trim())
            put("capabilities", JsonArray(capabilities.sorted().map { JsonPrimitive(it) }))
            put("ops", JsonArray(opRegistry.sortedBy { it.name }.map { op ->
                val i = OpRegistry[op]
                buildJsonObject {
                    put("op", op.name); put("class", i.cls.name); put("capability", i.capability?.let { JsonPrimitive(it) } ?: JsonNull)
                    putJsonObject("axes") { for ((a, m) in i.axes) put(a, m.name) }
                    put("arity", if (i.arity.last == Int.MAX_VALUE) "${i.arity.first}+" else "${i.arity.first}..${i.arity.last}")
                    put("input", i.inputShape); put("output", i.outputShape)
                }
            }))
            if (schemaSharing()) put("catalog", JsonArray(catalogs.sortedBy { it.node }.map { c ->
                buildJsonObject {
                    put("node", c.node); put("name", c.nodeName)
                    putJsonArray("tables") { for (t in c.tables) add(buildJsonObject {
                        put("name", t.name)
                        putJsonArray("columns") { for (col in t.columns) add(buildJsonObject {
                            put("name", col.name); put("type", col.type.name); put("groupable", col.groupable)
                            put("confidential", col.confidential); put("identifier", col.identifier)
                        }) }
                    }) }
                    putJsonArray("docs") { for (d in c.docs) add(d) }
                }
            }))
        }
        return listOf(Msg("system", SYSTEM), Msg("user", Canon.canonical(payload)))
    }

    companion object {
        const val TAG = "CloudPlanner"

        val SYSTEM: String = """
            You plan a question over a swarm of phones as a graph of typed steps. Reply with one JSON object only.
            Fields: goal; nodes[]: {id, op (one of the listed ops), cap (a listed capability or null), input (a named plan input or null),
            axis ("shard" | "chunk" | "fanout" | null), replicas (2 for LLM/VLM ops, 1 when the input is private, else null),
            schema (a JSON Schema draft 2020-12 object encoded as a string, no ${'$'}ref; required for FIELDS and LLM_MAP), needs[] (ids of
            earlier nodes), owner (the node id that holds the data for AGG/DOC steps, else null), params (op params as a JSON string:
            LLM_MAP needs template with {item} and maxTokens; AGG/DOC need q; TREND/RANK need measure)}; edges[] {from, to, when:
            "always" | "if_empty" | "if_confidence_below:<x>" | "if_count_above:<n>"}; privacy[] {input, privacy: "public" | "released" |
            "private"}; confidence 0..1; clarification (a question for the user, or null).
            Rules: never add an exec field; a step over private data runs on its owner with replicas 1; at most 12 nodes and 6 LLM
            calls per phone; the last node is the answer; a confidential column may only be averaged or counted.
        """.trimIndent()

        /** The strict response schema of the spec 7.3 graph (object forms that strict mode cannot express are strings). */
        val GRAPH_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonArray("required") { add("goal"); add("nodes"); add("edges"); add("privacy"); add("confidence"); add("clarification") }
            putJsonObject("properties") {
                putJsonObject("goal") { put("type", "string") }
                putJsonObject("nodes") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object"); put("additionalProperties", false)
                        putJsonArray("required") { add("id"); add("op"); add("cap"); add("input"); add("axis"); add("replicas"); add("schema"); add("needs"); add("owner"); add("params") }
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("op") { put("type", "string") }
                            putJsonObject("cap") { putJsonArray("type") { add("string"); add("null") } }
                            putJsonObject("input") { putJsonArray("type") { add("string"); add("null") } }
                            putJsonObject("axis") { putJsonArray("type") { add("string"); add("null") }; putJsonArray("enum") { for (a in T2Params.AXES.sorted()) add(a); add(JsonNull) } }
                            putJsonObject("replicas") { putJsonArray("type") { add("integer"); add("null") } }
                            putJsonObject("schema") { putJsonArray("type") { add("string"); add("null") } }
                            putJsonObject("needs") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                            putJsonObject("owner") { putJsonArray("type") { add("string"); add("null") } }
                            putJsonObject("params") { putJsonArray("type") { add("string"); add("null") } }
                        }
                    }
                }
                putJsonObject("edges") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object"); put("additionalProperties", false)
                        putJsonArray("required") { add("from"); add("to"); add("when") }
                        putJsonObject("properties") {
                            putJsonObject("from") { put("type", "string") }; putJsonObject("to") { put("type", "string") }; putJsonObject("when") { put("type", "string") }
                        }
                    }
                }
                putJsonObject("privacy") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object"); put("additionalProperties", false)
                        putJsonArray("required") { add("input"); add("privacy") }
                        putJsonObject("properties") {
                            putJsonObject("input") { put("type", "string") }
                            putJsonObject("privacy") { put("type", "string"); putJsonArray("enum") { add("public"); add("released"); add("private") } }
                        }
                    }
                }
                putJsonObject("confidence") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
                putJsonObject("clarification") { putJsonArray("type") { add("string"); add("null") } }
            }
        }
    }
}
