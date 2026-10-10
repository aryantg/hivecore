package com.hivecore.node

import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.SplitPlan
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.split.Splitters
import com.hivecore.engine.split.ChunkStatus
import com.hivecore.engine.split.WorkQueue
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.Recipe
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkloadSpec
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import com.hivecore.core.Canon
import com.hivecore.node.SwarmHarness
import com.hivecore.node.textai.TextExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The splitting engine on a 3-phone fake mesh (SwarmHarness: real routers, SwarmNodes, TaskRunners, profiles). */
class SplitMeshTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    /** Deterministic EMBED stand-in: one hash per text, [msPerItem] of (virtual) work per item. */
    class HashEmbed(var msPerItem: Long) : OpExecutor {
        override val ops = setOf(Op.EMBED)
        var chunks = 0
        var items = 0
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            val texts = (task.params["texts"] as JsonArray).map { it.jsonObject }
            delay(msPerItem * texts.size)
            chunks++; items += texts.size
            return Result.success(buildJsonObject {
                put("op", "EMBED"); put("model", "hash")
                putJsonArray("items") { texts.forEach { t -> add(buildJsonObject {
                    put("id", t["id"]!!); put("sha256", Canon.sha256Hex(t["text"]!!.jsonPrimitive.content))
                }) } }
            })
        }
    }

    private class Mesh(val h: SwarmHarness, val nodes: List<SwarmHarness.Node>, val exec: Map<String, HashEmbed>) {
        val a get() = nodes[0]; val b get() = nodes[1]; val c get() = nodes[2]
    }

    private suspend fun TestScope.mesh(vararg msPerItem: Long): Mesh {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val nodes = listOf(hr, sales, accounts)
        val ex = nodes.mapIndexed { i, n -> n.id to HashEmbed(msPerItem[i]).also { n.runner.register(it) } }.toMap()
        h.start()
        return Mesh(h, nodes, ex)
    }

    private fun texts(n: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", "item number $i") } }
    private fun job(n: Int, chunks: Int? = null, private: Boolean = false, owner: String? = null) =
        SplitJob("emb", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(texts(n), private = private), chunks = chunks, owner = owner)

    private suspend fun TestScope.run(m: Mesh, j: SplitJob): List<SplitProgress> {
        val out = m.a.swarm.runJob(j, useMemo = false).toList()
        testScheduler.runCurrent()
        return out
    }

    /** The unsplit reference: one task over every item on one executor. */
    private suspend fun reference(j: SplitJob): String {
        val whole = Splitters.whole(j.opKey, j.params, j.input)
        val out = HashEmbed(0).execute(TaskSpec("w", j.op, JsonObject(j.params + whole.params)), emptyList(), emptyList()).getOrThrow()
        return Canon.hash(out)
    }

    private fun eligible(n: SwarmHarness.Node, on: Boolean) = n.policy.set(n.policy.profile.value.copy(eligible = on, ts = n.policy.profile.value.ts + 1))

    @Test fun fastPhoneTakesMoreChunksAndTheResultEqualsTheUnsplitRun() = runTest {
        val m = mesh(10, 100, 100)
        val j = job(60, chunks = 15)
        val p = run(m, j).last()
        assertNull(p.error)
        assertTrue(p.done)
        assertEquals(reference(j), p.resultHash)
        val fast = m.exec.getValue("hr").chunks; val b = m.exec.getValue("sales").chunks; val c = m.exec.getValue("accounts").chunks
        assertEquals(15, fast + b + c)
        assertTrue("fast $fast vs $b/$c", fast > b && fast > c)
        assertTrue(p.chunks.all { it.status == ChunkStatus.DONE })
        assertEquals(fast, p.nodes.single { it.node == m.a.nodeId }.chunks)
        assertTrue(p.bytesMoved > 0)
        assertTrue("speed-up ${p.speedup}", p.speedup > 1.0)
        // the TASKs went to the other phones as ordinary TASK messages
        assertTrue(m.b.got(MsgType.TASK).isNotEmpty())
    }

    @Test fun stragglerIsDuplicatedAndTheFirstResultWins() = runTest {
        val m = mesh(10, 10, 700)
        val j = job(48, chunks = 12)
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue("duplicates ${p.duplicates}", p.duplicates >= 1)
        assertTrue(p.chunks.any { it.duplicated })
        assertTrue("elapsed ${p.elapsedMs}", p.elapsedMs < 2 * 4 * 700)   // did not wait for the slow phone's queue
        assertEquals(0, p.mismatches)
    }

    @Test fun killedPhoneLosesOnlyItsInFlightChunks() = runTest {
        val m = mesh(100, 100, 100)
        val j = job(60, chunks = 15)
        val d = async { m.a.swarm.runJob(j, useMemo = false).toList() }
        testScheduler.advanceTimeBy(450)
        testScheduler.runCurrent()
        val before = m.exec.getValue("accounts").chunks
        m.h.bus.setOffline("accounts", true)
        val p = d.await().last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue(p.notes.toString(), p.reassigned + p.duplicates >= 1)   // re-queued at its deadline, or rescued earlier as a straggler
        assertTrue(p.chunks.any { it.attempts >= 2 })
        assertTrue("accounts ran $before before it died", before >= 1)
        assertEquals(15, p.chunks.count { it.status == ChunkStatus.DONE })
    }

    @Test fun privateItemsStayOnTheOwner() = runTest {
        val m = mesh(10, 10, 10)
        val p = run(m, job(20, chunks = 6, private = true)).last()
        assertTrue(p.done)
        assertEquals(0, m.exec.getValue("sales").chunks + m.exec.getValue("accounts").chunks)
        assertTrue(m.b.got(MsgType.TASK).isEmpty() && m.c.got(MsgType.TASK).isEmpty())
        // private data owned by Sales: only Sales runs it, even though HR commands the job
        val q = run(m, job(20, chunks = 6, private = true, owner = m.b.nodeId).copy(jobId = "emb2")).last()
        assertTrue(q.error, q.done)
        assertEquals(q.chunks.size, m.exec.getValue("sales").chunks)
        assertEquals(0, m.exec.getValue("accounts").chunks)
        assertEquals(p.resultHash, q.resultHash)
        assertTrue(m.c.got(MsgType.TASK).isEmpty())
    }

    @Test fun sameJobOnOneTwoAndThreePhonesGivesTheSameHash() = runTest {
        val m = mesh(30, 50, 70)
        val j = job(37)
        eligible(m.b, false); eligible(m.c, false); testScheduler.runCurrent()
        val one = run(m, j).last()
        eligible(m.b, true); testScheduler.runCurrent()
        val two = run(m, j).last()
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j).last()
        assertEquals(reference(j), one.resultHash)
        assertEquals(one.resultHash, two.resultHash)
        assertEquals(one.resultHash, three.resultHash)
        assertEquals(setOf(m.a.nodeId), one.nodes.filter { it.chunks > 0 }.map { it.node }.toSet())
        assertEquals(3, three.nodes.count { it.chunks > 0 })
        assertTrue(one.chunks.size < three.chunks.size)                    // ≥ 2 chunks per eligible phone
    }

    @Test fun treeSummaryIsIdenticalOnTwoAndThreePhones() = runTest {
        val m = mesh(1, 1, 1)
        m.nodes.forEach { it.runner.register(TextExecutor(null, null, null, null)) }
        val text = (1..120).joinToString(" ") { "Clause $it fixes the fee at ${it * 7} rupees for site ${it % 5}." }
        val j = SplitJob("sum", Op.SUMMARIZE, buildJsonObject { put("sentences", 5) }, input = SplitInput.Text(text, "contract"))
        eligible(m.c, false); testScheduler.runCurrent()
        val two = run(m, j).last()
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j).last()
        assertTrue(two.error, two.done)
        assertEquals(3, two.chunks.size)
        assertEquals(two.resultHash, three.resultHash)
        assertEquals(5, three.result!!.jsonObject["picks"]!!.jsonArray.size)
    }

    @Test fun workloadRunsEachRecipeStageAsASplitJob() = runTest {
        val m = mesh(10, 10, 10)
        // each recipe stage becomes one split job over the workload's items; `release` decides private mode
        val recipe = Recipe("r", "1", "test", listOf(TaskSpec("s1", Op.IQA, JsonObject(emptyMap()))), emptyList())
        m.a.swarm.recipes = { id, v -> recipe.takeIf { id == "r" && v == "1" } }
        val missing = m.a.swarm.runWorkload(WorkloadSpec("w0", "nope", "1", m.a.nodeId, listOf("x"))).toList().last()
        assertTrue(missing.error!!.contains("not installed"))
        val jobs = SplitJob.forWorkload(WorkloadSpec("w1", "r", "1", m.a.nodeId, listOf("a", "b", "c"), release = "items"), recipe)
        assertEquals(listOf("w1/s1"), jobs.map { it.jobId })
        assertEquals(listOf(false, false, false), (jobs.single().input as SplitInput.Blobs).private)
        val priv = SplitJob.forWorkload(WorkloadSpec("w2", "r", "1", m.b.nodeId, listOf("a")), recipe).single()
        assertEquals(m.b.nodeId, priv.owner)
        assertEquals(listOf(true), (priv.input as SplitInput.Blobs).private)
    }

    @Test fun swarmNodeIsTheCoreJobRunner() = runTest {
        val m = mesh(10, 100, 100)
        val runner: com.hivecore.core.JobRunner = m.a.swarm
        val input = Canon.json.encodeToJsonElement(SplitInput.serializer(), SplitInput.Items(texts(24)))
        val req = com.hivecore.core.JobRequest("Embed 24 notes", op = Op.EMBED,
            params = buildJsonObject { put("input", input); put("modelId", "hash") })
        val views = runner.run(req).toList()
        testScheduler.runCurrent()
        val last = views.last()
        assertTrue(last.error, last.done)
        assertEquals("shard", last.axis)
        assertEquals("concat", last.merge)
        assertTrue(last.speedup!! > 0)
        assertNull(views.first().speedup)
        assertTrue(last.chunks.all { it.state == com.hivecore.core.ChunkState.DONE && it.label.startsWith("items ") })
        assertTrue(last.nodes.map { it.name }.containsAll(listOf("HR", "Sales")))
        assertEquals(last, runner.jobs.value.first())
        assertTrue(last.resultSummary!!.contains("concat merge"))
        // a private job never leaves this phone
        val salesBefore = m.exec.getValue("sales").chunks
        val priv = runner.run(req.copy(private = true)).toList().last()
        assertTrue(priv.done)
        assertEquals(salesBefore, m.exec.getValue("sales").chunks)
        assertEquals(last.resultRef, priv.resultRef)
    }

    @Test fun taskBodiesUseTheChunkTaskIds() = runTest {
        val m = mesh(10, 10, 10)
        run(m, job(8, chunks = 4))
        val ids = (m.b.got(MsgType.TASK) + m.c.got(MsgType.TASK)).map { MeshJson.decode<TaskBody>(it.body).task.id }
        assertTrue(ids.toString(), ids.all { WorkQueue.parseTaskId(it) != null })
        assertTrue(ids.isNotEmpty())
        assertEquals(JsonPrimitive("EMBED"), JsonPrimitive(MeshJson.decode<TaskBody>(m.b.got(MsgType.TASK).first().body).task.op.name))
    }
}
