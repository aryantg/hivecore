package com.hivecore.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Plan 6 "core/Models.kt" — frozen after Wave 0. Change requests go to the integrator.

enum class NodeRole { HR, SALES, ACCOUNTS }
enum class LlmBackend { NPU, GPU, CPU }
enum class AnswerChip { LLM, TEMPLATE, /** swarm plan: a composite plan across phones */ PLAN }
enum class ColType { INTEGER, REAL, TEXT }

@Serializable data class ColumnDef(val name: String, val type: ColType,
    val confidential: Boolean = false,   // AVG/COUNT only; triggers approval
    val identifier: Boolean = false,     // no filters on it when the table is sensitive
    val groupable: Boolean = false,      // allowed in group_by
    val aliases: List<String> = emptyList())
@Serializable data class TableDef(val name: String, val columns: List<ColumnDef>, val rowCount: Long,
    val aliases: List<String> = emptyList(), val confidential: Boolean = false) {
    val isSensitive get() = confidential || columns.any { it.confidential } }
@Serializable data class DocDef(val id: String, val name: String, val kind: String, val units: Int, val unitLabel: String,
    val chunkCount: Int, val confidential: Boolean = false)
@Serializable data class Schema(val node: NodeRole, val tables: List<TableDef>, val docs: List<DocDef>)

@Serializable data class Filter(val column: String, val op: String, val value: JsonElement)   // eq neq gt gte lt lte in
@Serializable data class Intent(val table: String, val metric: String, val column: String? = null,
    @SerialName("group_by") val groupBy: List<String> = emptyList(), val filters: List<Filter> = emptyList())
@Serializable data class IntentSet(val intents: List<Intent> = emptyList(), @SerialName("not_relevant") val notRelevant: Boolean = false)

sealed interface Compiled {
    data class Query(val intent: Intent, val sql: String, val args: List<Any?>, val countSql: String,
                     val touchesConfidential: Boolean, val description: String, val signature: String) : Compiled
    data class Refused(val intent: Intent?, val reason: String) : Compiled
    data class NotRelevant(val reason: String) : Compiled
}

@Serializable data class AggTable(val node: String, val title: String, val columns: List<String>, val rows: List<List<JsonElement>>,
    val rowsScanned: Long /* after WHERE */, val rowsHeld: Long /* TableDef.rowCount */, val source: String /* sql|rollup|swarm */,
    /** swarm reduce notes, e.g. "West: division by zero" (swarm plan §4) */
    val notes: List<String> = emptyList())
@Serializable data class DocAnswer(val text: String, val doc: String, val locator: String, val chunksRead: Int)

@Serializable sealed interface NodeOutcome {
    @Serializable @SerialName("tables")       data class Tables(val tables: List<AggTable>, val chip: AnswerChip, val intentHash: String) : NodeOutcome
    @Serializable @SerialName("doc")          data class Doc(val answer: DocAnswer, val chip: AnswerChip) : NodeOutcome
    @Serializable @SerialName("refused")      data class Refused(val reason: String) : NodeOutcome
    @Serializable @SerialName("declined")     data class Declined(val byOwner: Boolean) : NodeOutcome
    @Serializable @SerialName("not_relevant") data class NotRelevant(val holds: List<String>) : NodeOutcome
    @Serializable @SerialName("timeout")      data object Timeout : NodeOutcome
}
@Serializable data class Timing(val sttMs: Long? = null, val intentMs: Long? = null, val meshMs: Long? = null, val joinMs: Long? = null,
                  val voiceMs: Long? = null, val totalMs: Long? = null)
@Serializable data class Provenance(val node: String, val line: String)
@Serializable sealed interface AskOutcome {                       // serializable: the adb hook writes it to hook_result.json
    @Serializable @SerialName("answer") data class Answer(val text: String, val tables: List<AggTable>, val joined: AggTable?,
        val docAnswers: List<DocAnswer>, val chip: AnswerChip, val provenance: List<Provenance>, val timing: Timing, val notes: List<String>,
        val rowsMoved: Long, val rowsHeld: Long, val approvals: Int, val phones: Int,
        /** swarm plan §8.2: set when a composite (T2) plan produced the answer */
        val plan: PlanSummary? = null,
        /** a structured result beside the sentence (a deck: `{"kind":"deck", …}`), when the plan's output is one */
        val artifact: JsonElement? = null) : AskOutcome
    @Serializable @SerialName("refusal") data class Refusal(val reasons: List<Pair<String, String>>, val timing: Timing) : AskOutcome
    @Serializable @SerialName("nonode")  data class NoNode(val holdings: List<Pair<String, List<String>>>, val timing: Timing) : AskOutcome
}
sealed interface AskProgress {
    data class Status(val text: String) : AskProgress
    /** A voice question after misheard vocabulary words were repaired: what is actually asked and shown. */
    data class Heard(val question: String) : AskProgress
    data class Done(val outcome: AskOutcome) : AskProgress
}
