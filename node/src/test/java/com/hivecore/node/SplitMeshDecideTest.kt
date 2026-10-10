package com.hivecore.node

import com.hivecore.core.Canon
import com.hivecore.core.MergeKind
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.Question
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeDecisionModel
import com.hivecore.engine.split.ChunkPlanner
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.Splitters
import com.hivecore.node.textai.TextExecutor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decision-driven chunking over the 3-phone fake swarm (docs/plans/decision-chunking.md §11): the orchestrator decides
 * once, the merged result equals the unsplit run on 1, 2 and 3 phones, a TREE summary is identical on 2 and 3 phones,
 * and another owner's private text is never shown to the model.
 */
class SplitMeshDecideTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    /** EMBED-like: one item per sentence unit with a hash of it. */
    private class Units : OpExecutor {
        override val ops = setOf(Op.EMBED)
        var chunks = 0
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            chunks++
            val units = task.params["units"]!!.jsonArray
            return Result.success(buildJsonObject { put("items", JsonArray(units.map { u -> buildJsonObject { put("id", u.jsonObject["unit"]!!); put("v", Canon.hash(u)) } })) })
        }
    }

    private val text = (0 until 120).joinToString(" ") { i -> if (i % 30 == 0) "Section ${i / 30} begins here." else "Clause $i fixes the fee at ${i * 7} rupees for site ${i % 5}." }
    private fun model() = FakeDecisionModel().apply {
        boundaryRule = { q, state -> val i = q.id.removePrefix("b").toInt(); if (state.contains("s${i + 1}: Section")) 0.95 else 0.05 }
    }

    private class Mesh(val h: SwarmHarness, val nodes: List<SwarmHarness.Node>, val model: FakeDecisionModel, val exec: Map<String, Units>) {
        val a get() = nodes[0]; val b get() = nodes[1]; val c get() = nodes[2]
    }

    private suspend fun TestScope.mesh(): Mesh {
        val model = model()
        val h = SwarmHarness(this).also { harness = it }
        h.decisionModelOf = { id -> if (id == "hr") model else null }           // only the orchestrator decides
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val nodes = listOf(hr, sales, accounts)
        val ex = nodes.associate { n -> n.id to Units().also { n.runner.register(it) } }
        nodes.forEach { it.runner.register(TextExecutor(null, null, null, null)) }
        h.start()
        return Mesh(h, nodes, model, ex)
    }

    private suspend fun TestScope.run(m: Mesh, j: SplitJob): SplitProgress {
        val out = m.a.swarm.runJob(j, useMemo = false).toList()
        testScheduler.runCurrent()
        return out.last()
    }

    private fun eligible(n: SwarmHarness.Node, on: Boolean) = n.policy.set(n.policy.profile.value.copy(eligible = on, ts = n.policy.profile.value.ts + 1))

    private fun reference(j: SplitJob): String {
        val units = Splitters.whole(j.opKey, j.params, j.input).params["units"]!!.jsonArray
        return Canon.hash(buildJsonObject { put("items", JsonArray(units.map { u -> buildJsonObject { put("id", u.jsonObject["unit"]!!); put("v", Canon.hash(u)) } })) })
    }

    @Test fun decidedStitchCutsGiveTheUnsplitHashOnOneTwoAndThreePhones() = runTest {
        val m = mesh()
        val j = SplitJob("st", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Text(text, "memo"), unitMs = 5.0)
        assertEquals(MergeKind.STITCH, ChunkPlanner().plan(j, emptyList(), "me").getOrThrow().kind)
        val ref = reference(j)
        eligible(m.b, false); eligible(m.c, false); testScheduler.runCurrent()
        val one = run(m, j)
        eligible(m.b, true); testScheduler.runCurrent()
        val two = run(m, j.copy(jobId = "st-2"))
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j.copy(jobId = "st-3"))
        for (p in listOf(one, two, three)) {
            assertTrue(p.error, p.done); assertFalse(p.partialReason ?: "", p.partial); assertEquals(ref, p.resultHash)
            assertTrue(p.notes.any { it.contains("decided boundaries") })
            assertEquals(120, p.chunks.sumOf { it.units })
        }
        assertEquals(3, three.nodes.count { it.chunks > 0 })
        // the orchestrator asked grouped boundary questions (A), difficulty scores (B) and verdicts (E); nothing else
        assertTrue(m.model.requests.any { r -> r.questions.all { it is Question.Noul && it.group != null } })
        assertTrue(m.model.requests.any { r -> r.questions.all { it is Question.Score } })
        assertTrue(m.model.requests.all { r -> r.questions.all { it is Question.Score || (it is Question.Noul && (it.group != null || it.id.startsWith("v"))) } })
        assertTrue(m.b.got(com.hivecore.mesh.MsgType.TASK).isNotEmpty())
    }

    @Test fun decidedTreeSummaryIsIdenticalOnTwoAndThreePhones() = runTest {
        val m = mesh()
        val j = SplitJob("sum", Op.SUMMARIZE, buildJsonObject { put("sentences", 5) }, input = SplitInput.Text(text, "contract"))
        eligible(m.c, false); testScheduler.runCurrent()
        val two = run(m, j)
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j.copy(jobId = "sum-3"))
        assertTrue(two.error, two.done); assertTrue(three.error, three.done)
        assertEquals(listOf(30, 30, 30, 30), two.chunks.map { it.units })                 // the sections, not 40/40/40
        assertEquals(two.chunks.map { it.chunkId }, three.chunks.map { it.chunkId })
        assertEquals(two.resultHash, three.resultHash)
        assertEquals(5, three.result!!.jsonObject["picks"]!!.jsonArray.size)
        assertTrue(three.chunks.all { it.verified == true })
    }

    @Test fun anotherOwnersPrivateTextIsNeverDecidedOn() = runTest {
        val m = mesh()
        val j = SplitJob("pv", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Text(text, "memo", private = true), owner = m.b.nodeId, unitMs = 5.0)
        val p = run(m, j)
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue(m.model.requests.isEmpty())                                             // no boundary, difficulty or verdict request
        assertEquals(p.chunks.size, m.exec.getValue("sales").chunks)
        assertEquals(0, m.exec.getValue("hr").chunks + m.exec.getValue("accounts").chunks)
        assertTrue(p.notes.none { it.contains("decided boundaries") })
    }
}
