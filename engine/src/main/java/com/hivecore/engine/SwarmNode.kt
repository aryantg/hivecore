package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.mesh.MeshRouterPort
import com.hivecore.core.ApprovalGate
import com.hivecore.core.AskProgress
import com.hivecore.core.Catalog
import com.hivecore.core.Comparator
import com.hivecore.core.DifferencingGuard
import com.hivecore.core.DocAnswerer
import com.hivecore.core.DocIndex
import com.hivecore.core.IntentCompiler
import com.hivecore.core.IntentPlanner
import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.JobRunner
import com.hivecore.core.LedgerStore
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.OpExecutor
import com.hivecore.core.PlanCompiler
import com.hivecore.core.Planner
import com.hivecore.core.QueryExecutor
import com.hivecore.core.Recipe
import com.hivecore.core.Reducer
import com.hivecore.core.RouteDecision
import com.hivecore.core.SLog
import com.hivecore.core.Scheduler
import com.hivecore.core.SchemaSource
import com.hivecore.core.TaskRunner
import com.hivecore.core.WorkerActivityLog
import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.core.WorkerPolicy
import com.hivecore.core.WorkerTaskContext
import com.hivecore.core.WorkloadSpec
import com.hivecore.mesh.BlobBody
import com.hivecore.mesh.BlobDeletedBody
import com.hivecore.mesh.JournalBody
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.ReclaimBody
import com.hivecore.mesh.ReclaimResBody
import com.hivecore.mesh.SignalBody
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import com.hivecore.mesh.TaskSignalBody
import com.hivecore.core.Link
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.JobResume
import com.hivecore.engine.journal.JournalPull
import com.hivecore.engine.journal.ReclaimResponder
import com.hivecore.engine.journal.ResumeCoordinator
import com.hivecore.engine.split.SplitConstants
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.JobViews
import com.hivecore.engine.split.Mergers
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.TreeSelector
import com.hivecore.engine.split.WorkQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * The swarm wiring on one phone. Subscribes to the router's verified stream for the swarm message types (§8.1) and
 * dispatches them: PROFILE → [ProfileBeacon]; PLAN → [OwnerPlanHandler]; PLAN_CONSENT_REQ/RES, TASK_RESULT,
 * TASK_YIELD → [Commander]; TASK → the owner handler (this phone's AGG/DOC tasks) or the [TaskRunner] (worker
 * tasks); TASK_CANCEL → stops a running worker task; BLOB/BLOB_DELETED → [BlobStore].
 *
 * Messages to this phone itself (the commander is often also an owner or a worker) are dispatched in-process,
 * synchronously and in order, through the same handlers ([sendTo]).
 *
 * Worker tasks run only while [acceptWork] says so (the user opted in to worker mode); otherwise, and when the
 * runner yields, the commander gets TASK_YIELD and reassigns (R8).
 *
 * State and recovery (spec 13): [build] wires the journal and chunk memo into the split commander; the
 * [ReclaimResponder] and [ResumeCoordinator] subscribe to the router on their own (this node only forwards RECLAIM,
 * RECLAIM_RES and JOURNAL to its optional hooks and never consumes them); [resume] is `JobRunner.resume` (13.3).
 * Placement (spec 8.3): MODEL_REQ goes to [onModelReq]; MODEL_READY / MODEL_FAIL reach the live split job and
 * [onModelReply] (the placement coordinator) alike.
 */
class SwarmNode(
    private val router: MeshRouterPort,
    private val scope: CoroutineScope,
    val runner: TaskRunner,
    val beacon: ProfileBeacon? = null,
    val blobs: BlobStore? = null,
    private val acceptWork: () -> Boolean = { true },
    private val onBlobDeleted: (String, BlobDeletedBody) -> Unit = { _, _ -> },
) : JobRunner {
    val selfId: String get() = router.nodeId

    lateinit var commander: Commander
    private val commanderOrNull: Commander? get() = if (::commander.isInitialized) commander else null
    var owner: OwnerPlanHandler? = null
    /** Split jobs commanded by this phone (§4B). */
    var split: SplitCommander? = null
    /** The orchestrator's journal (spec 13.1); [JobJournal.None] on a phone without the state phase. */
    var journal: JobJournal = JobJournal.None
    /** Answers peers' RECLAIMs from this phone's memo (spec 13.3); started with [start]. */
    var reclaim: ReclaimResponder? = null
    /** This phone's resume driver (spec 13.3); started with [start]. */
    var resumeCoordinator: ResumeCoordinator? = null
    /** `JobRunner.resume`: the deputy pull, the journal snapshot and the RECLAIM round. */
    var jobResume: JobResume? = null
    /**
     * Spec 8.3: placement before a split job's queue opens. (job, planId) -> the phones that reported MODEL_READY for
     * its capability once the queue may open (null = no placement for this job). Later READYs reach the live run
     * through MODEL_READY on the same planId. Null hook = the profile view decides, as before placement existed.
     */
    var placeFor: (suspend (SplitJob, String) -> Set<String>?)? = null
    /** The last emission of every split job this phone commanded (done, partial, error): the ledger's receipt exchange. */
    var onSplitDone: (SplitProgress) -> Unit = { }
    /** A chunk this phone ran for another commander: (TASK, commander, output, outputHash); the executor's `task` receipt. */
    var onTaskRan: (TaskBody, String, JsonElement, String) -> Unit = { _, _, _, _ -> }
    private val runSeq = java.util.concurrent.atomic.AtomicLong(0)
    /** Recipe lookup for [runWorkload] (id, version; version "" = the installed one). */
    var recipes: (String, String) -> Recipe? = { _, _ -> null }
    /** Recipe definitions with axis/merge/input (workload/Recipes); null = unknown id. */
    var recipeDefs: (String) -> com.hivecore.engine.workload.RecipeDef? = { null }
    /** Items for recipes whose input is "none" (the EVAL sweep: the question sets as EvalCase JSON). */
    var evalCases: () -> List<JsonElement> = { emptyList() }
    /** The installed model ids as items for a recipe over `model` items (BENCH); phone gate finding: `bench_all` had no item source. */
    var modelItems: () -> List<JsonElement> = { emptyList() }
    /**
     * Items for recipes whose input is "table" (Kavira data amendment): the rows of this phone's table grouped as the
     * recipe's [com.hivecore.engine.workload.RecipeSource] says, each `{"item": key, "text": lines}`. Throws with the
     * sentence the Jobs screen shows when the table is not on this phone or a column is confidential.
     */
    var tableItems: (com.hivecore.engine.workload.RecipeSource) -> List<JsonElement> = { throw IllegalStateException("this phone has no data store for table recipes") }
    /** Private mode (§7): tables and documents (ids and names) the owner marked private; their chunks never leave this phone. */
    var privateTables: () -> Set<String> = { emptySet() }
    var privateDocs: () -> Set<String> = { emptySet() }
    /** Called after this phone ran a task for another commander: (commander, ms). Feeds "today's contributed work". */
    var onWorkedForOthers: (String, Long) -> Unit = { _, _ -> }
    /** nodeId → display name for the job views. */
    var nameOf: (String) -> String = { if (it == router.nodeId) "here" else it.take(8) }

    // ---- v2 plan hooks. Each phase's component is built elsewhere and plugged in here (SwarmNode.build / Graph). ----
    /** Every verified PLAN, before the owner handler sees it: the plan store (C9) records it, split plans included. */
    var onPlanSeen: ((PlanBody, String) -> Unit)? = null
    /**
     * C9 task authorisation: returns null to accept the TASK, or the reason to drop it (no held PLAN from that
     * orchestrator with this task's keyOf). Null hook = accept everything (pre-v2 behaviour).
     */
    var taskAuthoriser: ((TaskBody, String) -> String?)? = null
    /** Dropped unauthorised TASKs, (from, reason); the UI warns after 3 in a minute from one peer (§4.5). */
    var onUnauthorisedTask: (String, String) -> Unit = { _, _ -> }
    /** MODEL_REQ from an orchestrator (§5.4): the runtime loads the model and replies MODEL_READY / MODEL_FAIL via [reply]. */
    var onModelReq: ((ModelReqBody, String, reply: suspend (MsgType, String) -> Result<Unit>) -> Unit)? = null
    /** Every verified MODEL_READY / MODEL_FAIL (type, from, body): the placement coordinator's `onMessage`. */
    var onModelReply: ((MsgType, String, String) -> Unit)? = null
    /** RECLAIM from an orchestrator resuming (§11.3): the memo answers RECLAIM_RES via [reply]. */
    var onReclaim: ((ReclaimBody, String, reply: suspend (MsgType, String) -> Result<Unit>) -> Unit)? = null
    /** RECLAIM_RES for this phone's resume. */
    var onReclaimRes: ((ReclaimResBody, String) -> Unit)? = null
    /** JOURNAL rows streamed to this phone as deputy (§11.4). */
    var onJournal: ((JournalBody, String) -> Unit)? = null
    /** ROSTER / REVOKE gossip (§4.4): the roster store keeps the highest valid version. */
    var onRoster: ((MsgType, String, String) -> Unit)? = null
    /** SIGNAL (WebRTC offer / answer / candidate) for the blob transport (§3.3). */
    var onSignal: ((SignalBody, String) -> Unit)? = null

    // ---- the worker timeline (display only) ----
    /** Which model a task's type calls for on this phone, for the timeline. */
    data class ModelChoice(val capability: String, val modelId: String?, val reason: String, val warm: Boolean?)
    /** Picks the model for a task the way its executor will (the app plugs in the catalog and the pool); null = the op needs no model. */
    var modelChoice: (com.hivecore.core.TaskSpec) -> ModelChoice? = ::defaultModelChoice
    var clock: () -> Long = System::currentTimeMillis
    /** This phone's worker timeline, plus the mirror of its workers' timelines for the jobs it commands; see [attachActivity]. */
    var activity: WorkerActivityLog? = null
        private set
    private val planChunks = LinkedHashMap<String, Int>()   // split planId -> chunk count, from the PLAN
    private val placing = HashSet<String>()                  // planIds of this phone's jobs in placement

    /** A run of this phone's that its workers' timelines are mirrored for: in placement or live. */
    private fun mirrored(planId: String): Boolean = synchronized(lock) { planId in placing } || split?.isLive(planId) == true

    /**
     * Records this phone's worker events in [log] and sends each one for another commander to that commander as
     * TASK_PROGRESS; events for this phone's own jobs go straight to its mirror.
     */
    fun attachActivity(log: WorkerActivityLog) {
        activity = log
        log.onEmit { e ->
            if (e.node != selfId || e.kind == WorkerEventKind.RECEIPT_COUNTERSIGNED) return@onEmit
            if (e.commander == selfId) { if (mirrored(e.planId)) log.onPeer(e); return@onEmit }
            scope.launch { router.send(e.commander, MsgType.TASK_PROGRESS, MeshJson.encode(e)).onFailure { SLog.d(TAG, "TASK_PROGRESS not sent: ${it.message}") } }
        }
    }

    private fun event(body: TaskBody, kind: WorkerEventKind, choice: ModelChoice? = null): WorkerEvent {
        val parsed = WorkQueue.parseTaskId(body.task.id)
        return WorkerEvent(body.planId, kind, selfId, body.commander, clock(), taskId = body.task.id, op = body.task.op.name,
            capability = choice?.capability, modelId = choice?.modelId, reason = choice?.reason, warm = choice?.warm,
            chunkIndex = body.chunkIndex ?: parsed?.first, chunkCount = body.chunkCount ?: synchronized(lock) { planChunks[body.planId] },
            label = body.label, stage = body.stage)
    }

    private fun emit(e: WorkerEvent) { activity?.emit(e) }

    private fun chooseModel(task: com.hivecore.core.TaskSpec): ModelChoice? =
        try { modelChoice(task) } catch (e: Exception) { SLog.w(TAG, "model choice: ${e.message}"); defaultModelChoice(task) }

    private val jobList = MutableStateFlow<List<JobProgress>>(emptyList())
    override val jobs: StateFlow<List<JobProgress>> = jobList

    /**
     * core.JobRunner (§4B.4): a recipe over the request's files (each stored as a blob) or one op over them; an op job
     * may instead carry its [SplitInput] as `params.input`. `private` keeps every chunk on this phone.
     */
    override fun run(request: JobRequest): Flow<JobProgress> {
        val jobId = "job-" + java.util.UUID.randomUUID().toString().take(8)
        val store = blobs
        val raw: Flow<SplitProgress> = try {
            val files = request.inputs.map { File(it) }
            val ids = files.map { f -> store?.put(f) ?: throw IllegalStateException("this phone has no blob store for ${f.name}") }
            when {
                request.recipeId != null -> {
                    val def = recipeDefs(request.recipeId!!)
                    val r = def?.toRecipe() ?: recipes(request.recipeId!!, "") ?: throw IllegalArgumentException("recipe ${request.recipeId} is not installed")
                    when {
                        ids.isNotEmpty() -> runWorkload(WorkloadSpec(jobId, r.id, r.version, selfId, ids, release = if (request.private) "none" else "items"))
                        def?.input == "prompt" -> {
                            val prompt = (request.params["prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                                ?: throw IllegalArgumentException("recipe ${r.id} needs params.prompt")
                            runStages(jobId, RecipeJobs.promptJobs(jobId, r, prompt, selfId, request.private))
                        }
                        def?.input == "none" -> {
                            val cases = if ("model" in r.itemKinds) modelItems() else evalCases()
                            if (cases.isEmpty()) throw IllegalArgumentException("recipe ${r.id}: no items on this phone")
                            runStages(jobId, RecipeJobs.itemJobs(jobId, r, cases, selfId, request.private))
                        }
                        def?.input == "table" -> {
                            val src = def.source ?: throw IllegalArgumentException("recipe ${r.id} names no source table")
                            val items = tableItems(src)
                            if (items.isEmpty()) throw IllegalArgumentException("recipe ${r.id}: ${src.table} has no rows for it on this phone")
                            runStages(jobId, RecipeJobs.itemJobs(jobId, r, items, selfId, request.private, key = "texts"))
                        }
                        else -> throw IllegalArgumentException("recipe ${r.id} needs input files")
                    }
                }
                request.op != null -> {
                    val explicit = request.params["input"]?.let { Canon.json.decodeFromJsonElement(SplitInput.serializer(), it) }
                    val input = when {
                        explicit != null -> markPrivate(explicit, request.private)
                        ids.isNotEmpty() -> SplitInput.Blobs(ids, files.map { it.length() }, ids.map { request.private })
                        else -> throw IllegalArgumentException("the job has no inputs")
                    }
                    runJob(SplitJob(jobId, request.op!!, JsonObject(request.params - "input"), request.params["modelId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                        input, owner = selfId))
                }
                else -> throw IllegalArgumentException("a job needs a recipe or an op")
            }
        } catch (e: Exception) {
            flow { emit(SplitProgress(jobId, request.op?.name ?: "", "", "", emptyList(), emptyList(), 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, done = false, error = e.message ?: e.javaClass.simpleName)) }
        }
        return raw.onEach { if (it.done || it.error != null) splitDone(it) }.map { JobViews.of(it, request.title, nameOf) }.onEach { p ->
            synchronized(jobList) { jobList.value = listOf(p) + jobList.value.filter { it.jobId != p.jobId } }
        }
    }

    private fun splitDone(p: SplitProgress) { try { onSplitDone(p) } catch (e: Exception) { SLog.w(TAG, "onSplitDone: ${e.message}") } }

    override fun cancel(jobId: String) { split?.cancel(jobId) }

    /**
     * Spec 13.3: resumes a journaled split job after a restart. [JobResume] pulls the deputy's newer rows, loads the
     * snapshot and runs the RECLAIM round; the job is then re-run from its journaled spec with every output already
     * held prefilled, so only the missing chunks run, under the next `jobId#run`.
     */
    override fun resume(jobId: String): Flow<JobProgress> {
        val raw: Flow<SplitProgress> = flow {
            val r = jobResume ?: throw IllegalStateException("this phone keeps no journal to resume from")
            val sc = split ?: throw IllegalStateException("this node has no split commander")
            val snap = journal.load(jobId) ?: throw IllegalArgumentException("job $jobId is not in the journal")
            val spec = snap.job.spec ?: throw IllegalArgumentException("job $jobId has no stored spec")
            val job = Canon.json.decodeFromJsonElement(SplitJob.serializer(), spec)
            val plan = r.resume(jobId) ?: throw IllegalArgumentException("job $jobId is not in the journal")
            val run = snap.job.planId.substringAfterLast('#').toLongOrNull()?.plus(1)
            SLog.d(TAG, "resume $jobId: ${plan.prefilled.size} held, ${plan.missing.size} to run, run #${run ?: "?"}")
            emitAll(sc.run(enforcePrivate(job), useMemo = true, prefilled = plan.prefilled, planRun = run))
        }.catch { e ->
            emit(SplitProgress(jobId, "", "", "", emptyList(), emptyList(), 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, done = false, error = e.message ?: e.javaClass.simpleName))
        }
        return raw.onEach { if (it.done || it.error != null) splitDone(it) }.map { JobViews.of(it, "Resumed ${it.op.lowercase()}", nameOf) }.onEach { p ->
            synchronized(jobList) { jobList.value = listOf(p) + jobList.value.filter { it.jobId != p.jobId } }
        }
    }

    private fun markPrivate(i: SplitInput, on: Boolean): SplitInput = if (!on) i else when (i) {
        is SplitInput.Items -> i.copy(private = true)
        is SplitInput.Blobs -> i.copy(private = i.blobIds.map { true })
        is SplitInput.Text -> i.copy(private = true)
        is SplitInput.Audio -> i.copy(private = true)
        is SplitInput.Video -> i.copy(private = true)
        is SplitInput.Image -> i.copy(private = true)
        is SplitInput.Table -> i.copy(private = true)
        is SplitInput.Seeds -> i.copy(private = true)
        is SplitInput.FanOut -> i.copy(private = true)
    }

    private val lock = Any()
    private val workerJobs = HashMap<String, Pair<String, Job>>()   // "planId|taskId" -> (commander, job)
    private val handledTasks = LinkedHashMap<String, Boolean>()
    private var collector: Job? = null

    /** Subscribes to the router (call before the router starts) and starts the PROFILE beacon, the RECLAIM responder and the resume coordinator. */
    fun start(periodicProfiles: Boolean = true): Job {
        collector?.let { return it }
        val j = scope.launch { router.verified.collect { if (it.type in TYPES) dispatch(it.type, it.from, it.body) } }
        collector = j
        reclaim?.start(scope)
        resumeCoordinator?.start(scope)
        beacon?.start(scope, periodicProfiles)
        return j
    }

    fun stop() { collector?.cancel(); collector = null; beacon?.stop(); reclaim?.stop(); resumeCoordinator?.stop() }

    /** A composite question asked on this phone: this phone commands the plan ([planned]: the tiered planner's plan, else T1 plans it). */
    fun askComposite(question: String, route: RouteDecision, catalogs: List<Catalog>, planned: com.hivecore.core.PlannedQuestion? = null,
                     cloudSteps: Set<String> = emptySet()): Flow<AskProgress> =
        commander.askComposite(question, route, catalogs, planned, cloudSteps)

    /**
     * Splits one op over [input] across the swarm (§4B) with this phone as commander: chunk grid updates, then a last
     * emission with `done` and the merged result (or `error`). Inputs marked private never leave [owner] (default: here).
     */
    fun runJob(op: Op, params: JsonObject, input: SplitInput, modelId: String? = null, owner: String? = null,
               jobId: String = java.util.UUID.randomUUID().toString()): Flow<SplitProgress> =
        runJob(SplitJob(jobId, op, params, modelId, input, owner = owner))

    /**
     * [ready] = the phones placement reported MODEL_READY for this job's capability; null runs [placeFor] first (spec
     * 8.3: the queue opens once one phone is ready) and, without a placement hook, the profile view decides.
     */
    fun runJob(job: SplitJob, useMemo: Boolean = true, ready: Set<String>? = null): Flow<SplitProgress> = flow {
        val sc = split ?: throw IllegalStateException("this node has no split commander")
        val j = enforcePrivate(job)
        val place = placeFor
        if (ready != null || place == null) {
            try { emitAll(sc.run(j, useMemo, ready)) } finally { activity?.forgetJob(j.jobId) }
            return@flow
        }
        val run = runSeq.incrementAndGet()
        val planId = "${j.jobId}#$run"
        // the workers' model-boot events (TASK_PROGRESS) arrive while placement runs, before the split run is live
        synchronized(lock) { placing += planId }
        try {
            val placed = place(j, planId)
            emitAll(sc.run(j, useMemo, placed, planRun = run))
        } finally { synchronized(lock) { placing -= planId }; activity?.forgetJob(j.jobId) }
    }

    /** A job over a table or document the owner marked private runs on this phone only (§7). */
    fun enforcePrivate(job: SplitJob): SplitJob {
        val i = job.input
        val priv = when (i) {
            is SplitInput.Table -> i.table in safe(privateTables)
            is SplitInput.Text -> i.doc in safe(privateDocs)
            else -> false
        }
        return if (priv) job.copy(input = markPrivate(i, true)) else job
    }

    private fun safe(f: () -> Set<String>): Set<String> = try { f() } catch (e: Exception) { SLog.w(TAG, "private marks: ${e.message}"); emptySet() }

    /**
     * A workload (§4A.1): each recipe stage is one split job over the items (private unless `release == "items"`),
     * run in stage order. Emits every stage's progress; the last emission carries `{stages: {stageId: result}}`.
     */
    fun runWorkload(spec: WorkloadSpec): Flow<SplitProgress> {
        val recipe = recipeDefs(spec.recipeId)?.toRecipe()?.takeIf { spec.recipeVersion.isEmpty() || it.version == spec.recipeVersion }
            ?: recipes(spec.recipeId, spec.recipeVersion)
        val jobs = recipe?.let { SplitJob.forWorkload(spec, it) }
        if (recipe == null || jobs.isNullOrEmpty()) return flow {
            emit(SplitProgress(spec.jobId, "", "", "", emptyList(), emptyList(), 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, done = false,
                error = "recipe ${spec.recipeId}@${spec.recipeVersion} is not installed"))
        }
        return runStages(spec.jobId, RecipeJobs.Staged(jobs, recipe.stages.filter { it.inputs.isNotEmpty() }.map { it.id }))
    }

    /** Runs each stage as one split job, in order; the last emission carries `{stages: {stageId: result}}` (+ `skipped`). */
    private fun runStages(jobId: String, staged: RecipeJobs.Staged): Flow<SplitProgress> = flow {
        val results = LinkedHashMap<String, JsonElement>()
        var last: SplitProgress? = null
        for (j in staged.jobs) {
            var end: SplitProgress? = null
            runJob(j).collect { emit(it); end = it }
            val e = end ?: return@flow
            if (!e.done) return@flow
            results[j.jobId.substringAfterLast('/')] = e.result ?: JsonNull
            last = e
        }
        if (staged.skipped.isNotEmpty()) SLog.w(TAG, "job $jobId: stages ${staged.skipped} need an earlier stage's output and were not run")
        val merged = buildJsonObject {
            put("stages", JsonObject(results))
            if (staged.skipped.isNotEmpty()) put("skipped", kotlinx.serialization.json.JsonArray(staged.skipped.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        val end = last ?: return@flow
        emit(end.copy(jobId = jobId, result = merged, resultHash = Canon.hash(merged)))
    }

    /** Signed unicast to [to]; to this phone itself the message is dispatched in-process. */
    suspend fun sendTo(to: String, type: MsgType, body: String): Result<Unit> {
        if (to == selfId) { dispatch(type, selfId, body); return Result.success(Unit) }
        return router.send(to, type, body)
    }

    /** Routes one verified swarm message (also the test entry point). Never suspends. */
    fun dispatch(type: MsgType, from: String, body: String) {
        try {
            when (type) {
                MsgType.PROFILE -> beacon?.onProfile(from, body)
                MsgType.PLAN -> {
                    val plan = MeshJson.decode<PlanBody>(body)
                    onPlanSeen?.invoke(plan, from)
                    if (plan.chunkIds.isNotEmpty() && from != selfId) {
                        val count = plan.chunkIds.size / SplitConstants.MAX_ATTEMPTS
                        synchronized(lock) { planChunks[plan.planId] = count; while (planChunks.size > 100) planChunks.remove(planChunks.keys.first()) }
                        emit(WorkerEvent(plan.planId, WorkerEventKind.PLAN_RECEIVED, selfId, from, clock(), op = plan.question.removePrefix("split ").uppercase(), chunkCount = count))
                    }
                    owner?.onPlan(plan, from) { t, b -> sendTo(from, t, b) }
                }
                MsgType.PLAN_CONSENT_REQ, MsgType.PLAN_CONSENT_RES, MsgType.TASK_RESULT, MsgType.TASK_YIELD -> {
                    val b = if (type == MsgType.TASK_RESULT) inlineOutput(body, from) ?: return else body
                    if (commanderOrNull?.onMessage(type, from, b) != true && split?.onMessage(type, from, b) != true)
                        SLog.d(TAG, "$type from ${from.take(8)} matches no live plan or split job")
                }
                MsgType.TASK -> onTask(MeshJson.decode(body), from)
                MsgType.TASK_CANCEL -> onCancel(MeshJson.decode(body), from)
                MsgType.BLOB -> onBlob(MeshJson.decode(body), from)
                MsgType.BLOB_DELETED -> onBlobDeleted(from, MeshJson.decode(body))
                // ---- v2 ----
                MsgType.MODEL_REQ -> onModelReq?.invoke(MeshJson.decode(body), from) { t, b -> sendTo(from, t, b) }
                    ?: SLog.d(TAG, "MODEL_REQ from ${from.take(8)}: no runtime handler on this phone")
                MsgType.MODEL_READY, MsgType.MODEL_FAIL -> {
                    // The live split job adds the phone (8.3 "later MODEL_READYs add phones"); placement records it.
                    split?.onMessage(type, from, body)
                    onModelReply?.invoke(type, from, body)
                }
                MsgType.RECLAIM -> onReclaim?.invoke(MeshJson.decode(body), from) { t, b -> sendTo(from, t, b) }
                MsgType.RECLAIM_RES -> onReclaimRes?.invoke(MeshJson.decode(body), from)
                MsgType.JOURNAL -> onJournal?.invoke(MeshJson.decode(body), from)
                MsgType.ROSTER, MsgType.REVOKE -> onRoster?.invoke(type, from, body)
                MsgType.SIGNAL -> onSignal?.invoke(MeshJson.decode(body), from)
                MsgType.TASK_PROGRESS -> {
                    val e = MeshJson.decode<WorkerEvent>(body)
                    // display only, and only from the phone it names, for a job this phone runs now
                    if (e.node == from && e.commander == selfId && mirrored(e.planId)) activity?.onPeer(e)
                }
                else -> Unit
            }
        } catch (e: Exception) {
            SLog.w(TAG, "$type from ${from.take(8)} ignored: ${e.message}")
        }
    }

    private fun onTask(body: TaskBody, from: String) {
        if (body.commander != from) { SLog.w(TAG, "TASK from ${from.take(8)} names another commander; ignored"); return }
        taskAuthoriser?.invoke(body, from)?.let { why ->
            SLog.w(TAG, "TASK ${body.task.id} from ${from.take(8)} dropped: $why")
            onUnauthorisedTask(from, why)
            return
        }
        val key = "${body.planId}|${body.task.id}"
        val o = owner
        if (o != null && o.owns(body)) {
            // Idempotent inside the owner handler (it re-sends the answer it already gave).
            scope.launch { o.onTask(body, from) { t, b -> sendTo(from, t, b) } }
            return
        }
        synchronized(lock) {
            // the same TASK delivered twice is ignored; a fresh attempt of the same task id (a schema retry on this
            // phone, a new nonce) runs again
            if (handledTasks.put("$key|${body.nonce}", true) != null) { SLog.d(TAG, "TASK $key again; ignored"); return }
            while (handledTasks.size > 1000) handledTasks.remove(handledTasks.keys.first())
        }
        if (OpClasses.of(body.task.op) == OpClass.OWNER) {
            scope.launch { reply(from, TaskResultBody(body.planId, body.task.id, selfId, error = "this phone does not own the data for ${body.task.id}", errorClass = SplitConstants.ERR_RUNTIME)) }
            return
        }
        if (from != selfId && !acceptWork()) {
            scope.launch { sendTo(from, MsgType.TASK_YIELD, MeshJson.encode(TaskSignalBody(body.planId, body.task.id, selfId, "worker mode is off"))) }
            emit(event(body, WorkerEventKind.YIELDED).copy(error = "worker mode is off"))
            return
        }
        val log = activity
        val choice = if (log != null) chooseModel(body.task) else null
        emit(event(body, WorkerEventKind.CHUNK_RECEIVED, choice))
        val job = scope.launch {
            val files = body.task.blobs.map { id -> blobs?.file(id) }
            if (files.any { it == null }) {
                SLog.w(TAG, "TASK ${body.task.id} from ${from.take(8)}: ${files.count { it == null }} of ${files.size} blob(s) missing")
                reply(from, TaskResultBody(body.planId, body.task.id, selfId, error = "released items are missing on this phone", errorClass = SplitConstants.ERR_BLOBS_MISSING))
                emit(event(body, WorkerEventKind.RESULT_FAILED, choice).copy(error = "released items are missing on this phone"))
                return@launch
            }
            val inputs = body.task.inputs.map { body.inputs[it] ?: JsonNull }
            SLog.d(TAG, "TASK ${body.task.id} ${body.task.op} from ${from.take(8)}: ${files.size} blob(s), running")
            if (choice != null) emit(event(body, WorkerEventKind.MODEL_SELECTED, choice))
            emit(event(body, WorkerEventKind.CHUNK_RUNNING, choice))
            val t0 = System.nanoTime()
            // the task context lets the model pool and the memo report a cold load or a memo hit against this chunk
            val r = if (log == null) runner.run(body.task, inputs, files.filterNotNull(), from)
                else kotlinx.coroutines.withContext(WorkerTaskContext(log, event(body, WorkerEventKind.CHUNK_RUNNING, choice), clock)) {
                    runner.run(body.task, inputs, files.filterNotNull(), from)
                }
            val ms = (System.nanoTime() - t0) / 1_000_000
            SLog.d(TAG, "TASK ${body.task.id} ran in $ms ms: ${r.exceptionOrNull()?.message ?: "ok"}")
            r.fold(
                onSuccess = { out ->
                    val hash = Canon.hash(out)
                    if (reply(from, TaskResultBody(body.planId, body.task.id, selfId, out, hash, ms = ms)))
                        emit(event(body, WorkerEventKind.RESULT_SENT, choice).copy(ms = ms))
                    else emit(event(body, WorkerEventKind.RESULT_FAILED, choice).copy(ms = ms, error = "the result did not reach the orchestrator"))
                    if (from != selfId) {
                        try { onWorkedForOthers(from, ms) } catch (e: Exception) { SLog.w(TAG, "contribution counter: ${e.message}") }
                        try { onTaskRan(body, from, out, hash) } catch (e: Exception) { SLog.w(TAG, "task receipt: ${e.message}") }
                    }
                },
                onFailure = { e ->
                    if (e.message == TaskRunnerImpl.YIELDED) {
                        sendTo(from, MsgType.TASK_YIELD, MeshJson.encode(TaskSignalBody(body.planId, body.task.id, selfId, TaskRunnerImpl.YIELDED)))
                        emit(event(body, WorkerEventKind.YIELDED, choice))
                    } else {
                        reply(from, TaskResultBody(body.planId, body.task.id, selfId, error = e.message ?: e.javaClass.simpleName, errorClass = TaskFailure.classOf(e)))
                        emit(event(body, WorkerEventKind.RESULT_FAILED, choice).copy(ms = ms, error = e.message ?: e.javaClass.simpleName))
                    }
                }
            )
        }
        synchronized(lock) { workerJobs[key] = from to job }
        job.invokeOnCompletion { synchronized(lock) { if (workerJobs[key]?.second === job) workerJobs.remove(key) } }
    }

    /** False when the result did not leave this phone. */
    private suspend fun reply(to: String, res: TaskResultBody): Boolean {
        var body = MeshJson.encode(res)
        val store = blobs
        val output = res.output
        // Files an executor produced (a generated picture) travel with the result when this phone holds them as blobs:
        // the output names each by its sha256, the orchestrator's deck step reads them from its own store.
        if (output != null && to != selfId && store != null) for (id in attachments(output)) {
            if (!store.has(id)) continue
            sendBlob(to, id, res.planId).onFailure { SLog.w(TAG, "TASK_RESULT ${res.taskId}: attachment ${id.take(8)} not sent: ${it.message}") }
        }
        if (body.length > MAX_INLINE_RESULT && output != null && to != selfId && store != null) {
            // Phone gate finding: a 30-photo OCR chunk is ~74 KB of JSON, over the 64 KB envelope; send it as a blob.
            val id = store.put(Canon.canonical(output).toByteArray(Charsets.UTF_8))
            val sent = sendBlob(to, id, res.planId)
            if (sent.isFailure) { SLog.w(TAG, "TASK_RESULT ${res.taskId}: output blob not sent: ${sent.exceptionOrNull()?.message}"); return false }
            SLog.d(TAG, "TASK_RESULT ${res.taskId}: ${body.length} B output sent as blob ${id.take(8)} (${sent.getOrNull()} chunks)")
            body = MeshJson.encode(res.copy(output = null, outputBlob = id))
        }
        return sendTo(to, MsgType.TASK_RESULT, body).onFailure { SLog.w(TAG, "TASK_RESULT ${res.taskId} not sent: ${it.message}") }.isSuccess
    }

    /**
     * A TASK_RESULT whose output came as a blob ([TaskResultBody.outputBlob]) with the output restored inline, so the
     * commanders see one shape; null (dropped) when the blob is missing or does not match the signed hash.
     */
    private fun inlineOutput(body: String, from: String): String? {
        val res = MeshJson.decode<TaskResultBody>(body)
        val id = res.outputBlob ?: return body
        val bytes = blobs?.file(id)?.readBytes()
        if (bytes == null) { SLog.w(TAG, "TASK_RESULT ${res.taskId} from ${from.take(8)}: output blob ${id.take(8)} missing"); return null }
        val out = runCatching { Canon.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        if (out == null || Canon.hash(out) != res.outputHash) { SLog.w(TAG, "TASK_RESULT ${res.taskId} from ${from.take(8)}: output blob does not match its hash"); return null }
        return MeshJson.encode(res.copy(output = out, outputBlob = null))
    }

    private fun onCancel(b: TaskSignalBody, from: String) {
        val key = "${b.planId}|${b.taskId}"
        val entry = synchronized(lock) { workerJobs[key] } ?: return
        if (entry.first != from) return
        entry.second.cancel()
        SLog.d(TAG, "TASK ${b.taskId} cancelled by its commander: ${b.reason}")
        emit(WorkerEvent(b.planId, WorkerEventKind.CANCELLED, selfId, from, clock(), taskId = b.taskId,
            chunkIndex = WorkQueue.parseTaskId(b.taskId)?.first, error = b.reason))
    }

    private fun onBlob(b: BlobBody, from: String) {
        val store = blobs ?: return
        if (b.owner != from) return
        store.onChunk(b).onFailure { SLog.w(TAG, "BLOB ${b.blobId.take(8)} chunk ${b.seq}/${b.total} rejected: ${it.message}") }
    }

    /**
     * Sends every chunk of a stored blob to [to] (owner → worker, after a RELEASE). Phone gate finding: every frame is
     * signed by the Keystore key, so 16 KB frames moved a 30-photo chunk at ~0.9 MB/s on the office LAN; a peer whose
     * profile reports a LAN link gets [LAN_CHUNK] frames (Nearby's 32 KB payload cap keeps the rest at [TRANSPORT_CHUNK]).
     */
    suspend fun sendBlob(to: String, blobId: String, jobId: String, chunkBytes: Int = blobChunkFor(to)): Result<Int> {
        val store = blobs ?: return Result.failure(IllegalStateException("no blob store"))
        val chunks = store.chunks(blobId, selfId, jobId, chunkBytes)
        if (chunks.isEmpty()) return Result.failure(IllegalStateException("blob ${blobId.take(8)} is not stored"))
        for (c in chunks) sendTo(to, MsgType.BLOB, MeshJson.encode(c)).onFailure { return Result.failure(it) }
        return Result.success(chunks.size)
    }

    private fun blobChunkFor(to: String): Int =
        if (beacon?.snapshot()?.firstOrNull { it.nodeId == to }?.link?.kind == com.hivecore.core.LinkKind.LAN) LAN_CHUNK else TRANSPORT_CHUNK

    /** After the TASK_RESULT that used [blobIds] was countersigned: delete them and tell the owner. */
    suspend fun releaseBlobs(owner: String, jobId: String, blobIds: List<String>) {
        val store = blobs ?: return
        val body = store.deleteAfterCountersign(jobId, selfId, blobIds)
        sendTo(owner, MsgType.BLOB_DELETED, MeshJson.encode(body))
    }

    companion object {
        private const val TAG = "SwarmNode"

        /** The sha256 of every `items[].sha256` in an executor output: files that may travel with the result. */
        fun attachments(output: JsonElement): List<String> {
            val items = (output as? JsonObject)?.get("items") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return items.mapNotNull { ((it as? JsonObject)?.get("sha256") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { s -> s.length == 64 } }.distinct()
        }

        private suspend fun sendBlobsFor(node: SwarmNode, to: String, ids: List<String>, jobId: String): Result<Long> {
            var bytes = 0L
            for (id in ids) {
                node.sendBlob(to, id, jobId).onFailure { return Result.failure(it) }
                bytes += node.blobs?.file(id)?.length() ?: 0L
            }
            return Result.success(bytes)
        }
        /** Chunk size that fits today's transports (Nearby bytes payloads ≤ 32 KB, router envelopes ≤ 64 KB) after base64. */
        const val TRANSPORT_CHUNK = 16 * 1024
        /** Blob frame bytes over a LAN link: 40 KB is ~54 KB of base64, inside the 64 KB envelope with its headers. */
        const val LAN_CHUNK = 40 * 1024
        /** A TASK_RESULT body longer than this sends its output as a blob (envelopes are ≤ 64 KB with headers). */
        const val MAX_INLINE_RESULT = 48 * 1024
        val TYPES = setOf(MsgType.PROFILE, MsgType.PLAN, MsgType.PLAN_CONSENT_REQ, MsgType.PLAN_CONSENT_RES, MsgType.TASK,
            MsgType.TASK_RESULT, MsgType.TASK_YIELD, MsgType.TASK_CANCEL, MsgType.BLOB, MsgType.BLOB_DELETED,
            // v2
            MsgType.MODEL_REQ, MsgType.MODEL_READY, MsgType.MODEL_FAIL, MsgType.RECLAIM, MsgType.RECLAIM_RES, MsgType.JOURNAL,
            MsgType.ROSTER, MsgType.REVOKE, MsgType.SIGNAL, MsgType.TASK_PROGRESS)

        /** Without a catalog: the task's own model id ("requested"), else none named ("built-in"); null for ops with no capability. */
        fun defaultModelChoice(task: com.hivecore.core.TaskSpec): ModelChoice? {
            val cap = com.hivecore.core.OpRegistry[task.op].capability ?: return null
            val id = task.modelId ?: (task.params["modelId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return ModelChoice(cap, id, if (id != null) "requested" else "built-in", null)
        }

        /**
         * Builds the whole swarm side of one phone over real neighbours. Call before `router.start()`, then [start].
         *
         * @param intentPlanner/compiler/executor the owner's existing ask path (the same instances NodeAnswerer uses)
         * @param planner        the composite planner (swarm/PlannerImpl)
         * @param nameOf         nodeId → display name (PeerStore / last HELLO)
         * @param dataVersionOf  owner nodeId → its data version for memo keys (e.g. a hash of its last HELLO catalog)
         * @param journal        the orchestrator's write-ahead journal (13.1); with one, the resume driver is built too
         * @param memo           this phone's chunk memo (13.2); with one, the RECLAIM responder is built too
         * @param escalator      the orchestrator's escalation gate (15); [Escalator.Off] on every node
         * @param readyFor       placement's MODEL_READY set per capability (8.3) for jobs started outside [runJob]'s `ready`
         * @param unitMsOf       the catalog `unitMsP50` for a (node, capability) (10.5)
         * @param onCooldown     a (node, capability, until) cooldown the split commander started (14); feeds the cooldown table
         * @param linkOf         the measured link to a peer (5.6; the session layer), filled into its received PROFILE
         * @param journalPull    the deputy pull used before a resume (13.4)
         */
        fun build(
            router: MeshRouterPort,
            selfName: String,
            scope: CoroutineScope,
            schema: SchemaSource,
            intentPlanner: IntentPlanner,
            compiler: IntentCompiler,
            executor: QueryExecutor,
            approval: ApprovalGate,
            guard: DifferencingGuard,
            planner: Planner,
            planCompiler: PlanCompiler,
            scheduler: Scheduler = SchedulerImpl(),
            reducer: Reducer = ReducerImpl(),
            comparator: Comparator = ComparatorImpl(),
            policy: WorkerPolicy,
            ledger: LedgerStore? = null,
            docIndex: DocIndex? = null,
            docAnswerer: DocAnswerer? = null,
            blobDir: File? = null,
            /** where a deck plan's .pptx lands on the orchestrator (null = decks are built as structure only) */
            deckDir: File? = null,
            runner: TaskRunner = TaskRunnerImpl(router.nodeId),
            extraExecutors: List<OpExecutor> = emptyList(),
            nameOf: (String) -> String = { it.take(8) },
            modelHash: () -> String = { "" },
            dataVersionOf: ((String) -> String)? = null,
            backend: () -> String = { "" },
            clock: () -> Long = System::currentTimeMillis,
            treeSelector: TreeSelector = Mergers.extractiveSelector,
            decorateProfile: (com.hivecore.core.Profile) -> com.hivecore.core.Profile = { it },
            journal: JobJournal = JobJournal.None,
            memo: ChunkMemo = ChunkMemo.None,
            escalator: Escalator = Escalator.Off,
            schemaValidator: SchemaValidator = JsonSchemaValidator,
            readyFor: (String) -> Set<String>? = { null },
            unitMsOf: (String, String) -> Double? = { _, _ -> null },
            allowMobileData: () -> Boolean = { false },
            onCooldown: (String, String, Long) -> Unit = { _, _, _ -> },
            linkOf: (String) -> Link? = { null },
            journalPull: JournalPull? = null,
            ensureAwake: suspend (String) -> Boolean = { true },
            deputy: () -> String? = { null },
            /** the decision model for the chunking points (docs/plans/decision-chunking.md); null = deterministic cutters only */
            decisionModel: com.hivecore.core.DecisionModel? = null,
            /** the per-job budget of decisions (LLM calls, share of the job estimate) */
            decisionBudget: com.hivecore.engine.decide.DecisionBudget = com.hivecore.engine.decide.DecisionBudget(),
        ): SwarmNode {
            val selfId = router.nodeId
            lateinit var node: SwarmNode
            val beacon = ProfileBeacon(selfId, policy, { t, b -> router.broadcast(t, b) }, clock, runner.queueDepth, decorate = decorateProfile, linkOf = linkOf)
            // Default: an owner's data version is the one its last PROFILE carried (§8.2 memo keys).
            val dataVersionOf: (String) -> String = dataVersionOf ?: { id -> beacon.lastOf(id)?.dataVersion.orEmpty() }
            node = SwarmNode(router, scope, runner, beacon, blobDir?.let { BlobStore(it, TRANSPORT_CHUNK) }, acceptWork = { policy.optIn.value })
            lateinit var owner: OwnerPlanHandler
            val agg = AggExecutor(selfName, schema, intentPlanner, compiler, executor, docIndex, docAnswerer) { owner.prepared(it) }
            owner = OwnerPlanHandler(selfId, selfName, schema, agg, guard, approval, runner, scope,
                requesterName = { id -> nameOf(id) }, clock = clock, backend = backend)
            runner.register(agg)
            extraExecutors.forEach { runner.register(it) }
            node.owner = owner
            node.commander = Commander(selfId, selfName, planner, planCompiler, scheduler, reducer, comparator,
                profiles = { beacon.snapshot() }, transmit = { to, t, b -> node.sendTo(to, t, b) }, ledger = ledger,
                nameOf = { id -> if (id == selfId) selfName else nameOf(id) }, modelHash = modelHash, dataVersionOf = dataVersionOf, clock = clock,
                escalator = escalator, schemaValidator = schemaValidator, deckDir = deckDir, blobFile = { sha -> node.blobs?.file(sha) })
            node.nameOf = { id -> if (id == selfId) selfName else nameOf(id) }
            node.split = SplitCommander(selfId, comparator, profiles = { beacon.snapshot() }, transmit = { to, t, b -> node.sendTo(to, t, b) },
                sendBlobs = { to, ids, jobId -> sendBlobsFor(node, to, ids, jobId) }, clock = clock, selector = treeSelector, modelHash = modelHash,
                journal = journal, memo = memo, escalator = escalator, schema = schemaValidator, readyFor = readyFor, unitMsOf = unitMsOf,
                allowMobileData = allowMobileData, onCooldown = onCooldown, ensureAwake = ensureAwake, deputy = deputy,
                decisions = decisionModel?.let { com.hivecore.engine.decide.ChunkDecisions(it, selfId, decisionBudget, clock) })
            node.journal = journal
            node.clock = clock
            if (memo !== ChunkMemo.None) node.reclaim = ReclaimResponder(router, memo)
            if (journal !== JobJournal.None) {
                val coordinator = ResumeCoordinator(router, memo, journal, clock)
                node.resumeCoordinator = coordinator
                node.jobResume = JobResume(journal, coordinator, journalPull)
            }
            return node
        }
    }
}
