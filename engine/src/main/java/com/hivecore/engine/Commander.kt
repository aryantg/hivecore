package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.AggTable
import com.hivecore.core.AnswerChip
import com.hivecore.core.AskOutcome
import com.hivecore.core.AskProgress
import com.hivecore.core.Catalog
import com.hivecore.core.Comparator
import com.hivecore.core.Confidence
import com.hivecore.core.Dag
import com.hivecore.core.DocAnswer
import com.hivecore.core.LedgerStore
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.PlanCompiler
import com.hivecore.core.PlanSummary
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.Planner
import com.hivecore.core.Profile
import com.hivecore.core.Provenance
import com.hivecore.core.Reducer
import com.hivecore.core.RouteDecision
import com.hivecore.core.SLog
import com.hivecore.core.Scheduler
import com.hivecore.core.TaskSpec
import com.hivecore.core.Timing
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.PlanConsentReqBody
import com.hivecore.mesh.PlanConsentResBody
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import com.hivecore.mesh.TaskSignalBody
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Swarm plan §6.3, the commander (the phone that was asked):
 *  plan ([Planner]) → validate ([PlanCompiler]; errors refuse) → assign ([Scheduler] over the latest PROFILEs incl.
 *  this phone) → PLAN to every participant → TASKs in DAG order as their inputs complete (this phone's own tasks go
 *  through the same messages, delivered in-process) → TASK_RESULTs → reduce ops locally ([Reducer]) → a
 *  deterministic English sentence and an [AskOutcome.Answer] with its [PlanSummary].
 *
 * Deadlines: `max(4 s, 2 × est)` per attempt; an owner that sent PLAN_CONSENT_REQ gets [approvalMs] (65 s). A
 * data-free task that times out or yields is reassigned ([Scheduler.reassign]); an owner task that times out ends
 * the plan with "no reply from HR". A PLAN_CONSENT_RES decline or refusal ends it with the owner's reason.
 *
 * Verification (§6.4): LLM/VLM-class data-free tasks run on two phones and their typed outputs are compared
 * ([Comparator]); a mismatch adds a third run, and with no agreeing pair the plan refuses. Owner AGG results are
 * checked against their invariants (declared dims present, group counts summing to rows scanned).
 *
 * Memoization: `memoKey = sha256(canonical task + dataVersion(owner) + modelHash)`; a task whose verified result is
 * held is not sent again. The ledger gets a `plan` entry (planHash, assignment, profiles) and one `task` entry per
 * executed task.
 *
 * Escalation (spec 15.1, [EscalationThresholds]): a result that fails the step's `params.schema` is counted per phone
 * and retried; three failures across two phones, a three-way replica disagreement, a VOTE below 0.6 or a local judge
 * (VERIFY / EVAL) below 0.7 build an [EscalationRequest] with the step's inputs and privacy lineage ([Lineage]) and
 * ask the [escalator]. A `Ran` outcome takes the step's place with executor `cloud:<slug>` (never memoized; the plan
 * summary and the provenance show the slug); `Refused` / `Failed` refuse the plan with both reasons and nothing is
 * re-dispatched. A private step never reaches the gate. [Escalator.Off] everywhere but the orchestrator wiring.
 */
class Commander(
    private val selfId: String,
    private val selfName: String,
    private val planner: Planner,
    private val compiler: PlanCompiler,
    private val scheduler: Scheduler,
    private val reducer: Reducer,
    private val comparator: Comparator,
    private val profiles: () -> List<Profile>,
    /** Signed unicast; `to == selfId` must be delivered in-process (SwarmNode does). */
    private val transmit: suspend (to: String, type: MsgType, body: String) -> Result<Unit>,
    private val ledger: LedgerStore? = null,
    private val nameOf: (String) -> String = { it.take(8) },
    private val modelHash: () -> String = { "" },
    /** The owner's data version (changes on every ingest/delete); "" for data-free tasks. */
    private val dataVersionOf: (String) -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    private val approvalMs: Long = 65_000L,
    private val minDeadlineMs: Long = 4_000L,
    private val escalator: Escalator = Escalator.Off,
    private val schemaValidator: SchemaValidator = SchemaValidator.NONE,
) {
    // ------------------------------------------------------------------ inbound

    private sealed interface Event {
        data class ConsentReq(val node: String) : Event
        data class ConsentRes(val node: String, val approved: Boolean, val refusal: String?) : Event
        data class Result(val node: String, val body: TaskResultBody) : Event
        data class Yield(val node: String, val taskId: String, val reason: String) : Event
    }

    private val lock = Any()
    private val runs = HashMap<String, Channel<Event>>()
    private val memo = LinkedHashMap<String, Memo>()

    private class Memo(val output: JsonElement, val executors: List<String>)

    /** Number of memoized task results held. */
    val memoSize: Int get() = synchronized(lock) { memo.size }

    /** A verified swarm message addressed to a plan this phone commands. Returns false when no live plan matches. */
    fun onMessage(type: MsgType, from: String, body: String): Boolean {
        val (planId, ev) = try {
            when (type) {
                MsgType.PLAN_CONSENT_REQ -> MeshJson.decode<PlanConsentReqBody>(body).let { it.planId to Event.ConsentReq(from) }
                MsgType.PLAN_CONSENT_RES -> MeshJson.decode<PlanConsentResBody>(body).let { it.planId to Event.ConsentRes(from, it.approved, it.refusal) }
                MsgType.TASK_RESULT -> MeshJson.decode<TaskResultBody>(body).let { it.planId to Event.Result(from, it) }
                MsgType.TASK_YIELD -> MeshJson.decode<TaskSignalBody>(body).let { it.planId to Event.Yield(from, it.taskId, it.reason) }
                else -> return false
            }
        } catch (e: Exception) {
            SLog.w(TAG, "$type from ${from.take(8)} ignored: ${e.message}"); return false
        }
        val ch = synchronized(lock) { runs[planId] } ?: return false
        ch.trySend(ev)
        return true
    }

    // ------------------------------------------------------------------ ask

    /**
     * Runs one composite question end to end; the last emission is [AskProgress.Done]. [planned] is a plan the tiered
     * planner (spec 7.2) already produced for [question]; null plans it here with the T1 [planner].
     */
    fun askComposite(question: String, route: RouteDecision, catalogs: List<Catalog>, planned: PlannedQuestion? = null,
                     cloudSteps: Set<String> = emptySet()): Flow<AskProgress> = channelFlow {
        val t0 = clock()
        fun timing() = Timing(totalMs = clock() - t0)
        send(AskProgress.Status("Planning…"))
        val planned = planned ?: planner.plan(question, route, catalogs).getOrElse { e ->
            send(AskProgress.Done(AskOutcome.Refusal(listOf(selfName to (e.message ?: "this question cannot be planned")), timing())))
            return@channelFlow
        }
        if (planned.confidence < Confidence.LOW) {
            val why = if (planned.clarification.isEmpty()) "not sure what the question asks"
                else "did you mean " + planned.clarification.joinToString(" or ") + "?"
            send(AskProgress.Done(AskOutcome.Refusal(listOf(selfName to why), timing())))
            return@channelFlow
        }
        val dag = planned.dag
        val mh = modelHash()
        val check = compiler.validate(dag, catalogs, mh)
        if (!check.ok) {
            send(AskProgress.Done(AskOutcome.Refusal(check.errors.map { selfName to it }, timing())))
            return@channelFlow
        }
        val snapshot = profiles()
        val assignment = scheduler.assign(dag, snapshot, selfId)
        val planId = UUID.randomUUID().toString()
        val events = Channel<Event>(Channel.UNLIMITED)
        synchronized(lock) { runs[planId] = events }
        try {
            val run = PlanRun(planId, check.planHash, question, dag, assignment, snapshot, mh, planned.confidence, events, cloudSteps) { send(it) }
            val outcome = run.execute(t0)
            send(AskProgress.Done(outcome))
        } finally {
            synchronized(lock) { runs.remove(planId) }
            events.close()
        }
    }

    // ------------------------------------------------------------------ one plan

    private class Failed(val reasons: List<Pair<String, String>>) : Exception(reasons.joinToString { it.second })

    private inner class TaskRun(val spec: TaskSpec) {
        val cls: OpClass = OpClasses.of(spec.op)
        var replicate: Boolean = (cls == OpClass.LLM || cls == OpClass.VLM) &&
            (spec.params["replicate"] as? JsonPrimitive)?.content != "none" &&
            ((spec.params["replicas"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 2) > 1
        var output: JsonElement? = null
        val running = LinkedHashMap<String, Long>()          // node -> deadlineAt
        val tried = LinkedHashSet<String>()
        val results = LinkedHashMap<String, JsonElement>()   // node -> output (replicas)
        var executors: List<String> = emptyList()
        var ms = 0L
        var verified = false
        var memoKey: String? = null
        var fromMemo = false
        var dispatched = false
        /** node -> results of this task that failed `params.schema` (spec 15.1) */
        val schemaFails = LinkedHashMap<String, Int>()
        /** the cloud slug that answered this step after an escalation (spec 15.3) */
        var cloud: String? = null
        val done get() = output != null
    }

    private inner class PlanRun(
        val planId: String,
        val planHash: String,
        val question: String,
        val dag: Dag,
        val assignment: Map<String, String>,
        val snapshot: List<Profile>,
        val modelHash: String,
        val confidence: Double,
        val events: Channel<Event>,
        /** spec 9.1 CLOUD option: step ids the user chose to run in the cloud from this phone (eligible ones only) */
        val cloudSteps: Set<String>,
        val progress: suspend (AskProgress) -> Unit,
    ) {
        val tasks = LinkedHashMap<String, TaskRun>().also { m -> dag.tasks.forEach { m[it.id] = TaskRun(it) } }
        /** privacy lineage per task (spec 15.2 step 3) */
        val lineage: Map<String, String> = Lineage.lineages(dag.tasks)
        val consentPending = HashSet<String>()
        val notes = ArrayList<String>()
        var approvals = 0
        val costModel = SchedulerImpl()
        /** phones that hold this PLAN (P10: every phone gets it before its first TASK, replicas and reassignments included) */
        val planned = HashSet<String>()
        val planBody: String by lazy { MeshJson.encode(PlanBody(planId, planHash, selfId, question, dag, assignment, clock(), UUID.randomUUID().toString())) }

        suspend fun execute(t0: Long): AskOutcome {
            // Memo hits first: a held verified result is not asked for again.
            for (t in tasks.values) if (t.cls != OpClass.REDUCE) {
                val key = memoKey(t.spec)
                t.memoKey = key
                synchronized(lock) { memo[key] }?.let { m ->
                    t.output = m.output; t.executors = m.executors; t.fromMemo = true; t.verified = true
                }
            }
            ledgerPlan()
            val participants = tasks.values.filter { !it.done && it.cls != OpClass.REDUCE }.mapNotNull { assignment[it.spec.id] }.distinct()
            val others = participants.filter { it != selfId }
            if (others.isNotEmpty()) progress(AskProgress.Status("Asking ${others.joinToString(", ") { nameOf(it) }}…"))
            for (p in participants) sendPlan(p)
            try {
                loop()
            } catch (f: Failed) {
                cancelRunning("plan refused")
                return AskOutcome.Refusal(f.reasons, Timing(totalMs = clock() - t0))
            }
            return answer(t0)
        }

        private suspend fun loop() {
            while (true) {
                advance()
                val out = tasks[dag.output]
                if (out?.done == true) return
                val next = tasks.values.flatMap { it.running.values }.minOrNull()
                    ?: throw Failed(listOf(selfName to "the plan cannot make progress"))
                val ev = withTimeoutOrNull(maxOf(0L, next - clock())) { events.receive() }
                if (ev == null) timeouts() else handle(ev)
            }
        }

        /** Runs every reduce whose inputs are ready and dispatches every data task whose inputs are ready. */
        private suspend fun advance() {
            var progressed = true
            while (progressed) {
                progressed = false
                for (t in tasks.values) {
                    if (t.done || t.dispatched) continue
                    if (t.spec.inputs.any { tasks[it]?.done != true }) continue
                    // A step whose input is still being judged waits (the judge may replace that input, spec 15.1).
                    if (!isJudge(t.spec) && t.spec.inputs.any { judgePending(it) }) continue
                    t.dispatched = true
                    progressed = true
                    when {
                        t.cls == OpClass.REDUCE -> reduce(t)
                        t.spec.id in cloudSteps && cloudFirst(t) -> Unit
                        else -> dispatchFirst(t)
                    }
                }
            }
        }

        private fun reduce(t: TaskRun) {
            val inputs = t.spec.inputs.map { tasks.getValue(it).output!! }
            val t1 = clock()
            val out = when (t.spec.op) {
                Op.RENDER -> inputs.first()
                else -> reducer.run(t.spec.op, t.spec.params, inputs).getOrElse {
                    throw Failed(listOf(selfName to "${t.spec.op} failed: ${it.message}"))
                }
            }
            Canon.notesOf(out).forEach { if (it !in notes) notes += it }
            t.output = out; t.executors = listOf(selfId); t.ms = clock() - t1; t.verified = true
        }

        private fun isJudge(spec: TaskSpec) = spec.op == Op.VERIFY || spec.op == Op.EVAL

        /** A judge of [taskId] exists in the plan and has not finished. */
        private fun judgePending(taskId: String): Boolean =
            tasks.values.any { isJudge(it.spec) && it.spec.inputs.firstOrNull() == taskId && !it.done }

        /**
         * Spec 9.1: a step the CLOUD option covers runs on this phone through the gate before any phone is asked. The
         * gate's pre-flight still applies (lineage, caps, the orchestrator role). True when the cloud answered; a
         * refusal or a cloud failure leaves a note and the step runs locally (the user's answer is never lost to a
         * cloud outage), and a private or owner step never goes near the gate.
         */
        private suspend fun cloudFirst(t: TaskRun): Boolean {
            val lin = lineage[t.spec.id] ?: Lineage.PUBLIC
            if (lin == Lineage.PRIVATE || t.cls == OpClass.OWNER || t.cls == OpClass.REDUCE) return false
            val inputs = t.spec.inputs.associateWith { tasks[it]?.output ?: JsonNull }
            val req = EscalationRequest(jobId = planId, planId = planId, commander = selfId, node = t.spec, inputs = inputs,
                privacy = lin, trigger = EscalationTrigger.UserChoice, localAttempts = 0)
            val g = try { escalator.tryEscalate(req) } catch (e: Exception) { GateOutcome.Failed(e.message ?: e.javaClass.simpleName) }
            return when (g) {
                is GateOutcome.Ran -> {
                    t.output = g.output; t.executors = listOf(cloudExecutor(g.slug)); t.verified = true; t.cloud = g.slug
                    notes += "${t.spec.op} ${t.spec.id}: ${g.slug} answered (CLOUD option)"
                    SLog.d(TAG, "${t.spec.op} ${t.spec.id} answered by cloud:${g.slug} (user choice)")
                    true
                }
                is GateOutcome.Refused -> { notes += "${t.spec.op} ${t.spec.id} ran on the phones instead of the cloud (${g.reason})"; false }
                is GateOutcome.Failed -> { notes += "${t.spec.op} ${t.spec.id} ran on the phones; the cloud call failed (${g.reason})"; false }
            }
        }

        private suspend fun dispatchFirst(t: TaskRun) {
            val primary = assignment[t.spec.id] ?: selfId
            dispatch(t, primary)
            if (t.replicate) {
                val second = scheduler.reassign(t.spec, snapshot, selfId, setOf(primary))
                if (second == null || second == primary) {
                    t.replicate = false
                    notes += "${t.spec.op} ${t.spec.id} ran on one phone only (not verified)"
                } else dispatch(t, second)
            }
        }

        private suspend fun sendPlan(node: String) {
            if (!planned.add(node)) return
            transmit(node, MsgType.PLAN, planBody).onFailure { SLog.w(TAG, "PLAN to ${node.take(8)} not sent: ${it.message}"); planned.remove(node) }
        }

        private suspend fun dispatch(t: TaskRun, node: String) {
            sendPlan(node)
            t.tried += node
            t.running[node] = clock() + deadline(t, node)
            val inputs = t.spec.inputs.associateWith { tasks.getValue(it).output!! }
            val body = TaskBody(planId, t.spec, inputs, deadline(t, node), modelHash, selfId, clock(), UUID.randomUUID().toString())
            transmit(node, MsgType.TASK, MeshJson.encode(body)).onFailure {
                SLog.w(TAG, "TASK ${t.spec.id} to ${node.take(8)} not sent: ${it.message}")
                t.running[node] = clock()   // times out at once
            }
        }

        private fun deadline(t: TaskRun, node: String): Long =
            if (node in consentPending) approvalMs else maxOf(minDeadlineMs, (2 * estimate(t.spec, node)).toLong())

        private fun estimate(spec: TaskSpec, node: String): Double {
            val p = snapshot.firstOrNull { it.nodeId == node }
            return if (spec.op == Op.AGG || spec.op == Op.DOC) {
                // The owner runs one LLM intent pass per sub-question (§9: 1.8–2.5 s on the NPU).
                val tps = p?.tokPerSec?.takeIf { it > 0 } ?: SchedulerImpl.DEFAULT_TOK_PER_SEC
                SchedulerImpl.DEFAULT_PROMPT_TOKENS / SchedulerImpl.DEFAULT_PREFILL_TOK_PER_SEC * 1000 + SchedulerImpl.DEFAULT_MAX_TOKENS / tps * 1000
            } else costModel.cost(spec, p, selfId)
        }

        private suspend fun handle(ev: Event) {
            when (ev) {
                is Event.ConsentReq -> {
                    if (consentPending.add(ev.node)) progress(AskProgress.Status("Waiting for ${nameOf(ev.node)}'s approval…"))
                    for (t in tasks.values) t.running[ev.node]?.let { t.running[ev.node] = maxOf(it, clock() + approvalMs) }
                }
                is Event.ConsentRes -> {
                    consentPending.remove(ev.node)
                    if (!ev.approved) throw Failed(listOf(nameOf(ev.node) to (ev.refusal ?: "declined by ${nameOf(ev.node)}")))
                    approvals++
                    for (t in tasks.values) if (ev.node in t.running) t.running[ev.node] = clock() + maxOf(minDeadlineMs, (2 * estimate(t.spec, ev.node)).toLong())
                }
                is Event.Yield -> {
                    val t = tasks[ev.taskId] ?: return
                    if (ev.node !in t.running) return
                    SLog.d(TAG, "${t.spec.op} ${t.spec.id} yielded by ${nameOf(ev.node)}: ${ev.reason}")
                    failAttempt(t, ev.node, "yielded", timeout = false)
                }
                is Event.Result -> onResult(ev.node, ev.body)
            }
        }

        private suspend fun onResult(node: String, b: TaskResultBody) {
            val t = tasks[b.taskId] ?: return
            if (node !in t.running) return     // late, duplicate or cancelled
            // The owner answers only after its PLAN_CONSENT_RES (same link, in order): a figure arriving while its
            // consent is still pending means the router dropped that answer (bad or debug-only signature).
            if (t.cls == OpClass.OWNER && b.error == null && node in consentPending)
                throw Failed(listOf(nameOf(node) to "${nameOf(node)}'s approval could not be verified"))
            val out = b.output
            when {
                b.error != null -> { val err = b.error!!
                    if (err == TaskRunnerImpl.YIELDED) failAttempt(t, node, err, timeout = false)
                    else if (t.cls == OpClass.OWNER) throw Failed(listOf(nameOf(node) to err))
                    else failAttempt(t, node, err, timeout = false)
                }
                out == null || b.outputHash != Canon.hash(out) -> failAttempt(t, node, "output hash does not match", timeout = false)
                else -> {
                    t.running.remove(node)
                    t.ms = maxOf(t.ms, b.ms)
                    if (t.spec.op == Op.AGG) invariants(t, node, out)
                    val schema = t.spec.params[SchemaValidator.PARAM] as? JsonObject
                    val schemaWhy = if (schema != null) schemaValidator.validate(out, schema) else null
                    if (schemaWhy != null) { onSchemaFailure(t, node, schemaWhy); return }
                    if (!t.replicate) {
                        t.output = out; t.executors = listOf(node); t.verified = t.cls == OpClass.OWNER
                        remember(t)
                        afterOutput(t)
                        return
                    }
                    t.results[node] = out
                    agreeing(t)?.let { (a, c) ->
                        t.output = t.results.getValue(a); t.executors = listOf(a, c); t.verified = true
                        cancel(t, "verified")
                        remember(t)
                        afterOutput(t)
                        return
                    }
                    if (t.running.isNotEmpty()) return
                    if (t.results.size >= EscalationThresholds.DISAGREEING_REPLICAS) {
                        SLog.w(TAG, "replicas disagree on ${t.spec.op} ${t.spec.id}: ${t.results.map { (n, o) -> "${nameOf(n)}=${Canon.hash(o).take(8)}" }}")
                        escalate(t, EscalationTrigger.VerificationFailed("${t.results.size} phones disagreed on ${t.spec.op} '${t.spec.id}'", t.results.size),
                            "the phones disagree on ${t.spec.op.name.lowercase()} '${t.spec.id}'")
                        return
                    }
                    val third = scheduler.reassign(t.spec, snapshot, selfId, t.tried)
                    if (third == null || third in t.tried)
                        throw Failed(listOf(selfName to "the phones disagree on ${t.spec.op.name.lowercase()} '${t.spec.id}' and no third phone can break the tie"))
                    dispatch(t, third)
                }
            }
        }

        /**
         * Spec 15.1: a result that fails the step's schema is counted per phone and the step retried (an untried phone
         * first, then the tried phone with the fewest failures); [EscalationThresholds.SCHEMA_ATTEMPTS] failures across
         * [EscalationThresholds.SCHEMA_PHONES] phones escalate the step.
         */
        private suspend fun onSchemaFailure(t: TaskRun, node: String, why: String) {
            t.schemaFails[node] = (t.schemaFails[node] ?: 0) + 1
            val fails = t.schemaFails.values.sum()
            val phones = t.schemaFails.size
            SLog.w(TAG, "${t.spec.op} ${t.spec.id}: ${nameOf(node)} failed the schema ($fails/$phones): $why")
            if (t.cls == OpClass.OWNER) throw Failed(listOf(nameOf(node) to "its figure for '${t.spec.id}' does not fit the expected shape ($why)"))
            val local = "the answers for ${t.spec.op.name.lowercase()} '${t.spec.id}' failed the schema $fails times on $phones phone${if (phones == 1) "" else "s"}"
            if (fails >= EscalationThresholds.SCHEMA_ATTEMPTS && phones >= EscalationThresholds.SCHEMA_PHONES) {
                if (t.running.isNotEmpty()) cancel(t, "escalating")
                escalate(t, EscalationTrigger.VerificationFailed("schema validation failed $fails times across $phones phones ($why)", fails), local)
                return
            }
            if (fails >= EscalationThresholds.SCHEMA_ATTEMPTS) throw Failed(listOf(selfName to "$local, so no answer is given"))
            if (t.running.isNotEmpty()) return     // a replica is still running; it may still fit
            val next = scheduler.reassign(t.spec, snapshot, selfId, t.tried) ?: t.tried.minByOrNull { t.schemaFails[it] ?: 0 }
                ?: throw Failed(listOf(selfName to "$local and no phone is left to retry"))
            notes += "${t.spec.op} ${t.spec.id} retried on ${nameOf(next)} (schema: $why)"
            dispatch(t, next)
        }

        /**
         * The one path to the gate (spec 15.2, 15.3). A private step (lineage, or an owner op) is refused here without
         * calling it. `Ran` makes the cloud output the step's result under executor `cloud:<slug>`; a refusal or a
         * cloud failure ends the plan with the local reason and the gate's reason (a refused low-confidence vote keeps
         * its local answer, marked not verified).
         */
        private suspend fun escalate(t: TaskRun, trigger: EscalationTrigger, localReason: String) {
            val lin = lineage[t.spec.id] ?: Lineage.PUBLIC
            if (lin == Lineage.PRIVATE || t.cls == OpClass.OWNER) {
                if (trigger is EscalationTrigger.LowConfidence && t.output != null) {
                    t.verified = false
                    notes += "${t.spec.op} ${t.spec.id}: $localReason, and its data is private, so no cloud re-run"
                    return
                }
                throw Failed(listOf(selfName to "$localReason, and its data is private, so no cloud re-run"))
            }
            val inputs = t.spec.inputs.associateWith { tasks[it]?.output ?: JsonNull }
            val req = EscalationRequest(jobId = planId, planId = planId, commander = selfId, node = t.spec, inputs = inputs,
                privacy = lin, trigger = trigger, localAttempts = maxOf(1, t.tried.size))
            val g = try { escalator.tryEscalate(req) } catch (e: Exception) { GateOutcome.Failed(e.message ?: e.javaClass.simpleName) }
            when (g) {
                is GateOutcome.Ran -> {
                    t.running.clear()
                    t.output = g.output; t.executors = listOf(cloudExecutor(g.slug)); t.verified = true; t.cloud = g.slug
                    notes += "${t.spec.op} ${t.spec.id}: ${g.slug} answered after $localReason"
                    SLog.d(TAG, "${t.spec.op} ${t.spec.id} answered by cloud:${g.slug} (${trigger.name})")
                }
                is GateOutcome.Refused -> {
                    if (trigger is EscalationTrigger.LowConfidence && t.output != null) {
                        t.verified = false
                        notes += "${t.spec.op} ${t.spec.id}: $localReason; no cloud re-run (${g.reason})"
                    } else throw Failed(listOf(selfName to "$localReason; no cloud re-run (${g.reason})"))
                }
                is GateOutcome.Failed -> throw Failed(listOf(selfName to "$localReason; the cloud re-run failed (${g.reason})"))
            }
        }

        /** After a step settled: a low VOTE confidence escalates it; a judge below the threshold escalates the judged step. */
        private suspend fun afterOutput(t: TaskRun) {
            val out = t.output as? JsonObject ?: return
            if (t.cloud != null) return
            val conf = (out["confidence"] as? JsonPrimitive)?.doubleOrNull
            if (conf != null && out["votes"] != null && conf < EscalationThresholds.VOTE) {
                escalate(t, EscalationTrigger.LowConfidence(conf), "the vote on '${t.spec.id}' is low (confidence $conf)")
                return
            }
            if (isJudge(t.spec)) {
                val score = (out["score"] as? JsonPrimitive)?.doubleOrNull ?: return
                val judged = t.spec.inputs.firstOrNull()?.let { tasks[it] } ?: return
                if (score < EscalationThresholds.JUDGE && judged.cloud == null)
                    escalate(judged, EscalationTrigger.VerificationFailed("the local judge scored '${judged.spec.id}' $score, below ${EscalationThresholds.JUDGE}", maxOf(1, judged.tried.size)),
                        "the local judge scored ${judged.spec.op.name.lowercase()} '${judged.spec.id}' $score")
            }
        }

        private fun agreeing(t: TaskRun): Pair<String, String>? {
            val rs = t.results.entries.toList()
            for (i in rs.indices) for (j in i + 1 until rs.size)
                if (comparator.agree(t.spec.op, rs[i].value, rs[j].value, null)) return rs[i].key to rs[j].key
            return null
        }

        /** Owner AGG invariants (§6.4): declared dims present, group counts sum to rows scanned. */
        private fun invariants(t: TaskRun, node: String, out: JsonElement) {
            val table = runCatching { Canon.decodeTable(out) }.getOrElse { throw Failed(listOf(nameOf(node) to "returned a figure that is not a table")) }
            val dims = ((t.spec.params["expect"] as? JsonObject)?.get("dims") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val missing = dims.filter { it !in table.columns }
            if (missing.isNotEmpty()) throw Failed(listOf(nameOf(node) to "its figure for '${(t.spec.params["q"] as? JsonPrimitive)?.content}' is not by ${missing.joinToString(", ")}"))
            val ni = table.columns.indexOf("n")
            if (ni >= 0) {
                val sum = table.rows.sumOf { (it.getOrNull(ni) as? JsonPrimitive)?.longOrNull ?: 0L }
                if (sum != table.rowsScanned) throw Failed(listOf(nameOf(node) to "its group counts ($sum) do not add up to the rows it scanned (${table.rowsScanned})"))
            }
        }

        private suspend fun failAttempt(t: TaskRun, node: String, why: String, timeout: Boolean) {
            t.running.remove(node)
            if (t.cls == OpClass.OWNER) throw Failed(listOf(nameOf(node) to if (timeout) "no reply from ${nameOf(node)}" else why))
            val next = scheduler.reassign(t.spec, snapshot, selfId, t.tried)
            if (next != null && next !in t.tried) {
                SLog.d(TAG, "${t.spec.op} ${t.spec.id}: ${nameOf(node)} $why; reassigned to ${nameOf(next)}")
                notes += "${t.spec.op} ${t.spec.id} moved from ${nameOf(node)} to ${nameOf(next)} ($why)"
                dispatch(t, next)
                return
            }
            if (t.running.isNotEmpty()) return
            if (t.replicate && t.results.size == 1) {
                val (only, out) = t.results.entries.first()
                t.output = out; t.executors = listOf(only); t.verified = false
                notes += "${t.spec.op} ${t.spec.id} ran on one phone only (not verified)"
                return
            }
            throw Failed(listOf(selfName to "no phone left to run ${t.spec.op.name.lowercase()} '${t.spec.id}' (${if (timeout) "no reply" else why})"))
        }

        private suspend fun timeouts() {
            val now = clock()
            for (t in tasks.values.toList()) for ((node, at) in t.running.entries.toList()) if (at <= now && node in t.running) {
                cancelOne(t, node, "deadline")
                failAttempt(t, node, "no reply", timeout = true)
            }
        }

        private suspend fun cancel(t: TaskRun, reason: String) {
            for (node in t.running.keys.toList()) cancelOne(t, node, reason)
            t.running.clear()
        }

        private suspend fun cancelOne(t: TaskRun, node: String, reason: String) {
            transmit(node, MsgType.TASK_CANCEL, MeshJson.encode(TaskSignalBody(planId, t.spec.id, selfId, reason)))
        }

        private suspend fun cancelRunning(reason: String) { for (t in tasks.values) cancel(t, reason) }

        // ------------------------------------------------------------------ memo + ledger

        private fun memoKey(spec: TaskSpec): String {
            val dv = if (OpClasses.of(spec.op) == OpClass.OWNER) spec.owner?.let(dataVersionOf).orEmpty() else ""
            return Canon.sha256Hex(Canon.canonical(Canon.json.encodeToJsonElement(TaskSpec.serializer(), spec)) + "|" + dv + "|" + modelHash)
        }

        private fun remember(t: TaskRun) {
            val key = t.memoKey ?: return
            if (!t.verified && t.cls != OpClass.OWNER) return
            val out = t.output ?: return
            synchronized(lock) {
                memo[key] = Memo(out, t.executors)
                while (memo.size > MAX_MEMO) memo.remove(memo.keys.first())
            }
        }

        private fun ledgerPlan() {
            val l = ledger ?: return
            try {
                val entry = buildJsonObject {
                    put("kind", "plan")
                    put("planHash", planHash)
                    put("question", question)
                    put("patternId", dag.patternId)
                    put("patternsVersion", dag.patternsVersion)
                    put("modelHash", modelHash)
                    put("assignment", Canon.json.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), assignment))
                    put("profiles", Canon.json.encodeToJsonElement(ListSerializer(Profile.serializer()), snapshot))
                    put("dag", Canon.json.encodeToJsonElement(Dag.serializer(), dag))
                }
                l.append(planId, "plan", Canon.canonical(entry))
            } catch (e: Exception) { SLog.e(TAG, "ledger plan entry failed", e) }
        }

        private fun ledgerTasks() {
            val l = ledger ?: return
            for (t in tasks.values) {
                val out = t.output ?: continue
                try {
                    val entry = buildJsonObject {
                        put("kind", "task")
                        put("planId", planId)
                        put("taskId", t.memoKey ?: Canon.sha256Hex(planId + t.spec.id))
                        put("task", t.spec.id)
                        put("op", t.spec.op.name)
                        put("executors", JsonArray(t.executors.map { JsonPrimitive(it) }))
                        put("outputHash", Canon.hash(out))
                        put("verifiedBy", JsonArray((if (t.verified && t.executors.size > 1) t.executors.drop(1) else emptyList()).map { JsonPrimitive(it) }))
                        put("memo", t.fromMemo)
                        t.cloud?.let { put("source", cloudExecutor(it)) }
                    }
                    l.append(planId, "task", Canon.canonical(entry))
                } catch (e: Exception) { SLog.e(TAG, "ledger task entry failed", e) }
            }
        }

        // ------------------------------------------------------------------ answer

        private fun answer(t0: Long): AskOutcome {
            ledgerTasks()
            val final = tasks.getValue(dag.output)
            val ownerTables = tasks.values.filter { it.spec.op == Op.AGG }.mapNotNull { r -> r.output?.let { runCatching { Canon.decodeTable(it) }.getOrNull() } }
            val docs = tasks.values.filter { it.spec.op == Op.DOC }.mapNotNull { r ->
                r.output?.let { runCatching { Canon.json.decodeFromJsonElement(DocAnswer.serializer(), it) }.getOrNull() }
            }
            val out = final.output!!
            val finalTable = if (final.spec.op == Op.AGG) null else runCatching { Canon.decodeTable(out) }.getOrNull()
            val producer = producerOf(final)
            val inputsOfProducer = producer.spec.inputs.mapNotNull { id -> tasks[id]?.output?.let { runCatching { Canon.decodeTable(it) }.getOrNull() } }
            val text = PlanRenderer.render(producer.spec, out, inputsOfProducer, notes)
            val lines = tasks.values.map { r ->
                val where = r.executors.joinToString("+") { if (it == selfId) "here" else if (isCloud(it)) it else nameOf(it) }.ifEmpty { "–" }
                val time = if (r.fromMemo) "memo" else PlanRenderer.seconds(r.ms)
                "${r.spec.op} · $where · $time" + if (r.verified) " ✓" else ""
            }
            val provenance = provenance()
            val phones = (tasks.values.flatMap { it.executors }.filter { !isCloud(it) } + selfId).distinct().size
            return AskOutcome.Answer(
                text = text, tables = ownerTables, joined = finalTable, docAnswers = docs, chip = AnswerChip.PLAN,
                provenance = provenance, timing = Timing(totalMs = clock() - t0), notes = notes.toList(),
                rowsMoved = 0, rowsHeld = ownerTables.sumOf { it.rowsHeld }, approvals = approvals, phones = phones,
                plan = PlanSummary(dag.patternId, dag.tasks.size, lines, tasks.values.count { it.verified }, confidence, planHash)
            )
        }

        /** RENDER passes its input through: the sentence template is the one of the op that produced the value. */
        private fun producerOf(t: TaskRun): TaskRun =
            if (t.spec.op == Op.RENDER && t.spec.inputs.isNotEmpty()) tasks[t.spec.inputs.first()]?.let { producerOf(it) } ?: t else t

        private fun provenance(): List<Provenance> {
            val out = ArrayList<Provenance>()
            val byNode = LinkedHashMap<String, MutableList<TaskRun>>()
            for (t in tasks.values) for (n in t.executors) byNode.getOrPut(n) { ArrayList() } += t
            for ((node, ts) in byNode) {
                val name = if (node == selfId) selfName else if (isCloud(node)) node else nameOf(node)
                val tables = ts.filter { it.spec.op == Op.AGG }.mapNotNull { r -> r.output?.let { runCatching { Canon.decodeTable(it) }.getOrNull() } }
                val parts = ArrayList<String>()
                if (tables.isNotEmpty()) {
                    val rows = tables.sumOf { it.rowsScanned }
                    val aggs = tables.sumOf { it.rows.size }
                    parts += "${RenderText.indian(rows)} rows → $aggs aggregates"
                }
                val ops = ts.filter { it.spec.op != Op.AGG }.groupBy { it.spec.op }.map { (op, l) -> if (l.size == 1) op.name else "${op.name} ×${l.size}" }
                parts.addAll(ops)
                out += Provenance(name, "$name: ${parts.joinToString(", ")}")
            }
            return out
        }
    }

    companion object {
        private const val TAG = "Commander"
        private const val MAX_MEMO = 500
        private const val CLOUD_PREFIX = "cloud:"
        /** The executor name of a cloud-answered step (spec 15.3 `source = cloud:<slug>`). */
        fun cloudExecutor(slug: String) = CLOUD_PREFIX + slug
        fun isCloud(executor: String) = executor.startsWith(CLOUD_PREFIX)
    }
}

/**
 * Deterministic English for a plan's output (R2: no model writes the sentence). One template per op; numbers use
 * Indian digit grouping like [RenderText]; reduce notes (division by zero, missing keys) are appended.
 */
object PlanRenderer {

    fun render(producer: TaskSpec, out: JsonElement, inputs: List<AggTable>, notes: List<String>): String {
        val body = when {
            isTable(out) -> table(producer, Canon.decodeTable(out), inputs)
            out is JsonObject && out["text"] != null && out["doc"] != null ->
                RenderText.renderDoc(Canon.json.decodeFromJsonElement(DocAnswer.serializer(), out))
            out is JsonPrimitive && out.isString -> out.content
            else -> "Result: ${Canon.canonical(out)}"
        }
        return if (notes.isEmpty()) body else "$body (${notes.joinToString("; ")})"
    }

    private fun isTable(e: JsonElement) = e is JsonObject && e["columns"] is JsonArray && e["rows"] is JsonArray

    fun table(spec: TaskSpec, t: AggTable, inputs: List<AggTable>): String {
        if (t.rows.isEmpty()) return "${sentenceCase(t.title)}: no rows match."
        val p = { k: String -> (spec.params[k] as? JsonPrimitive)?.content }
        return when (spec.op) {
            Op.RATIO, Op.DIFF, Op.SHARE -> arith(spec.op, t, p("num"), p("den"), inputs)
            Op.RANK -> {
                val m = p("measure") ?: t.columns.last()
                val mi = t.columns.indexOf(m)
                val ri = t.columns.indexOf("rank")
                val keys = t.columns.indices.filter { it != mi && it != ri && !ReducerImpl.isMeasure(t.columns[it]) }
                "Ranked by ${measureName(m)}: " + t.rows.joinToString(" · ") { r ->
                    "${if (ri >= 0) number(r[ri]) + ". " else ""}${keys.joinToString(" / ") { text(r[it]) }} ${number(r.getOrElse(mi) { JsonNull })}"
                } + "."
            }
            else -> generic(t)
        }
    }

    private fun arith(op: Op, t: AggTable, num: String?, den: String?, inputs: List<AggTable>): String {
        val vi = t.columns.size - 1
        val tail = if (op == Op.SHARE && inputs.size == 1) 1 else 2      // [num, (den,) value] after the key columns
        val keyCols = t.columns.indices.filter { it < vi - tail }
        val numName = num?.let { measureName(it) } ?: "value"
        val denName = when {
            op == Op.SHARE && inputs.size == 1 -> "the total"
            den == "n" || den?.startsWith("count") == true -> singular(RenderText.noun(inputs.getOrNull(1)?.node ?: ""))
            den != null -> measureName(den)
            else -> "value"
        }
        val by = keyCols.joinToString(", ") { t.columns[it] }
        val head = when (op) {
            Op.RATIO -> "$numName per $denName"
            Op.DIFF -> "$numName minus $denName"
            else -> "$numName as a share of $denName"
        } + if (by.isEmpty()) "" else " by $by"
        val pct = if (op == Op.SHARE) "%" else ""
        val rows = t.rows.joinToString(" · ") { r ->
            val key = keyCols.joinToString(" / ") { text(r[it]) }
            val v = r.getOrElse(vi) { JsonNull }
            (if (key.isEmpty()) "" else "$key ") + number(v) + (if (v is JsonNull) "" else pct)
        }
        return "${sentenceCase(head)}: $rows."
    }

    private fun generic(t: AggTable): String {
        val dims = t.columns.indices.filter { !ReducerImpl.isMeasure(t.columns[it]) && t.rows.all { r -> r.getOrNull(it).let { v -> v !is JsonPrimitive || v.isString || v is JsonNull } } }
        val measures = t.columns.indices.filter { it !in dims }
        return "${sentenceCase(t.title)}: " + t.rows.joinToString(" · ") { r ->
            val key = dims.joinToString(" / ") { text(r[it]) }
            val facts = measures.joinToString(", ") { "${measureName(t.columns[it])} ${number(r.getOrElse(it) { JsonNull })}" }
            if (key.isEmpty()) facts else "$key: $facts"
        } + "."
    }

    fun measureName(m: String): String = when {
        m == "n" -> "count"
        m.startsWith("sum_") -> human(m.removePrefix("sum_"))
        m.startsWith("avg_") -> "average " + human(m.removePrefix("avg_"))
        m.startsWith("min_") -> "minimum " + human(m.removePrefix("min_"))
        m.startsWith("max_") -> "maximum " + human(m.removePrefix("max_"))
        m.startsWith("count_") -> "count of " + human(m.removePrefix("count_"))
        m == "share_pct" -> "share %"
        else -> human(m)
    }

    private fun human(s: String) = s.removePrefix("c_").replace('_', ' ')

    private fun singular(noun: String) = when (noun) { "rows" -> "row"; else -> noun.removeSuffix("s") }

    private fun sentenceCase(s: String) = s.replaceFirstChar { it.uppercase() }

    fun seconds(ms: Long): String = if (ms < 1000) "$ms ms" else String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)

    private fun text(v: JsonElement): String = when (v) {
        is JsonNull -> "(blank)"
        is JsonPrimitive -> if (v.isString) v.content else number(v)
        else -> v.toString()
    }

    fun number(v: JsonElement): String = when (v) {
        is JsonNull -> "–"
        is JsonPrimitive -> if (v.isString) v.content else v.longOrNull?.let { RenderText.indian(it) }
            ?: v.doubleOrNull?.let { RenderText.indian(it) } ?: v.content
        else -> v.toString()
    }
}
