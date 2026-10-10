package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.Dag
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.Profile
import com.hivecore.core.Scheduler
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonPrimitive

/**
 * Swarm plan §6.2: deterministic assignment, a pure function of (dag, profiles as a set, self).
 *
 *  - OWNER ops go to their owner (R1), with no choice; one without an owner falls back to self.
 *  - REDUCE ops run on the commander (self).
 *  - Every other task goes to an eligible phone: `eligible`, plus `heavyOk` for HEAVY, `big == "llm"` for LLM and
 *    `big == "vlm"` for VLM. With no eligible phone the commander runs it.
 *  - Cost per (task, phone), in ms. LLM/VLM: promptTokens / prefill + maxTokens / tokPerSec + payload / link.
 *    LIGHT/HEAVY: items × p50(modelId) + (modelId warm ? 0 : warmMs) + payload / link. Self pays no link time.
 *    Task params override the defaults: `promptTokens`, `maxTokens`, `items`, `payloadBytes`, `warmMs`.
 *  - List scheduling: tasks longest first (by their cost on a default phone, ties by id), each to the phone with the
 *    earliest finish (queued work + own backlog of [QUEUE_MS] per queued task); ties go to the lower
 *    sha256(nodeId + taskId).
 *
 * Profiles are de-duplicated per nodeId (latest `ts`, then canonical bytes) and sorted, so their order never matters.
 */
class SchedulerImpl : Scheduler {

    override fun assign(dag: Dag, profiles: List<Profile>, self: String): Map<String, String> {
        val ps = normalise(profiles)
        val out = LinkedHashMap<String, String>()
        val ready = HashMap<String, Double>()           // nodeId → ms of work already assigned
        ps.forEach { ready[it.nodeId] = it.queueDepth * QUEUE_MS }
        ready.putIfAbsent(self, 0.0)
        val free = ArrayList<TaskSpec>()
        for (t in dag.tasks) when (OpClasses.of(t.op)) {
            OpClass.OWNER -> out[t.id] = t.owner?.takeIf { it.isNotBlank() } ?: self
            OpClass.REDUCE -> out[t.id] = self
            else -> free += t
        }
        val order = free.sortedWith(compareByDescending<TaskSpec> { cost(it, null, self) }.thenBy { it.id })
        for (t in order) {
            val cands = candidates(t, ps, self, emptySet())
            val pick = best(t, cands, self, ready) ?: self
            out[t.id] = pick
            val p = cands.firstOrNull { it.nodeId == pick }
            ready[pick] = (ready[pick] ?: 0.0) + cost(t, p, self)
        }
        // Keep the DAG's task order in the map.
        return dag.tasks.associate { it.id to out.getValue(it.id) }
    }

    override fun reassign(task: TaskSpec, profiles: List<Profile>, self: String, failed: Set<String>): String? {
        val ps = normalise(profiles)
        return when (OpClasses.of(task.op)) {
            OpClass.OWNER -> null                                         // owner tasks cannot move (R1)
            OpClass.REDUCE -> self.takeIf { it !in failed }
            else -> {
                val ready = HashMap<String, Double>()
                ps.forEach { ready[it.nodeId] = it.queueDepth * QUEUE_MS }
                ready.putIfAbsent(self, 0.0)
                best(task, candidates(task, ps, self, failed), self, ready) ?: self.takeIf { it !in failed }
            }
        }
    }

    /** Eligible phones for [t], minus [failed]. */
    fun candidates(t: TaskSpec, ps: List<Profile>, self: String, failed: Set<String>): List<Profile> {
        val cls = OpClasses.of(t.op)
        return ps.filter { p ->
            p.nodeId !in failed && p.eligible && when (cls) {
                OpClass.HEAVY -> p.heavyOk
                OpClass.LLM -> p.big == "llm"
                OpClass.VLM -> p.big == "vlm"
                else -> true
            }
        }
    }

    private fun best(t: TaskSpec, cands: List<Profile>, self: String, ready: Map<String, Double>): String? =
        cands.map { p -> Triple(p.nodeId, (ready[p.nodeId] ?: 0.0) + cost(t, p, self), Canon.sha256Hex(p.nodeId + t.id)) }
            .minWithOrNull(compareBy<Triple<String, Double, String>> { it.second }.thenBy { it.third })?.first

    /** Estimated ms for [t] on [p] (null = a phone with default figures, used for the longest-first order). */
    fun cost(t: TaskSpec, p: Profile?, self: String): Double {
        val cls = OpClasses.of(t.op)
        val payload = num(t, "payloadBytes") ?: DEFAULT_PAYLOAD_BYTES
        val linkKbps = p?.linkKbps?.takeIf { it > 0 } ?: DEFAULT_LINK_KBPS
        val transfer = if (p != null && p.nodeId == self) 0.0 else payload * 8.0 / linkKbps   // bytes·8 / kbit/s = ms
        return when (cls) {
            OpClass.LLM, OpClass.VLM -> {
                val prompt = num(t, "promptTokens") ?: DEFAULT_PROMPT_TOKENS
                val maxTok = num(t, "maxTokens") ?: DEFAULT_MAX_TOKENS
                val tps = p?.tokPerSec?.takeIf { it > 0 } ?: DEFAULT_TOK_PER_SEC
                prompt / DEFAULT_PREFILL_TOK_PER_SEC * 1000.0 + maxTok / tps * 1000.0 + transfer
            }
            else -> {
                val items = num(t, "items") ?: 1.0
                val p50 = t.modelId?.let { p?.p50?.get(it) } ?: if (cls == OpClass.HEAVY) DEFAULT_HEAVY_P50_MS else DEFAULT_P50_MS
                val warm = t.modelId == null || (p != null && t.modelId in p.warm)
                items * p50 + (if (warm) 0.0 else num(t, "warmMs") ?: DEFAULT_WARM_MS) + transfer
            }
        }
    }

    private fun num(t: TaskSpec, k: String): Double? = (t.params[k] as? JsonPrimitive)?.content?.toDoubleOrNull()

    companion object {
        const val DEFAULT_TOK_PER_SEC = 15.0
        const val DEFAULT_PREFILL_TOK_PER_SEC = 1400.0
        const val DEFAULT_PROMPT_TOKENS = 700.0
        const val DEFAULT_MAX_TOKENS = 64.0
        const val DEFAULT_LINK_KBPS = 1200.0          // ~150 KB/s, the §9 Bluetooth planning figure
        const val DEFAULT_PAYLOAD_BYTES = 4000.0
        const val DEFAULT_P50_MS = 200.0
        const val DEFAULT_HEAVY_P50_MS = 3000.0
        const val DEFAULT_WARM_MS = 2000.0
        const val QUEUE_MS = 3000.0

        /** One profile per nodeId (latest ts, then smallest canonical form), sorted by nodeId. */
        fun normalise(profiles: List<Profile>): List<Profile> =
            profiles.groupBy { it.nodeId }.map { (_, v) ->
                v.sortedWith(compareByDescending<Profile> { it.ts }.thenBy { Canon.canonical(Canon.json.encodeToJsonElement(Profile.serializer(), it)) }).first()
            }.sortedBy { it.nodeId }
    }
}
