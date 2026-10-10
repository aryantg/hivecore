package com.hivecore.engine.placement

import com.hivecore.core.ModelEntry
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.PlacementRow
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelFailBody
import com.hivecore.mesh.ModelReadyBody
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.Verified
import com.hivecore.runtime.catalog.ErrorClasses
import com.hivecore.runtime.catalog.ModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Orchestrator side of placement for one job (spec 8.2, 8.3):
 *  - [request] runs [Placement.plan] over the latest profiles and sends one `MODEL_REQ` per assignment through
 *    [send], each after its journal `placements` row (`requested`) is written; the request's `deadlineMs` is
 *    `max(10 s, 3 × loadMsP50)` of the build asked for (20.2);
 *  - [onMessage] (or [attach] on a verified-message flow) consumes `MODEL_READY` / `MODEL_FAIL` for this plan:
 *    the first READY for a capability opens its work queue ([onOpen]), later ones add phones ([onAdded]); a FAIL
 *    puts `(node, capability)` on the 10-minute cooldown and re-solves the remaining demand over phones not yet
 *    asked; when nothing is left to ask and nobody is ready, [onGap] reports the capability;
 *  - [expire] turns a request past its deadline into `MODEL_FAIL {class: runtime}` (cooldown and re-solve like any
 *    other failure); [watch] runs that on a timer; [nextWake] feeds the commander's own wake loop.
 * The orchestrator's own loads go through the same message, delivered in-process by the caller's [send] (16.8).
 */
class PlacementCoordinator(
    val jobId: String,
    val planId: String,
    private val selfId: String,
    private val catalog: ModelCatalog,
    private val cooldowns: CooldownTable,
    private val profiles: () -> List<Profile>,
    private val send: suspend (to: String, type: MsgType, body: String) -> Result<Unit>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val journal: JobJournal = JobJournal.None,
    private val heavyCaps: Set<String> = Placement.DEFAULT_HEAVY,
    private val blobCaps: Set<String> = Placement.DEFAULT_BLOB,
    /** Units still to do per capability when re-solving (default: the demand as requested). */
    private val remaining: (String) -> Int? = { null },
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) {
    /** First READY for a capability: the work queue may open with [node]. */
    var onOpen: (capability: String, node: String) -> Unit = { _, _ -> }
    /** A later READY: [node] joins the capability's phones. */
    var onAdded: (capability: String, node: String) -> Unit = { _, _ -> }
    /** Nothing is ready for the capability and no phone is left to ask (capability gap, job ends partial). */
    var onGap: (capability: String, reason: String) -> Unit = { _, _ -> }

    private class Req(val a: Placement.Assignment, val entry: ModelEntry, val sentAt: Long, val deadlineAt: Long)

    private val lock = Mutex()
    private val pending = LinkedHashMap<Pair<String, String>, Req>()            // (node, capability) → request
    private val readyBy = LinkedHashMap<String, LinkedHashMap<String, ModelReadyBody>>()  // capability → node → reply
    private val asked = LinkedHashSet<Pair<String, String>>()
    private val bigOf = HashMap<String, String>()                              // node → big model id in this job
    private var demand: Map<String, Int> = emptyMap()
    private val failureNotes = ArrayList<String>()
    private var lastOutcome: Placement.Outcome? = null

    /** Human-readable notes for the job ("b7c1… could not load … for vlm (oom)"). */
    val failures: List<String> get() = synchronized(failureNotes) { failureNotes.toList() }
    /** The last solver outcome (assignments, swap cost, gaps), for the plan panel. */
    val outcome: Placement.Outcome? get() = lastOutcome

    fun ready(capability: String): Set<String> = readyBy[capability]?.keys?.toSet().orEmpty()
    fun readyModel(capability: String, node: String): String? = readyBy[capability]?.get(node)?.modelId
    fun pending(capability: String): Set<String> = pending.keys.filter { it.second == capability }.map { it.first }.toSet()
    val anyPending: Boolean get() = pending.isNotEmpty()
    /** modelId READY per capability and node (the journal's capability snapshot). */
    fun snapshot(): Map<String, Map<String, String>> = readyBy.mapValues { e -> e.value.mapValues { it.value.modelId } }

    /** Places [demand] (capability → units) and sends the MODEL_REQs; returns the solver's outcome. */
    suspend fun request(demand: Map<String, Int>): Placement.Outcome = lock.withLock {
        this.demand = demand
        val out = solveLocked(demand)
        for (a in out.assignments) sendLocked(a)
        out
    }

    private fun solveLocked(demand: Map<String, Int>): Placement.Outcome {
        val out = Placement.plan(demand, profiles(), catalog.entries, selfId, heavyCaps, blobCaps, cooldowns, clock(), asked.toSet(), bigOf.toMap())
        lastOutcome = out
        return out
    }

    private suspend fun sendLocked(a: Placement.Assignment) {
        val key = a.nodeId to a.capability
        val entry = catalog.entry(a.modelId, a.capability) ?: return
        asked += key
        if (entry.big) bigOf[a.nodeId] = entry.modelId
        journal.placement(PlacementRow(jobId, a.nodeId, a.capability, entry.modelId, entry.sha256, STATE_REQUESTED))
        val now = clock()
        val deadline = deadlineMs(entry)
        pending[key] = Req(a, entry, now, now + deadline)
        val body = ModelReqBody(planId, a.capability, entry.modelId, entry.sha256, deadline, selfId, now, nonce())
        send(a.nodeId, MsgType.MODEL_REQ, MeshJson.encode(body)).onFailure {
            failLocked(a.nodeId, a.capability, entry.modelId, ErrorClasses.UNSUPPORTED, "MODEL_REQ not sent: ${it.message}", cool = false)
        }
    }

    /** Routes a verified message; true when it was a MODEL_READY / MODEL_FAIL of this plan. */
    suspend fun onMessage(type: MsgType, from: String, body: String): Boolean = when (type) {
        MsgType.MODEL_READY -> runCatching { MeshJson.decode<ModelReadyBody>(body) }.getOrNull()?.let { onReady(from, it) } != null
        MsgType.MODEL_FAIL -> runCatching { MeshJson.decode<ModelFailBody>(body) }.getOrNull()?.let { onFail(from, it) } != null
        else -> false
    }

    /** Collects READY / FAIL from a verified-message flow (the router's `verified`, or `SwarmNode.onModelReply`). */
    fun attach(flow: Flow<Verified>, scope: CoroutineScope): Job = scope.launch { flow.collect { onMessage(it.type, it.from, it.body) } }

    /** A MODEL_READY; returns the capability it readies (null = not one of ours). */
    suspend fun onReady(from: String, b: ModelReadyBody): String? = lock.withLock {
        if (b.planId != planId || b.node != from) return@withLock null
        val key = pending.keys.firstOrNull { it.first == from && matches(it.second, b.modelId) } ?: return@withLock null
        val req = pending.remove(key)!!
        val entry = catalog.entry(b.modelId, key.second) ?: req.entry
        if (entry.big) bigOf[from] = entry.modelId
        journal.placement(PlacementRow(jobId, from, key.second, entry.modelId, entry.sha256, STATE_READY, b.loadMs))
        val phones = readyBy.getOrPut(key.second) { LinkedHashMap() }
        val first = phones.isEmpty()
        phones[from] = b
        SLog.d(TAG, "${key.second} ready on ${from.take(8)} (${b.modelId}, ${b.loadMs} ms${if (b.warm) ", warm" else ""})")
        if (first) onOpen(key.second, from) else onAdded(key.second, from)
        key.second
    }

    /** A MODEL_FAIL; applies the cooldown, re-solves the capability; returns it (null = not one of ours). */
    suspend fun onFail(from: String, b: ModelFailBody): String? = lock.withLock {
        if (b.planId != planId || b.node != from) return@withLock null
        val key = pending.keys.firstOrNull { it.first == from && matches(it.second, b.modelId) } ?: return@withLock null
        failLocked(from, key.second, b.modelId, b.errorClass, b.message, cool = true)
        resolveLocked(key.second)
        key.second
    }

    private fun failLocked(node: String, capability: String, modelId: String, cls: String, msg: String?, cool: Boolean) {
        val req = pending.remove(node to capability) ?: return
        if (cool) cooldowns.mark(node, capability)
        journal.placement(PlacementRow(jobId, node, capability, modelId, req.entry.sha256, "$STATE_FAILED:$cls"))
        if (bigOf[node] == req.entry.modelId) bigOf.remove(node)            // the slot promised to this build is free again
        synchronized(failureNotes) { failureNotes += "${node.take(8)} could not load ${req.entry.modelId} for $capability ($cls${msg?.let { ": $it" } ?: ""})" }
        SLog.w(TAG, failureNotes.last())
    }

    /** Re-solves [capability] over phones not asked yet (spec 8.3 "re-runs the solver for the remaining demand"). */
    private suspend fun resolveLocked(capability: String) {
        val units = remaining(capability) ?: demand[capability] ?: return
        if (units <= 0) return
        val out = Placement.plan(mapOf(capability to units), profiles(), catalog.entries, selfId, heavyCaps, blobCaps, cooldowns, clock(),
            asked.toSet(), bigOf.filterValues { id -> catalog.entriesOf(id).none { it.capability == capability } })
        for (a in out.assignments) sendLocked(a)
        if (out.assignments.isEmpty() && ready(capability).isEmpty() && pending(capability).isEmpty())
            onGap(capability, "no phone can load $capability now: " + failures.filter { " for $capability " in it }.joinToString("; "))
    }

    /** A phone that left the job (removal, run-time cooldown): it no longer counts as ready for [capability]. */
    suspend fun drop(node: String, capability: String) = lock.withLock {
        readyBy[capability]?.remove(node)
        pending.remove(node to capability)
        Unit
    }

    /** Requests past their deadline become `runtime` failures (spec 8.3); returns the capabilities affected. */
    suspend fun expire(): List<String> = lock.withLock {
        val now = clock()
        val late = pending.filter { now >= it.value.deadlineAt }.keys.toList()
        for ((n, c) in late) {
            val req = pending.getValue(n to c)
            failLocked(n, c, req.entry.modelId, ErrorClasses.RUNTIME, "no MODEL_READY within ${req.deadlineAt - req.sentAt} ms", cool = true)
        }
        for (c in late.map { it.second }.distinct()) resolveLocked(c)
        late.map { it.second }.distinct()
    }

    /** The earliest MODEL_REQ deadline (null = nothing pending). */
    fun nextWake(): Long? = pending.values.minOfOrNull { it.deadlineAt }

    /** Runs [expire] whenever the next deadline passes, until nothing is pending or the scope ends. */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val next = nextWake() ?: break
            delay(maxOf(1L, next - clock()))
            expire()
        }
    }

    private fun matches(capability: String, replied: String): Boolean = catalog.forCapability(capability).any { it.modelId == replied }

    companion object {
        const val STATE_REQUESTED = "requested"
        const val STATE_READY = "ready"
        const val STATE_FAILED = "failed"
        const val MIN_DEADLINE_MS = 10_000L
        private const val TAG = "Placement"

        /** Spec 20.2: `max(10 s, 3 × loadMsP50)`. */
        fun deadlineMs(entry: ModelEntry): Long = maxOf(MIN_DEADLINE_MS, 3L * entry.loadMsP50)
    }
}
