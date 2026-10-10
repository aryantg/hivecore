package com.hivecore.node

import com.hivecore.core.ModelEntry
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkerActivityLog
import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.core.WorkerEventKind.CHUNK_RECEIVED
import com.hivecore.core.WorkerEventKind.CHUNK_RUNNING
import com.hivecore.core.WorkerEventKind.MODEL_LOADING
import com.hivecore.core.WorkerEventKind.MODEL_READY
import com.hivecore.core.WorkerEventKind.MODEL_SELECTED
import com.hivecore.core.WorkerEventKind.PLAN_RECEIVED
import com.hivecore.core.WorkerEventKind.RESULT_SENT
import com.hivecore.engine.placement.ModelReqHandler
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.fakes.FakeCapabilityPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The worker timeline on a 3-phone fake mesh: each worker's own events, and the orchestrator's TASK_PROGRESS mirror. */
class WorkerActivityMeshTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    private val embed = ModelEntry("hash", "embed", "ort", "", 1_000, 300, 5, 0, false)

    private fun texts(n: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", "item number $i") } }

    @Test fun workersReportModelBootChunkAndReturnAndTheOrchestratorMirrorsThem() = runTest {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val nodes = listOf(hr, sales, accounts)
        nodes.forEach { it.runner.register(SplitMeshTest.HashEmbed(10)) }
        val logs = nodes.associate { n -> n.id to WorkerActivityLog().also { n.swarm.attachActivity(it) } }
        val catalog = ModelCatalog(listOf(embed))
        val pools = HashMap<String, FakeCapabilityPool>()
        for (n in listOf(sales, accounts)) {
            val pool = FakeCapabilityPool(defaultLoadMs = 240).also { pools[n.id] = it }
            n.swarm.onModelReq = ModelReqHandler(n.nodeId, { catalog }, pool, n.scope, activity = logs.getValue(n.id), clock = h.now)::handle
        }
        // placement: MODEL_REQ to both workers, then the profile view decides (as with no ready set)
        hr.swarm.placeFor = { job, planId ->
            for (n in listOf(sales, accounts))
                hr.swarm.sendTo(n.nodeId, MsgType.MODEL_REQ, MeshJson.encode(ModelReqBody(planId, "embed", job.modelId!!, "", 10_000, hr.nodeId, h.now(), "n-${n.id}")))
            delay(200)
            null
        }
        h.start()
        val mirrored = ArrayList<WorkerEvent>()
        val watch = h.scope().launch { logs.getValue("hr").peers.collect { m -> mirrored += m.values } }

        val job = SplitJob("emb", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(texts(40)), chunks = 8)
        val p = hr.swarm.runJob(job).toList().last()
        testScheduler.runCurrent()
        assertTrue(p.error, p.done)

        for (w in listOf(sales, accounts)) {
            val evs = logs.getValue(w.id).local.value
            val kinds = evs.map { it.kind }
            // the model the task type needs is picked and booted (placement, before any chunk), then the chunks
            assertInOrder(kinds, MODEL_SELECTED, MODEL_LOADING, MODEL_READY, PLAN_RECEIVED, CHUNK_RECEIVED, CHUNK_RUNNING, RESULT_SENT)
            val sel = evs.first { it.kind == MODEL_SELECTED && it.taskId == null }
            assertEquals("embed", sel.capability); assertEquals("hash", sel.modelId); assertEquals("requested", sel.reason)
            val ready = evs.first { it.kind == MODEL_READY }
            assertEquals(false, ready.warm); assertEquals(240L, ready.loadMs)
            val got = evs.filter { it.kind == CHUNK_RECEIVED }
            assertTrue("${w.id} got no chunk", got.isNotEmpty())
            got.forEach { c ->
                assertNotNull(c.chunkIndex); assertEquals(8, c.chunkCount)
                assertTrue("label of ${c.taskId}", !c.label.isNullOrBlank())
                assertEquals(hr.nodeId, c.commander); assertEquals(w.nodeId, c.node)
            }
            assertEquals(got.size, evs.count { it.kind == RESULT_SENT })
            // the chunk-time choice sees the placed model warm
            assertTrue(evs.filter { it.kind == MODEL_SELECTED && it.taskId != null }.all { it.reason == "requested" })
        }
        // every worker event went to the orchestrator as TASK_PROGRESS, the model boot (during placement) included
        for (w in listOf(sales, accounts)) {
            val sent = hr.got(MsgType.TASK_PROGRESS).filter { it.from == w.nodeId }.map { MeshJson.decode<WorkerEvent>(it.body).kind }
            assertInOrder(sent, MODEL_READY, CHUNK_RECEIVED, RESULT_SENT)
        }
        // and its lanes saw each phone live, its own chunks included (the flow conflates, so not every event)
        for (n in nodes) assertTrue("no mirror of ${n.id}", mirrored.any { it.node == n.nodeId })
        // and drops them once the job is over
        assertTrue(logs.getValue("hr").peers.value.isEmpty())
        // the orchestrator's own chunks never leave as TASK_PROGRESS
        assertTrue(nodes.flatMap { n -> n.got(MsgType.TASK_PROGRESS) }.none { MeshJson.decode<WorkerEvent>(it.body).commander == it.from })
        watch.cancel()
        h.close()   // inside runTest: the beacons and relay pingers never go idle
    }

    @Test fun anOlderOrchestratorsTaskStillShowsItsChunkAndStrayProgressIsDropped() = runTest {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val logs = listOf(hr, sales, accounts).associate { n -> n.id to WorkerActivityLog().also { n.swarm.attachActivity(it) } }
        h.start()
        // a TASK without the chunk fields (pre-timeline orchestrator): the index comes from the task id
        val body = MeshJson.encode(TaskBody("job9#1", TaskSpec("c3.a1", Op.EXTRACT), emptyMap(), 4000, "m1", hr.nodeId, h.now(), "n1"))
        sales.swarm.dispatch(MsgType.TASK, hr.nodeId, body)
        testScheduler.runCurrent()
        val got = logs.getValue("sales").local.value.first { it.kind == CHUNK_RECEIVED }
        assertEquals(3, got.chunkIndex); assertNull(got.chunkCount); assertNull(got.label)
        assertTrue(logs.getValue("sales").local.value.any { it.kind == RESULT_SENT })
        // EXTRACT names no model on this task: the timeline says the app's own choice
        val sel = logs.getValue("sales").local.value.first { it.kind == MODEL_SELECTED }
        assertEquals("llm.json", sel.capability); assertEquals("built-in", sel.reason)
        // hr runs no such job: the progress the worker sent is not mirrored
        assertTrue(hr.got(MsgType.TASK_PROGRESS).isNotEmpty())
        assertTrue(logs.getValue("hr").peers.value.isEmpty())
        // a peer cannot speak for another phone
        val forged = MeshJson.encode(WorkerEvent("job9#1", WorkerEventKind.RESULT_SENT, sales.nodeId, hr.nodeId, h.now()))
        hr.swarm.dispatch(MsgType.TASK_PROGRESS, accounts.nodeId, forged)
        assertTrue(logs.getValue("hr").peers.value.isEmpty())
        h.close()
    }

    private fun assertInOrder(kinds: List<WorkerEventKind>, vararg want: WorkerEventKind) {
        var at = 0
        for (k in want) {
            val i = kinds.subList(at, kinds.size).indexOf(k)
            assertTrue("$k missing after position $at in $kinds", i >= 0)
            at += i + 1
        }
    }
}
