package com.hivecore.trust

import com.hivecore.core.Canon
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import com.hivecore.core.TaskSpec
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.TaskBody
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A warning the UI shows: [peer] sent [count] unauthorised TASKs within the last minute. */
data class UnauthorisedWarning(val peer: String, val count: Int)

/**
 * Spec 6.5 (P10) task authorisation. Records every verified PLAN (`SwarmNode.onPlanSeen`) and answers "may this
 * TASK run?" (`SwarmNode.taskAuthoriser`): yes only when a PLAN with that `planId` is held, it came from the same
 * orchestrator, and the task's spec hash appears in the plan. Split jobs send a PLAN whose dag holds one spec
 * template per op plus the ordered `chunkIds`: a chunk TASK is authorised when its id is one of the chunk ids and it
 * instantiates a template (same op and model; every template param present unchanged). Unauthorised tasks are
 * dropped and counted; three in a minute from one peer raise [warnings] / [onWarning].
 *
 * The spec hash is `Canon.hash(TaskSpec)` (the same `keyOf` the owner plan handler uses). Plans are kept in memory
 * (the last [maxPlans]) and, with a [db], in `_plans` so a node restart keeps authorising a running job.
 */
class PlanAuthoriser(
    private val db: SqlDb? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxPlans: Int = 200,
) {
    private class Held(val plan: PlanBody, val from: String, val keys: Set<String>)

    private val lock = Any()
    private val plans = LinkedHashMap<String, Held>()
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val warningsFlow = MutableSharedFlow<UnauthorisedWarning>(replay = 0, extraBufferCapacity = 32)
    val warnings: SharedFlow<UnauthorisedWarning> = warningsFlow
    var onWarning: (peer: String, count: Int) -> Unit = { _, _ -> }

    init {
        db?.let { d ->
            d.exec("CREATE TABLE IF NOT EXISTS _plans(planId TEXT PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL)")
            for (r in d.query("SELECT sender, body FROM _plans ORDER BY ts DESC LIMIT ?", listOf(maxPlans)).asReversed()) {
                runCatching { MeshJson.decode<PlanBody>(r["body"] as String) }.getOrNull()?.let { remember(it, r["sender"] as String, persist = false) }
            }
        }
    }

    /** `SwarmNode.onPlanSeen`: a verified PLAN from [from] (the envelope signer, already checked to be its commander). */
    fun onPlanSeen(plan: PlanBody, from: String) {
        if (plan.commander != from) { SLog.w(TAG, "PLAN ${plan.planId.take(8)} names another commander; not recorded"); return }
        remember(plan, from, persist = true)
    }

    private fun remember(plan: PlanBody, from: String, persist: Boolean) {
        val keys = plan.dag.tasks.map { keyOf(it) }.toSet()
        synchronized(lock) {
            plans.remove(plan.planId)
            plans[plan.planId] = Held(plan, from, keys)
            while (plans.size > maxPlans) plans.remove(plans.keys.first())
        }
        if (persist) db?.exec("INSERT OR REPLACE INTO _plans(planId, sender, body, ts) VALUES (?,?,?,?)", listOf(plan.planId, from, MeshJson.encode(plan), clock()))
    }

    /** The held PLAN, or null. */
    fun held(planId: String): PlanBody? = synchronized(lock) { plans[planId]?.plan }

    /** `SwarmNode.taskAuthoriser`: null = run it; otherwise the reason it is dropped (counted against [from]). */
    fun authorise(task: TaskBody, from: String): String? {
        val why = check(task, from)
        if (why != null) unauthorised(from, why)
        return why
    }

    private fun check(task: TaskBody, from: String): String? {
        val held = synchronized(lock) { plans[task.planId] } ?: return "no PLAN ${task.planId.take(8)} held"
        if (held.from != from) return "PLAN ${task.planId.take(8)} was sent by another orchestrator"
        if (task.commander != from) return "TASK names another commander"
        val spec = task.task
        if (keyOf(spec) in held.keys) return null
        val plan = held.plan
        if (plan.chunkIds.isNotEmpty()) {
            if (spec.id !in plan.chunkIds) return "chunk ${spec.id} is not in PLAN ${task.planId.take(8)}"
            val template = plan.dag.tasks.firstOrNull { t ->
                t.op == spec.op && t.modelId == spec.modelId && t.params.all { (k, v) -> spec.params[k] == v } &&
                    (t.owner == null || t.owner == spec.owner)
            } ?: return "chunk ${spec.id} does not instantiate a template of PLAN ${task.planId.take(8)}"
            return null.also { SLog.d(TAG, "chunk ${spec.id} authorised against template ${template.id}") }
        }
        return "task ${spec.id} is not in PLAN ${task.planId.take(8)}"
    }

    /** Dropped unauthorised TASKs from [peer] in the last minute. */
    fun unauthorisedInLastMinute(peer: String): Int = synchronized(lock) { prune(peer).size }

    private fun unauthorised(peer: String, why: String) {
        SLog.w(TAG, "unauthorised TASK from ${peer.take(8)}: $why")
        val count = synchronized(lock) { prune(peer).also { it.addLast(clock()) }.size }
        if (count == WARN_AFTER) {
            SLog.w(TAG, "${peer.take(8)} sent $count unauthorised tasks within a minute")
            warningsFlow.tryEmit(UnauthorisedWarning(peer, count))
            runCatching { onWarning(peer, count) }
        }
    }

    /** Caller holds [lock]. */
    private fun prune(peer: String): ArrayDeque<Long> {
        val q = failures.getOrPut(peer) { ArrayDeque() }
        val cutoff = clock() - WINDOW_MS
        while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
        return q
    }

    companion object {
        private const val TAG = "PlanAuth"
        const val WARN_AFTER = 3
        const val WINDOW_MS = 60_000L
        /** The task spec hash: canonical JSON of the TaskSpec, sha256 (equal to the owner plan handler's `keyOf`). */
        fun keyOf(t: TaskSpec): String = Canon.hash(Canon.json.encodeToJsonElement(TaskSpec.serializer(), t))
    }
}
