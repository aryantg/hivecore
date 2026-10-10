package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.Compiled
import com.hivecore.core.DocAnswer
import com.hivecore.core.DocAnswerer
import com.hivecore.core.DocIndex
import com.hivecore.core.IntentCompiler
import com.hivecore.core.IntentPlanner
import com.hivecore.core.NodeOutcome
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.SLog
import com.hivecore.core.Schema
import com.hivecore.core.SchemaSource
import com.hivecore.core.ScoredChunk
import com.hivecore.core.TableDef
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** What the owner prepared for one AGG/DOC task when its PLAN arrived (compiled once, consented once). */
sealed interface PreparedTask {
    data class Agg(val query: Compiled.Query) : PreparedTask
    data class Doc(val question: String, val chunks: List<ScoredChunk>, val confidentialDocs: List<String>) : PreparedTask
}

/**
 * Swarm plan §4 owner ops: AGG (sub-question → [com.hivecore.core.AggTable]) and DOC (sub-question →
 * [DocAnswer]) over the owner's own compile/execute path: the same [IntentPlanner] → [IntentCompiler] →
 * [com.hivecore.core.QueryExecutor] chain and floors NodeAnswerer uses, so a figure released to a commander
 * is exactly the figure a direct question would release.
 *
 * Execution only runs what [prepared] returns for the task (OwnerPlanHandler compiled it, ran the differencing guard
 * and got the owner's consent when the PLAN arrived). A task nobody prepared fails: nothing data-bound runs without
 * those checks.
 */
class AggExecutor(
    private val nodeName: String,
    private val schema: SchemaSource,
    private val planner: IntentPlanner,
    private val compiler: IntentCompiler,
    private val executor: com.hivecore.core.QueryExecutor,
    private val docIndex: DocIndex? = null,
    private val docAnswerer: DocAnswerer? = null,
    private val prepared: (TaskSpec) -> PreparedTask? = { null },
) : OpExecutor {

    override val ops: Set<Op> = setOf(Op.AGG, Op.DOC)

    /** The sub-question of an AGG/DOC task (`params.q`). */
    fun questionOf(task: TaskSpec): String? = (task.params["q"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Compiles one AGG sub-question to exactly one query (the planner's first intent that compiles; extra intents of a
     * multi-part answer are dropped and logged). Failure messages are the refusal shown to the commander.
     */
    suspend fun compileAgg(question: String, s: Schema = schema.schema()): Result<Compiled.Query> {
        val pr = planner.plan(question, s).getOrElse { return Result.failure(IllegalStateException("could not understand '$question'")) }
        if (pr.intents.notRelevant || pr.intents.intents.isEmpty())
            return Result.failure(IllegalStateException("$nodeName holds nothing about '$question'"))
        var refusal: String? = null
        for (intent in pr.intents.intents) {
            when (val c = compiler.compile(intent, s)) {
                is Compiled.Query -> {
                    if (pr.intents.intents.size > 1) SLog.w(TAG, "$nodeName: '$question' gave ${pr.intents.intents.size} intents; using ${c.signature}")
                    return Result.success(c)
                }
                is Compiled.Refused -> if (refusal == null) refusal = c.reason
                is Compiled.NotRelevant -> Unit
            }
        }
        return Result.failure(IllegalStateException(refusal ?: "$nodeName holds nothing about '$question'"))
    }

    /** The relevant chunks for a DOC sub-question, and which of their documents are confidential. */
    fun prepareDoc(question: String, s: Schema = schema.schema()): Result<PreparedTask.Doc> {
        val index = docIndex ?: return Result.failure(IllegalStateException("$nodeName has no documents"))
        val chunks = index.relevant(question, 3)
        if (chunks.isEmpty()) return Result.failure(IllegalStateException("$nodeName's documents do not cover '$question'"))
        val confidential = chunks.map { it.chunk.docName }.distinct().filter { n -> s.docs.any { it.name == n && it.confidential } }
        return Result.success(PreparedTask.Doc(question, chunks, confidential))
    }

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
        val prep = prepared(task) ?: return Result.failure(IllegalStateException("task ${task.id} was not prepared by a PLAN"))
        return when (prep) {
            is PreparedTask.Agg -> runAgg(prep.query)
            is PreparedTask.Doc -> runDoc(prep)
        }
    }

    private fun runAgg(q: Compiled.Query): Result<JsonElement> {
        val s = schema.schema()
        val table = s.tables.firstOrNull { it.name == q.intent.table } ?: TableDef(q.intent.table, emptyList(), 0)
        return when (val o = executor.run(q, table, nodeName)) {
            is NodeOutcome.Tables -> o.tables.firstOrNull()?.let { Result.success(Canon.encodeTable(it)) }
                ?: Result.failure(IllegalStateException("no aggregates"))
            is NodeOutcome.Refused -> Result.failure(IllegalStateException(o.reason))
            else -> Result.failure(IllegalStateException("executor returned ${o::class.simpleName}"))
        }
    }

    private suspend fun runDoc(p: PreparedTask.Doc): Result<JsonElement> {
        val answerer = docAnswerer ?: return Result.failure(IllegalStateException("$nodeName cannot read documents"))
        val a = answerer.answer(p.question, p.chunks).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("not found in $nodeName's documents"))
        return Result.success(Canon.json.encodeToJsonElement(DocAnswer.serializer(), a))
    }

    companion object { private const val TAG = "AggExecutor" }
}
