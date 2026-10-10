package com.hivecore.ui.vm

import com.hivecore.core.OpRegistry
import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.core.WorkerEventKind.CANCELLED
import com.hivecore.core.WorkerEventKind.CHUNK_MEMO_HIT
import com.hivecore.core.WorkerEventKind.CHUNK_RECEIVED
import com.hivecore.core.WorkerEventKind.CHUNK_RUNNING
import com.hivecore.core.WorkerEventKind.MODEL_FAILED
import com.hivecore.core.WorkerEventKind.MODEL_LOADING
import com.hivecore.core.WorkerEventKind.MODEL_READY
import com.hivecore.core.WorkerEventKind.MODEL_SELECTED
import com.hivecore.core.WorkerEventKind.PLAN_RECEIVED
import com.hivecore.core.WorkerEventKind.RECEIPT_COUNTERSIGNED
import com.hivecore.core.WorkerEventKind.RESULT_FAILED
import com.hivecore.core.WorkerEventKind.RESULT_SENT
import com.hivecore.core.WorkerEventKind.YIELDED
import com.hivecore.ui.Format

/**
 * The worker timeline as the Jobs screen shows it (pure, JVM-tested): one [WorkingFor] card per job this phone worked
 * on for another phone, with four steps (Model → Chunk → Run → Return), and the one-line phrase an orchestrator's
 * phone lane shows for a worker's latest mirrored event.
 */
object WorkerTimeline {
    enum class StepState { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

    data class Step(val state: StepState, val title: String, val detail: String? = null)

    data class WorkingFor(
        val planId: String,
        val commander: String,
        val commanderName: String,
        /** "Split extract", or the recipe stage */
        val what: String,
        val model: Step,
        val chunk: Step,
        val run: Step,
        val back: Step,
        /** chunks of this job sent back so far */
        val sent: Int,
        val lastTs: Long,
        /** every step settled: nothing more is expected from this phone for the job */
        val settled: Boolean,
    ) {
        val steps: List<Step> get() = listOf(model, chunk, run, back)
    }

    /** At most [max] jobs, the most recent first; events this phone ran for itself are left out (its own lanes show them). */
    fun build(events: List<WorkerEvent>, selfId: String, nameOf: (String) -> String, max: Int = 3): List<WorkingFor> {
        val forOthers = events.filter { it.commander != selfId }
        val receipts = forOthers.filter { it.kind == RECEIPT_COUNTERSIGNED }
        val byPlan = LinkedHashMap<String, MutableList<WorkerEvent>>()
        for (e in forOthers) if (e.kind != RECEIPT_COUNTERSIGNED) byPlan.getOrPut(e.planId) { ArrayList() } += e
        return byPlan.values.map { plan(it, receipts.filter { r -> r.jobId == it.first().jobId }, nameOf) }
            .sortedByDescending { it.lastTs }.take(max)
    }

    private fun plan(es: List<WorkerEvent>, receipts: List<WorkerEvent>, nameOf: (String) -> String): WorkingFor {
        val first = es.first()
        val who = nameOf(first.commander)
        val chunkEv = es.lastOrNull { it.kind == CHUNK_RECEIVED }
        val taskId = chunkEv?.taskId
        val task = if (taskId == null) emptyList() else es.filter { it.taskId == taskId }
        val ending = task.lastOrNull { it.kind in ENDINGS }
        val sent = es.count { it.kind == RESULT_SENT }
        val count = es.firstNotNullOfOrNull { it.chunkCount }
        val op = (chunkEv?.op ?: es.firstNotNullOfOrNull { it.op })
        val stage = es.firstNotNullOfOrNull { it.stage }
        val what = listOfNotNull(op?.let { "Split ${it.lowercase()}" } ?: "Split job", stage?.let { "stage $it" }).joinToString(" · ")

        val model = modelStep(es, task, op, ending)
        val chunk = when {
            chunkEv == null -> Step(StepState.ACTIVE, "Chunk", "Waiting for a chunk" + (count?.let { " of $it" } ?: ""))
            else -> Step(StepState.DONE, "Chunk", chunkWords(chunkEv))
        }
        val run = when {
            chunkEv == null -> Step(StepState.PENDING, "Run")
            ending?.kind == RESULT_SENT -> Step(StepState.DONE, "Run",
                if (task.any { it.kind == CHUNK_MEMO_HIT }) "Answered from the memo" else "${op?.let { o -> o.lowercase() + " " } ?: ""}took ${Format.duration(ending.ms ?: 0)}")
            ending?.kind == RESULT_FAILED -> Step(StepState.FAILED, "Run", ending.error ?: "failed")
            ending?.kind == YIELDED -> Step(StepState.SKIPPED, "Run", "Handed back: ${ending.error ?: "this phone was in use"}")
            ending?.kind == CANCELLED -> Step(StepState.SKIPPED, "Run", "Cancelled by $who" + (ending.error?.let { " ($it)" } ?: ""))
            task.any { it.kind == CHUNK_RUNNING } -> Step(StepState.ACTIVE, "Run", "Running ${op?.lowercase() ?: "the task"}…")
            else -> Step(StepState.ACTIVE, "Run", "Queued on this phone")
        }
        val receipt = receipts.lastOrNull()
        val back = when {
            receipt != null -> Step(StepState.DONE, "Return", "Sent ${Format.plural(sent, "chunk")} to $who · countersigned ✓")
            ending?.kind == RESULT_SENT -> Step(StepState.DONE, "Return", "Sent to $who" + (if (sent > 1) " · $sent chunks so far" else ""))
            ending?.kind == RESULT_FAILED -> Step(StepState.FAILED, "Return", "Told $who the chunk failed")
            ending?.kind == YIELDED -> Step(StepState.SKIPPED, "Return", "$who gives the chunk to another phone")
            ending?.kind == CANCELLED -> Step(StepState.SKIPPED, "Return")
            else -> Step(StepState.PENDING, "Return", "Goes to $who when done")
        }
        val lastTs = maxOf(es.maxOf { it.ts }, receipt?.ts ?: 0L)
        return WorkingFor(first.planId, first.commander, who, what, model, chunk, run, back, sent, lastTs,
            settled = chunkEv != null && ending != null)
    }

    private fun modelStep(es: List<WorkerEvent>, task: List<WorkerEvent>, op: String?, ending: WorkerEvent?): Step {
        val models = es.filter { it.kind in MODEL_KINDS }
        val sel = models.lastOrNull { it.kind == MODEL_SELECTED }
        if (sel == null) {
            val cap = op?.let { o -> runCatching { OpRegistry.byName(o)?.capability }.getOrNull() }
            return when {
                task.any { it.kind == CHUNK_MEMO_HIT } -> Step(StepState.SKIPPED, "Model", "Not needed: the chunk was in the memo")
                op != null && cap == null -> Step(StepState.SKIPPED, "Model", "No model needed for ${op.lowercase()}")
                else -> Step(StepState.PENDING, "Model", "Picked when the task type is known")
            }
        }
        val name = sel.modelId ?: "built-in"
        val choice = "${sel.capability} → $name" + when (sel.reason) {
            "requested" -> " (named by the job)"
            "preferred" -> " (this phone's pick for ${sel.capability})"
            "built-in" -> " (built into the app)"
            else -> ""
        }
        // a cold load reported by placement (MODEL_REQ, before the chunk) or by the pool (lazily, inside the chunk)
        val cold = models.lastOrNull { it.kind == MODEL_READY && it.warm == false }
        val last = models.last()
        return when {
            last.kind == MODEL_FAILED -> Step(StepState.FAILED, "Model", "$choice · failed: ${last.error ?: "load error"}")
            last.kind == MODEL_LOADING -> Step(StepState.ACTIVE, "Model", "$choice · booting…")
            cold != null -> Step(StepState.DONE, "Model", "$choice · booted in ${Format.duration(cold.loadMs ?: 0)}")
            last.kind == MODEL_READY || sel.warm == true -> Step(StepState.DONE, "Model", "$choice · already in memory")
            ending != null || sel.modelId == null -> Step(StepState.DONE, "Model", choice)
            else -> Step(StepState.ACTIVE, "Model", "$choice · loading…")
        }
    }

    /** "Chunk 3 of 8 · items 41–60" (1-based for people). */
    fun chunkWords(e: WorkerEvent): String = listOfNotNull(
        e.chunkIndex?.let { i -> "Chunk ${i + 1}" + (e.chunkCount?.let { " of $it" } ?: "") } ?: e.taskId?.let { "Task $it" },
        e.label?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /** The orchestrator's phone lane line for a worker's latest event. */
    fun phrase(e: WorkerEvent): String {
        val chunk = e.chunkIndex?.let { i -> "chunk ${i + 1}" + (e.chunkCount?.let { "/$it" } ?: "") } ?: "its chunk"
        val model = e.modelId ?: "a model"
        return when (e.kind) {
            PLAN_RECEIVED -> "got the plan" + (e.chunkCount?.let { " ($it chunks)" } ?: "")
            MODEL_SELECTED -> "picked $model for ${e.capability}" + if (e.warm == true) " · warm" else ""
            MODEL_LOADING -> "booting $model…"
            MODEL_READY -> "$model ready · " + if (e.warm == true) "was warm" else "booted in ${Format.duration(e.loadMs ?: 0)}"
            MODEL_FAILED -> "$model failed to load"
            CHUNK_RECEIVED -> "got $chunk" + (e.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            CHUNK_RUNNING -> "running $chunk" + (e.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            CHUNK_MEMO_HIT -> "$chunk from its memo"
            RESULT_SENT -> "sent $chunk back · ${Format.duration(e.ms ?: 0)}"
            RESULT_FAILED -> "$chunk failed: ${e.error ?: "error"}"
            YIELDED -> "handed $chunk back (in use)"
            CANCELLED -> "$chunk cancelled"
            RECEIPT_COUNTERSIGNED -> "countersigned"
        }
    }

    /** True while the event means the phone is busy with a model or a chunk (the lane shows a spinner). */
    fun busy(e: WorkerEvent): Boolean = e.kind in setOf(MODEL_SELECTED, MODEL_LOADING, CHUNK_RECEIVED, CHUNK_RUNNING)

    private val MODEL_KINDS = setOf(MODEL_SELECTED, MODEL_LOADING, MODEL_READY, MODEL_FAILED)
    private val ENDINGS: Set<WorkerEventKind> = setOf(RESULT_SENT, RESULT_FAILED, YIELDED, CANCELLED)
}
