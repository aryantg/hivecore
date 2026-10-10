package com.hivecore.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Spec section 17 (Jobs screen) — the job/progress seam between the split engine (engine/split, the producer) and the
// UI and debug hooks (consumers). Frozen once merged: the splitter emits exactly these, the Jobs screen renders them.
// Phase 3 added, additively: per-chunk badges (duplicated, re-cut, verified, cloud), per-node link kind, the partial
// reason and the resumable flag.

/** A job to split across phones: a recipe over items, or one op over one large input. */
@Serializable data class JobRequest(
    val title: String,
    /** a recipe id from assets/swarm/recipes (null = run [op] directly) */
    val recipeId: String? = null,
    val op: Op? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    /** local file paths of the items (photos, docs, audio, …) or empty for prompt/question jobs */
    val inputs: List<String> = emptyList(),
    /** private mode: when true, nothing leaves this phone; chunks run only here */
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
    /** section 11.2 step 5: a straggler copy ran elsewhere */
    val duplicated: Boolean = false,
    /** section 14: halved into two child chunks (or, with [recutOf], is one of the halves) */
    val recut: Boolean = false,
    val recutOf: Int? = null,
    /** section 12.1: true = two replicas agreed; false = one phone only ("not verified"); null = not a replicated op */
    val verified: Boolean? = null,
    /** section 15.3: a cloud model answered this chunk (the badge shows the slug) */
    val cloud: String? = null,
)

@Serializable data class NodeLoad(
    val nodeId: String,
    val name: String,
    val chunksDone: Int,
    val busyMs: Long,
    val itemsPerSec: Double,
    /** section 5.6 link kind the phone is reached over ("LAN", "P2P", "BT", "WAN_DIRECT", "WAN_RELAY"); null for this phone */
    val link: String? = null,
)

@Serializable data class JobProgress(
    val jobId: String,
    val title: String,
    /** "shard" | "chunk" | "fanout" | "pipeline" | "graph" (section 10.2) */
    val axis: String,
    /** "concat" | "stitch" | "tree" | "monoid" | "topk" | "vote" | "rank" (section 10.4) */
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
    /** section 14: the job ended with failed chunks; [partialReason] is the one-sentence explanation */
    val partial: Boolean = false,
    val partialReason: String? = null,
    /** section 13.1: journaled and offered for resume after a restart */
    val resumable: Boolean = false,
    /** section 15.3: cloud slugs that answered a chunk or the step (the cloud badge) */
    val cloud: List<String> = emptyList(),
    /** chunks accepted from a single phone ("not verified", section 12.1) */
    val unverified: Int = 0,
    /** the merged output once done (recipes: `{"stages":{...}}`), so the result viewer can show it; null before and when not kept */
    val result: kotlinx.serialization.json.JsonElement? = null,
)

interface JobRunner {
    /** every job this phone commands, most recent first */
    val jobs: StateFlow<List<JobProgress>>
    fun run(request: JobRequest): Flow<JobProgress>
    fun cancel(jobId: String)
    /** Spec section 13.3: resumes a journaled job after a restart (RECLAIM, then requeue). Default: not supported. */
    fun resume(jobId: String): Flow<JobProgress> = kotlinx.coroutines.flow.flow { throw UnsupportedOperationException("resume is not available on this runner") }
}
