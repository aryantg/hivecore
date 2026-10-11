package com.hivecore.planner

import com.hivecore.engine.Lineage
import com.hivecore.engine.SchemaValidator
import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Op
import com.hivecore.core.PlannedQuestion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

// v2 plan §6.1 / §14: the cloud planner tier as the planner sees it. Only the interface lives here; the implementation
// is in the cloud package and is injected by the app wiring (behind BuildConfig.FEATURE_CLOUD). Nothing in this file
// knows about HTTP, keys or slugs.

/**
 * T2: asks a cloud model for a §6.2 graph when the local tiers cannot produce a confident plan. Implementations build
 * the payload from names and flags only (C4), convert the graph to a [com.hivecore.core.Dag] whose
 * [TaskSpec.params][com.hivecore.core.TaskSpec.params] carry the graph fields under the [T2Params] keys, run
 * the plan compiler, and make one [repair] round with the error list before giving up. Results carry `tier = "T2"`.
 */
interface T2Planner {
    /**
     * @param catalogs     every phone's public catalog; sent (names and flags only) only when schema sharing is on
     * @param capabilities the swarm's capability summary (catalog capability names any member can serve)
     * @param opRegistry   ops the swarm can execute; the graph may only use these
     */
    suspend fun plan(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>): Result<PlannedQuestion>

    /** The one repair round (§6.3): the same payload plus the [previous] graph and the compiler's [errors]. */
    suspend fun repair(question: String, catalogs: List<Catalog>, capabilities: Set<String>, opRegistry: Set<Op>,
                       previous: JsonElement, errors: List<String>): Result<PlannedQuestion>
}

/**
 * Where a §6.2 graph node's fields live in [TaskSpec.params][com.hivecore.core.TaskSpec.params] after the
 * T2 conversion. The compiler, the scheduler and the gate read them under these fixed keys.
 */
object T2Params {
    /** catalog capability the node needs, e.g. "ocr", "llm.json" (string) */
    const val CAP = "cap"
    /** split axis: "shard" | "chunk" | "fanout" (string); the compiler requires a merge kind for (op, axis) */
    const val AXIS = "axis"
    /** replica count (integer, default 2 for LLM/VLM ops) */
    const val REPLICAS = "replicas"
    /** JSON Schema (draft 2020-12 subset, no `$ref`) the output must satisfy (object); same key as [SchemaValidator.PARAM] */
    const val SCHEMA = SchemaValidator.PARAM
    /** privacy lineage of the node's own plan input: "public" | "released" | "private" ([Lineage]); inherited downstream */
    const val PRIVACY = "privacy"
    /** the named plan input the node reads, e.g. "items" (string) */
    const val INPUT = "input"
    /** LLM_MAP prompt template; `{item}` is replaced by each item's text (string) */
    const val TEMPLATE = "template"
    /** LLM_MAP / escalation output token cap (integer) */
    const val MAX_TOKENS = "maxTokens"
    /** never allowed (rule C2): a node may not ask for the cloud; the compiler rejects any task carrying it */
    const val EXEC = "exec"
    /** conditional edges into the node (spec 7.3): `{producerId: "if_empty" | "if_confidence_below:<x>" | "if_count_above:<n>"}`; absent = always */
    const val WHEN = "when"

    const val PATTERN_ID = "t2"
    const val VERSION = "t2-graph-1"

    val AXES = setOf("shard", "chunk", "fanout")
}

/** Cache keys of §6.1: `sha256(question + catalogHash + capabilityHash)`. */
object PlanKeys {
    fun catalogHash(catalogs: List<Catalog>): String =
        Canon.hash(Canon.json.encodeToJsonElement(ListSerializer(Catalog.serializer()), catalogs.sortedBy { it.node }))

    fun capabilityHash(capabilities: Set<String>): String = Canon.sha256Hex(capabilities.sorted().joinToString("\n"))

    fun key(question: String, catalogs: List<Catalog>, capabilities: Set<String>): String =
        Canon.sha256Hex(question.trim().lowercase() + catalogHash(catalogs) + capabilityHash(capabilities))
}

/** A cached plan (spec 7.2): the plan and the tier that produced it ("T1" | "T2"). */
data class CachedPlan(val plan: PlannedQuestion, val tier: String)

/** A small LRU plan cache keyed by [PlanKeys.key]; entries record their tier (spec 7.2). */
class PlanCache(private val max: Int = 64) {
    private val map = LinkedHashMap<String, CachedPlan>(16, 0.75f, true)

    @Synchronized fun get(key: String): CachedPlan? = map[key]

    @Synchronized fun put(key: String, plan: PlannedQuestion, tier: String) {
        map[key] = CachedPlan(plan, tier)
        while (map.size > max) map.remove(map.keys.first())
    }

    @Synchronized fun clear() = map.clear()
    val size: Int @Synchronized get() = map.size
}

/**
 * Words that name a capability in a question, and the capability each op needs. The planner escalates to T2 when a
 * question names a capability that no text pattern's ops cover (§6.1); catalog capability names follow §5.3.
 */
object CapabilityCues {
    val WORDS: Map<String, List<String>> = linkedMapOf(
        "ocr" to listOf("ocr", "scan", "scanned", "scans", "receipt", "receipts", "handwritten"),
        "asr" to listOf("transcribe", "transcript", "transcription", "recording", "recordings", "audio", "voice note"),
        "vlm" to listOf("photo", "photos", "picture", "pictures", "image", "images", "screenshot", "screenshots"),
        "detect" to listOf("detect", "count the people", "objects in"),
        "image.gen" to listOf("draw", "drawing", "sketch", "illustrate", "illustration", "illustrations", "image of", "images of",
            "picture of", "pictures of", "create an image", "make an image", "generate an image", "generate images", "logo", "poster", "artwork"),
        "llm.json" to listOf("extract fields", "fill in", "structured"),
        "llm.text" to listOf("summarise", "summarize", "summary", "translate", "rewrite"),
    )

    /** The catalog capability [op] runs on (null = pure Kotlin or owner data only). */
    fun of(op: Op): String? = when (op) {
        Op.OCR, Op.LAYOUT -> "ocr"
        Op.ASR, Op.VAD_SPLIT -> "asr"
        Op.VLM_ASK -> "vlm"
        Op.DETECT, Op.SEGMENT, Op.TRACK_AGG -> "detect"
        Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.UPSCALE, Op.SUPERRES -> "image.gen"
        Op.FIELDS, Op.METADATA, Op.LLM_MAP, Op.AGG -> "llm.json"
        Op.EXTRACT, Op.SUMMARIZE, Op.GEN_TEXT, Op.PHRASE, Op.DOC -> "llm.text"
        else -> null
    }

    /**
     * Capabilities whose cue words occur in [question] as whole words (case-insensitive, plural-insensitive). A request
     * to draw ("create an image of …") names image.gen only: its "image" is the one being made, not one being looked at.
     */
    fun named(question: String): Set<String> {
        val q = QText(question)
        val named = WORDS.filterValues { words -> words.any { q.find(it) != null } }.keys
        return if ("image.gen" in named) named - "vlm" else named
    }
}
