package com.hivecore.core.fakes

import com.hivecore.core.Catalog
import com.hivecore.core.ClosedChooser
import com.hivecore.core.Comparator
import com.hivecore.core.Dag
import com.hivecore.core.Decision
import com.hivecore.core.DifferencingGuard
import com.hivecore.core.Intent
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.PlanCheck
import com.hivecore.core.PlanCompiler
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.Planner
import com.hivecore.core.Profile
import com.hivecore.core.Reducer
import com.hivecore.core.RouteDecision
import com.hivecore.core.Scheduler
import com.hivecore.core.Schema
import com.hivecore.core.SwarmRouter
import com.hivecore.core.TaskRunner
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkerPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

// Fakes for core/Swarm.kt (swarm plan Wave S0): scripted, deterministic, JVM-only.

/** Picks [answers] by question substring; otherwise the first option, confidence 1.0. */
class FakeClosedChooser : ClosedChooser {
    val answers = LinkedHashMap<String, Int>()     // question substring → option index (-1 = no confident choice)
    var confidence = 1.0
    val calls = mutableListOf<Pair<String, List<String>>>()
    override suspend fun <T> choose(question: String, options: List<T>, render: (T) -> String, k: Int): Decision<T> {
        calls += question to options.map(render)
        val idx = answers.entries.firstOrNull { question.contains(it.key, ignoreCase = true) }?.value ?: 0
        if (idx < 0 || idx >= options.size) return Decision(null, 0.0, options, 0, k)
        return Decision(options[idx], confidence, options, k, k)
    }
}

/** Routes by [composite] flag set by the test; owners = every catalog whose table names appear in the question. */
class FakeSwarmRouter(var composite: Boolean = false) : SwarmRouter {
    override fun route(question: String, selfId: String, catalogs: List<Catalog>): RouteDecision {
        val q = question.lowercase()
        val owners = catalogs.filter { c -> c.tables.any { t -> q.contains(t.name) || t.aliases.any { q.contains(it) } } }.map { it.node }
        return RouteDecision(composite, owners, emptyList(), "fake")
    }
}

class FakePlanner(var result: Result<PlannedQuestion>? = null) : Planner {
    override suspend fun plan(question: String, route: RouteDecision, catalogs: List<Catalog>): Result<PlannedQuestion> =
        result ?: Result.failure(IllegalStateException("no plan scripted"))
}

class FakePlanCompiler : PlanCompiler {
    override fun validate(dag: Dag, catalogs: List<Catalog>, modelHash: String) =
        PlanCheck(true, emptyList(), "fakehash-${dag.patternId}-${dag.tasks.size}",
            dag.tasks.filter { it.owner != null }.groupBy({ it.owner!! }, { it.params.toString() }))
}

/** Owner tasks to their owner, everything else to [self]. */
class FakeScheduler : Scheduler {
    override fun assign(dag: Dag, profiles: List<Profile>, self: String): Map<String, String> =
        dag.tasks.associate { it.id to (it.owner ?: self) }
    override fun reassign(task: TaskSpec, profiles: List<Profile>, self: String, failed: Set<String>): String? =
        if (self in failed) null else self
}

/** Returns [outputs] per op, or the first input unchanged. */
class FakeReducer : Reducer {
    val outputs = HashMap<Op, JsonElement>()
    override fun run(op: Op, params: JsonObject, inputs: List<JsonElement>): Result<JsonElement> =
        outputs[op]?.let { Result.success(it) } ?: inputs.firstOrNull()?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("$op has no input"))
}

class FakeComparator : Comparator {
    override fun agree(op: Op, a: JsonElement, b: JsonElement, rule: JsonObject?): Boolean = a == b
}

/** Dispatches to registered executors, counting calls; [yielding] makes every run fail with "yielded". */
class FakeTaskRunner : TaskRunner {
    private val q = MutableStateFlow(0)
    override val queueDepth: StateFlow<Int> = q
    private val executors = ArrayList<OpExecutor>()
    val ran = mutableListOf<TaskSpec>()
    var yielding = false
    override fun register(executor: OpExecutor) { executors += executor }
    override suspend fun run(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>, from: String): Result<JsonElement> {
        if (yielding) return Result.failure(IllegalStateException("yielded"))
        ran += task
        val ex = executors.firstOrNull { task.op in it.ops } ?: return Result.failure(IllegalStateException("no executor for ${task.op}"))
        return ex.execute(task, inputs, blobs)
    }
    override fun yieldAll() { yielding = true }
}

class FakeWorkerPolicy(initial: Profile) : WorkerPolicy {
    private val p = MutableStateFlow(initial)
    override val profile: StateFlow<Profile> = p
    private val o = MutableStateFlow(initial.workerOptIn)
    override val optIn: StateFlow<Boolean> = o
    override fun setOptIn(on: Boolean) { o.value = on; p.value = p.value.copy(workerOptIn = on, eligible = on && p.value.idle) }
    fun set(profile: Profile) { p.value = profile }
}

/** Returns [refusal] for every check (null = allowed) and records what was remembered. */
class FakeDifferencingGuard : DifferencingGuard {
    var refusal: String? = null
    val remembered = mutableListOf<Pair<String, List<Intent>>>()
    override fun check(requester: String, intents: List<Intent>, schema: Schema, nowMs: Long): String? = refusal
    override fun remember(requester: String, intents: List<Intent>, nowMs: Long) { remembered += requester to intents }
}

class FakeModelRegistry(private val manifests: List<ModelManifest> = emptyList(), private val root: File = File("models")) : ModelRegistry {
    override fun installed(): List<ModelManifest> = manifests
    override fun manifest(id: String): ModelManifest? = manifests.firstOrNull { it.id == id }
    override fun dir(id: String): File? = manifest(id)?.let { File(root, id) }
}

/** A runtime whose [run] returns [outputs] (or echoes the inputs). */
class FakeModelRuntime(override val id: String = "fake") : ModelRuntime {
    val outputs = HashMap<String, Map<String, Any>>()   // modelId → outputs
    var loads = 0
    override fun supports(m: ModelManifest): Boolean = true
    override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> {
        loads++
        return Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
    }
    override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> =
        Result.success(outputs[session.manifest.id] ?: inputs)
}

/** Big slot + LRU over [runtime]; no byte budget. */
class FakeModelPool(private val registry: ModelRegistry, private val runtime: ModelRuntime = FakeModelRuntime()) : ModelPool {
    private val b = MutableStateFlow("none")
    override val big: StateFlow<String> = b
    private val w = MutableStateFlow<List<String>>(emptyList())
    override val warm: StateFlow<List<String>> = w
    private val sessions = LinkedHashMap<String, ModelSession>()
    override suspend fun <T> with(modelId: String, block: suspend (ModelSession) -> T): Result<T> {
        val m = registry.manifest(modelId) ?: return Result.failure(IllegalArgumentException("unknown model $modelId"))
        val s = sessions[modelId] ?: runtime.load(m, registry.dir(modelId) ?: File(".")).getOrElse { return Result.failure(it) }
            .also { sessions[modelId] = it; w.value = sessions.keys.toList() }
        return runCatching { block(s) }
    }
    override suspend fun claimBig(kind: String): Result<Unit> { b.value = kind; return Result.success(Unit) }
}

/** Emits a scripted sequence of [com.hivecore.core.JobProgress] per request (UI previews and VM tests). */
class FakeJobRunner(var script: (com.hivecore.core.JobRequest) -> List<com.hivecore.core.JobProgress> = { emptyList() }) :
    com.hivecore.core.JobRunner {
    private val all = MutableStateFlow<List<com.hivecore.core.JobProgress>>(emptyList())
    override val jobs: StateFlow<List<com.hivecore.core.JobProgress>> = all
    val cancelled = mutableListOf<String>()
    override fun run(request: com.hivecore.core.JobRequest) = kotlinx.coroutines.flow.flow {
        for (p in script(request)) { all.value = listOf(p) + all.value.filter { it.jobId != p.jobId }; emit(p) }
    }
    override fun cancel(jobId: String) { cancelled += jobId }
}
