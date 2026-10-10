package com.hivecore.cloud

import com.hivecore.core.Canon
import com.hivecore.core.JsonSchema
import com.hivecore.core.OpClass
import com.hivecore.core.OpRegistry
import com.hivecore.core.SLog
import com.hivecore.core.TaskSpec
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.JsonSchemaValidator
import com.hivecore.engine.Lineage
import com.hivecore.engine.SchemaValidator
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.planner.T2Params
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Spec 15.1: which automatic triggers may fire; the time budget is off by default. */
data class TriggerSettings(
    val capabilityGap: Boolean = true,
    val verificationFailed: Boolean = true,
    val lowConfidence: Boolean = true,
    val userVerdict: Boolean = true,
    val timeBudget: Boolean = false,
    /** spec 9.1: the CLOUD option picked in the plan panel */
    val userChoice: Boolean = true,
) {
    fun enabled(t: EscalationTrigger): Boolean = when (t) {
        is EscalationTrigger.CapabilityGap -> capabilityGap
        is EscalationTrigger.VerificationFailed -> verificationFailed
        is EscalationTrigger.LowConfidence -> lowConfidence
        is EscalationTrigger.UserVerdict -> userVerdict
        is EscalationTrigger.TimeBudget -> timeBudget
        is EscalationTrigger.UserChoice -> userChoice
    }
}

/**
 * Spec 15: the one gate on the orchestrator through which a failed local step may be re-run on a cloud model. It
 * implements the engines' [Escalator] seam; `:app` injects it into the Commander and SplitCommander factories only.
 *
 * Pre-flight, in order (15.2): the trigger is on; `plan.commander == selfId`; the op is not OWNER-class; the lineage
 * is public or released; the request carries only the step's prompt, inputs and schema ([CloudPayload]); spend caps;
 * consent (remembered per job or per month). A failed check is [GateOutcome.Refused] with the reason.
 *
 * Procedure (15.3): same inputs and schema, `temperature 0`, strict schema, the role's fallback chain; the reply is
 * validated against the schema and hashed (`sha256(canonical(output))`); a ledger `task` entry carries
 * `source = cloud:<slug>`, the trigger, bytes, tokens, cost and the local attempts replaced; the output is memoized
 * under `cloud:<slug>:<chunkId>`, never under the local chunk id. A cloud failure is [GateOutcome.Failed]: the job
 * never retries on a node because the cloud failed.
 */
class EscalationGate(
    private val selfId: String,
    private val cloud: CloudClient,
    private val config: ModelsConfig,
    private val caps: SpendCaps,
    private val consent: ConsentSheet,
    private val consentStore: ConsentStore,
    private val ledger: LedgerSink,
    private val memo: ChunkMemo = ChunkMemo.None,
    private val triggers: () -> TriggerSettings = { TriggerSettings() },
    private val validator: SchemaValidator = JsonSchemaValidator,
    private val clock: () -> Long = System::currentTimeMillis,
) : Escalator {

    override suspend fun tryEscalate(req: EscalationRequest): GateOutcome {
        val out = try {
            gate(req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SLog.e(TAG, "gate failed", e)
            GateOutcome.Failed(e.message ?: "escalation failed")
        }
        val (outcome, reason) = when (out) {
            is GateOutcome.Ran -> "ran" to null
            is GateOutcome.Refused -> "refused" to out.reason
            is GateOutcome.Failed -> "failed" to out.reason
        }
        if (out !is GateOutcome.Ran) ledger.decision(GateDecision(req.jobId, req.node.id, req.trigger.name, outcome, reason, null, 0, 0, 0, 0.0, clock()))
        return out
    }

    private suspend fun gate(req: EscalationRequest): GateOutcome {
        val step = req.node
        val info = OpRegistry[step.op]
        // 15.1: only the listed triggers, and only when switched on
        if (!triggers().enabled(req.trigger)) return GateOutcome.Refused("the ${req.trigger.name.replace('_', ' ')} trigger is switched off")
        // 15.2 (1): P3, the orchestrator role only
        if (req.commander != selfId) return GateOutcome.Refused("only the phone that made the request may use the cloud")
        // (2): no OWNER-class step
        if (info.cls == OpClass.OWNER) return GateOutcome.Refused("${step.op.name.lowercase()} '${step.id}' reads owner data and never leaves its phone")
        // (3): lineage public or released
        if (req.privacy == Lineage.PRIVATE) return GateOutcome.Refused("'${step.id}' derives from private data, so no cloud re-run")
        if (req.privacy != Lineage.PUBLIC && req.privacy != Lineage.RELEASED) return GateOutcome.Refused("'${step.id}' has an unknown privacy lineage '${req.privacy}'")
        // (4): only the prompt, the inputs and the schema
        val schema = step.params[SchemaValidator.PARAM] as? JsonObject ?: return GateOutcome.Refused("'${step.id}' declares no output schema, so a cloud answer could not be checked")
        JsonSchema.schemaError(schema)?.let { return GateOutcome.Refused("'${step.id}' schema is not valid: $it") }
        val payload = CloudPayload.build(step, req.inputs, schema, req.media)
        val role = if (req.media.isNotEmpty() || info.cls == OpClass.VLM) CloudRole.ESCALATE_MULTIMODAL else CloudRole.ESCALATE_TEXT
        val slug = config.primary(role)
        val maxTokens = ((step.params[T2Params.MAX_TOKENS] as? JsonPrimitive)?.intOrNull ?: config.role(role).maxTokens).coerceIn(1, config.role(role).maxTokens)
        // (5): spend
        val estimateInr = config.estimateInr(slug, payload.tokensIn, maxTokens.toLong())
        caps.check(role, req.jobId, estimateInr)?.let { return GateOutcome.Refused(it) }
        // (6): consent, remembered per job or per month. Picking the CLOUD option in the plan panel (9.1) is the consent
        // for that plan: the panel already named the steps, the cost and what leaves, so the sheet is not shown again.
        val month = caps.month()
        if (req.trigger is EscalationTrigger.UserChoice) consentStore.allowJob(req.jobId)
        if (!consentStore.allowed(req.jobId, month)) {
            val ask = ConsentRequest(req.jobId, step.id, slug, payload.bytes, payload.items, req.privacy, req.trigger.name, estimateInr)
            when (consent.ask(ask)) {
                ConsentDecision.KEEP_LOCAL -> return GateOutcome.Refused("you chose to keep '${step.id}' local")
                ConsentDecision.ALLOW_ONCE -> consentStore.allowJob(req.jobId)
                ConsentDecision.ALLOW_MONTH -> consentStore.allowMonth(month)
            }
        }
        // 15.3 (1): the call
        val reply = cloud.complete(role, payload.messages, schema, maxTokens).getOrElse { e ->
            val kind = (e as? CloudException)?.kind
            return GateOutcome.Failed(when (kind) {
                CloudException.Kind.NO_COMPLIANT_ENDPOINT -> e.message ?: "no compliant endpoint"
                else -> "cloud call failed: ${e.message}"
            })
        }
        // (2): validate and hash
        validator.validate(reply.output, schema)?.let { return GateOutcome.Failed("the cloud answer failed the schema: $it") }
        val hash = Canon.hash(reply.output)
        // (3): ledger
        val costInr = reply.costUsd?.let { it * config.inrPerUsd } ?: config.estimateInr(reply.slug, reply.tokensIn.toLong(), reply.tokensOut.toLong())
        caps.record(req.jobId, costInr)
        ledger.task(CloudTaskEntry(req.jobId, req.planId, step.id, req.chunkId, reply.slug, req.trigger.name, reply.bytesSent, reply.tokensIn, reply.tokensOut,
            costInr, reply.costUsd, req.localAttempts, hash, clock()))
        ledger.decision(GateDecision(req.jobId, step.id, req.trigger.name, "ran", null, reply.slug, reply.bytesSent, reply.tokensIn, reply.tokensOut, reply.costUsd ?: 0.0, clock()))
        // (5): memo under the cloud key only
        memo.put(memoKey(reply.slug, req.chunkId ?: step.id), hash, reply.output)
        SLog.d(TAG, "${step.op} '${step.id}' answered by cloud:${reply.slug} (${req.trigger.name}, ${reply.attempts} attempt(s))")
        return GateOutcome.Ran(reply.output, hash, reply.slug)
    }

    companion object {
        const val TAG = "EscalationGate"
        fun memoKey(slug: String, chunkId: String): String = "cloud:$slug:$chunkId"
    }
}

/**
 * Spec 15.2 step 4: what leaves the phone for an escalated step is exactly its prompt (the op and the prompt-bearing
 * params), its inputs and its output schema. Anything else in the task params (catalog `expect`, owners, model ids,
 * placement hints) is dropped here, so no catalog value can ride along.
 */
object CloudPayload {
    /** The params that are the step's prompt. */
    val PROMPT_KEYS = listOf(T2Params.TEMPLATE, "prompt", "instruction", "q", "question", "labels", "fields", "k", "lang")

    data class Payload(val messages: List<Msg>, val bytes: Long, val tokensIn: Long, val items: Int)

    fun build(step: TaskSpec, inputs: Map<String, JsonElement>, schema: JsonObject, media: List<ByteArray> = emptyList()): Payload {
        val prompt = StringBuilder()
        prompt.append("Operation: ").append(step.op.name).append('\n')
        for (k in PROMPT_KEYS) step.params[k]?.let { prompt.append(k).append(": ").append(if (it is JsonPrimitive) it.content else Canon.canonical(it)).append('\n') }
        if (inputs.isNotEmpty()) {
            prompt.append("Inputs:\n")
            for ((id, v) in inputs.toSortedMap()) prompt.append(id).append(": ").append(Canon.canonical(v)).append('\n')
        }
        val system = "You re-run one step of an on-device pipeline whose local models could not agree. Reply with exactly one JSON value " +
            "that satisfies this JSON Schema, and nothing else.\nSchema: " + Canon.canonical(schema)
        val user = prompt.toString()
        val bytes = user.toByteArray(Charsets.UTF_8).size.toLong() + system.toByteArray(Charsets.UTF_8).size + media.sumOf { it.size }
        val items = inputs.values.sumOf { countItems(it) }.coerceAtLeast(1) + media.size
        return Payload(listOf(Msg("system", system), Msg("user", user, media)), bytes, bytes / 4 + media.size * IMAGE_TOKENS, items)
    }

    private fun countItems(e: JsonElement): Int = when (e) {
        is kotlinx.serialization.json.JsonArray -> e.size
        is JsonObject -> listOf("items", "texts", "units", "picks", "segments").firstNotNullOfOrNull { (e[it] as? kotlinx.serialization.json.JsonArray)?.size } ?: 1
        else -> 1
    }

    /** tokens an image costs on the multimodal slugs, for the estimate */
    const val IMAGE_TOKENS = 1000L
}
