package com.hivecore.engine.split

import com.hivecore.core.Canon
import com.hivecore.core.Comparator
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.MergeKind
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.TaskRunner
import com.hivecore.core.TaskSpec
import com.hivecore.engine.ComparatorImpl
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.Lineage
import com.hivecore.engine.SchemaValidator
import com.hivecore.engine.journal.AttemptRow
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.ChunkRow
import com.hivecore.engine.journal.EscalationRow
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.JobRow
import com.hivecore.engine.journal.JobState
import com.hivecore.engine.journal.OutputRow
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelFailBody
import com.hivecore.mesh.ModelReadyBody
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import com.hivecore.mesh.TaskSignalBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs split jobs (spec sections 10–15) from this phone: [ChunkPlanner] → [WorkQueue] → TASK / TASK_RESULT messages
 * (the same ones the graph [com.hivecore.engine.Commander] uses; this phone's own chunks are delivered in-process and
 * run on its TaskRunner) → [Mergers] in chunk-index order on this phone (the orchestrator loop of section 11.4).
 *
 * Message ids: `planId` = "<jobId>#<run>", `taskId` = "c<chunk>.a<attempt>", so retries and duplicates are distinct
 * tasks for the workers' de-duplication, and a late result of an old attempt is recognised and compared, never
 * double-counted.
 *
 * Seams (never the cloud package itself): the [journal] gets its row before any TASK goes out and before the merge
 * runs (13.1); the [memo] pre-fills finished chunks before dispatch and stores accepted, verified outputs (13.2);
 * cloud outputs are memoized under `cloud:<slug>:<chunkId>` only (15.3); the [escalator] decides verification
 * failures and low-confidence votes (12.1, 15.1); [schema] validates outputs against `params.schema`; [readyFor]
 * is placement's MODEL_READY set per capability (10.5); [onCooldown] reports the 10-minute (node, capability)
 * cooldown an oom / model_load / thermal result starts (14).
 *
 * P10 (spec 6.5, 16.3): before a phone's first TASK of a run it gets a PLAN whose dag holds the job's one template
 * (`SplitPlan.template`) and whose `chunkIds` list every task id the run may send (chunk × attempt); a re-cut that adds
 * chunks re-sends it. Nodes drop chunk TASKs that instantiate no held PLAN.
 */
class SplitCommander(
    private val selfId: String,
    private val comparator: Comparator? = ComparatorImpl(),
    private val profiles: () -> List<Profile>,
    private val transmit: suspend (to: String, type: MsgType, body: String) -> Result<Unit>,
    private val sendBlobs: suspend (to: String, blobIds: List<String>, jobId: String) -> Result<Long> = { _, _, _ -> Result.success(0L) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val planner: ChunkPlanner = ChunkPlanner(),
    private val selector: TreeSelector = Mergers.extractiveSelector,
    private val modelHash: () -> String = { "" },
    private val slots: Int = SplitConstants.SLOTS,
    private val journal: JobJournal = JobJournal.None,
    private val memo: ChunkMemo = ChunkMemo.None,
    private val escalator: Escalator = Escalator.Off,
    private val schema: SchemaValidator = SchemaValidator.NONE,
    /** phones with MODEL_READY for a capability (placement, section 8.3); null = no placement ran, use the profile view */
    private val readyFor: (capability: String) -> Set<String>? = { null },
    /** the catalog `unitMsP50` for a (node, capability) when the profile carries no benchmark (section 10.5) */
    private val unitMsOf: (nodeId: String, capability: String) -> Double? = { _, _ -> null },
    /** section 5.6 metered policy: blobs over mobile data only when the swarm allows it */
    private val allowMobileData: () -> Boolean = { false },
    /** a (node, capability) cooldown started by a TASK_RESULT of class oom / model_load / thermal or a MODEL_FAIL */
    private val onCooldown: (nodeId: String, capability: String, untilTs: Long) -> Unit = { _, _, _ -> },
    /**
     * Section 5.5 / 14 "node asleep": called before a TASK to a WAN phone (`RelayPresence.ensureAwake`); false = the
     * phone did not come back within the wake grace, so the attempt is faulted blamelessly and the phone paused.
     */
    private val ensureAwake: suspend (nodeId: String) -> Boolean = { true },
    /** section 13.4: the deputy this run streams its journal to (recorded in the job row so a resume knows whom to ask); null = none */
    private val deputy: () -> String? = { null },
) {
    private sealed interface Event {
        data class Result(val node: String, val body: TaskResultBody) : Event
        data class Yield(val node: String, val taskId: String, val reason: String) : Event
        data class ModelReady(val node: String) : Event
        data class ModelFail(val node: String, val errorClass: String) : Event
        data object Cancel : Event
    }

    private val lock = Any()
    private val runs = HashMap<String, Channel<Event>>()
    private val cooldowns = HashMap<Pair<String, String>, Long>()
    private val seq = AtomicLong(0)

    /** Active (node, capability) cooldowns (section 14): until timestamps, this commander's own view. */
    fun cooldowns(): Map<Pair<String, String>, Long> = synchronized(lock) { val now = clock(); cooldowns.filterValues { it > now } }

    /** Starts (or extends) a cooldown, e.g. from a MODEL_FAIL seen by placement; reported through [onCooldown]. */
    fun cooldown(nodeId: String, capability: String, untilTs: Long = clock() + SplitConstants.CAPABILITY_COOLDOWN_MS) {
        synchronized(lock) { cooldowns[nodeId to capability] = untilTs }
        onCooldown(nodeId, capability, untilTs)
    }

    private fun cooled(nodeId: String, capability: String?, now: Long): Boolean =
        capability != null && synchronized(lock) { (cooldowns[nodeId to capability] ?: 0L) > now }

    /** Stops every live run of [jobId]: running chunks get TASK_CANCEL, the run ends with error "cancelled". */
    fun cancel(jobId: String): Boolean {
        val chs = synchronized(lock) { runs.filterKeys { it.substringBeforeLast('#') == jobId }.values.toList() }
        chs.forEach { it.trySend(Event.Cancel) }
        return chs.isNotEmpty()
    }

    /** TASK_RESULT / TASK_YIELD / MODEL_READY / MODEL_FAIL for a live split job; false when no job of this phone matches. */
    fun onMessage(type: MsgType, from: String, body: String): Boolean {
        val (planId, ev) = try {
            when (type) {
                MsgType.TASK_RESULT -> MeshJson.decode<TaskResultBody>(body).let { it.planId to Event.Result(from, it) }
                MsgType.TASK_YIELD -> MeshJson.decode<TaskSignalBody>(body).let { it.planId to Event.Yield(from, it.taskId, it.reason) }
                MsgType.MODEL_READY -> MeshJson.decode<ModelReadyBody>(body).let { it.planId to Event.ModelReady(from) }
                MsgType.MODEL_FAIL -> MeshJson.decode<ModelFailBody>(body).let { it.planId to Event.ModelFail(from, it.errorClass) }
                else -> return false
            }
        } catch (e: Exception) { return false }
        val ch = synchronized(lock) { runs[planId] } ?: return false
        ch.trySend(ev)
        return true
    }

    /** True while the run [planId] ("<jobId>#<run>") is live on this phone (a worker's TASK_PROGRESS is shown only then). */
    fun isLive(planId: String): Boolean = synchronized(lock) { planId in runs }

    /**
     * Runs [job] to completion; emits the chunk grid as it changes; the last emission has `done` (complete, or
     * `partial` with a reason) or `error` (cancelled, could not plan, merge failed). [ready] overrides [readyFor] for
     * this run (the phones placement reported MODEL_READY). A resume (13.3) passes the outputs it already holds
     * (journaled and reclaimed) as [prefilled] (chunkId -> output) so only the missing chunks run, and [planRun]
     * continues the journaled `jobId#run` numbering so the resumed attempts are new task ids on every phone.
     */
    fun run(job: SplitJob, useMemo: Boolean = true, ready: Set<String>? = null, prefilled: Map<String, JsonElement> = emptyMap(),
            planRun: Long? = null): Flow<SplitProgress> = channelFlow {
        val t0 = clock()
        val cap = job.cap
        val profs = profiles().filter { !cooled(it.nodeId, cap, t0) }
        val readySet = (ready ?: cap?.let { readyFor(it) })?.filter { !cooled(it, cap, t0) }?.toSet()
        val plan = planner.plan(job, profs, selfId, readySet, unitMsOf).getOrElse { e ->
            send(failed(job, e.message ?: "cannot plan the split", t0)); return@channelFlow
        }
        val snapshot = profs.associateBy { it.nodeId }
        val links = plan.eligible.associateWith { n -> if (n == selfId) Link.defaultFor(LinkKind.LAN) else WorkQueue.linkFrom(snapshot[n]) }
        fun isWan(n: String) = (links[n]?.kind ?: LinkKind.LAN).let { it == LinkKind.WAN_DIRECT || it == LinkKind.WAN_RELAY }
        val q = WorkQueue(plan, plan.eligible, clock, comparator, slots = slots,
            warm = { n -> job.modelId != null && snapshot[n]?.warm?.contains(job.modelId) == true },
            linkOf = { n -> links[n] ?: Link.UNKNOWN }, allowMobileData = allowMobileData(), replicas = plan.replicas)
        val planId = "${job.jobId}#${planRun ?: seq.incrementAndGet()}"
        val journaled = journal !== JobJournal.None
        val privacy = when {
            plan.chunks.all { it.private } -> Lineage.PRIVATE
            plan.chunks.none { it.private } -> Lineage.PUBLIC
            else -> "mixed"
        }
        val planHash = Canon.sha256Hex(Canon.canonical(Canon.json.encodeToJsonElement(com.hivecore.core.Dag.serializer(), plan.dag)) +
            ChunkPlanner.PATTERNS_VERSION + (job.modelSha256 ?: modelHash()))
        val deputyId = try { deputy() } catch (e: Exception) { null }
        fun jobRow(state: String) = JobRow(job.jobId, planId, planHash, "split", state, t0, clock(), privacy,
            capsSnapshot = deputyId?.let { d -> kotlinx.serialization.json.buildJsonObject { put("deputy", kotlinx.serialization.json.JsonPrimitive(d)) } },
            spec = Canon.json.encodeToJsonElement(SplitJob.serializer(), job))
        fun chunkRow(c: Chunk, state: String, attempts: Int) = ChunkRow(job.jobId, c.chunkId, c.index, c.params, if (c.private) plan.owner else null,
            state, attempts, c.recutOf?.let { q.chunkAt(it)?.chunkId }, c.units)
        // 13.1 write-ahead: the job and every chunk are journaled before the first TASK goes out.
        journal.begin(jobRow(JobState.RUNNING))
        var journaledChunks = 0
        fun journalNewChunks() { while (journaledChunks < q.chunkCount) { journal.chunk(chunkRow(q.chunkAt(journaledChunks)!!, "queued", 0)); journaledChunks++ } }
        journalNewChunks()
        // 13.3: a resume brings the outputs the journal and the swarm's memos already hold (their output rows are journaled).
        plan.chunks.forEach { c ->
            prefilled[c.chunkId]?.let { out -> q.prefill(c.index, out); journal.chunk(chunkRow(c, "done", 0)) }
        }
        // 13.2: memo hits are pre-filled before dispatch.
        if (useMemo) plan.chunks.forEach { c ->
            if (c.chunkId in prefilled) return@forEach
            memo.get(c.chunkId)?.let { hit ->
                q.prefill(c.index, hit.outputJson)
                journal.output(OutputRow(job.jobId, c.chunkId, hit.outputHash, hit.outputJson, "memo"))
                journal.chunk(chunkRow(c, "done", 0))
            }
        }
        val events = Channel<Event>(Channel.UNLIMITED)
        synchronized(lock) { runs[planId] = events }
        val blobsAt = HashSet<String>()     // "node|blob" already shipped
        val outages = HashMap<String, Int>()
        val stepCloud = ArrayList<String>()
        val planned = HashMap<String, Int>()  // node -> chunk count the PLAN it holds covers (P10)
        try {
            /** P10: the PLAN a phone authorises this run's chunk TASKs against; sent before its first TASK and after a re-cut. */
            suspend fun ensurePlanned(node: String) {
                if (node == selfId || planned[node] == q.chunkCount) return
                val ids = (0 until q.chunkCount).flatMap { i -> (1..SplitConstants.MAX_ATTEMPTS).map { a -> WorkQueue.taskId(i, a) } }
                val dag = com.hivecore.core.Dag("split", ChunkPlanner.PATTERNS_VERSION, listOf(plan.template()), "split")
                val body = PlanBody(planId, planHash, selfId, "split ${job.op.name.lowercase()}", dag, emptyMap(), clock(), UUID.randomUUID().toString(), ids)
                transmit(node, MsgType.PLAN, MeshJson.encode(body)).onSuccess { planned[node] = q.chunkCount }
                    .onFailure { SLog.w(TAG, "PLAN $planId to ${node.take(8)} not sent: ${it.message}") }
            }
            suspend fun cancel(a: WorkQueue.Attempt, why: String) {
                transmit(a.node, MsgType.TASK_CANCEL, MeshJson.encode(TaskSignalBody(planId, WorkQueue.taskId(a.chunk, a.no), selfId, why)))
            }
            fun attemptRow(c: Chunk, a: Int, node: String, outcome: String, hash: String? = null, cls: String? = null, deadline: Long = 0L) =
                AttemptRow(job.jobId, c.chunkId, a, node, clock(), deadline, outcome, hash, cls)
            fun unreachable(node: String) {
                if (isWan(node) && (outages[node] ?: 0) == 0) {
                    outages[node] = 1
                    q.pause(node, SplitConstants.RELAY_OUTAGE_WAIT_MS)
                    q.notes += "${node.take(8)} is not reachable over the relay; waiting ${SplitConstants.RELAY_OUTAGE_WAIT_MS / 1000} s"
                } else q.removeNode(node, "unreachable")
            }
            suspend fun dispatch(a: WorkQueue.Assignment) {
                val remote = a.node != selfId
                val c = a.chunk
                if (remote && c.blobs.isNotEmpty()) {
                    val missing = c.blobs.filter { "${a.node}|$it" !in blobsAt }
                    if (missing.isNotEmpty()) {
                        val sent = sendBlobs(a.node, missing, job.jobId)
                        sent.onSuccess { b -> q.addBytes(b); missing.forEach { blobsAt += "${a.node}|$it" } }
                        if (sent.isFailure) {
                            journal.attempt(attemptRow(c, a.attempt, a.node, "error", cls = SplitConstants.ERR_BLOBS_MISSING))
                            q.onFault(a.node, c.index, a.attempt, "items could not be sent", SplitConstants.ERR_BLOBS_MISSING)
                            q.removeNode(a.node, "unreachable"); return
                        }
                    }
                }
                if (remote && isWan(a.node) && !ensureAwake(a.node)) {
                    journal.attempt(attemptRow(c, a.attempt, a.node, "error", cls = "asleep"))
                    q.onFault(a.node, c.index, a.attempt, "asleep", WorkQueue.UNREACHABLE)
                    q.pause(a.node, SplitConstants.WAN_WAKE_GRACE_MS)
                    q.notes += "${a.node.take(8)} is asleep; ${SplitConstants.WAN_WAKE_GRACE_MS / 1000} s wake grace"
                    return
                }
                if (remote) ensurePlanned(a.node)
                q.sent(a.node, c.index, a.attempt)
                val now = clock()
                journal.attempt(AttemptRow(job.jobId, c.chunkId, a.attempt, a.node, now, now + a.deadlineMs, if (a.duplicate) "duplicate" else "sent"))
                journal.chunk(chunkRow(c, "running", a.attempt))
                val body = MeshJson.encode(TaskBody(planId, plan.task(c, a.taskId), emptyMap(), a.deadlineMs, job.modelSha256 ?: modelHash(), selfId, now, UUID.randomUUID().toString(),
                    chunkIndex = c.index, chunkCount = q.chunkCount, label = runCatching { Splitters.label(job.input, c) }.getOrNull(), stage = job.jobId.substringAfterLast('/', "").ifEmpty { null }))
                if (remote) q.addBytes(body.length.toLong())
                transmit(a.node, MsgType.TASK, body).onFailure {
                    SLog.w(TAG, "TASK ${a.taskId} to ${a.node.take(8)} not sent: ${it.message}")
                    journal.attempt(attemptRow(c, a.attempt, a.node, "error", cls = "unreachable"))
                    q.onFault(a.node, c.index, a.attempt, "unreachable", WorkQueue.UNREACHABLE)
                    if (remote) unreachable(a.node)
                }
            }
            suspend fun cancelSuperseded() {
                for (a in q.superseded()) { cancel(a, "chunk settled"); q.cancelAttempt(a) }
            }
            suspend fun accepted(c: Chunk, out: JsonElement, node: String, r: WorkQueue.Outcome.Accepted) {
                val hash = Canon.hash(out)
                journal.output(OutputRow(job.jobId, c.chunkId, hash, out, "node:$node"))
                journal.chunk(chunkRow(c, "done", 0))
                if (useMemo && r.verified != false) memo.put(c.chunkId, hash, out)
            }
            suspend fun escalateChunk(vf: WorkQueue.VerificationFailure) {
                val c = q.chunkAt(vf.index) ?: return
                val lineage = if (c.private) Lineage.PRIVATE else if (c.blobs.isNotEmpty()) Lineage.RELEASED else Lineage.PUBLIC
                val trigger = EscalationTrigger.VerificationFailed(vf.reason, vf.attempts)
                if (lineage == Lineage.PRIVATE) {
                    journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, "", 0, 0, 0, 0.0, clock(), "refused:private lineage"))
                    q.failVerification(vf.index, "${vf.reason} and the data is private, so no cloud re-run")
                    return
                }
                val req = EscalationRequest(job.jobId, planId, selfId, plan.task(c), emptyMap(), lineage, trigger, vf.attempts, c.chunkId)
                when (val g = try { escalator.tryEscalate(req) } catch (e: Exception) { GateOutcome.Failed(e.message ?: e.javaClass.simpleName) }) {
                    is GateOutcome.Ran -> {
                        journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, g.slug, 0, 0, 0, 0.0, clock(), "ran"))
                        journal.output(OutputRow(job.jobId, c.chunkId, g.outputHash, g.output, g.source))
                        journal.chunk(chunkRow(c, "done", vf.attempts))
                        q.acceptExternal(vf.index, g.output, g.source)
                        if (useMemo) memo.put("${g.source}:${c.chunkId}", g.outputHash, g.output)
                        q.notes += "chunk ${vf.index}: ${g.slug} answered after ${vf.reason}"
                    }
                    is GateOutcome.Refused -> {
                        journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, "", 0, 0, 0, 0.0, clock(), "refused:${g.reason}"))
                        q.failVerification(vf.index, "${vf.reason}; no cloud re-run (${g.reason})")
                    }
                    is GateOutcome.Failed -> {
                        journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, "", 0, 0, 0, 0.0, clock(), "failed:${g.reason}"))
                        q.failVerification(vf.index, "${vf.reason}; the cloud re-run failed (${g.reason})")
                    }
                }
            }
            suspend fun afterEvent() {
                journalNewChunks()
                for (p in q.takeAssembled()) q.chunkAt(p)?.let { pc -> q.output(p)?.let { po ->
                    journal.output(OutputRow(job.jobId, pc.chunkId, Canon.hash(po), po, "recut")); journal.chunk(chunkRow(pc, "done", 0))
                } }
                for (vf in q.takeVerificationFailures()) escalateChunk(vf)
                cancelSuperseded()
            }
            suspend fun onResult(ev: Event.Result) {
                val b = ev.body
                val (i, n) = WorkQueue.parseTaskId(b.taskId) ?: return
                val c = q.chunkAt(i) ?: return
                val out = b.output
                val schemaObj = job.params[SchemaValidator.PARAM] as? JsonObject
                val err = b.error
                when {
                    err != null -> {
                        val cls = b.errorClass ?: SplitConstants.classify(err)
                        journal.attempt(attemptRow(c, n, ev.node, if (cls == SplitConstants.ERR_YIELDED) "yield" else "error", cls = cls))
                        when (cls) {
                            SplitConstants.ERR_YIELDED -> { q.onFault(ev.node, i, n, SplitConstants.ERR_YIELDED, cls); q.pause(ev.node, SplitConstants.YIELD_BACKOFF_MS) }
                            in SplitConstants.COOLDOWN_CLASSES -> {
                                q.onFault(ev.node, i, n, "$cls: $err", cls)
                                cooldown(ev.node, cap ?: job.op.name)
                                q.removeNode(ev.node, "$cls; ${cap ?: job.op.name} cools down for ${SplitConstants.CAPABILITY_COOLDOWN_MS / 60_000} min")
                            }
                            SplitConstants.ERR_SCHEMA -> q.onSchemaFault(ev.node, i, n, err)
                            else -> q.onFault(ev.node, i, n, "error: $err", cls)
                        }
                    }
                    out == null || b.outputHash != Canon.hash(out) -> {
                        journal.attempt(attemptRow(c, n, ev.node, "mismatch", b.outputHash))
                        q.onHashMismatch(ev.node, i, n)
                    }
                    schemaObj != null && schema.validate(out, schemaObj) != null -> {
                        val why = schema.validate(out, schemaObj) ?: "schema"
                        journal.attempt(attemptRow(c, n, ev.node, "error", b.outputHash, SplitConstants.ERR_SCHEMA))
                        q.onSchemaFault(ev.node, i, n, why)
                    }
                    else -> {
                        if (ev.node != selfId) q.addBytes(Canon.canonical(out).length.toLong())
                        journal.attempt(attemptRow(c, n, ev.node, "ok", b.outputHash))
                        outages.remove(ev.node)
                        when (val r = q.onResult(ev.node, i, n, out, b.ms)) {
                            is WorkQueue.Outcome.Accepted -> accepted(c, out, ev.node, r)
                            else -> Unit
                        }
                    }
                }
            }

            /**
             * Phone gate finding: each dispatch waited for its own photo transfer, so this phone's slot and the other
             * workers idled behind the slowest transfer. Local and blob-free assignments go first; the remote ones'
             * blobs are sent in parallel, then each is dispatched as before (queue state stays on this coroutine).
             */
            suspend fun dispatchAll(batch: List<WorkQueue.Assignment>) {
                val (now, shipping) = batch.partition { it.node == selfId || it.chunk.blobs.isEmpty() }
                now.forEach { dispatch(it) }
                if (shipping.size > 1) {
                    val sent = kotlinx.coroutines.coroutineScope {
                        shipping.map { a ->
                            val missing = a.chunk.blobs.filter { "${a.node}|$it" !in blobsAt }
                            async { Triple(a, missing, if (missing.isEmpty()) Result.success(0L) else sendBlobs(a.node, missing, job.jobId)) }
                        }.awaitAll()
                    }
                    for ((a, missing, r) in sent) r.onSuccess { b -> if (missing.isNotEmpty()) q.addBytes(b); missing.forEach { blobsAt += "${a.node}|$it" } }
                }
                shipping.forEach { dispatch(it) }        // a failed parallel send is retried (and handled) in dispatch
            }

            while (true) {
                dispatchAll(q.fill())
                afterEvent()
                if (q.finished) break
                send(q.progress.value.copy(resumable = journaled, elapsedMs = clock() - t0))
                val wake = q.nextWake()
                if (wake == null && q.running().isEmpty()) {
                    if (q.fill().also { re -> dispatchAll(re) }.isNotEmpty()) continue
                    q.failStranded()
                    continue
                }
                val ev = if (wake == null) events.receive() else withTimeoutOrNull(maxOf(0L, wake - clock())) { events.receive() }
                when (ev) {
                    null -> for (a in q.overdue()) {
                        cancel(a, "deadline")
                        val c = q.chunkAt(a.chunk)
                        if (c != null) journal.attempt(attemptRow(c, a.no, a.node, "timeout", cls = "timeout"))
                        val drop = q.onMiss(a.node, a.chunk, a.no)
                        if (a.node != selfId) {
                            if (drop) q.removeNode(a.node, "missed ${SplitConstants.MISSES_TO_DROP} deadlines")
                            else if (isWan(a.node)) { q.pause(a.node, SplitConstants.WAN_WAKE_GRACE_MS); q.notes += "${a.node.take(8)} may be asleep; ${SplitConstants.WAN_WAKE_GRACE_MS / 1000} s wake grace" }
                        }
                    }
                    is Event.Yield -> WorkQueue.parseTaskId(ev.taskId)?.let { (i, n) ->
                        q.chunkAt(i)?.let { journal.attempt(attemptRow(it, n, ev.node, "yield", cls = SplitConstants.ERR_YIELDED)) }
                        q.onFault(ev.node, i, n, SplitConstants.ERR_YIELDED, SplitConstants.ERR_YIELDED)
                        q.pause(ev.node, SplitConstants.YIELD_BACKOFF_MS)        // P7: it rejoins when idle, not in a tight loop
                    }
                    is Event.ModelReady -> { q.addNode(ev.node); q.notes += "${ev.node.take(8)} is ready for ${cap ?: job.op.name}" }
                    is Event.ModelFail -> {
                        cooldown(ev.node, cap ?: job.op.name)
                        q.removeNode(ev.node, "model failed to load (${ev.errorClass}); cooling down")
                    }
                    is Event.Cancel -> {
                        q.running().forEach { cancel(it, "cancelled") }
                        journal.job(jobRow(JobState.CANCELLED))
                        send(q.snapshot().copy(error = "cancelled", elapsedMs = clock() - t0)); return@channelFlow
                    }
                    is Event.Result -> onResult(ev)
                }
            }
            q.running().forEach { cancel(it, "job done") }
            // 13.1: the merge runs only after the state change is journaled.
            journal.job(jobRow(JobState.MERGING))
            val outs = q.outputsInOrder()
            val present = q.chunksWithOutput()
            var result: JsonElement? = try {
                if (outs.isEmpty()) null else Mergers.merge(plan.kind, outs, present, job.params, selector)
            } catch (e: Exception) {
                journal.job(jobRow(JobState.FAILED))
                send(q.snapshot().copy(error = "merge failed: ${e.message}", elapsedMs = clock() - t0)); return@channelFlow
            }
            // 15.1 low confidence: a VOTE below the threshold goes to the gate for the whole step.
            val conf = ((result as? JsonObject)?.get("confidence") as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (plan.kind == MergeKind.VOTE && conf != null && conf < SplitConstants.VOTE_MIN_CONFIDENCE) {
                val trigger = EscalationTrigger.LowConfidence(conf)
                if (privacy != Lineage.PUBLIC) {
                    journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, "", 0, 0, 0, 0.0, clock(), "refused:private lineage"))
                    q.notes += "vote confidence $conf is low and the data is private, so no cloud re-run"
                } else {
                    val req = EscalationRequest(job.jobId, planId, selfId, TaskSpec("step", job.op, job.params, modelId = job.modelId), emptyMap(), Lineage.PUBLIC, trigger, plan.chunks.size)
                    when (val g = try { escalator.tryEscalate(req) } catch (e: Exception) { GateOutcome.Failed(e.message ?: e.javaClass.simpleName) }) {
                        is GateOutcome.Ran -> {
                            journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, g.slug, 0, 0, 0, 0.0, clock(), "ran"))
                            result = g.output; stepCloud += g.slug
                            q.notes += "vote confidence $conf was low; ${g.slug} answered the step"
                        }
                        is GateOutcome.Refused -> { journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, "", 0, 0, 0, 0.0, clock(), "refused:${g.reason}")); q.notes += "vote confidence $conf is low; no cloud re-run (${g.reason})" }
                        is GateOutcome.Failed -> { journal.escalation(EscalationRow(job.jobId, selfId, trigger.name, "", 0, 0, 0, 0.0, clock(), "failed:${g.reason}")); q.notes += "vote confidence $conf is low; the cloud re-run failed (${g.reason})" }
                    }
                }
            }
            val snap = q.snapshot()
            journal.job(jobRow(if (q.partial) JobState.PARTIAL else JobState.DONE))
            send(snap.copy(done = true, result = result, resultHash = result?.let { Canon.hash(it) }, elapsedMs = clock() - t0,
                partial = q.partial, partialReason = q.partialReason, cloud = (snap.cloud + stepCloud).distinct(), resumable = false))
        } finally {
            synchronized(lock) { runs.remove(planId) }
            events.close()
        }
    }

    /** [run] to the end; the final [SplitProgress]. */
    suspend fun runToEnd(job: SplitJob, useMemo: Boolean = true, ready: Set<String>? = null): SplitProgress = run(job, useMemo, ready).last()

    private fun failed(job: SplitJob, why: String, t0: Long) = SplitProgress(job.jobId, job.op.name, job.merge?.name ?: "", "", emptyList(),
        emptyList(), 0, clock() - t0, 0.0, 0.0, 0.0, 0, 0, 0, done = false, error = why)

    companion object {
        private const val TAG = "SplitCommander"

        /**
         * A split commander with no mesh: every chunk runs on [runner] on this phone (the 1-phone baseline and the
         * debug hook before the swarm node is wired). [blobFile] resolves released items to files.
         */
        fun standalone(selfId: String, runner: TaskRunner, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis,
                       blobFile: (String) -> File? = { null }, selector: TreeSelector = Mergers.extractiveSelector,
                       journal: JobJournal = JobJournal.None, memo: ChunkMemo = ChunkMemo.None, escalator: Escalator = Escalator.Off,
                       schema: SchemaValidator = SchemaValidator.NONE): SplitCommander {
            lateinit var sc: SplitCommander
            sc = SplitCommander(selfId, ComparatorImpl(), profiles = { emptyList() }, clock = clock, selector = selector, journal = journal, memo = memo,
                escalator = escalator, schema = schema, transmit = { _, type, body ->
                if (type == MsgType.TASK) {
                    val b = MeshJson.decode<TaskBody>(body)
                    scope.launch {
                        val t = clock()
                        val r = runner.run(b.task, emptyList(), b.task.blobs.mapNotNull(blobFile), selfId)
                        val res = r.fold({ TaskResultBody(b.planId, b.task.id, selfId, it, Canon.hash(it), ms = clock() - t) },
                            { TaskResultBody(b.planId, b.task.id, selfId, error = it.message ?: it.javaClass.simpleName,
                                errorClass = (it as? com.hivecore.engine.TaskFailure)?.errorClass) })
                        sc.onMessage(MsgType.TASK_RESULT, selfId, MeshJson.encode(res))
                    }
                }
                Result.success(Unit)
            })
            return sc
        }
    }
}
