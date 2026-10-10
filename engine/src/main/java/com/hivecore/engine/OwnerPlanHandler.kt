package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.mesh.PlanConsent
import com.hivecore.core.ApprovalGate
import com.hivecore.core.ApprovalResult
import com.hivecore.core.Compiled
import com.hivecore.core.DifferencingGuard
import com.hivecore.core.Hashing
import com.hivecore.core.Intent
import com.hivecore.core.Op
import com.hivecore.core.SLog
import com.hivecore.core.SchemaSource
import com.hivecore.core.TaskRunner
import com.hivecore.core.TaskSpec
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.PlanConsentReqBody
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** How the owner answers the commander: over the mesh, or in-process when the commander is this phone. */
typealias SwarmDeliver = suspend (MsgType, String) -> Unit

/**
 * Swarm plan §7, owner side. On a PLAN that names this phone as the owner of AGG/DOC tasks:
 *  1. compile every sub-question once through the owner's own path ([AggExecutor.compileAgg] / [AggExecutor.prepareDoc]);
 *     a sub-question that does not compile refuses the plan with the compiler's reason;
 *  2. run the [DifferencingGuard] over all of them together (and the requester's last 24 h); a refusal is sent as a
 *     PLAN_CONSENT_RES with `refusal` = the guard's reason;
 *  3. when any figure touches confidential data: one PLAN_CONSENT_REQ listing every figure, then ONE
 *     [ApprovalGate.requestPlan] (one fingerprint for the plan) and the signed PLAN_CONSENT_RES (or the decline).
 *     Plans with no confidential figure send nothing and simply run.
 * TASKs for those tasks wait for that preparation, then run on the [TaskRunner] at owner priority (the
 * [AggExecutor] registered there executes the prepared query) and are answered by one TASK_RESULT each; answered
 * intents are remembered for the cross-plan differencing window. TASK and TASK_RESULT are idempotent by taskId.
 */
class OwnerPlanHandler(
    private val selfId: String,
    private val nodeName: String,
    private val schema: SchemaSource,
    private val agg: AggExecutor,
    private val guard: DifferencingGuard,
    private val approval: ApprovalGate,
    private val runner: TaskRunner,
    private val scope: CoroutineScope,
    /** The pinned peer's name (from its key-signed HELLO) for the approval screen; null when unknown. */
    private val requesterName: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val backend: () -> String = { "" },
) {
    private class Prep(val tasks: Map<String, PreparedTask>, val intents: Map<String, Intent>, val refusal: String?)
    private class OwnerPlan(val body: PlanBody, val prep: Deferred<Prep>)

    private val lock = Any()
    private val plans = LinkedHashMap<String, OwnerPlan>()
    private val preparedByKey = HashMap<String, PreparedTask>()
    private val results = LinkedHashMap<String, TaskResultBody>()     // "planId|taskId" -> the answer sent

    /** For [AggExecutor]'s `prepared` hook: the preparation of the task about to run. */
    fun prepared(task: TaskSpec): PreparedTask? = synchronized(lock) { preparedByKey[keyOf(task)] }

    /** The AGG/DOC tasks of [dag] this phone owns. */
    fun ownedTasks(body: PlanBody): List<TaskSpec> = body.dag.tasks.filter { (it.op == Op.AGG || it.op == Op.DOC) && it.owner == selfId }

    /** A verified PLAN from [from] (= its commander). Preparation starts at once; returns false when nothing is ours. */
    fun onPlan(body: PlanBody, from: String, deliver: SwarmDeliver): Boolean {
        if (body.commander != from) { SLog.w(TAG, "PLAN ${body.planId.take(8)}: commander != sender; ignored"); return false }
        val mine = ownedTasks(body)
        if (mine.isEmpty()) return false
        synchronized(lock) {
            if (plans.containsKey(body.planId)) return true
            val prep = scope.async { prepare(body, mine, deliver) }
            plans[body.planId] = OwnerPlan(body, prep)
            while (plans.size > MAX_PLANS) plans.remove(plans.keys.first())
        }
        return true
    }

    private suspend fun prepare(body: PlanBody, mine: List<TaskSpec>, deliver: SwarmDeliver): Prep {
        val s = schema.schema()
        val tasks = LinkedHashMap<String, PreparedTask>()
        val intents = LinkedHashMap<String, Intent>()
        val queries = ArrayList<Compiled.Query>()
        var refusal: String? = null
        for (t in mine) {
            val q = agg.questionOf(t)
            if (q == null) { refusal = "task ${t.id} has no sub-question"; break }
            if (t.op == Op.AGG) {
                val c = agg.compileAgg(q, s).getOrElse { refusal = it.message ?: "could not compile '$q'"; null } ?: break
                tasks[t.id] = PreparedTask.Agg(c); intents[t.id] = c.intent; queries += c
            } else {
                val d = agg.prepareDoc(q, s).getOrElse { refusal = it.message ?: "could not read documents for '$q'"; null } ?: break
                tasks[t.id] = d
            }
        }
        val docHashes = tasks.values.filterIsInstance<PreparedTask.Doc>().filter { it.confidentialDocs.isNotEmpty() }
            .map { d -> Hashing.sha256Hex("doc|" + d.chunks.joinToString("\n") { "${it.chunk.docName}|${it.chunk.locator}" }) }
        val req = PlanConsentReqBody(body.planId, body.planHash, selfId, PlanConsent.intentHashes(queries) + docHashes)
        if (refusal == null) refusal = guard.check(body.commander, intents.values.toList(), s, clock())
        if (refusal != null) {
            SLog.d(TAG, "$nodeName: plan ${body.planId.take(8)} refused: $refusal")
            deliver(MsgType.PLAN_CONSENT_RES, MeshJson.encode(PlanConsent.declined(req, clock(), refusal)))
            return Prep(emptyMap(), emptyMap(), refusal)
        }
        val confidentialDocs = tasks.values.filterIsInstance<PreparedTask.Doc>().flatMap { it.confidentialDocs }.distinct()
        if (queries.none { it.touchesConfidential } && confidentialDocs.isEmpty()) return Prep(tasks, intents, null)

        deliver(MsgType.PLAN_CONSENT_REQ, MeshJson.encode(req))
        val requester = if (body.commander == selfId) nodeName else requesterName(body.commander) ?: "peer ${body.commander.take(8)}"
        val description = PlanConsent.description(requester, queries) +
            if (confidentialDocs.isEmpty()) "" else "\nAlso reads ${confidentialDocs.joinToString(", ")} (confidential)."
        SLog.d(TAG, "$nodeName: asking owner for plan ${body.planId.take(8)}: ${req.intentHashes.size} figure(s)")
        return when (val r = approval.requestPlan(body.planId, requester, description, body.planHash, req.intentHashes)) {
            is ApprovalResult.Approved -> {
                deliver(MsgType.PLAN_CONSENT_RES, MeshJson.encode(PlanConsent.approved(req, r.sig, r.signedBy, clock())))
                Prep(tasks, intents, null)
            }
            ApprovalResult.Declined, ApprovalResult.TimedOut -> {
                deliver(MsgType.PLAN_CONSENT_RES, MeshJson.encode(PlanConsent.declined(req, clock())))
                Prep(emptyMap(), emptyMap(), if (r == ApprovalResult.Declined) "declined by $nodeName" else "no approval from $nodeName in time")
            }
        }
    }

    /** True when [body] is a TASK for an AGG/DOC task this phone prepared (or is preparing) a plan for. */
    fun owns(body: TaskBody): Boolean = synchronized(lock) { plans.containsKey(body.planId) } &&
        (body.task.op == Op.AGG || body.task.op == Op.DOC) && body.task.owner == selfId

    /** A TASK from the plan's commander: waits for the preparation, runs it, and sends one TASK_RESULT. */
    suspend fun onTask(body: TaskBody, from: String, deliver: SwarmDeliver) {
        val key = "${body.planId}|${body.task.id}"
        synchronized(lock) { results[key] }?.let { deliver(MsgType.TASK_RESULT, MeshJson.encode(it)); return }
        val plan = synchronized(lock) { plans[body.planId] }
        val fail: (String) -> TaskResultBody = { TaskResultBody(body.planId, body.task.id, selfId, error = it) }
        val res: TaskResultBody = when {
            plan == null -> fail("no PLAN ${body.planId.take(8)} on $nodeName")
            plan.body.commander != from -> fail("TASK sender is not the plan's commander")
            plan.body.dag.tasks.none { it.id == body.task.id && keyOf(it) == keyOf(body.task) } -> fail("task ${body.task.id} differs from the PLAN")
            else -> {
                val prep = plan.prep.await()
                val p = prep.tasks[body.task.id]
                when {
                    prep.refusal != null -> fail(prep.refusal)
                    p == null -> fail("task ${body.task.id} was not prepared")
                    else -> {
                        synchronized(lock) { preparedByKey[keyOf(body.task)] = p }
                        val t0 = clock()
                        val r = runner.run(body.task, emptyList(), emptyList(), from)
                        val ms = clock() - t0
                        r.fold(
                            onSuccess = { out ->
                                prep.intents[body.task.id]?.let { guard.remember(from, listOf(it), clock()) }
                                TaskResultBody(body.planId, body.task.id, selfId, out, Canon.hash(out), ms = ms, backend = backend())
                            },
                            onFailure = { fail(it.message ?: it.javaClass.simpleName) }
                        )
                    }
                }
            }
        }
        synchronized(lock) {
            results[key] = res
            while (results.size > MAX_RESULTS) results.remove(results.keys.first())
        }
        deliver(MsgType.TASK_RESULT, MeshJson.encode(res))
    }

    companion object {
        private const val TAG = "OwnerPlan"
        private const val MAX_PLANS = 50
        private const val MAX_RESULTS = 500
        fun keyOf(t: TaskSpec): String = Canon.hash(Canon.json.encodeToJsonElement(TaskSpec.serializer(), t))
    }
}
