package com.hivecore.planner

import com.hivecore.core.ModelEntry
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpRegistry
import com.hivecore.core.Profile
import com.hivecore.core.TaskSpec
import com.hivecore.engine.Lineage
import com.hivecore.engine.SchedulerImpl
import kotlinx.serialization.json.JsonPrimitive

/** Spec 9.1: where a plan runs. */
enum class ExecutionMode { LOCAL, CLOUD }

/** Spec 9.2 policy settings: the policy picks the default; the user can always flip the choice before the job starts. */
enum class CloudPolicy { ASK_EVERY_TIME, LOCAL_UNLESS_IMPOSSIBLE, FASTEST }

/** List price of the slug that would answer a step (USD per million tokens, spec 15.5). */
data class StepPrice(val slug: String, val usdInPerMillion: Double, val usdOutPerMillion: Double)

/** The prices the CLOUD estimate uses: the text and multimodal escalation slugs, converted at [inrPerUsd]. */
data class CloudPrices(val text: StepPrice, val multimodal: StepPrice, val inrPerUsd: Double)

/** A model a phone would load for the LOCAL option (`swap` = its big slot holds something else now). */
data class ModelLoad(val nodeId: String, val capability: String, val modelId: String?, val swap: Boolean)

/** The LOCAL estimate (spec 9.1): time on N phones vs on one, which phones, which models load. */
data class LocalEstimate(val ms: Long, val singlePhoneMs: Long, val phones: List<String>, val loads: List<ModelLoad>)

/** The CLOUD estimate (spec 9.1): time, cost in INR, bytes that leave, which steps go to the cloud. */
data class CloudEstimate(val ms: Long, val costInr: Double, val bytesOut: Long, val steps: List<String>, val tokensIn: Long, val tokensOut: Long)

/** One step as the plan panel shows it: its lineage and whether the cloud may ever run it (spec 9.3). */
data class StepView(val id: String, val op: Op, val cls: OpClass, val lineage: String, val cloudEligible: Boolean, val reason: String?)

/** One of the two options; [enabled] false carries the [reason] the panel shows greyed out. */
data class ExecutionOption(val mode: ExecutionMode, val enabled: Boolean, val reason: String?, val local: LocalEstimate?, val cloud: CloudEstimate?)

/** Both options, the default the policy picked, whether the user must confirm, and the 9.2 signals that fired. */
data class ExecutionChoice(
    val local: ExecutionOption,
    val cloud: ExecutionOption,
    val default: ExecutionMode,
    val askUser: Boolean,
    val signals: List<String>,
    val steps: List<StepView>,
    /** capabilities no local model serves (spec 9.2 last row), flagged for the compiler's note */
    val capabilityGaps: Set<String>,
)

/**
 * Spec 9: a pure function from a compiled plan, the latest profiles, the model catalog, the cloud prices, the policy
 * and the orchestrator's connectivity to the LOCAL and CLOUD options with their estimates and the default.
 *
 *  - LOCAL: time on N phones = the critical path through the steps, each split across the phones that serve its
 *    capability (spec 10.5 sizing, the scheduler's per-unit defaults); time on one phone = every step on the
 *    orchestrator; which phones take work; which (phone, capability) pairs load a model and whether the big slot swaps.
 *  - CLOUD: LLM/VLM steps whose lineage is public or released run on the orchestrator through the cloud; OWNER, REDUCE
 *    and private-lineage steps stay local. Time = the local makespan of the steps that stay plus one cloud call per
 *    unit; cost = units × (promptTokens × in + maxTokens × out) at list price in INR; bytes = the prompt payload per
 *    unit. Private-lineage steps are never cloud-eligible; with no eligible step the option is disabled with the reason.
 *  - Default (spec 9.2): offline or disabled → LOCAL; a capability gap on a cloud-eligible step → CLOUD; many items on
 *    ≥ 2 eligible phones → LOCAL; long single-shot reasoning on small text → CLOUD; hot or low phones → CLOUD for public
 *    steps. The policy resolves the signals: ask every time (default, [ExecutionChoice.askUser]), local unless
 *    impossible, fastest.
 */
object ExecutionOptions {
    /** cloud round trip per call before tokens stream */
    const val CLOUD_LATENCY_MS = 2_500.0
    /** output tokens per second a mid-tier cloud model streams at */
    const val CLOUD_TOK_PER_SEC = 60.0
    /** "many items": units across the cloud-eligible steps */
    const val MANY_ITEMS = 10
    /** "long single-shot reasoning": max tokens of a one-unit step */
    const val LONG_OUTPUT_TOKENS = 256
    const val HOT_THERMAL = 2
    const val LOW_BATTERY_PCT = 40

    fun compute(
        plan: CompiledPlan,
        profiles: List<Profile>,
        catalog: List<ModelEntry>,
        prices: CloudPrices?,
        policy: CloudPolicy,
        online: Boolean,
        selfId: String,
    ): ExecutionChoice {
        val dag = plan.dag
        val ps = SchedulerImpl.normalise(profiles)
        val self = ps.firstOrNull { it.nodeId == selfId }
        val lineage = lineages(dag.tasks)
        val served = ps.flatMap { p -> p.capabilities.map { it.capability } }.toSet() + catalog.map { it.capability }.toSet()
        val gaps = LinkedHashSet<String>()
        val steps = dag.tasks.map { t ->
            val info = OpRegistry[t.op]
            val cap = capOf(t)
            if (cap != null && cap !in served) gaps += cap
            val lin = lineage.getValue(t.id)
            val (eligible, reason) = when {
                info.cls == OpClass.OWNER -> false to "owner step: runs only on the phone that holds the data"
                info.cls == OpClass.REDUCE -> false to "pure reduce: runs on this phone"
                info.cls != OpClass.LLM && info.cls != OpClass.VLM -> false to "${info.cls.name.lowercase()} step: runs locally"
                lin == Lineage.PRIVATE -> false to "private lineage: never leaves its phone"
                else -> true to null
            }
            StepView(t.id, t.op, info.cls, lin, eligible, reason)
        }
        val cloudSteps = steps.filter { it.cloudEligible }.map { it.id }.toSet()

        // LOCAL
        val local = localEstimate(dag, ps, self, selfId, catalog)

        // CLOUD
        val cloudOption: ExecutionOption
        if (cloudSteps.isEmpty()) {
            val llmSteps = steps.filter { it.cls == OpClass.LLM || it.cls == OpClass.VLM }
            val why = if (llmSteps.isEmpty()) "no step of this plan uses a model the cloud could run"
            else "every model step reads private data, so nothing may leave the phones"
            cloudOption = ExecutionOption(ExecutionMode.CLOUD, false, why, null, null)
        } else if (prices == null) {
            cloudOption = ExecutionOption(ExecutionMode.CLOUD, false, "no cloud model configured", null, null)
        } else {
            val stayDag = dag.copy(tasks = dag.tasks.filter { it.id !in cloudSteps })
            val stay = if (stayDag.tasks.isEmpty()) null else localEstimate(stayDag, ps, self, selfId, catalog)
            var ms = 0.0; var cost = 0.0; var bytes = 0L; var tin = 0L; var tout = 0L
            for (t in dag.tasks) if (t.id in cloudSteps) {
                val units = units(t)
                val prompt = num(t, "promptTokens") ?: SchedulerImpl.DEFAULT_PROMPT_TOKENS
                val maxTok = num(t, T2Params.MAX_TOKENS) ?: SchedulerImpl.DEFAULT_MAX_TOKENS
                val payload = num(t, "payloadBytes") ?: SchedulerImpl.DEFAULT_PAYLOAD_BYTES
                val price = if (OpRegistry[t.op].cls == OpClass.VLM) prices.multimodal else prices.text
                ms += units * (CLOUD_LATENCY_MS + maxTok / CLOUD_TOK_PER_SEC * 1000.0)
                cost += units * (prompt * price.usdInPerMillion + maxTok * price.usdOutPerMillion) / 1_000_000.0 * prices.inrPerUsd
                bytes += (units * payload).toLong(); tin += (units * prompt).toLong(); tout += (units * maxTok).toLong()
            }
            val est = CloudEstimate((ms + (stay?.ms ?: 0L)).toLong(), round2(cost), bytes, dag.tasks.map { it.id }.filter { it in cloudSteps }, tin, tout)
            cloudOption = if (!online) ExecutionOption(ExecutionMode.CLOUD, false, "no internet on this phone", null, est)
            else ExecutionOption(ExecutionMode.CLOUD, true, null, stay, est)
        }
        val localOption = ExecutionOption(ExecutionMode.LOCAL, true, null, local, null)

        // signals (spec 9.2)
        val signals = ArrayList<String>()
        var pushCloud = 0; var pushLocal = 0; var forcedLocal = false; var gapCloud = false
        if (!online) { signals += "no internet on this phone → LOCAL"; forcedLocal = true }
        if (!cloudOption.enabled) { signals += "cloud disabled: ${cloudOption.reason} → LOCAL"; forcedLocal = true }
        val gapSteps = dag.tasks.filter { t -> capOf(t)?.let { it in gaps } == true }
        if (gapSteps.isNotEmpty()) {
            val cloudable = gapSteps.filter { it.id in cloudSteps }
            if (cloudable.isNotEmpty()) { signals += "no local model for ${cloudable.joinToString { it.id }} → CLOUD for that step"; gapCloud = true; pushCloud++ }
            val stuck = gapSteps.filter { it.id !in cloudSteps }
            if (stuck.isNotEmpty()) signals += "no local model for ${stuck.joinToString { it.id }} and the step may not leave (private or owner data)"
        }
        val eligiblePhones = ps.count { it.eligible && it.nodeId != selfId } + 1
        val cloudUnits = dag.tasks.filter { it.id in cloudSteps }.sumOf { units(it) }
        if (cloudUnits >= MANY_ITEMS && eligiblePhones >= 2) { signals += "$cloudUnits items and $eligiblePhones eligible phones → LOCAL"; pushLocal++ }
        val singleShot = cloudSteps.isNotEmpty() && dag.tasks.filter { it.id in cloudSteps }.all { units(it) <= 2 } &&
            dag.tasks.filter { it.id in cloudSteps }.any { (num(it, T2Params.MAX_TOKENS) ?: SchedulerImpl.DEFAULT_MAX_TOKENS) >= LONG_OUTPUT_TOKENS }
        if (singleShot) { signals += "long single-shot reasoning on small text → CLOUD"; pushCloud++ }
        val stressed = self != null && (self.thermal >= HOT_THERMAL || (self.batteryPct < LOW_BATTERY_PCT && !self.charging)) &&
            ps.none { it.eligible && it.nodeId != selfId }
        if (stressed && cloudSteps.isNotEmpty()) { signals += "this phone is hot or low on battery and no other phone is eligible → CLOUD for public steps"; pushCloud++ }

        val default: ExecutionMode; val ask: Boolean
        when {
            forcedLocal -> { default = ExecutionMode.LOCAL; ask = false }
            policy == CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE -> { default = if (gapCloud) ExecutionMode.CLOUD else ExecutionMode.LOCAL; ask = false }
            policy == CloudPolicy.FASTEST -> {
                val cms = cloudOption.cloud?.ms ?: Long.MAX_VALUE
                default = if (gapCloud || cms < local.ms) ExecutionMode.CLOUD else ExecutionMode.LOCAL; ask = false
            }
            else -> { default = if (gapCloud || pushCloud > pushLocal) ExecutionMode.CLOUD else ExecutionMode.LOCAL; ask = true }
        }
        return ExecutionChoice(localOption, cloudOption, default, ask, signals, steps, gaps)
    }

    /** Strictest lineage per task (spec 15.2 step 3): own `privacy`, owner data is private, blobs are released, inputs inherited. */
    fun lineages(tasks: List<TaskSpec>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (t in tasks) {
            var lin = when (str(t, T2Params.PRIVACY)) { Lineage.PRIVATE -> Lineage.PRIVATE; Lineage.RELEASED -> Lineage.RELEASED; else -> Lineage.PUBLIC }
            if (OpRegistry[t.op].cls == OpClass.OWNER) lin = Lineage.PRIVATE
            if (t.blobs.isNotEmpty()) lin = Lineage.strictest(lin, Lineage.RELEASED)
            for (i in t.inputs) out[i]?.let { lin = Lineage.strictest(lin, it) }
            out[t.id] = lin
        }
        return out
    }

    /**
     * The LOCAL time model (spec 10.5 sizing): a step over `items`/`units` with a split axis is cut across the phones
     * that can serve its capability (plus the orchestrator), so it takes `ceil(units / phones) × unit ms` on N phones
     * and `units × unit ms` on one; OWNER steps run on their owner, REDUCE steps on the orchestrator. The plan's time is
     * its critical path through those step times; the single-phone baseline is every step on the orchestrator.
     */
    private fun localEstimate(dag: com.hivecore.core.Dag, ps: List<Profile>, self: Profile?, selfId: String, catalog: List<ModelEntry>): LocalEstimate {
        val finish = HashMap<String, Double>()
        var critical = 0.0; var single = 0.0
        val phones = sortedSetOf<String>()
        val loads = LinkedHashMap<Pair<String, String>, ModelLoad>()
        for (t in dag.tasks) {
            val info = OpRegistry[t.op]
            val cap = capOf(t)
            val units = units(t)
            val cands: List<Profile?> = when (info.cls) {
                OpClass.OWNER -> listOf(ps.firstOrNull { it.nodeId == (t.owner ?: selfId) })
                OpClass.REDUCE -> listOf(self)
                else -> {
                    val workers = ps.filter { p -> p.nodeId != selfId && p.eligible && (info.cls != OpClass.HEAVY || p.heavyOk) && serves(p, cap, info.cls) }
                    listOf(self) + workers
                }
            }
            val ids = cands.mapIndexed { i, p -> p?.nodeId ?: if (i == 0 && info.cls != OpClass.OWNER) selfId else (t.owner ?: selfId) }
            val unit = cands.map { unitMs(t, it, info.cls) }.sorted().let { it[it.size / 2] }
            val splits = info.cls != OpClass.OWNER && info.cls != OpClass.REDUCE && (str(t, T2Params.AXIS) != null || info.shard != null) && units > 1
            val n = if (splits) ids.distinct().size else 1
            val ms = Math.ceil(units.toDouble() / n) * unit + warmMs(t, cands.firstOrNull(), info.cls)
            val start = t.inputs.maxOfOrNull { finish[it] ?: 0.0 } ?: 0.0
            finish[t.id] = start + ms
            critical = maxOf(critical, finish[t.id]!!)
            single += units * unitMs(t, self, info.cls) + warmMs(t, self, info.cls)
            val used = if (splits) ids.distinct() else listOf(ids.first())
            phones += used
            if (cap != null && info.cls != OpClass.OWNER && info.cls != OpClass.REDUCE) for (node in used) {
                val entry = catalog.firstOrNull { it.capability == cap }
                val p = ps.firstOrNull { it.nodeId == node }
                val warm = p?.capabilities?.any { it.capability == cap && it.warm } == true
                val bigKind = when (info.cls) { OpClass.LLM -> "llm"; OpClass.VLM -> "vlm"; else -> null }
                val swap = if (bigKind != null || entry?.big == true) (p?.big ?: "none") != bigKind && !warm else !warm
                loads.putIfAbsent(node to cap, ModelLoad(node, cap, entry?.modelId, swap))
            }
        }
        return LocalEstimate(critical.toLong(), single.toLong(), phones.toList(), loads.values.toList())
    }

    private fun serves(p: Profile, cap: String?, cls: OpClass): Boolean = when (cls) {
        OpClass.LLM -> p.big == "llm" || (cap != null && p.capabilities.any { it.capability == cap })
        OpClass.VLM -> p.big == "vlm" || (cap != null && p.capabilities.any { it.capability == cap })
        else -> cap == null || p.capabilities.any { it.capability == cap } || p.capabilities.isEmpty()
    }

    /** Expected ms per unit on [p] (the scheduler's defaults: spec 10.5 step 3). */
    private fun unitMs(t: TaskSpec, p: Profile?, cls: OpClass): Double = when (cls) {
        OpClass.LLM, OpClass.VLM -> {
            val prompt = num(t, "promptTokens") ?: SchedulerImpl.DEFAULT_PROMPT_TOKENS
            val maxTok = num(t, T2Params.MAX_TOKENS) ?: SchedulerImpl.DEFAULT_MAX_TOKENS
            val tps = p?.tokPerSec?.takeIf { it > 0 } ?: SchedulerImpl.DEFAULT_TOK_PER_SEC
            prompt / SchedulerImpl.DEFAULT_PREFILL_TOK_PER_SEC * 1000.0 + maxTok / tps * 1000.0
        }
        OpClass.HEAVY -> t.modelId?.let { p?.p50?.get(it) } ?: SchedulerImpl.DEFAULT_HEAVY_P50_MS
        else -> t.modelId?.let { p?.p50?.get(it) } ?: SchedulerImpl.DEFAULT_P50_MS
    }

    private fun warmMs(t: TaskSpec, p: Profile?, cls: OpClass): Double {
        if (cls == OpClass.OWNER || cls == OpClass.REDUCE) return 0.0
        val cap = capOf(t) ?: return 0.0
        val warm = p?.capabilities?.any { it.capability == cap && it.warm } == true ||
            (cls == OpClass.LLM && p?.big == "llm") || (cls == OpClass.VLM && p?.big == "vlm")
        return if (warm) 0.0 else num(t, "warmMs") ?: SchedulerImpl.DEFAULT_WARM_MS
    }

    private fun capOf(t: TaskSpec): String? = str(t, T2Params.CAP) ?: OpRegistry[t.op].capability
    private fun units(t: TaskSpec): Int = (num(t, "items") ?: num(t, "units") ?: 1.0).toInt().coerceAtLeast(1)
    private fun num(t: TaskSpec, k: String): Double? = (t.params[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
    private fun str(t: TaskSpec, k: String): String? = (t.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
