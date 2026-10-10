package com.hivecore.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Swarm plan §4B.4 — the job/progress seam between the splitting engine (swarm/split, the producer) and the UI and
// debug hooks (consumers). Frozen once merged: the splitter emits exactly these, the Jobs screen renders them.

/** A job to split across phones: a recipe over items, or one op over one large input. */
@Serializable data class JobRequest(
    val title: String,
    /** a recipe id from assets/swarm/recipes (null = run [op] directly) */
    val recipeId: String? = null,
    val op: Op? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    /** local file paths of the items (photos, docs, audio, …) or empty for prompt/question jobs */
    val inputs: List<String> = emptyList(),
    /** private mode (§7): when true, nothing leaves this phone; chunks run only here */
    val private: Boolean = false,
)

enum class ChunkState { QUEUED, RUNNING, DONE, DUPLICATED, FAILED }

@Serializable data class ChunkView(
    val index: Int,
    val chunkId: String,
    val state: ChunkState,
    /** display name of the phone running/that ran it; null while queued */
    val node: String? = null,
    val ms: Long? = null,
    /** short human label, e.g. "invoices 41–60", "audio 02:30–03:00", "tile 3,2", "seed 7" */
    val label: String = "",
)

@Serializable data class NodeLoad(
    val nodeId: String,
    val name: String,
    val chunksDone: Int,
    val busyMs: Long,
    val itemsPerSec: Double,
)

@Serializable data class JobProgress(
    val jobId: String,
    val title: String,
    /** "shard" | "chunk" | "fanout" | "pipeline" | "graph" (§4B.1) */
    val axis: String,
    /** "concat" | "stitch" | "tree" | "monoid" | "topk" | "vote" | "rank" (§4B.2) */
    val merge: String,
    val chunks: List<ChunkView>,
    val nodes: List<NodeLoad>,
    val bytesMoved: Long = 0,
    val elapsedMs: Long = 0,
    /** estimate of the same job on this phone alone (BENCH-based); null until known */
    val singlePhoneMs: Long? = null,
    /** singlePhoneMs / elapsedMs once done; null before */
    val speedup: Double? = null,
    /** true when a sampled chunk re-run locally matched; null when not checked */
    val identicalToSingle: Boolean? = null,
    val done: Boolean = false,
    /** one-line result, e.g. "120 invoices → 118 rows, 2 sent back (blurry)" */
    val resultSummary: String? = null,
    /** where the merged result landed: table name, folder path, or doc id */
    val resultRef: String? = null,
    val error: String? = null,
)

interface JobRunner {
    /** every job this phone commands, most recent first */
    val jobs: StateFlow<List<JobProgress>>
    fun run(request: JobRequest): Flow<JobProgress>
    fun cancel(jobId: String)
    /** Spec section 13.3: resumes a journaled job after a restart (RECLAIM, then requeue). Default: not supported. */
    fun resume(jobId: String): Flow<JobProgress> = kotlinx.coroutines.flow.flow { throw UnsupportedOperationException("resume is not available on this runner") }
}
