package com.hivecore.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// The worker timeline seam: what a phone does with a chunk it was given (which model it picked for the task type and
// booted, which chunk it got, when the result went back, when the orchestrator countersigned), for the Jobs screen
// on the worker and, mirrored over TASK_PROGRESS, on the orchestrator's phone lanes. Display only: nothing in the
// split engine reads these events back, so a lost one only leaves a gap on screen.

enum class WorkerEventKind {
    /** a split PLAN arrived: this phone may get chunks of it ([WorkerEvent.chunkCount] known from here) */
    PLAN_RECEIVED,
    /** the model for the task type was chosen ([WorkerEvent.capability] → [WorkerEvent.modelId], [WorkerEvent.reason]) */
    MODEL_SELECTED,
    MODEL_LOADING,
    /** the model is resident: [WorkerEvent.warm] = it already was, else loaded in [WorkerEvent.loadMs] */
    MODEL_READY,
    MODEL_FAILED,
    CHUNK_RECEIVED,
    CHUNK_RUNNING,
    /** the chunk was answered from the memo, so no model ran */
    CHUNK_MEMO_HIT,
    /** TASK_RESULT went back to the orchestrator ([WorkerEvent.ms] of work) */
    RESULT_SENT,
    /** an error TASK_RESULT went back ([WorkerEvent.error]) */
    RESULT_FAILED,
    YIELDED,
    CANCELLED,
    /** the orchestrator's `plan` receipt countersigned this phone's `task` entry: the job's acknowledgement */
    RECEIPT_COUNTERSIGNED,
}

@Serializable data class WorkerEvent(
    /** "<jobId>#<run>", or the bare jobId for a receipt (the ledger keys by job) */
    val planId: String,
    val kind: WorkerEventKind,
    /** the phone that did the work */
    val node: String,
    /** the orchestrator the work is for */
    val commander: String,
    val ts: Long,
    val taskId: String? = null,
    val op: String? = null,
    val capability: String? = null,
    val modelId: String? = null,
    /** why this model: "requested" (the job named it), "preferred" (the catalog's first for the capability), "built-in" (no model file) */
    val reason: String? = null,
    val warm: Boolean? = null,
    val loadMs: Long? = null,
    /** 0-based chunk index */
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
    /** the chunk's slice in words, e.g. "items 41–60" */
    val label: String? = null,
    val stage: String? = null,
    val ms: Long? = null,
    val error: String? = null,
) {
    /** The job this event belongs to (a recipe stage keeps its "/stage" suffix). */
    val jobId: String get() = planId.substringBeforeLast('#')
}

/** Read side, for the UI. */
interface WorkerActivity {
    /** This phone's own worker events, oldest first, the last [WorkerActivityLog.CAP] kept. */
    val local: StateFlow<List<WorkerEvent>>
    /** The latest mirrored event of each peer working on a job this phone commands (nodeId → event). */
    val peers: StateFlow<Map<String, WorkerEvent>>
}

/** The write side: the swarm node, placement, the model pool and the receipt exchange emit here. */
class WorkerActivityLog(private val cap: Int = CAP) : WorkerActivity {
    private val localState = MutableStateFlow<List<WorkerEvent>>(emptyList())
    private val peerState = MutableStateFlow<Map<String, WorkerEvent>>(emptyMap())
    override val local: StateFlow<List<WorkerEvent>> = localState
    override val peers: StateFlow<Map<String, WorkerEvent>> = peerState
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(WorkerEvent) -> Unit>()

    /** Called for every local event after it is recorded (the swarm node forwards it to the commander). */
    fun onEmit(l: (WorkerEvent) -> Unit) { listeners += l }

    fun emit(e: WorkerEvent) {
        synchronized(this) { localState.value = (localState.value + e).takeLast(cap) }
        for (l in listeners) try { l(e) } catch (x: Exception) { SLog.w(TAG, "listener: ${x.message}") }
    }

    /** A peer's event mirrored to this phone (already checked to belong to a live job of this phone). */
    fun onPeer(e: WorkerEvent) {
        synchronized(this) { peerState.value = peerState.value + (e.node to e) }
    }

    /** The job ended: its workers' mirrored events leave the phone lanes. */
    fun forgetJob(jobId: String) {
        synchronized(this) { peerState.value = peerState.value.filterValues { it.jobId != jobId } }
    }

    companion object {
        const val CAP = 200
        private const val TAG = "WorkerActivity"
    }
}

/**
 * The worker task a coroutine is running (installed by the swarm node around the task runner), so code below it, the
 * model pool and the memo, can report against the right chunk without knowing about the mesh.
 */
class WorkerTaskContext(val log: WorkerActivityLog, val base: WorkerEvent, private val clock: () -> Long = System::currentTimeMillis) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerTaskContext> {
        suspend fun current(): WorkerTaskContext? = currentCoroutineContext()[Key]
    }

    fun emit(kind: WorkerEventKind, modelId: String? = null, warm: Boolean? = null, loadMs: Long? = null, reason: String? = null, error: String? = null) {
        log.emit(base.copy(kind = kind, ts = clock(), modelId = modelId ?: base.modelId, warm = warm, loadMs = loadMs,
            reason = reason ?: base.reason, error = error))
    }
}
