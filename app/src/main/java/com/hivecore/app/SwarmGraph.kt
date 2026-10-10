package com.hivecore.app

import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.core.fakes.FakeTransport
import com.hivecore.engine.device.WorkerPolicyImpl
import com.hivecore.engine.SwarmNode
import com.hivecore.planner.PlanCompilerImpl
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.planner.PlannerImpl
import com.hivecore.planner.Patterns
import com.hivecore.planner.SwarmRouterImpl
import com.hivecore.mesh.MeshRouterPort
import com.hivecore.core.AnswerChip
import com.hivecore.core.ApprovalGate
import com.hivecore.core.AskOutcome
import com.hivecore.core.AskProgress
import com.hivecore.core.Catalog
import com.hivecore.core.ClosedChooser
import com.hivecore.core.Decision
import com.hivecore.core.DifferencingGuard
import com.hivecore.core.DocAnswerer
import com.hivecore.core.DocIndex
import com.hivecore.core.IntentPlanner
import com.hivecore.core.LedgerStore
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.Ocr
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.RouteDecision
import com.hivecore.core.SLog
import com.hivecore.core.SchemaSource
import com.hivecore.core.SqlDb
import com.hivecore.core.SwarmRouter
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkerPolicy
import com.hivecore.node.data.RuleIntentCompiler
import com.hivecore.node.data.SqlDifferencingGuard
import com.hivecore.node.data.SqlQueryExecutor
import com.hivecore.node.devai.Bench
import com.hivecore.node.devai.DevAiExecutor
import com.hivecore.node.gen.ClipScorer
import com.hivecore.node.gen.GenExecutor
import com.hivecore.node.gen.ImageGenerator
import com.hivecore.node.gen.ImageSafetyCheck
import com.hivecore.node.gen.SuperRes
import com.hivecore.runtime.llm.ClosedChooserImpl
import com.hivecore.node.media.AudioDecoder
import com.hivecore.node.media.FrameSource
import com.hivecore.node.media.MediaExecutor
import com.hivecore.node.textai.GenText
import com.hivecore.node.textai.TextExecutor
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.node.vision.VisionExecutor
import com.hivecore.node.vision.VlmAskExecutor
import com.hivecore.engine.workload.RecipeDef
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.security.MessageDigest

/**
 * The swarm side of one phone, wired from its parts (the integrator's Wave S3 wiring, kept free of Android so the
 * whole stack can be built over a FakeTransport in JVM tests). `core.Graph` builds the Android seams (runtimes, image
 * codec, VLM, audio decoder, ML Kit OCR) and passes them in [Deps]; tests pass fakes.
 *
 * Call [build] BEFORE `router.start()`: it subscribes the [SwarmNode] to the router's verified stream and starts the
 * PROFILE beacon.
 */
class SwarmGraph(
    val node: SwarmNode,
    val runner: TaskRunnerImpl,
    val policy: WorkerPolicy,
    val chooser: ClosedChooser,
    val planner: PlannerImpl,
    val router: SwarmRouter,
    val executors: List<OpExecutor>,
    val selfCatalog: () -> Catalog,
    private val peerCatalogs: () -> List<Catalog>,
) {
    /** This phone's catalog plus every peer's last HELLO catalog (names and flags only, §8.1). */
    fun catalogs(): List<Catalog> {
        val self = selfCatalog()
        return listOf(self) + peerCatalogs().filter { it.node != self.node }
    }

    /** The swarm route for [question] over [catalogs]: composite (T2) or not. */
    fun route(question: String, catalogs: List<Catalog> = catalogs()): RouteDecision = router.route(question, node.selfId, catalogs)

    /**
     * The AskOrchestrator seam: a T2 question becomes a plan this phone commands; anything else returns null and goes
     * the existing QuestionRouter path unchanged.
     */
    fun compositeOrNull(question: String): Flow<AskProgress>? {
        val cats = try { catalogs() } catch (e: Exception) { SLog.w(TAG, "catalogs: ${e.message}"); return null }
        val r = route(question, cats)
        SLog.d(TAG, "route '${question.take(60)}': composite=${r.composite} owners=${r.owners.map { it.take(8) }} cues=${r.cues.map { it.op }} (${r.reason})")
        return if (r.composite) node.askComposite(question, r, cats) else null
    }

    fun stop() = node.stop()

    /** Everything one phone's swarm needs. Defaults suit the phone; tests override clocks and seams. */
    class Deps(
        val router: MeshRouterPort,
        val selfName: String,
        val scope: kotlinx.coroutines.CoroutineScope,
        val db: SqlDb,
        val schema: SchemaSource,
        val intentPlanner: IntentPlanner,
        val approval: ApprovalGate,
        val llm: LlmEngine,
        val patterns: Patterns,
        /** WorkerPolicyImpl on the phone (it needs the runner's queue depth); a FakeWorkerPolicy in tests */
        val policy: (TaskRunnerImpl) -> WorkerPolicy,
        val executors: (ExecutorEnv) -> List<OpExecutor> = { emptyList() },
        val chooser: ClosedChooser = ClosedChooserImpl(llm),
        val ledger: LedgerStore? = null,
        val docIndex: DocIndex? = null,
        val docAnswerer: DocAnswerer? = null,
        val guard: DifferencingGuard? = null,
        val blobDir: File? = null,
        val nameOf: (String) -> String = { it.take(8) },
        val modelHash: () -> String = { "" },
        /** this phone's data version (default: [dataVersion] over [db]) */
        val dataVersion: () -> String? = { dataVersion(db) },
        /** BENCH p50 per model on this phone (Profile.p50) */
        val p50: () -> Map<String, Double> = { emptyMap() },
        /** tables kept out of this phone's public catalog (the per-table hide switch, §15 Q1) */
        val hidden: () -> Set<String> = { emptySet() },
        /** peers' last HELLO catalogs */
        val peerCatalogs: () -> List<Catalog> = { emptyList() },
        val recipeDefs: () -> List<RecipeDef> = { emptyList() },
        val evalCases: () -> List<JsonElement> = { emptyList() },
        val backend: () -> String = { llm.backend.value?.name?.lowercase() ?: "" },
        val clock: () -> Long = System::currentTimeMillis,
        /** "today" for the planner's date phrases (tests pin it; the phone uses [clock]) */
        val today: () -> Long = clock,
        val router2: SwarmRouter = SwarmRouterImpl(),
        val periodicProfiles: Boolean = true,
        /** reuse a runner across rebuilds (its executors are cleared first); null = a new one */
        val runner: TaskRunnerImpl? = null,
        /** private mode (§7): table names and doc ids marked private (kept out of the catalog; split jobs stay here) */
        val privateTables: () -> Set<String> = { emptySet() },
        val privateDocs: () -> Set<String> = { emptySet() },
        /** (commander, ms) after each task this phone ran for another phone */
        val onWorkedForOthers: (String, Long) -> Unit = { _, _ -> },
    )

    /** What executor factories get: the chooser and the node's own pieces. */
    class ExecutorEnv(val chooser: ClosedChooser, val llm: LlmEngine, val selfCatalog: () -> Catalog, val db: SqlDb,
                      val schema: SchemaSource, val selfName: String)

    companion object {
        private const val TAG = "SwarmGraph"

        fun build(d: Deps): SwarmGraph {
            val runner = d.runner?.also { it.clearExecutors() } ?: TaskRunnerImpl(d.router.nodeId)
            val policy = d.policy(runner)
            val selfCatalog = { catalogOf(d) }
            val env = ExecutorEnv(d.chooser, d.llm, selfCatalog, d.db, d.schema, d.selfName)
            val executors = d.executors(env)
            val planner = PlannerImpl(d.chooser, d.patterns, d.today)
            val node = SwarmNode.build(
                router = d.router, selfName = d.selfName, scope = d.scope, schema = d.schema,
                intentPlanner = d.intentPlanner, compiler = RuleIntentCompiler(), executor = SqlQueryExecutor(d.db) { AnswerChip.LLM }, planCompiler = PlanCompilerImpl(),
                approval = d.approval, guard = d.guard ?: SqlDifferencingGuard(d.db, RuleIntentCompiler(), d.clock),
                planner = planner, policy = policy, ledger = d.ledger, docIndex = d.docIndex, docAnswerer = d.docAnswerer,
                blobDir = d.blobDir, runner = runner, extraExecutors = executors, nameOf = d.nameOf, modelHash = d.modelHash,
                backend = d.backend, clock = d.clock,
                decorateProfile = { p -> p.copy(dataVersion = d.dataVersion(), p50 = p.p50 + d.p50()) },
            )
            node.recipeDefs = { id -> d.recipeDefs().firstOrNull { it.id == id } }
            node.recipes = { id, v -> d.recipeDefs().firstOrNull { it.id == id && (v.isEmpty() || it.version == v) }?.toRecipe() }
            node.evalCases = d.evalCases
            node.privateTables = d.privateTables
            node.privateDocs = {
                val ids = d.privateDocs()
                if (ids.isEmpty()) ids else ids + d.schema.schema().docs.filter { it.id in ids }.map { it.name }
            }
            node.onWorkedForOthers = d.onWorkedForOthers
            node.start(d.periodicProfiles)
            SLog.d(TAG, "swarm node ${d.router.nodeId.take(8)} (${d.selfName}) started: ${runnerOps(executors)}")
            return SwarmGraph(node, runner, policy, d.chooser, planner, d.router2, executors, selfCatalog, d.peerCatalogs)
        }

        /** The public catalog (§8.1): hidden and private tables left out, private documents left out. */
        fun catalogOf(d: Deps): Catalog {
            val s = d.schema.schema()
            val privDocs = d.privateDocs()
            val c = Catalog.of(d.router.nodeId, d.selfName, s, d.hidden() + d.privateTables())
            if (privDocs.isEmpty()) return c
            val names = s.docs.filter { it.id in privDocs }.map { it.name }.toSet()
            return c.copy(docs = c.docs.filter { it !in names })
        }

        private fun runnerOps(ex: List<OpExecutor>): String = ex.joinToString(", ") { e -> e.javaClass.simpleName + e.ops.map { it.name }.sorted() }

        /** sha256 over this phone's data sources, documents and confidentiality flags (memo keys change with the data). */
        fun dataVersion(db: SqlDb): String? {
            val md = MessageDigest.getInstance("SHA-256")
            var any = false
            for (sql in listOf(
                "SELECT id, tbl, fileName, rows, importedAt FROM _sources ORDER BY id",
                "SELECT id, hash, confidential FROM _docs ORDER BY id",
                "SELECT tbl, name, confidential, identifier FROM _columns ORDER BY tbl, name",
            )) {
                val rows = try { db.query(sql) } catch (e: Exception) { continue }
                any = true
                for (r in rows) md.update((r.values.joinToString("\u0001") + "\n").toByteArray())
                md.update("|".toByteArray())
            }
            if (!any) return null
            return md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}

/**
 * The standard executor set (checklist item 1). Every seam is nullable: a phone without the model or runtime simply
 * does not register the op, so the scheduler never sends it there (a missing executor fails the task cleanly).
 */
object SwarmExecutors {
    class Seams(
        val pool: ModelPool? = null,
        val registry: ModelRegistry? = null,
        /** LiteRT QNN (vision, text embeddings) */
        val tensorRuntime: ModelRuntime? = null,
        /** ONNX Runtime QNN (Whisper, SD 1.5) */
        val ortRuntime: ModelRuntime? = null,
        val codec: ImageCodec? = null,
        val outDir: File,
        val vlmAsk: (suspend (String, String, List<String>) -> Decision<String>)? = null,
        val ocr: Ocr? = null,
        val audioDecoder: AudioDecoder? = null,
        val frames: FrameSource? = null,
        val imageGenerator: ImageGenerator? = null,
        val ask: (suspend (String) -> AskOutcome)? = null,
        val bench: Bench? = null,
        val clipModel: () -> String? = { registry?.installed()?.firstOrNull { it.task == "embed_image_text" }?.id },
    )

    fun build(env: SwarmGraph.ExecutorEnv, s: Seams): List<OpExecutor> {
        val out = ArrayList<OpExecutor>()
        val vision = if (s.pool != null && s.tensorRuntime != null && s.codec != null)
            VisionExecutor(s.pool, s.tensorRuntime, s.codec, File(s.outDir, "vision"), s.registry) else null
        vision?.let { out += it }
        s.vlmAsk?.let { out += VlmAskExecutor(it) }
        out += TextExecutor(env.llm, env.chooser, s.pool, s.tensorRuntime, s.registry)
        if (s.pool != null && s.ortRuntime != null && s.registry != null)
            out += MediaExecutor.create(s.pool, s.ortRuntime, s.registry, s.audioDecoder, s.frames, File(s.outDir, "media"))
        if (s.imageGenerator != null) {
            val clip = if (vision != null) clipScorer(vision, s.clipModel) else null
            out += GenExecutor(s.imageGenerator, File(s.outDir, "gen"), s.codec,
                postCheck = if (vision != null) clipSafety(vision, s.clipModel, File(s.outDir, "gen_check")) else null,
                clip = clip, superRes = vision?.let { superRes(it) })
        }
        out += DevAiExecutor(ask = s.ask, bench = s.bench)
        return out
    }

    /** SCORE_IMAGE's CLIP similarity: vision TAG with the prompt as the only vocabulary entry, cosine mode. */
    fun clipScorer(vision: OpExecutor, model: () -> String?): ClipScorer = ClipScorer { image, prompt ->
        val id = model() ?: throw IllegalStateException("no CLIP model installed")
        val t = TaskSpec("clip", Op.TAG, buildJsonObject {
            putJsonArray("vocabulary") { add(JsonPrimitive(prompt)) }; put("template", "{}"); put("mode", "cosine"); put("threshold", -2.0)
        }, modelId = id)
        val o = vision.execute(t, emptyList(), listOf(image)).getOrThrow()
        o.jsonObject["items"]!!.jsonArray[0].jsonObject["tags"]!!.jsonArray[0].jsonObject["score"]!!.jsonPrimitive.doubleOrNull ?: 0.0
    }

    /** Labels the post-check votes over (CLIP zero-shot); the first is the safe reading. */
    val SAFETY_LABELS = listOf("an ordinary safe photo", "explicit nudity", "graphic violence and gore")

    /**
     * Generated-image post-check: CLIP zero-shot (softmax over [SAFETY_LABELS]); safe when the safe label wins. No CLIP
     * model installed → null (the generator's prompt blocklist still applies; the metadata records "postCheck: none").
     */
    fun clipSafety(vision: OpExecutor, model: () -> String?, tmp: File): ImageSafetyCheck? = ImageSafetyCheck { png ->
        val id = model() ?: return@ImageSafetyCheck true
        tmp.mkdirs()
        val f = File(tmp, "check_${System.nanoTime()}.png").apply { writeBytes(png) }
        try {
            val t = TaskSpec("safety", Op.TAG, buildJsonObject {
                putJsonArray("vocabulary") { SAFETY_LABELS.forEach { add(JsonPrimitive(it)) } }; put("template", "{}"); put("threshold", 0.0)
            }, modelId = id)
            val tags = vision.execute(t, emptyList(), listOf(f)).getOrThrow().jsonObject["items"]!!.jsonArray[0].jsonObject["tags"]!!.jsonArray
            (tags.firstOrNull()?.jsonObject?.get("tag")?.jsonPrimitive?.content ?: SAFETY_LABELS[0]) == SAFETY_LABELS[0]
        } finally { f.delete() }
    }

    /** UPSCALE → vision SUPERRES (tiled). */
    fun superRes(vision: OpExecutor): SuperRes = SuperRes { task, blobs -> vision.execute(task, emptyList(), blobs) }

    /**
     * VLM_ASK as a Jev-style closed choice: [k] runs with rotated option orders (greedy decoding), each answer matched to
     * an option; the value is the majority option when more than half the runs agree, else null (refuse, R6).
     */
    suspend fun vlmVote(question: String, options: List<String>, k: Int = 3,
                        run: suspend (List<String>) -> String?): Decision<String> {
        val votes = IntArray(options.size)
        val raw = ArrayList<String>()
        for (i in 0 until k) {
            val order = options.indices.map { options[(it + i) % options.size] }
            val choice = run(order)
            raw += choice ?: ""
            val idx = options.indexOf(choice)
            if (idx >= 0) votes[idx]++
        }
        val best = votes.indices.maxByOrNull { votes[it] } ?: return Decision(null, 0.0, options, 0, k, raw, votes.toList())
        val agreeing = votes[best]
        val unique = votes.count { it == agreeing } == 1
        val value = if (unique && agreeing * 2 > k) options[best] else null
        return Decision(value, agreeing.toDouble() / k, options, agreeing, k, raw, votes.toList())
    }
}

/** EVAL items for the on-phone sweep: the two question sets as EvalCase JSON. */
object EvalSets {
    fun cases(q50: String?, composite: String?): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        q50?.let { out += com.hivecore.node.devai.EvalCases.parse(it, "q50").map(com.hivecore.node.devai.EvalCases::toJson) }
        composite?.let { out += com.hivecore.node.devai.EvalCases.parse(it, "comp").map(com.hivecore.node.devai.EvalCases::toJson) }
        return out
    }
}
