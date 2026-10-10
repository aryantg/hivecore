package com.hivecore.node

import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.engine.journal.AttemptOutcome
import com.hivecore.engine.journal.AttemptRow
import com.hivecore.engine.journal.ChunkRow
import com.hivecore.engine.journal.ChunkStates
import com.hivecore.engine.journal.JobRow
import com.hivecore.engine.journal.JobState
import com.hivecore.engine.split.ChunkPlanner
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.Splitters
import com.hivecore.mesh.MsgType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 13.3 through `SwarmNode` on the 3-phone fake swarm with the phase 7 wiring (journal, memo, RECLAIM responder
 * and resume coordinator built by `SwarmNode.build`): the responder and coordinator see their messages while the
 * node runs, and a force-stopped orchestrator resumes with zero re-runs of finished chunks.
 */
class ResumeMeshTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    /** Deterministic EMBED stand-in with [msPerItem] of virtual work per item; counts finished runs. */
    class HashEmbed(private val msPerItem: Long) : OpExecutor {
        override val ops = setOf(Op.EMBED)
        var runs = 0
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            val texts = (task.params["texts"] as JsonArray).map { it.jsonObject }
            delay(msPerItem * texts.size)
            runs++
            return Result.success(buildJsonObject {
                put("op", "EMBED"); put("model", "hash")
                putJsonArray("items") { texts.forEach { t -> add(buildJsonObject { put("id", t["id"]!!); put("sha256", Canon.sha256Hex(t["text"]!!.jsonPrimitive.content)) }) } }
            })
        }
    }

    private fun texts(n: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", "item number $i") } }
    private fun job(n: Int, chunks: Int) = SplitJob("emb", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(texts(n)), chunks = chunks)

    private suspend fun reference(j: SplitJob): String {
        val whole = Splitters.whole(j.opKey, j.params, j.input)
        return Canon.hash(HashEmbed(0).execute(TaskSpec("w", j.op, JsonObject(j.params + whole.params)), emptyList(), emptyList()).getOrThrow())
    }

    private fun TestScope.mesh(): Triple<SwarmHarness, List<SwarmHarness.Node>, Map<String, HashEmbed>> {
        val h = SwarmHarness(this, memoize = true).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val nodes = listOf(hr, sales, accounts)
        val ex = nodes.associate { n -> n.id to HashEmbed(10).also { n.runner.register(it) } }
        return Triple(h, nodes, ex)
    }

    @Test fun reclaimMessagesReachTheResponderAndTheCoordinatorWhileTheNodeRuns() = runTest {
        val (h, nodes, _) = mesh()
        val (a, b, c) = nodes
        h.start()
        val t0 = h.now()
        val out = buildJsonObject { put("text", "one") }
        a.journal.begin(JobRow("j1", "j1#1", "ph", "T1", JobState.RUNNING, t0, t0, "public",
            capsSnapshot = buildJsonObject { put("eligible", buildJsonArray { add(JsonPrimitive(b.nodeId)); add(JsonPrimitive(c.nodeId)) }) },
            spec = buildJsonObject { put("op", "EMBED") }))
        a.journal.chunk(ChunkRow("j1", "c0", 0, buildJsonObject { put("i", 0) }, null, ChunkStates.RUNNING, 1))
        a.journal.attempt(AttemptRow("j1", "c0", 1, b.nodeId, t0, t0 + 4000, AttemptOutcome.SENT))
        b.memo.put("c0", Canon.hash(out), out)

        val plan = a.swarm.resumeCoordinator!!.resume(a.journal.load("j1")!!)
        advanceUntilIdle()

        assertEquals(setOf(b.nodeId, c.nodeId), plan.respondents)                           // both RECLAIM_RES reached the coordinator
        assertEquals(1, b.swarm.reclaim!!.answered)                                         // the RECLAIM reached B's responder
        assertEquals(1, c.swarm.reclaim!!.answered)
        assertEquals(mapOf("c0" to out), plan.prefilled)                                     // fetched from B's memo with a memo-only TASK
        assertEquals(1, b.got(MsgType.RECLAIM).size)
        assertEquals(1, a.got(MsgType.RECLAIM_RES).count { it.from == b.nodeId })
        assertTrue(plan.missing.isEmpty())
    }

    @Test fun forceStoppedOrchestratorResumesWithZeroRerunsOfFinishedChunks() = runTest {
        val (h, nodes, ex) = mesh()
        val (a, b, c) = nodes
        h.start()
        val j = job(60, chunks = 12)
        val chunkIds = ChunkPlanner().plan(j, emptyList(), a.nodeId).getOrThrow().chunks.map { it.chunkId }
        val running = a.scope.launch { a.swarm.runJob(j).collect { } }
        advanceTimeBy(130)                                                                   // a few chunks done, several in flight
        assertTrue(running.isActive)
        val finishedBefore = a.journal.load(j.jobId)!!.chunks.count { it.state == ChunkStates.DONE }
        assertTrue("some chunks should be done before the kill ($finishedBefore)", finishedBefore in 1..11)

        // The orchestrator app is force-stopped: its process (scope, router, in-flight chunk) dies; the journal and memo files stay.
        val a2 = h.restart(a)
        advanceUntilIdle()                                                                   // B and C finish and memoize their in-flight chunks
        val runsBefore = ex.values.sumOf { it.runs }
        val held = (a2.journal.load(j.jobId)!!.outputs.map { it.chunkId } + b.memo.holding(chunkIds).map { it.chunkId } + c.memo.holding(chunkIds).map { it.chunkId }).toSet()
        assertTrue(held.size in 1 until chunkIds.size)

        // Relaunch: pin, start the stack, exchange profiles.
        val a2ex = HashEmbed(10).also { a2.runner.register(it) }
        for (o in listOf(b, c)) { a2.pin(o); o.pin(a2) }
        a2.swarm.start(periodicProfiles = false)
        testScheduler.runCurrent()
        a2.router.start()
        testScheduler.runCurrent()
        for (n in listOf(a2, b, c)) n.swarm.beacon!!.announce()
        testScheduler.runCurrent()

        val out = a2.swarm.resume(j.jobId).toList()
        testScheduler.runCurrent()
        val last = out.last()
        assertTrue(last.error, last.done)
        assertEquals("split:" + reference(j).take(16), last.resultRef)
        // zero re-runs of finished chunks: only the chunks nobody had finished ran again
        val runsAfter = ex.values.sumOf { it.runs } + a2ex.runs
        assertEquals(chunkIds.size - held.size, runsAfter - runsBefore)
        val snap = a2.journal.load(j.jobId)!!
        assertEquals(JobState.DONE, snap.job.state)
        assertEquals("${j.jobId}#2", snap.job.planId)
        assertEquals(chunkIds.size, snap.chunks.count { it.state == ChunkStates.DONE })
        assertTrue(snap.attempts.any { it.outcome == AttemptOutcome.RECLAIMED })
        assertEquals(listOf(last), a2.swarm.jobs.value.filter { it.jobId == j.jobId })
    }
}
