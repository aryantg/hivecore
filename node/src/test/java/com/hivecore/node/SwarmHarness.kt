package com.hivecore.node

import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.SplitPlan
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.AggExecutor
import com.hivecore.engine.Commander
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.MemoizingTaskRunner
import com.hivecore.engine.journal.SqliteChunkMemo
import com.hivecore.engine.journal.SqliteJobJournal
import com.hivecore.core.TaskRunner
import com.hivecore.engine.ComparatorImpl
import com.hivecore.engine.OwnerPlanHandler
import com.hivecore.engine.ProfileBeacon
import com.hivecore.engine.ReducerImpl
import com.hivecore.engine.SchedulerImpl
import com.hivecore.engine.SwarmNode
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.planner.PlanCompilerImpl
import com.hivecore.mesh.PlanConsent
import com.hivecore.mesh.MeshRouterAdapter
import com.hivecore.core.AnswerChip
import com.hivecore.core.ApprovalGate
import com.hivecore.core.ApprovalResult
import com.hivecore.core.AskOutcome
import com.hivecore.core.AskProgress
import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.DifferencingGuard
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.LlmBackend
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.Profile
import com.hivecore.core.RouteDecision
import com.hivecore.core.Signer
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeRelay
import com.hivecore.core.fakes.FakeIntentPlanner
import com.hivecore.core.fakes.FakeLedgerStore
import com.hivecore.core.fakes.FakePlanner
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.core.fakes.FakeTransport
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.node.data.DataStore
import com.hivecore.node.data.RuleIntentCompiler
import com.hivecore.node.data.SqlDifferencingGuard
import com.hivecore.node.data.SqlQueryExecutor
import com.hivecore.mesh.ConsentResBody
import com.hivecore.mesh.HelloBody
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.FakeRelaySockets
import com.hivecore.mesh.RelayClock
import com.hivecore.mesh.RelayTransport
import com.hivecore.mesh.Verified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import com.hivecore.node.data.Fixtures as DataFixtures

/** Plan-scoped approval gate for tests: approve with the approval key, approve node-signed (debug hook), or decline. */
class PlanGate(private val approvalSigner: Signer, private val nodeSigner: Signer, var mode: Mode = Mode.APPROVE) : ApprovalGate {
    enum class Mode { APPROVE, NODE_SIGNED, DECLINE, TIMEOUT }
    data class Asked(val planId: String, val requester: String, val description: String, val planHash: String, val intentHashes: List<String>)
    val asked = mutableListOf<Asked>()
    override val publicKeyB64: String get() = approvalSigner.publicKeyB64
    override suspend fun request(jobId: String, requester: String, description: String, intentHash: String): ApprovalResult = ApprovalResult.Declined
    override suspend fun requestPlan(planId: String, requester: String, description: String, planHash: String, intentHashes: List<String>): ApprovalResult {
        asked += Asked(planId, requester, description, planHash, intentHashes)
        val bytes = PlanConsent.canonicalBytes(planId, planHash, intentHashes)
        return when (mode) {
            Mode.APPROVE -> ApprovalResult.Approved(approvalSigner.sign(bytes), ConsentResBody.SIGNED_BY_APPROVAL)
            Mode.NODE_SIGNED -> ApprovalResult.Approved(nodeSigner.sign(bytes), ConsentResBody.SIGNED_BY_NODE)
            Mode.DECLINE -> ApprovalResult.Declined
            Mode.TIMEOUT -> ApprovalResult.TimedOut
        }
    }
}

/** The orchestrator's gate seam in the harness: tests swap [delegate] (default: cloud off) and read the requests. */
class RoutedEscalator : Escalator {
    var delegate: Escalator = Escalator.Off
    val requests = ArrayList<EscalationRequest>()
    override suspend fun tryEscalate(req: EscalationRequest): GateOutcome { requests += req; return delegate.tryEscalate(req) }
}

/** An EXTRACT-style executor returning a fixed output, counting its runs. */
class ConstExecutor(var output: JsonElement, override val ops: Set<Op> = setOf(Op.EXTRACT)) : OpExecutor {
    var runs = 0
    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> { runs++; return Result.success(output) }
}

/**
 * Three phones (HR, Sales, Accounts) on a [FakeBus] with real MeshRouters, the seed data in real DataStores on
 * JdbcSqlDb, and the real swarm stack from [SwarmNode.build] (Commander, OwnerPlanHandler, AggExecutor,
 * TaskRunnerImpl, ProfileBeacon, PlanCompilerImpl, SchedulerImpl, ReducerImpl, ComparatorImpl). The composite planner
 * is a [FakePlanner] scripted per test; the intent planner is the [FakeIntentPlanner]. Clocks are virtual.
 */
class SwarmHarness(private val testScope: TestScope, private val debugRouters: Boolean = false, private val blobRoot: File? = null,
                   /** phase 7 wiring: a SQLite journal and chunk memo per phone, a MemoizingTaskRunner, the RECLAIM responder and resume driver */
                   private val memoize: Boolean = false,
                   /** phase 6 wiring: every phone also runs a relay transport over this fake relay (always-on beside the LAN fake); a `remote` phone has only the relay */
                   val relay: FakeRelay? = null) {
    val base = 1_700_000_000_000L
    val now: () -> Long = { base + testScope.testScheduler.currentTime }
    val bus = FakeBus()
    val nodes = LinkedHashMap<String, Node>()
    private val scopes = ArrayList<CoroutineScope>()

    companion object { const val COMMISSION_Q = "average commission by region"; const val GROUP = "g1" }

    fun scope(): CoroutineScope =CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + SupervisorJob()).also { scopes += it }
    fun close() { scopes.forEach { it.cancel() } }

    inner class Node(val id: String, val name: String, val role: NodeRole, eligible: Boolean, val guardOverride: DifferencingGuard?,
                     /** a restarted phone: the same key and the same journal / memo database as the phone it replaces */
                     signerOverride: FakeSigner? = null, stateDbOverride: JdbcSqlDb? = null,
                     /** a phone in another city: reached through the relay only (spec 5.1 rank 5, WAN_RELAY) */
                     val remote: Boolean = false) {
        val scope = scope()
        val signer = signerOverride ?: FakeSigner()
        val approvalSigner = FakeSigner()
        val db = JdbcSqlDb.inMemory()
        /** the journal and memo database (shared with the phone this one replaces after a restart) */
        val stateDb: JdbcSqlDb = stateDbOverride ?: db
        val store: DataStore = DataFixtures.store(role, db)
        val intentPlanner = FakeIntentPlanner()
        val gate = PlanGate(approvalSigner, signer)
        val ledger = FakeLedgerStore(signer, now)
        val planner = FakePlanner()
        val received = ArrayList<Verified>()
        val peerStore = PeerStore(db)
        val transport: FakeTransport = bus.node(id, name)
        val relayTransport: RelayTransport? = relay?.let { r ->
            RelayTransport("ws://relay.test/ws", signer, GROUP, { 1L }, scope, FakeRelaySockets(r), clock = RelayClock(now), localClock = now)
        }
        val router = MeshRouter(
            transports = buildMap { if (!remote) put("fake", transport); relayTransport?.let { put(RelayTransport.NAME, it) } },
            signer = signer, peerStore = peerStore,
            localHello = { HelloBody("", name, "", approvalPubkey = approvalSigner.publicKeyB64, caps = listOf("sql", "npu")) },
            scope = scope, now = now, debugAllowNodeSignedApprovals = debugRouters,
            initialTransport = if (remote) RelayTransport.NAME else "fake",
            alwaysOn = if (relayTransport != null && !remote) setOf(RelayTransport.NAME) else emptySet(),
        )
        val nodeId: String get() = router.nodeId
        val policy = FakeWorkerPolicy(Profile(router.nodeId, 0L, backend = LlmBackend.NPU, tokPerSec = 15.0, modelHash = "m1",
            idle = eligible, charging = true, batteryPct = 90, workerOptIn = eligible, eligible = eligible, big = "llm"))
        val guard: DifferencingGuard = guardOverride ?: SqlDifferencingGuard(db, RuleIntentCompiler(), now)
        val memo = SqliteChunkMemo(stateDb, now)
        val journal = SqliteJobJournal(stateDb)
        val escalator = RoutedEscalator()
        val baseRunner = TaskRunnerImpl(router.nodeId)
        val runner: TaskRunner = if (memoize) MemoizingTaskRunner(baseRunner, memo) else baseRunner
        val extract = ConstExecutor(JsonArray(listOf(JsonPrimitive("s1"), JsonPrimitive("s3"))))
        val swarm: SwarmNode = SwarmNode.build(
            router = MeshRouterAdapter(router), selfName = name, scope = scope, schema = store,
            intentPlanner = intentPlanner, compiler = RuleIntentCompiler(), executor = SqlQueryExecutor(db) { AnswerChip.LLM },
            approval = gate, guard = guard, planner = planner, planCompiler = PlanCompilerImpl(), policy = policy, ledger = ledger, runner = runner,
            extraExecutors = listOf(extract), blobDir = blobRoot?.let { File(it, id) },
            nameOf = { nid -> nodes.values.firstOrNull { it.nodeId == nid }?.name ?: nid.take(8) },
            modelHash = { "m1" }, clock = now, escalator = escalator,
            journal = if (memoize) journal else JobJournal.None, memo = if (memoize) memo else ChunkMemo.None,
            linkOf = { nid -> if (relay != null) router.linkTo(nid) else null },     // measured links only with the relay rig; the LAN tests set links in profiles
        )

        init { scope.launch { router.verified.collect { received += it } } }

        fun got(type: MsgType): List<Verified> = received.filter { it.type == type }
        fun pin(o: Node) = peerStore.pin(o.nodeId, o.name, o.signer.publicKeyB64, o.approvalSigner.publicKeyB64, emptyList(), now())
        fun catalog(): Catalog = Catalog.of(nodeId, name, store.schema())
    }

    fun node(id: String, name: String, role: NodeRole, eligible: Boolean = true, guard: DifferencingGuard? = null, remote: Boolean = false): Node =
        Node(id, name, role, eligible, guard, remote = remote).also { nodes[id] = it }

    /** The phone [old] after a force-stop and relaunch: same key, same journal and memo files, a fresh process (stack, scope, router). */
    fun restart(old: Node): Node {
        old.swarm.stop()
        old.scope.cancel()
        nodes.remove(old.id)
        return Node(old.id, old.name, old.role, true, old.guardOverride, signerOverride = old.signer, stateDbOverride = old.stateDb, remote = old.remote).also { nodes[old.id] = it }
    }

    /**
     * The boot steps of a phone that [restart]ed while the others kept running: [setup] re-registers its executors,
     * then it pins the others (and they pin it), starts the stack and the router, and announces its PROFILE.
     */
    suspend fun relaunch(n: Node, setup: (Node) -> Unit = {}): Node {
        setup(n)
        for (o in nodes.values) if (o !== n) { n.pin(o); o.pin(n) }
        n.swarm.start(periodicProfiles = false)
        testScope.testScheduler.runCurrent()
        n.router.start()
        testScope.testScheduler.runCurrent()
        n.swarm.beacon!!.announce()
        testScope.testScheduler.runCurrent()
        return n
    }

    fun hrSalesAccounts(eligible: Boolean = true): Triple<Node, Node, Node> =
        Triple(node("hr", "HR", NodeRole.HR, eligible), node("sales", "Sales", NodeRole.SALES, eligible), node("accounts", "Accounts", NodeRole.ACCOUNTS, eligible))

    /** Pins everyone, starts the swarm nodes (except [skip]) and routers, then exchanges PROFILEs. */
    suspend fun start(vararg skip: Node) {
        val all = nodes.values.toList()
        for (a in all) for (b in all) if (a !== b) a.pin(b)
        relay?.roster(GROUP, 1, all.first().nodeId, all.map { FakeRelay.Member(it.nodeId, it.signer.publicKeyB64, it.name) })
        testScope.testScheduler.runCurrent()
        for (n in all) if (n !in skip) n.swarm.start(periodicProfiles = false)
        testScope.testScheduler.runCurrent()
        for (n in all) n.router.start()
        testScope.testScheduler.runCurrent()
        for (n in all) if (n !in skip) n.swarm.beacon!!.announce()
        testScope.testScheduler.runCurrent()
    }

    fun catalogs(): List<Catalog> = nodes.values.map { it.catalog() }

    class Asked(val outcome: AskOutcome, val statuses: List<String>) {
        val answer: AskOutcome.Answer get() = outcome as AskOutcome.Answer
        val refusal: AskOutcome.Refusal get() = outcome as AskOutcome.Refusal
    }

    suspend fun ask(from: Node, question: String, dag: Dag, confidence: Double = 1.0, cloudSteps: Set<String> = emptySet()): Asked {
        from.planner.result = Result.success(PlannedQuestion(dag, confidence))
        val owners = dag.tasks.mapNotNull { it.owner }.distinct()
        val progress = from.swarm.askComposite(question, RouteDecision(true, owners, emptyList(), "test"), catalogs(), cloudSteps = cloudSteps).toList()
        testScope.testScheduler.runCurrent()
        return Asked(progress.filterIsInstance<AskProgress.Done>().single().outcome, progress.filterIsInstance<AskProgress.Status>().map { it.text })
    }

    // ------------------------------------------------------------------ DAGs

    fun agg(id: String, owner: Node, q: String, dims: List<String> = emptyList()) = TaskSpec(id, Op.AGG, buildJsonObject {
        put("q", q)
        if (dims.isNotEmpty()) put("expect", buildJsonObject { put("dims", JsonArray(dims.map { JsonPrimitive(it) })) })
    }, owner = owner.nodeId)

    /** per_unit: "Revenue per rep by region this year" = Sales AGG(revenue by region) / HR AGG(reps by region). */
    fun perUnit(sales: Node, hr: Node) = Dag("per_unit", "p1", listOf(
        agg("a", sales, "revenue by region this year", listOf("region")),
        agg("b", hr, "reps by region", listOf("region")),
        TaskSpec("r", Op.RATIO, buildJsonObject { put("key", "region"); put("num", "sum_revenue"); put("den", "n") }, inputs = listOf("a", "b")),
    ), output = "r")

    /**
     * A per_unit plan whose HR figure is confidential (average commission by region), so HR must consent:
     * revenue by region (Sales) per average commission by region (HR).
     */
    fun consentDag(sales: Node, hr: Node): Dag {
        hr.intentPlanner.canned[COMMISSION_Q] = com.hivecore.core.IntentSet(listOf(
            com.hivecore.core.Intent("employees", "avg", "commission", groupBy = listOf("region"))))
        return Dag("per_unit", "p1", listOf(
            agg("a", sales, "revenue by region this year", listOf("region")),
            agg("b", hr, COMMISSION_Q, listOf("region")),
            TaskSpec("r", Op.RATIO, buildJsonObject { put("key", "region"); put("num", "sum_revenue"); put("den", "avg_commission") }, inputs = listOf("a", "b")),
        ), output = "r")
    }

    fun extractOnly() = Dag("multi_doc_extract", "p1", listOf(
        TaskSpec("x", Op.EXTRACT, buildJsonObject { put("q", "key points"); put("sentences", JsonArray((1..5).map { JsonPrimitive("s$it") })) }),
    ), output = "x")
}
