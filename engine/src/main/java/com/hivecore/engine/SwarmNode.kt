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
import com.hivecore.core.WorkerPolicy
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
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.JobViews
import com.hivecore.engine.split.Mergers
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.TreeSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    var owner: OwnerPlanHandler? = null
    /** Split jobs commanded by this phone (§4B). */
    var split: SplitCommander? = null
    /** Recipe lookup for [runWorkload] (id, version; version "" = the installed one). */
    var recipes: (String, String) -> Recipe? = { _, _ -> null }
    /** Recipe definitions with axis/merge/input (workload/Recipes); null = unknown id. */
    var recipeDefs: (String) -> com.hivecore.engine.workload.RecipeDef? = { null }
    /** Items for recipes whose input is "none" (the EVAL sweep: the question sets as EvalCase JSON). */
    var evalCases: () -> List<JsonElement> = { emptyList() }
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
    /** MODEL_READY / MODEL_FAIL that no live split job claimed (placement outside a job, e.g. the Models screen). */
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
                            val cases = evalCases()
                            if (cases.isEmpty()) throw IllegalArgumentException("recipe ${r.id}: no items on this phone")
                            runStages(jobId, RecipeJobs.itemJobs(jobId, r, cases, selfId, request.private))
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
        return raw.map { JobViews.of(it, request.title, nameOf) }.onEach { p ->
            synchronized(jobList) { jobList.value = listOf(p) + jobList.value.filter { it.jobId != p.jobId } }
        }
    }

    override fun cancel(jobId: String) { split?.cancel(jobId) }

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

    /** Subscribes to the router (call before the router starts) and starts the PROFILE beacon. */
    fun start(periodicProfiles: Boolean = true): Job {
        collector?.let { return it }
        val j = scope.launch { router.verified.collect { if (it.type in TYPES) dispatch(it.type, it.from, it.body) } }
        collector = j
        beacon?.start(scope, periodicProfiles)
        return j
    }

    fun stop() { collector?.cancel(); collector = null; beacon?.stop() }

    /** A composite (T2) question asked on this phone: this phone commands the plan. */
    fun askComposite(question: String, route: RouteDecision, catalogs: List<Catalog>): Flow<AskProgress> =
        commander.askComposite(question, route, catalogs)

    /**
     * Splits one op over [input] across the swarm (§4B) with this phone as commander: chunk grid updates, then a last
     * emission with `done` and the merged result (or `error`). Inputs marked private never leave [owner] (default: here).
     */
    fun runJob(op: Op, params: JsonObject, input: SplitInput, modelId: String? = null, owner: String? = null,
               jobId: String = java.util.UUID.randomUUID().toString()): Flow<SplitProgress> =
        runJob(SplitJob(jobId, op, params, modelId, input, owner = owner))

    fun runJob(job: SplitJob, useMemo: Boolean = true): Flow<SplitProgress> =
        (split ?: throw IllegalStateException("this node has no split commander")).run(enforcePrivate(job), useMemo)

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
        if (jobs.isNullOrEmpty()) return flow {
            emit(SplitProgress(spec.jobId, "", "", "", emptyList(), emptyList(), 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, done = false,
                error = "recipe ${spec.recipeId}@${spec.recipeVersion} is not installed"))
        }
        return runStages(spec.jobId, RecipeJobs.Staged(jobs, emptyList()))
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
                    owner?.onPlan(plan, from) { t, b -> sendTo(from, t, b) }
                }
                MsgType.PLAN_CONSENT_REQ, MsgType.PLAN_CONSENT_RES, MsgType.TASK_RESULT, MsgType.TASK_YIELD ->
                    if (!commander.onMessage(type, from, body) && split?.onMessage(type, from, body) != true)
                        SLog.d(TAG, "$type from ${from.take(8)} matches no live plan or split job")
                MsgType.TASK -> onTask(MeshJson.decode(body), from)
                MsgType.TASK_CANCEL -> onCancel(MeshJson.decode(body), from)
                MsgType.BLOB -> onBlob(MeshJson.decode(body), from)
                MsgType.BLOB_DELETED -> onBlobDeleted(from, MeshJson.decode(body))
                // ---- v2 ----
                MsgType.MODEL_REQ -> onModelReq?.invoke(MeshJson.decode(body), from) { t, b -> sendTo(from, t, b) }
                    ?: SLog.d(TAG, "MODEL_REQ from ${from.take(8)}: no runtime handler on this phone")
                MsgType.MODEL_READY, MsgType.MODEL_FAIL ->
                    if (split?.onMessage(type, from, body) != true) onModelReply?.invoke(type, from, body)
                MsgType.RECLAIM -> onReclaim?.invoke(MeshJson.decode(body), from) { t, b -> sendTo(from, t, b) }
                MsgType.RECLAIM_RES -> onReclaimRes?.invoke(MeshJson.decode(body), from)
                MsgType.JOURNAL -> onJournal?.invoke(MeshJson.decode(body), from)
                MsgType.ROSTER, MsgType.REVOKE -> onRoster?.invoke(type, from, body)
                MsgType.SIGNAL -> onSignal?.invoke(MeshJson.decode(body), from)
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
            if (handledTasks.put(key, true) != null) { SLog.d(TAG, "TASK $key again; ignored"); return }
            while (handledTasks.size > 1000) handledTasks.remove(handledTasks.keys.first())
        }
        if (OpClasses.of(body.task.op) == OpClass.OWNER) {
            scope.launch { reply(from, TaskResultBody(body.planId, body.task.id, selfId, error = "this phone does not own the data for ${body.task.id}")) }
            return
        }
        if (from != selfId && !acceptWork()) {
            scope.launch { sendTo(from, MsgType.TASK_YIELD, MeshJson.encode(TaskSignalBody(body.planId, body.task.id, selfId, "worker mode is off"))) }
            return
        }
        val job = scope.launch {
            val files = body.task.blobs.map { id -> blobs?.file(id) }
            if (files.any { it == null }) {
                reply(from, TaskResultBody(body.planId, body.task.id, selfId, error = "released items are missing on this phone"))
                return@launch
            }
            val inputs = body.task.inputs.map { body.inputs[it] ?: JsonNull }
            val t0 = System.nanoTime()
            val r = runner.run(body.task, inputs, files.filterNotNull(), from)
            val ms = (System.nanoTime() - t0) / 1_000_000
            r.fold(
                onSuccess = { out ->
                    reply(from, TaskResultBody(body.planId, body.task.id, selfId, out, Canon.hash(out), ms = ms))
                    if (from != selfId) try { onWorkedForOthers(from, ms) } catch (e: Exception) { SLog.w(TAG, "contribution counter: ${e.message}") }
                },
                onFailure = { e ->
                    if (e.message == TaskRunnerImpl.YIELDED)
                        sendTo(from, MsgType.TASK_YIELD, MeshJson.encode(TaskSignalBody(body.planId, body.task.id, selfId, TaskRunnerImpl.YIELDED)))
                    else reply(from, TaskResultBody(body.planId, body.task.id, selfId, error = e.message ?: e.javaClass.simpleName))
                }
            )
        }
        synchronized(lock) { workerJobs[key] = from to job }
        job.invokeOnCompletion { synchronized(lock) { if (workerJobs[key]?.second === job) workerJobs.remove(key) } }
    }

    private suspend fun reply(to: String, res: TaskResultBody) {
        sendTo(to, MsgType.TASK_RESULT, MeshJson.encode(res)).onFailure { SLog.w(TAG, "TASK_RESULT ${res.taskId} not sent: ${it.message}") }
    }

    private fun onCancel(b: TaskSignalBody, from: String) {
        val key = "${b.planId}|${b.taskId}"
        val entry = synchronized(lock) { workerJobs[key] } ?: return
        if (entry.first != from) return
        entry.second.cancel()
        SLog.d(TAG, "TASK ${b.taskId} cancelled by its commander: ${b.reason}")
    }

    private fun onBlob(b: BlobBody, from: String) {
        val store = blobs ?: return
        if (b.owner != from) return
        store.onChunk(b).onFailure { SLog.w(TAG, "BLOB ${b.blobId.take(8)} chunk ${b.seq}/${b.total} rejected: ${it.message}") }
    }

    /** Sends every chunk of a stored blob to [to] (owner → worker, after a RELEASE). */
    suspend fun sendBlob(to: String, blobId: String, jobId: String): Result<Int> {
        val store = blobs ?: return Result.failure(IllegalStateException("no blob store"))
        val chunks = store.chunks(blobId, selfId, jobId)
        if (chunks.isEmpty()) return Result.failure(IllegalStateException("blob ${blobId.take(8)} is not stored"))
        for (c in chunks) sendTo(to, MsgType.BLOB, MeshJson.encode(c)).onFailure { return Result.failure(it) }
        return Result.success(chunks.size)
    }

    /** After the TASK_RESULT that used [blobIds] was countersigned: delete them and tell the owner. */
    suspend fun releaseBlobs(owner: String, jobId: String, blobIds: List<String>) {
        val store = blobs ?: return
        val body = store.deleteAfterCountersign(jobId, selfId, blobIds)
        sendTo(owner, MsgType.BLOB_DELETED, MeshJson.encode(body))
    }

    companion object {
        private const val TAG = "SwarmNode"

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
        val TYPES = setOf(MsgType.PROFILE, MsgType.PLAN, MsgType.PLAN_CONSENT_REQ, MsgType.PLAN_CONSENT_RES, MsgType.TASK,
            MsgType.TASK_RESULT, MsgType.TASK_YIELD, MsgType.TASK_CANCEL, MsgType.BLOB, MsgType.BLOB_DELETED,
            // v2
            MsgType.MODEL_REQ, MsgType.MODEL_READY, MsgType.MODEL_FAIL, MsgType.RECLAIM, MsgType.RECLAIM_RES, MsgType.JOURNAL,
            MsgType.ROSTER, MsgType.REVOKE, MsgType.SIGNAL)

        /**
         * Builds the whole swarm side of one phone over real neighbours. Call before `router.start()`, then [start].
         *
         * @param intentPlanner/compiler/executor the owner's existing ask path (the same instances NodeAnswerer uses)
         * @param planner        the composite planner (swarm/PlannerImpl)
         * @param nameOf         nodeId → display name (PeerStore / last HELLO)
         * @param dataVersionOf  owner nodeId → its data version for memo keys (e.g. a hash of its last HELLO catalog)
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
            runner: TaskRunner = TaskRunnerImpl(router.nodeId),
            extraExecutors: List<OpExecutor> = emptyList(),
            nameOf: (String) -> String = { it.take(8) },
            modelHash: () -> String = { "" },
            dataVersionOf: ((String) -> String)? = null,
            backend: () -> String = { "" },
            clock: () -> Long = System::currentTimeMillis,
            treeSelector: TreeSelector = Mergers.extractiveSelector,
            decorateProfile: (com.hivecore.core.Profile) -> com.hivecore.core.Profile = { it },
        ): SwarmNode {
            val selfId = router.nodeId
            lateinit var node: SwarmNode
            val beacon = ProfileBeacon(selfId, policy, { t, b -> router.broadcast(t, b) }, clock, runner.queueDepth, decorate = decorateProfile)
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
                nameOf = { id -> if (id == selfId) selfName else nameOf(id) }, modelHash = modelHash, dataVersionOf = dataVersionOf, clock = clock)
            node.nameOf = { id -> if (id == selfId) selfName else nameOf(id) }
            node.split = SplitCommander(selfId, comparator, profiles = { beacon.snapshot() }, transmit = { to, t, b -> node.sendTo(to, t, b) },
                sendBlobs = { to, ids, jobId -> sendBlobsFor(node, to, ids, jobId) }, clock = clock, selector = treeSelector, modelHash = modelHash)
            return node
        }
    }
}
