package com.hivecore.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

// Swarm plan (docs/swarm-plan.md) §8.2 — the seams every swarm/workload package codes against. Frozen after Wave S0:
// agents report needed changes to the integrator instead of editing this file. Each interface has a fake in core/fakes.

// ---------------------------------------------------------------------------------------------------------------
// Operators (§4, §4A.3)

enum class Op {
    // questions (§4)
    AGG, DOC, EXTRACT, OCR, FIELDS, JOIN, RATIO, DIFF, SHARE, TREND, RANK, THRESHOLD, RECONCILE, VERIFY, RENDER, PHRASE,
    // vision
    IMG_CLASSIFY, DETECT, SEGMENT, TAG, IQA, VLM_ASK,
    // text / generative
    INFER, TEXT_CLASSIFY, SUMMARIZE, EMBED, KNN, CLUSTER, GEN_TEXT,
    // document AI
    LAYOUT, DOC_CLASSIFY, METADATA, REDACT,
    // AI development
    EVAL, BENCH, SYNTH_GEN, SYNTH_VALIDATE,
    // media
    VAD_SPLIT, ASR, IMG_PREPROC, SUPERRES, FRAME_SAMPLE, TRACK_AGG,
    // image generation
    GEN_IMAGE, GEN_VARIANTS, SCORE_IMAGE, UPSCALE,
    // v2 plan §6.4: generic per-item LLM op `params = {template, schema, maxTokens}`, output validated against `schema`
    LLM_MAP,
}

/** Who may execute an op; drives scheduling and worker eligibility (§6). */
enum class OpClass {
    /** pure Kotlin on the commander */ REDUCE,
    /** reads the owner's rows/docs/media: only the owner runs it (R1) */ OWNER,
    /** cheap, no model or a small NPU model */ LIGHT,
    /** needs the GenieX LLM (big slot = llm) */ LLM,
    /** needs the GenieX VLM (big slot = vlm) */ VLM,
    /** long NPU work (diffusion, long ASR, video): charger + thermal ≤ 1 only */ HEAVY,
}

object OpClasses {
    fun of(op: Op): OpClass = when (op) {
        Op.AGG, Op.DOC, Op.FRAME_SAMPLE, Op.VAD_SPLIT, Op.SYNTH_VALIDATE -> OpClass.OWNER
        Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.RANK, Op.THRESHOLD, Op.RECONCILE, Op.RENDER, Op.TRACK_AGG,
        Op.KNN, Op.CLUSTER -> OpClass.REDUCE
        Op.EXTRACT, Op.FIELDS, Op.PHRASE, Op.TEXT_CLASSIFY, Op.SUMMARIZE, Op.GEN_TEXT, Op.DOC_CLASSIFY, Op.METADATA,
        Op.SYNTH_GEN, Op.EVAL, Op.LLM_MAP -> OpClass.LLM
        Op.VLM_ASK -> OpClass.VLM
        Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.UPSCALE, Op.ASR, Op.SUPERRES -> OpClass.HEAVY
        Op.OCR, Op.IMG_CLASSIFY, Op.DETECT, Op.SEGMENT, Op.TAG, Op.IQA, Op.INFER, Op.EMBED, Op.LAYOUT, Op.REDACT,
        Op.BENCH, Op.IMG_PREPROC, Op.SCORE_IMAGE, Op.VERIFY -> OpClass.LIGHT
    }
}

/**
 * One node of a DAG. [inputs] are ids of earlier tasks whose outputs feed this one, in order. [owner] is fixed
 * for [OpClass.OWNER] ops (the nodeId holding the data) and null otherwise. [blobs] are content hashes of
 * released items (§4A.1) the task reads. [params] are op-specific and pinned (R4).
 */
@Serializable data class TaskSpec(
    val id: String,
    val op: Op,
    val params: JsonObject = JsonObject(emptyMap()),
    val inputs: List<String> = emptyList(),
    val owner: String? = null,
    val modelId: String? = null,
    val blobs: List<String> = emptyList(),
)

/** A plan: a pattern instance (question) or a recipe instance (workload). Tasks are topologically ordered. */
@Serializable data class Dag(
    val patternId: String,
    val patternsVersion: String,
    val tasks: List<TaskSpec>,
    /** the leaf whose output is the answer (questions) or the sink-bound outputs (workloads) */
    val output: String,
)

/** Result of [PlanCompiler.validate]. */
data class PlanCheck(
    val ok: Boolean,
    val errors: List<String>,
    /** sha256 of canonical(dag) + patternsVersion + modelHash (§5.2) */
    val planHash: String,
    /** per owner nodeId: the AGG/DOC sub-questions that touch that owner's data, for plan-scoped consent (§7) */
    val perOwner: Map<String, List<String>>,
)

// ---------------------------------------------------------------------------------------------------------------
// Routing (§2): T0/T1 already exist as ask/QuestionRouter (LOCAL/DIRECT/BROADCAST); the swarm adds COMPOSITE.

/** A peer's public catalog (§8.1): names and flags only, never values or row counts. */
@Serializable data class CatalogColumn(val name: String, val type: ColType, val groupable: Boolean = false,
    val confidential: Boolean = false, val identifier: Boolean = false, val aliases: List<String> = emptyList())
@Serializable data class CatalogTable(val name: String, val aliases: List<String> = emptyList(), val columns: List<CatalogColumn>)
@Serializable data class Catalog(val node: String, val nodeName: String, val tables: List<CatalogTable>, val docs: List<String> = emptyList()) {
    companion object {
        fun of(nodeId: String, nodeName: String, schema: Schema, hidden: Set<String> = emptySet()): Catalog = Catalog(
            nodeId, nodeName,
            schema.tables.filter { it.name !in hidden }.map { t ->
                CatalogTable(t.name, t.aliases, t.columns.map { c -> CatalogColumn(c.name, c.type, c.groupable, c.confidential, c.identifier, c.aliases) })
            },
            schema.docs.filter { !it.confidential }.map { it.name })
    }
}

/** Operator cues found in the question (lexicon, §2.1), in order of appearance. */
@Serializable data class Cue(val word: String, val op: Op)

data class RouteDecision(
    /** true = T2: a composite plan; false = T0/T1 handled by the existing ask pipeline */
    val composite: Boolean,
    /** nodeIds whose catalog matched a data term */
    val owners: List<String>,
    val cues: List<Cue>,
    val reason: String,
)

interface SwarmRouter { fun route(question: String, selfId: String, catalogs: List<Catalog>): RouteDecision }

// ---------------------------------------------------------------------------------------------------------------
// Jev-style closed decisions (§5.3)

/** A closed answer: the chosen option (null = no confident choice), agreement-based confidence, and the runs. */
data class Decision<T>(val value: T?, val confidence: Double, val options: List<T>, val agreeing: Int, val runs: Int, val raw: List<String> = emptyList(),
    /** votes per option index (same order as [options]); a clarification shows the top two */
    val votes: List<Int> = emptyList()) {
    /** Options with at least one vote, most votes first, ties by option order. */
    fun ranked(): List<T> = votes.indices.filter { votes[it] > 0 }
        .sortedWith(compareByDescending<Int> { votes[it] }.thenBy { it }).map { options[it] }
}

interface ClosedChooser {
    /**
     * Ask [question] with [options] rendered by [render]; k runs with deterministically permuted option orders.
     * The LLM output is parsed to an option index/label and validated; anything else counts as a non-vote (R3).
     */
    suspend fun <T> choose(question: String, options: List<T>, render: (T) -> String, k: Int = 3): Decision<T>
}

object Confidence {
    const val PROCEED = 0.8
    const val LOW = 0.5
}

interface Planner {
    /** Question → DAG over the closed operator set, or a failure whose message is shown as the refusal/clarification. */
    suspend fun plan(question: String, route: RouteDecision, catalogs: List<Catalog>): Result<PlannedQuestion>
}
data class PlannedQuestion(val dag: Dag, val confidence: Double, val clarification: List<String> = emptyList())

interface PlanCompiler { fun validate(dag: Dag, catalogs: List<Catalog>, modelHash: String): PlanCheck }

// ---------------------------------------------------------------------------------------------------------------
// Scheduling and execution (§6)

/** Capability profile (PROFILE message, §6.1). `eligible`/`heavyOk` are computed by the sender's [WorkerPolicy]. */
@Serializable data class Profile(
    val nodeId: String,
    val ts: Long,
    val backend: LlmBackend? = null,
    val tokPerSec: Double? = null,
    val modelHash: String? = null,
    val qairt: String? = null,
    val idle: Boolean = false,
    val charging: Boolean = false,
    val batteryPct: Int = 0,
    val batteryTempC: Float? = null,
    val thermal: Int = 0,
    val inCall: Boolean = false,
    val workerOptIn: Boolean = false,
    val eligible: Boolean = false,
    val heavyOk: Boolean = false,
    val queueDepth: Int = 0,
    val linkKbps: Double? = null,
    /** "llm" | "vlm" | "none" (§4A.2) */
    val big: String = "none",
    val warm: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    /** modelId → measured p50 ms (BENCH) */
    val p50: Map<String, Double> = emptyMap(),
    val reputation: Double = 1.0,
    /** hash of the sender's data sources and documents (_sources + _docs): owner-task memo keys change with the data */
    val dataVersion: String? = null,
    // ---- v2 plan §3.5 / §12.2: link class, catalog capabilities and capability cooldowns ----
    /** How this phone is reached by the receiver's view of the swarm: measured by the session layer (null = unknown, LAN assumed). */
    val link: Link? = null,
    /** Catalog capabilities this phone can serve (§5.3), with the warm flag per model. */
    val capabilities: List<CapabilityState> = emptyList(),
    /** (capability, until) pairs after MODEL_FAIL / oom / thermal (§9.3 rule 4); the queue skips them. */
    val cooldowns: List<Cooldown> = emptyList(),
)

// ---------------------------------------------------------------------------------------------------------------
// v2 plan §3 / §13.1: link class (the transport ladder's rank and measured quality)

/** Transport ladder (§3.1), best first. */
enum class LinkKind { LAN, P2P, BT, WAN_DIRECT, WAN_RELAY }

/** Measured link to a peer: a 2 KB echo at session start, refreshed every 5 min (§3.5). */
@Serializable data class Link(val kind: LinkKind, val kbps: Int, val rttMs: Int, val metered: Boolean = false) {
    /** Transfer rule (§3.5): `bytes·8 / kbps + 2·rttMs`, in ms. */
    fun transferMs(bytes: Long): Double = bytes * 8.0 / maxOf(1, kbps) + 2.0 * rttMs
    /** Blob-carrying chunks never move over Bluetooth or the relay (§9.1). */
    val carriesBlobs: Boolean get() = kind == LinkKind.LAN || kind == LinkKind.P2P || kind == LinkKind.WAN_DIRECT

    companion object {
        /** What a phone assumes about a peer it has not measured: the §9 Bluetooth planning figure. */
        val UNKNOWN = Link(LinkKind.LAN, 1200, 10, metered = false)
        fun defaultFor(kind: LinkKind): Link = when (kind) {
            LinkKind.LAN -> Link(kind, 20_000, 10)
            LinkKind.P2P -> Link(kind, 10_000, 20)
            LinkKind.BT -> Link(kind, 1_200, 50)
            LinkKind.WAN_DIRECT -> Link(kind, 2_000, 150)
            LinkKind.WAN_RELAY -> Link(kind, 500, 300)
        }
    }
}

/** One catalog capability a phone can serve (§5.3): the model it resolves to and whether it is loaded now. */
@Serializable data class CapabilityState(val capability: String, val modelId: String, val sha256: String, val warm: Boolean = false)

/** A capability on cooldown on a phone until [untilTs] (ms since epoch, the sender's clock). */
@Serializable data class Cooldown(val capability: String, val untilTs: Long)

interface Scheduler {
    /** Deterministic: same dag + same profiles (any order) + same self ⇒ same assignment (taskId → nodeId). */
    fun assign(dag: Dag, profiles: List<Profile>, self: String): Map<String, String>
    /** The next executor for a task that timed out or yielded on [failed] (null = none left). */
    fun reassign(task: TaskSpec, profiles: List<Profile>, self: String, failed: Set<String>): String?
}

/** Pure Kotlin reduce ops ([OpClass.REDUCE]) over JSON-encoded inputs (AggTable etc.). */
interface Reducer { fun run(op: Op, params: JsonObject, inputs: List<JsonElement>): Result<JsonElement> }

/** Replica comparison (§4A.3 rules); [rule] comes from the model manifest, null = canonical JSON equality. */
interface Comparator { fun agree(op: Op, a: JsonElement, b: JsonElement, rule: JsonObject? = null): Boolean }

/** One package's executor for a set of ops; TaskRunner dispatches to the registered executors. */
interface OpExecutor {
    val ops: Set<Op>
    suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement>
}

interface TaskRunner {
    val queueDepth: StateFlow<Int>
    fun register(executor: OpExecutor)
    /** Runs one task locally, respecting the LLM/NPU priority order (§6.3); fails fast with "yielded" when preempted. */
    suspend fun run(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>, from: String): Result<JsonElement>
    /** The owner picked the phone up: finish the current generation, then yield every queued worker task. */
    fun yieldAll()
}

interface WorkerPolicy {
    val profile: StateFlow<Profile>
    val optIn: StateFlow<Boolean>
    fun setOptIn(on: Boolean)
}

// ---------------------------------------------------------------------------------------------------------------
// Privacy (§7)

/** Owner-side plan check: null = allowed, else the refusal reason ("these figures together would reveal fewer than 5 people"). */
interface DifferencingGuard {
    fun check(requester: String, intents: List<Intent>, schema: Schema, nowMs: Long): String?
    fun remember(requester: String, intents: List<Intent>, nowMs: Long)
}

// ---------------------------------------------------------------------------------------------------------------
// Models and workloads (§4A)

@Serializable data class ModelManifest(
    val id: String,
    val task: String,
    /** "geniex_llm" | "geniex_vlm" | "litert_qnn" | "ort_qnn" | "mlkit" | "kotlin" */
    val runtime: String,
    val backend: String = "npu",
    val qairt: String? = null,
    /** files relative to the model folder; first = main file */
    val files: List<String>,
    val inputSpec: JsonObject = JsonObject(emptyMap()),
    val outputSpec: JsonObject = JsonObject(emptyMap()),
    val preprocess: JsonObject = JsonObject(emptyMap()),
    val labels: List<String> = emptyList(),
    val sha256: String,
    val licence: String,
    val sizeMb: Int = 0,
    val warmMs: Long = 0,
    val firstCompileMs: Long = 0,
    /** replica comparison rule (§4A.3), e.g. {"kind":"hash"} or {"kind":"cosine","min":0.999} */
    val compare: JsonObject = JsonObject(emptyMap()),
)

/** A loaded session: opaque handle owned by the runtime. */
interface ModelSession { val manifest: ModelManifest; fun close() }

interface ModelRuntime {
    val id: String
    fun supports(m: ModelManifest): Boolean
    suspend fun load(m: ModelManifest, dir: File): Result<ModelSession>
    /** Generic tensor call: named inputs (JSON arrays or base64 blobs per the manifest's inputSpec) → named outputs. */
    suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>>
}

interface ModelRegistry {
    fun installed(): List<ModelManifest>
    fun manifest(id: String): ModelManifest?
    fun dir(id: String): File?
}

/** Residency (§4A.2): one big slot (GenieX LLM or VLM, exclusive) + LRU small slots within a byte budget. */
interface ModelPool {
    val big: StateFlow<String>          // "llm" | "vlm" | "none"
    val warm: StateFlow<List<String>>
    suspend fun <T> with(modelId: String, block: suspend (ModelSession) -> T): Result<T>
    /** Swap the big slot; never called while the owner is asking (§4A.2). */
    suspend fun claimBig(kind: String): Result<Unit>
}

@Serializable data class Recipe(
    val id: String,
    val version: String,
    val title: String,
    /** stage templates; `{item}` in params is bound per item */
    val stages: List<TaskSpec>,
    val outputSchema: List<ColumnDef>,
    /** "all" | "sample:0.1" | "none" */
    val replicate: String = "sample:0.1",
    /** "table" | "folder" | "table+folder" */
    val sink: String = "table",
    val itemKinds: List<String> = listOf("image"),
)

@Serializable data class WorkloadSpec(
    val jobId: String,
    val recipeId: String,
    val recipeVersion: String,
    val owner: String,
    val items: List<String>,
    /** "none" | "items" */
    val release: String = "none",
)

@Serializable data class PlanSummary(
    val patternId: String,
    val tasks: Int,
    /** "op · phone · ms · ✓" lines in execution order */
    val lines: List<String>,
    val verified: Int,
    val confidence: Double,
    val planHash: String,
)

// ---------------------------------------------------------------------------------------------------------------
// v2 plan §5.3 / §13.3: the capability catalog (assets/models/catalog.json). The plan names a capability; placement
// resolves it to a model id; replicas compare only outputs from the same sha256.

/**
 * One catalog entry. `runtime` is "qairt" (AI Hub bundle, NPU) or "llama_cpp" (GGUF Q4_0); `big` = takes the one big slot.
 * `url` is where the build can be fetched (http(s); null = not downloadable from the app) and `downloadSha256` the hex
 * sha256 of the file at `url` when it differs from `sha256` (e.g. a zip archive of a bundle whose `sha256` is the
 * installed-folder rule). Both are optional so older catalogs still parse.
 */
@Serializable data class ModelEntry(
    val modelId: String,
    val capability: String,
    val runtime: String,
    val sha256: String,
    val sizeBytes: Long,
    val loadMsP50: Int,
    val unitMsP50: Int,
    val maxContext: Int,
    val big: Boolean,
    val url: String? = null,
    val downloadSha256: String? = null,
    /** Decision-model calibration for this build (docs/plans/decision-chunking.md §3); absent = identity, version "none". */
    val calibration: Calibration? = null,
)
