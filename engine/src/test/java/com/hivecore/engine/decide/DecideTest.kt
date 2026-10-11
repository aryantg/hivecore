package com.hivecore.engine.decide

import com.hivecore.core.Canon
import com.hivecore.core.MergeKind
import com.hivecore.core.Op
import com.hivecore.core.Profile
import com.hivecore.core.Question
import com.hivecore.core.fakes.FakeDecisionModel
import com.hivecore.engine.split.ChunkPlanner
import com.hivecore.engine.split.Mergers
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.Splitters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision point A and its cut, the chunk identity change and the fallback (docs/plans/decision-chunking.md §5, §6). */
class DecideTest {
    private val empty = JsonObject(emptyMap())
    /** The test jobs are milliseconds long, so the budget's share rule is relaxed; [budgetShareRuleStarvesCheapJobs] covers it. */
    private val budget = DecisionBudget(callMsEstimate = 1.0)
    private fun phone(id: String) = Profile(id, 0, eligible = true, idle = true, workerOptIn = true, p50 = mapOf("m" to 100.0), big = "llm")

    @Test fun budgetShareRuleStarvesCheapJobs() = runTest {
        val job = SplitJob("b", Op.EMBED, empty, "m", SplitInput.Text(text, "doc"))
        val plan = ChunkPlanner().plan(job, listOf(phone("me")), "me").getOrThrow()      // 120 × 100 ms = 12 s; 10 % buys one 1 s call
        val d = ChunkDecisions(model(), "me", DecisionBudget())
        assertNull(d.decideCut(job, plan))
        assertTrue(d.notes.any { it.contains("over budget") })
        val long = ChunkPlanner().plan(job.copy(unitMs = 4_000.0), listOf(phone("me")), "me").getOrThrow()   // an LLM-priced job affords it
        assertNotNull(ChunkDecisions(model(), "me", DecisionBudget()).decideCut(job.copy(unitMs = 4_000.0), long))
    }

    /** 120 sentences in 4 topics of 30; a sentence that opens a topic says "Section k". */
    private val text = (0 until 120).joinToString(" ") { i -> if (i % 30 == 0) "Section ${i / 30} begins here." else "Sentence $i stays on topic ${i / 30}." }
    /** The scripted model: a boundary after sentence i is a topic change when sentence i + 1 opens a section. */
    private fun model() = FakeDecisionModel().apply {
        boundaryRule = { q, state -> val i = q.id.removePrefix("b").toInt(); if (state.contains("s${i + 1}: Section")) 0.95 else 0.05 }
    }

    // ------------------------------------------------------------------ the DP

    @Test fun cutMaximisesBoundaryScoresWithinTheBand() {
        val peaks = setOf(29, 59, 89)
        val cuts = Cut.boundaries(120, 30, 40, 40, score = { i -> if (i in peaks) 0.95 else 0.05 })
        assertEquals(listOf(30, 60, 90), cuts)
        assertEquals(listOf(30, 30, 30, 30), Cut.sizes(120, cuts))
        assertTrue(Cut.boundaries(35, 30, 40, 40, score = { 0.5 }).isEmpty())                  // fits one chunk
        val flat = Cut.boundaries(100, 30, 40, 40, score = { 0.0 })
        assertEquals(flat, Cut.boundaries(100, 30, 40, 40, score = { 0.0 }))                   // deterministic
        assertTrue(Cut.sizes(100, flat).dropLast(1).all { it in 30..40 })
        assertEquals(listOf(40, 40, 40), Cut.sizes(120, Cut.boundaries(120, 30, 40, 40, score = { 0.0 })))   // no signal → the target size
        val tight = Cut.boundaries(7, 2, 3, 3, score = { 0.0 })
        assertTrue(Cut.sizes(7, tight).dropLast(1).all { it in 2..3 })
    }

    @Test fun metricsOfPerfectAndUninformedPredictions() {
        val gold = listOf(true, false, true, false)
        val perfect = Metrics.report(listOf(1.0, 0.0, 1.0, 0.0), gold)
        assertEquals(1.0, perfect.accuracy, 1e-9); assertEquals(0.0, perfect.brier, 1e-9); assertEquals(0.0, perfect.ece, 1e-9)
        val coin = Metrics.report(listOf(0.5, 0.5, 0.5, 0.5), gold)
        assertEquals(0.25, coin.brier, 1e-9); assertEquals(0.0, coin.ece, 1e-9)
        val sure = Metrics.report(listOf(0.9, 0.9, 0.9, 0.9), gold)
        assertEquals(0.5, sure.accuracy, 1e-9); assertEquals(0.4, sure.ece, 1e-9)
    }

    // ------------------------------------------------------------------ A over the planner

    @Test fun decidedCutFollowsTheModelAndIsIdenticalOnOneAndThreePhones() = runTest {
        val job = SplitJob("t", Op.SUMMARIZE, empty, input = SplitInput.Text(text, "doc"))
        val d = ChunkDecisions(model(), "me", budget)
        val one = ChunkPlanner().plan(job, listOf(phone("me")), "me").getOrThrow()
        val three = ChunkPlanner().plan(job, listOf(phone("a"), phone("b"), phone("me")), "me").getOrThrow()
        assertEquals(MergeKind.TREE, one.kind)
        val c1 = d.decideCut(job, one)!!.value
        val c3 = d.decideCut(job, three)!!.value
        assertEquals(listOf(30, 60, 90), c1.cuts)
        assertEquals(c1, c3)                                                                    // TREE: the text decides, never the phone count
        assertEquals("f".repeat(64) + ":cal-fake+t${Templates.VERSION}", c1.decider)
        val p1 = ChunkPlanner().plan(c1, listOf(phone("me")), "me").getOrThrow()
        val p3 = ChunkPlanner().plan(c3, listOf(phone("a"), phone("b"), phone("me")), "me").getOrThrow()
        assertEquals(listOf(30, 30, 30, 30), p1.chunks.map { it.units })
        assertEquals(p1.chunks.map { it.chunkId }, p3.chunks.map { it.chunkId })
        assertEquals(p1.chunks.map { Canon.canonical(it.params) }, p3.chunks.map { Canon.canonical(it.params) })   // identical bytes
        val rows = d.decideCut(job, one)!!.rows
        assertEquals(119, rows.size)                                                            // one journal row per boundary question
        assertTrue(rows.all { it.point == Templates.BOUNDARY && it.questionId.startsWith("b") && it.requestHash.length == 64 })
        assertEquals(3, rows.map { it.requestHash }.distinct().size)                            // three windows of 40 candidates
        // the fixed cut of the same job differs in ids (the opKey carries the decider) and in sizes
        assertEquals(listOf(40, 40, 40), one.chunks.map { it.units })
        assertTrue(one.chunks.map { it.chunkId }.toSet().intersect(p1.chunks.map { it.chunkId }.toSet()).isEmpty())
        assertTrue(p1.job.opKey.endsWith("|decide:" + c1.decider))
        assertEquals("SUMMARIZE@", one.job.opKey)
    }

    @Test fun stitchCutsAreContentDerivedAndMergeLikeTheUnsplitRun() = runTest {
        val job = SplitJob("s", Op.EMBED, empty, "m", SplitInput.Text(text, "doc"))
        val plan = ChunkPlanner().plan(job, listOf(phone("a"), phone("me")), "me").getOrThrow()
        assertEquals(MergeKind.STITCH, plan.kind)
        val decided = ChunkDecisions(model(), "me", budget).decideCut(job, plan)!!.value
        val p = ChunkPlanner().plan(decided, listOf(phone("a"), phone("me")), "me").getOrThrow()
        assertTrue(p.chunks.size >= 2)
        assertEquals(120, p.chunks.sumOf { it.units })
        // overlap context before each owned range, as for the fixed cut
        assertTrue(p.chunks.drop(1).all { it.overlapBefore == 1L })
        // STITCH merge of per-sentence outputs equals the unsplit run whatever the boundaries
        fun run(units: JsonArray) = buildJsonObject { put("items", JsonArray(units.map { u -> buildJsonObject { put("id", u.jsonObject["unit"]!!); put("v", Canon.hash(u)) } })) }
        val outs = p.chunks.map { c -> run(c.params["units"]!!.jsonArray) }
        val merged = Mergers.merge(MergeKind.STITCH, outs, p.chunks, job.params)
        val whole = Splitters.whole(job.opKey, job.params, job.input)
        assertEquals(Canon.hash(run(whole.params["units"]!!.jsonArray)), Canon.hash(merged))
    }

    @Test fun fallbackKeepsTodaysIdsAndADifferentDeciderChangesThem() = runTest {
        val job = SplitJob("f", Op.SUMMARIZE, empty, input = SplitInput.Text(text, "doc"))
        val plan = ChunkPlanner().plan(job, listOf(phone("me")), "me").getOrThrow()
        assertNull(ChunkDecisions(FakeDecisionModel(build = null), "me", budget).decideCut(job, plan))                  // unavailable
        assertNull(ChunkDecisions(model(), "me", budget).decideCut(job.copy(unitsPerChunk = 25), plan))               // an override wins
        assertNull(ChunkDecisions(model(), "me", budget).decideCut(job.copy(cuts = listOf(30), decider = "x"), plan)) // a resumed decided job
        assertNull(ChunkDecisions(model(), "me", budget).decideCut(job.copy(input = SplitInput.Text(text, "doc", private = true), owner = "hr"), plan.copy(owner = "hr")))  // another owner's private text
        assertNotNull(ChunkDecisions(model(), "me", budget).decideCut(job.copy(input = SplitInput.Text(text, "doc", private = true)), plan))   // own private text
        val failing = FakeDecisionModel().apply { failNext = IllegalStateException("boom") }
        assertNull(ChunkDecisions(failing, "me", budget).decideCut(job, plan))
        assertNull(ChunkDecisions(model(), "me", DecisionBudget(maxLlmCalls = 2, callMsEstimate = 1.0)).decideCut(job, plan))      // over budget
        val a = ChunkPlanner().plan(job.copy(cuts = listOf(30, 60, 90), decider = "A:c+t1"), emptyList(), "me").getOrThrow()
        val b = ChunkPlanner().plan(job.copy(cuts = listOf(30, 60, 90), decider = "B:c+t1"), emptyList(), "me").getOrThrow()
        assertEquals(a.chunks.map { it.units }, b.chunks.map { it.units })
        assertNotEquals(a.chunks.map { it.chunkId }, b.chunks.map { it.chunkId })
        // invalid boundaries are ignored, not fatal
        val odd = ChunkPlanner().plan(job.copy(cuts = listOf(0, 500, 60, 60, -1), decider = "x"), emptyList(), "me").getOrThrow()
        assertEquals(listOf(60, 60), odd.chunks.map { it.units })
    }

    // ------------------------------------------------------------------ B

    @Test fun difficultyFeedsCostAndReplicasAndChildrenInherit() = runTest {
        val job = SplitJob("d", Op.TEXT_CLASSIFY, buildJsonObject { put("labels", JsonArray(listOf(JsonPrimitive("a")))) }, "m",
            SplitInput.Items((0 until 8).map { JsonPrimitive("item $it") }), unitsPerChunk = 2)
        val plan = ChunkPlanner().plan(job, listOf(phone("a"), phone("me")), "me").getOrThrow()
        assertEquals(2, plan.replicas)
        val fake = FakeDecisionModel().apply { score("d0", 0.9); score("d1", 0.1); score("d2", 0.5) }
        val out = ChunkDecisions(fake, "me", budget).decideDifficulty(job, plan, smallBuild = "small", largeBuild = "large")!!
        val cs = out.value.chunks
        assertEquals(listOf(0.9, 0.1, 0.5, 0.5), cs.map { it.difficulty })
        assertEquals(listOf(1.2, 0.8, 1.0, 1.0), cs.map { it.costFactor })
        assertEquals(listOf(3, 2, null, null), cs.map { it.replicas })                           // hard → +1; easy → the registry's minReplicas (2 for LLM ops)
        assertEquals(listOf("large", "small", null, null), cs.map { it.build })
        assertEquals(2 * 1.2 * 100.0, out.value.expectedMs(cs[0], "a"), 1e-9)
        assertEquals(4, out.rows.size)
        assertTrue(out.rows.all { it.point == Templates.DIFFICULTY && it.modelSha256 == "f".repeat(64) })
        val kids = out.value.recut(cs[0], 4)!!
        assertTrue(kids.all { it.difficulty == 0.9 && it.costFactor == 1.2 && it.replicas == 3 && it.build == "large" })
        // a private chunk is never raised; one phone only never raises either
        val priv = ChunkPlanner().plan(job.copy(input = SplitInput.Items((0 until 4).map { JsonPrimitive("p$it") }, private = true)), listOf(phone("me")), "me").getOrThrow()
        val po = ChunkDecisions(FakeDecisionModel().apply { score("d.*", 0.9) }, "me", budget).decideDifficulty(job, priv)!!
        assertTrue(po.value.chunks.all { it.replicas == null && it.difficulty == 0.9 })
        val light = ChunkPlanner().plan(SplitJob("l", Op.IQA, empty, input = SplitInput.Items((0 until 4).map { JsonPrimitive("p$it") }), unitsPerChunk = 2), listOf(phone("a"), phone("me")), "me").getOrThrow()
        val lo = ChunkDecisions(FakeDecisionModel().apply { score("d0", 0.9); score("d1", 0.1) }, "me", budget).decideDifficulty(job.copy(op = Op.IQA), light)!!
        assertEquals(listOf(2, 1), lo.value.chunks.map { it.replicas })                           // a LIGHT op: hard → 2 replicas, easy → 1
    }
}
