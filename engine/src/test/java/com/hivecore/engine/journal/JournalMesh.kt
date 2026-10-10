package com.hivecore.engine.journal

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.engine.SwarmNode
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.mesh.HelloBody
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MeshRouterAdapter
import com.hivecore.mesh.MeshRouterPort
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.Verified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Phones on a [FakeBus] with real MeshRouters for the state and recovery tests: each has a signer, an in-memory
 * database with its PeerStore, chunk memo and (on the orchestrator) journal, a [MemoizingTaskRunner] over a
 * TaskRunnerImpl with one counting OCR executor, and a [SwarmNode] so a TASK is answered the way a phone answers it.
 * Clocks are virtual (the test scheduler's time).
 */
class JournalMesh(private val testScope: TestScope) {
    val base = 1_700_000_000_000L
    val now: () -> Long = { base + testScope.testScheduler.currentTime }
    val bus = FakeBus()
    val nodes = LinkedHashMap<String, Node>()
    private val scopes = ArrayList<CoroutineScope>()

    fun scope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + SupervisorJob()).also { scopes += it }
    fun close() { scopes.forEach { it.cancel() } }

    class CountingOcr : OpExecutor {
        override val ops = setOf(Op.OCR)
        var runs = 0
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            runs++; return Result.success(JsonPrimitive("computed:${task.id}"))
        }
    }

    inner class Node(val id: String, val name: String) {
        val scope = scope()
        val signer = FakeSigner()
        val db = JdbcSqlDb.inMemory()
        val peerStore = PeerStore(db)
        val transport = bus.node(id, name)
        val router = MeshRouter(mapOf("fake" to transport), signer, peerStore, { HelloBody("", name, "") }, scope, now = now)
        val port: MeshRouterPort = MeshRouterAdapter(router)
        val nodeId: String get() = router.nodeId
        val mesh: JournalMesh get() = this@JournalMesh
        val memo = SqliteChunkMemo(db, now)
        val journal = SqliteJobJournal(db)
        val executor = CountingOcr()
        val runner = MemoizingTaskRunner(TaskRunnerImpl(router.nodeId).also { it.register(executor) }, memo)
        val swarm = SwarmNode(port, scope, runner)
        val responder = ReclaimResponder(port, memo)
        val coordinator = ResumeCoordinator(port, memo, journal, now)
        val received = ArrayList<Verified>()

        init { scope.launch { router.verified.collect { received += it } } }

        fun pin(o: Node) = peerStore.pin(o.nodeId, o.name, o.signer.publicKeyB64, null, emptyList(), now())
    }

    fun node(id: String, name: String = id): Node = Node(id, name).also { nodes[id] = it }

    /** Pins everyone, starts the swarm nodes, responders and coordinators, then the routers. */
    suspend fun start() {
        val all = nodes.values.toList()
        for (a in all) for (b in all) if (a !== b) a.pin(b)
        testScope.testScheduler.runCurrent()
        for (n in all) { n.swarm.start(periodicProfiles = false); n.responder.start(n.scope); n.coordinator.start(n.scope) }
        testScope.testScheduler.runCurrent()
        for (n in all) n.router.start()
        testScope.testScheduler.runCurrent()
    }
}
