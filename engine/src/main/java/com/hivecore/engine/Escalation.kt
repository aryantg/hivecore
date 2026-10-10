package com.hivecore.engine

import com.hivecore.core.JsonSchema
import com.hivecore.core.TaskSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// v2 plan §7 / §13.4: the Escalation Gate is the only path to a cloud model and lives in the cloud package. The split
// engine and the composite Commander (this package) only know this seam: they describe why the local run proved not
// enough and get back an output labelled with its source, or a refusal. Nothing here depends on the cloud package
// (rule C1: the checkCloudBoundary Gradle task fails the build otherwise); the gate implements [Escalator] and the
// app wiring injects it into the Commander and SplitCommander factories only (never into a node-side component).

/** Why the local run proved not enough (§7.1). Nothing else escalates. */
@Serializable sealed class EscalationTrigger {
    @Serializable data class CapabilityGap(val capability: String) : EscalationTrigger()
    @Serializable data class VerificationFailed(val reason: String, val attempts: Int) : EscalationTrigger()
    @Serializable data class LowConfidence(val confidence: Double) : EscalationTrigger()
    @Serializable data object UserVerdict : EscalationTrigger()
    @Serializable data class TimeBudget(val estimateMs: Long, val budgetMs: Long) : EscalationTrigger()
    /** Spec 9.1: the user picked the CLOUD option in the plan panel, so the eligible steps go to the cloud first (not a 15.1 trigger: nothing failed). */
    @Serializable data object UserChoice : EscalationTrigger()

    /** The short name written to the journal's `escalations.trigger` and the ledger. */
    val name: String get() = when (this) {
        is CapabilityGap -> "capability_gap"; is VerificationFailed -> "verification_failed"
        is LowConfidence -> "low_confidence"; is UserVerdict -> "user_verdict"; is TimeBudget -> "time_budget"
        is UserChoice -> "user_choice"
    }
}

/** Privacy lineage of a task's inputs (C3): derived outputs inherit the strictest input privacy. */
object Lineage {
    const val PUBLIC = "public"
    /** released by the owner for this job (§4A.1); may leave the device like public data */
    const val RELEASED = "released"
    const val PRIVATE = "private"
    /** The `TaskSpec.params` key a plan node states its own input privacy under (planner `T2Params.PRIVACY`). */
    const val PARAM = "privacy"
    fun strictest(a: String, b: String): String = if (a == PRIVATE || b == PRIVATE) PRIVATE else if (a == RELEASED || b == RELEASED) RELEASED else PUBLIC

    /**
     * Strictest lineage per task of a plan (spec 15.2 step 3), tasks in topological order: the node's own
     * `params.privacy`, OWNER-class ops are private, blobs are released, and every input's lineage is inherited.
     */
    fun lineages(tasks: List<com.hivecore.core.TaskSpec>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (t in tasks) {
            var lin = when ((t.params[PARAM] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content) {
                PRIVATE -> PRIVATE; RELEASED -> RELEASED; else -> PUBLIC
            }
            if (com.hivecore.core.OpRegistry[t.op].cls == com.hivecore.core.OpClass.OWNER) lin = PRIVATE
            if (t.blobs.isNotEmpty()) lin = strictest(lin, RELEASED)
            for (i in t.inputs) out[i]?.let { lin = strictest(lin, it) }
            out[t.id] = lin
        }
        return out
    }
}

/** Spec 15.1 / 20.2 verification thresholds: when a local result is not enough and the gate is asked. */
object EscalationThresholds {
    /** A local judge (VERIFY / EVAL) scoring the judged step below this escalates that step. */
    const val JUDGE = 0.7
    /** A VOTE merge whose winning share is below this escalates the step. */
    const val VOTE = 0.6
    /** Schema validation failures of one step that escalate it, when spread over at least [SCHEMA_PHONES] phones. */
    const val SCHEMA_ATTEMPTS = 3
    const val SCHEMA_PHONES = 2
    /** Replicas that all disagree (no agreeing pair) before the step escalates. */
    const val DISAGREEING_REPLICAS = 3
}

/**
 * One failed node of one job, exactly as the local run saw it: the same [node] TaskSpec (with its `schema` in params
 * when the op has one) and the same [inputs]. [privacy] is the lineage of the inputs ([Lineage]); [localAttempts] is
 * how many local attempts the cloud answer replaces; [chunkId] is set for split-job chunks.
 */
data class EscalationRequest(
    val jobId: String,
    val planId: String,
    val commander: String,
    val node: TaskSpec,
    val inputs: Map<String, JsonElement>,
    val privacy: String,
    val trigger: EscalationTrigger,
    val localAttempts: Int,
    val chunkId: String? = null,
    /** bytes of released media to send once when the capability gap is multimodal (§7.4); empty otherwise */
    val media: List<ByteArray> = emptyList(),
)

/** What the gate decided (§13.4). */
sealed class GateOutcome {
    /** The cloud answered; [outputHash] = sha256(canonical(output)); [source] = `cloud:<slug>` (C5). */
    data class Ran(val output: JsonElement, val outputHash: String, val slug: String) : GateOutcome() {
        val source: String get() = "cloud:$slug"
    }
    /** A pre-flight check refused: private lineage, owner-class op, cap, no consent, not the commander, no compliant endpoint. */
    data class Refused(val reason: String) : GateOutcome()
    /** The cloud path itself failed (HTTP after the fallback chain, validation of the cloud output). The job never retries on a node because of this (§7.5). */
    data class Failed(val reason: String) : GateOutcome()
}

/** The seam the engines call. The cloud package's EscalationGate implements it; [Off] is the default everywhere. */
interface Escalator {
    suspend fun tryEscalate(req: EscalationRequest): GateOutcome

    /** No cloud (feature flag off, node role, tests). */
    object Off : Escalator {
        override suspend fun tryEscalate(req: EscalationRequest): GateOutcome = GateOutcome.Refused("cloud escalation is off")
    }
}

/**
 * Validates an op output against the JSON Schema in the task's `params.schema` (§6.2, §7.3). Returns null when valid,
 * else the first error. The draft 2020-12 validator lives in core/JsonSchema.kt; engines take this function so tests
 * can stub it. [TaskSpec.params] key: `schema`.
 */
fun interface SchemaValidator {
    fun validate(output: JsonElement, schema: JsonObject): String?

    companion object {
        /** Accepts everything (pre-v2 behaviour). */
        val NONE = SchemaValidator { _, _ -> null }
        const val PARAM = "schema"
    }
}
