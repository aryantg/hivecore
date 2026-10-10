package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.Confidence
import com.hivecore.core.Op
import com.hivecore.core.OpRegistry
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.Planner
import com.hivecore.core.RouteDecision
import com.hivecore.core.SLog
import com.hivecore.core.SwarmRouter
import kotlinx.coroutines.CancellationException

/** What the swarm can run right now (spec 7.2 "swarm capability summary"): catalog capabilities and executable ops. */
data class CapabilitySummary(
    val capabilities: Set<String>,
    val ops: Set<Op> = OpRegistry.entries.keys,
) {
    companion object {
        /** Every registry capability and op: the summary of a swarm whose profiles are not known yet. */
        val ALL = CapabilitySummary(OpRegistry.capabilities)
    }
}

/** The planner tier that produced a plan (spec 7.2). */
enum class PlanTier { T0, T1, T2 }

/**
 * The outcome of [TieredPlanner.plan]: the T0 route, the plan (null when no tier produced one), its compilation and
 * the tier. [ok] means the plan compiled and needs no clarification; [clarification] is what the Ask screen shows when
 * the planner is unsure (spec 7.4: T1's best candidate plus the top two readings); [refusal] is the sentence shown when
 * there is no plan at all. [note] explains a tier change ("cloud planner: …").
 */
data class TieredPlan(
    val route: RouteDecision,
    val tier: PlanTier,
    val plan: PlannedQuestion? = null,
    val compiled: CompiledPlan? = null,
    val fromCache: Boolean = false,
    val refusal: String? = null,
    val note: String? = null,
) {
    val clarification: List<String> get() = plan?.clarification ?: emptyList()
    val ok: Boolean get() = plan != null && compiled?.ok == true && refusal == null && clarification.isEmpty()
    val planHash: String? get() = compiled?.planHash
}

/** Spec 18.3 `Planner.plan(question, catalogs, caps)`: T0 → T1 → T2 with the tier rules of spec 7.2. */
interface TieredPlanner {
    suspend fun plan(question: String, catalogs: List<Catalog>, caps: CapabilitySummary): TieredPlan
}

/**
 * The planner tiers of spec 7.2 on the orchestrator.
 *
 *  - T0: the cue lexicon ([router]) finds owners and cues; nothing leaves the device.
 *  - T1: the pattern library and closed choices ([t1]) when T0 found owners or cues.
 *  - T2: the cloud planner ([t2], injected only in the orchestrator wiring, null elsewhere) when T1 confidence is below
 *    [Confidence.LOW] after one clarification (a clarification was returned within [clarifyWindowMs]), when no pattern
 *    matched, or when the question names a capability no pattern covers ([CapabilityCues]). [t2First] (a setting)
 *    asks T2 before T1. A T2 failure (spec 7.4: both the proposal and its repair round were rejected) falls back to
 *    T1's best candidate plus its clarification listing the top two readings, carried as a planner failure in [TieredPlan.note].
 *
 * Every plan is compiled ([compiler], spec 7.4) with the swarm's capabilities and ops; confident, compiled plans are
 * cached under `sha256(question + catalogHash + capabilityHash)` ([PlanKeys]) with their tier.
 */
class TieredPlannerImpl(
    private val selfId: String,
    private val router: SwarmRouter,
    private val t1: Planner,
    private val compiler: PlanCompilerImpl,
    private val t2: T2Planner? = null,
    private val t2First: () -> Boolean = { false },
    private val modelHash: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    private val cache: PlanCache? = PlanCache(),
    private val patterns: Patterns? = null,
    private val clarifyWindowMs: Long = CLARIFY_WINDOW_MS,
) : TieredPlanner {

    @Volatile private var lastClarificationAt: Long? = null

    /** Capabilities the text patterns' ops run on; a question naming any other capability goes to T2. */
    private val coveredCapabilities: Set<String> by lazy {
        patterns?.textPatterns?.flatMap { p -> p.dag.mapNotNull { CapabilityCues.of(it.op) } }?.toSet()
            ?: Patterns.DEFAULT_COVERED_CAPABILITIES
    }

    override suspend fun plan(question: String, catalogs: List<Catalog>, caps: CapabilitySummary): TieredPlan {
        val route = router.route(question, selfId, catalogs)
        val key = PlanKeys.key(question, catalogs, caps.capabilities)
        cache?.get(key)?.let { hit ->
            SLog.d(TAG, "plan cache hit (${hit.tier})")
            return TieredPlan(route, PlanTier.valueOf(hit.tier), hit.plan, compile(hit.plan, catalogs, caps), fromCache = true)
        }
        val cloud = t2
        if (cloud != null && t2First()) {
            val r = runT2(cloud, question, catalogs, caps)
            if (r.plan != null && r.compiled?.ok == true) return remember(key, r)
            SLog.w(TAG, "T2 first failed (${r.note}); local tiers")
        }
        val t1Result: Result<PlannedQuestion>? = if (route.composite) t1.plan(question, route, catalogs) else null
        val local = fromT1(route, t1Result, catalogs, caps)
        val why = if (cloud == null || t2First()) null else t2Reason(question, route, t1Result)
        if (cloud == null || why == null) return remember(key, local)
        SLog.d(TAG, "T2: $why")
        val r = runT2(cloud, question, catalogs, caps)
        if (r.plan != null && r.compiled?.ok == true) return remember(key, r.copy(note = why))
        SLog.w(TAG, "T2 failed (${r.note}); T1 result stands")
        // spec 7.4: T1's best candidate plus a clarification listing the top two readings, with the cloud failure noted
        val note = "cloud planner: ${r.note ?: "no plan"}"
        return remember(key, if (local.plan != null) local.copy(note = note)
            else local.copy(refusal = (local.refusal ?: "no plan") + " ($note)", note = note))
    }

    /** Why T1's result is not enough (null = it is). */
    private fun t2Reason(question: String, route: RouteDecision, t1: Result<PlannedQuestion>?): String? {
        val uncovered = CapabilityCues.named(question) - coveredCapabilities
        if (uncovered.isNotEmpty()) return "the question names ${uncovered.sorted().joinToString(", ")}, which no pattern covers"
        if (t1 == null) return null
        if (t1.exceptionOrNull() is NoPatternFits) return "no pattern matched"
        val pq = t1.getOrNull() ?: return null
        val since = lastClarificationAt
        if (pq.confidence < Confidence.LOW && since != null && clock() - since <= clarifyWindowMs)
            return "T1 confidence ${"%.2f".format(pq.confidence)} < ${Confidence.LOW} after one clarification"
        return null
    }

    private fun fromT1(route: RouteDecision, t1: Result<PlannedQuestion>?, catalogs: List<Catalog>, caps: CapabilitySummary): TieredPlan {
        if (t1 == null) return TieredPlan(route, PlanTier.T0, refusal = "That isn't a question over the swarm's tables or documents (${route.reason}).")
        val pq = t1.getOrElse { e -> return TieredPlan(route, PlanTier.T1, refusal = e.message ?: "this question cannot be planned") }
        if (pq.dag.tasks.isEmpty()) return TieredPlan(route, PlanTier.T1, pq, null)
        return TieredPlan(route, PlanTier.T1, pq, compile(pq, catalogs, caps))
    }

    private suspend fun runT2(cloud: T2Planner, question: String, catalogs: List<Catalog>, caps: CapabilitySummary): TieredPlan {
        val route = router.route(question, selfId, catalogs)
        val r = try {
            cloud.plan(question, catalogs, caps.capabilities, caps.ops)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val pq = r.getOrElse { e -> return TieredPlan(route, PlanTier.T2, note = e.message ?: "cloud planner failed") }
        val compiled = compile(pq, catalogs, caps)
        return TieredPlan(route, PlanTier.T2, pq, compiled, note = if (compiled.ok) null else compiled.check.errors.joinToString("; "))
    }

    private fun compile(pq: PlannedQuestion, catalogs: List<Catalog>, caps: CapabilitySummary): CompiledPlan =
        compiler.compile(pq.dag, catalogs, modelHash(), caps.capabilities, caps.ops, selfId)

    /** Caches confident, compiled plans; notes a clarification so the next unsure answer can go to T2. */
    private fun remember(key: String, r: TieredPlan): TieredPlan {
        if (r.ok && r.plan!!.confidence >= Confidence.LOW && !r.fromCache) cache?.put(key, r.plan, r.tier.name)
        lastClarificationAt = if (r.clarification.isNotEmpty()) clock() else null
        return r
    }

    companion object {
        const val TAG = "TieredPlanner"
        /** "after one clarification": a second unsure plan within this window of the first clarification goes to T2 */
        const val CLARIFY_WINDOW_MS = 10 * 60_000L
    }
}
