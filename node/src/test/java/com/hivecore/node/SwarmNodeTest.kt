package com.hivecore.node

import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.SplitPlan
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.SwarmNode
import com.hivecore.core.Canon
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import com.hivecore.mesh.TaskSignalBody
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class SwarmNodeTest {
    @get:Rule val tmp = TemporaryFolder()
    private var harness: SwarmHarness? = null
    private fun TestScope.mesh() = SwarmHarness(this, blobRoot = tmp.newFolder("blobs")).also { harness = it }
    @After fun tearDown() { harness?.close() }

    @Test fun blobTravelsInTransportSizedChunksAndIsDeletedAfterCountersign() = runTest {
        val h = mesh()
        val (hr, _, accounts) = h.hrSalesAccounts()
        h.start()
        val bytes = Random(3).nextBytes(100_000)
        val id = accounts.swarm.blobs!!.put(bytes)
        val n = accounts.swarm.sendBlob(hr.nodeId, id, "job1").getOrThrow()
        runCurrent()
        assertEquals((bytes.size + SwarmNode.TRANSPORT_CHUNK - 1) / SwarmNode.TRANSPORT_CHUNK, n)
        assertArrayEquals(bytes, hr.swarm.blobs!!.file(id)!!.readBytes())
        hr.swarm.releaseBlobs(accounts.nodeId, "job1", listOf(id))
        runCurrent()
        assertTrue(!hr.swarm.blobs!!.has(id))
        assertEquals(1, accounts.got(MsgType.BLOB_DELETED).size)
    }

    @Test fun workerModeOffYieldsAndWorkerTasksAreIdempotent() = runTest {
        val h = mesh()
        val hr = h.node("hr", "HR", NodeRole.HR, eligible = false)
        val sales = h.node("sales", "Sales", NodeRole.SALES)
        h.start()
        val task = TaskSpec("x", Op.EXTRACT)
        val body = MeshJson.encode(TaskBody("p1", task, emptyMap(), 4000, "m1", sales.nodeId, h.now(), "n1"))
        sales.router.send(hr.nodeId, MsgType.TASK, body)
        runCurrent()
        assertEquals("worker mode is off", MeshJson.decode<TaskSignalBody>(sales.got(MsgType.TASK_YIELD).single().body).reason)
        assertEquals(0, hr.extract.runs)
        // An opted-in worker runs it once, whatever the duplicates.
        hr.policy.setOptIn(true)
        val again = MeshJson.encode(TaskBody("p2", task, emptyMap(), 4000, "m1", sales.nodeId, h.now(), "n2"))
        hr.swarm.dispatch(MsgType.TASK, sales.nodeId, again)
        hr.swarm.dispatch(MsgType.TASK, sales.nodeId, again)
        runCurrent()
        assertEquals(1, hr.extract.runs)
        val res = sales.got(MsgType.TASK_RESULT).map { MeshJson.decode<TaskResultBody>(it.body) }.single()
        assertEquals(Canon.hash(res.output!!), res.outputHash)
    }
}
