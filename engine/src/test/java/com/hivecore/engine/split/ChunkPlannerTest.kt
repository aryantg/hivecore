package com.hivecore.engine.split

import com.hivecore.core.Mergeability
import com.hivecore.core.MergeKind
import com.hivecore.core.Op
import com.hivecore.core.Profile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private fun phone(id: String, p50: Double? = null, eligible: Boolean = true) =
        Profile(id, 0, eligible = eligible, idle = true, workerOptIn = true, p50 = p50?.let { mapOf("m" to it) } ?: emptyMap())
    private fun items(n: Int) = SplitInput.Items((0 until n).map { i -> buildJsonObject { put("id", "i$i"); put("text", "t$i") } })
    private val empty = JsonObject(emptyMap())

    @Test fun chunkSizeFollowsMeasuredSpeed() {
        val job = SplitJob("j", Op.EMBED, empty, "m", items(10_000))
        val fast = ChunkPlanner().plan(job, listOf(phone("a", 100.0), phone("b", 100.0), phone("me", 100.0)), "me").getOrThrow()
        val slow = ChunkPlanner().plan(job, listOf(phone("a", 400.0), phone("b", 400.0), phone("me", 400.0)), "me").getOrThrow()
        assertEquals(300, fast.chunks.first().units)          // 30 s / 100 ms
        assertEquals(75, slow.chunks.first().units)           // 30 s / 400 ms: 4× slower phones get 4× smaller chunks
        assertEquals(fast.chunks.first().units, 4 * slow.chunks.first().units)
        val est = fast.expectedMs(fast.chunks.first(), "a")
        assertTrue(est in 20_000.0..40_000.0)
        assertEquals(10_000, fast.chunks.sumOf { it.units })
    }

    @Test fun atLeastTwoChunksPerEligiblePhone() {
        val job = SplitJob("j", Op.IQA, empty, input = items(24))
        val p = ChunkPlanner().plan(job, listOf(phone("a"), phone("b"), phone("c", eligible = false), phone("me")), "me").getOrThrow()
        assertEquals(listOf("a", "b", "me"), p.eligible)
        assertTrue(p.chunks.size >= 6)
        assertEquals(24, p.chunks.sumOf { it.units })
        assertEquals(MergeKind.CONCAT, p.kind)
        assertEquals("merge", p.dag.output)
        assertEquals(p.chunks.size + 1, p.dag.tasks.size)
    }

    @Test fun treeChunkingDoesNotDependOnPhoneCount() {
        val text = (1..100).joinToString(" ") { "Line $it says something." }
        val job = SplitJob("t", Op.SUMMARIZE, empty, input = SplitInput.Text(text))
        val one = ChunkPlanner().plan(job, listOf(phone("me")), "me").getOrThrow()
        val three = ChunkPlanner().plan(job, listOf(phone("a"), phone("b"), phone("me")), "me").getOrThrow()
        assertEquals(one.chunks.map { it.chunkId }, three.chunks.map { it.chunkId })
        assertEquals(listOf(40, 40, 20), one.chunks.map { it.units })
    }

    @Test fun chunkIdsAreStableAndDependOnOpParamsAndSlice() {
        val job = SplitJob("j", Op.EMBED, empty, "m", items(10), unitsPerChunk = 3)
        val a = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow().chunks
        val b = ChunkPlanner().plan(job.copy(jobId = "other"), emptyList(), "x").getOrThrow().chunks
        assertEquals(a.map { it.chunkId }, b.map { it.chunkId })
        assertEquals(a.size, a.map { it.chunkId }.toSet().size)
        val c = ChunkPlanner().plan(job.copy(params = JsonObject(mapOf("prefix" to JsonPrimitive("q: ")))), emptyList(), "me").getOrThrow().chunks
        assertNotEquals(a[0].chunkId, c[0].chunkId)
    }

    @Test fun privateBlobsAreChunkedApartAndPinnedToTheOwner() {
        val job = SplitJob("j", Op.IQA, empty, input = SplitInput.Blobs(listOf("b1", "b2", "b3", "b4", "b5"), private = listOf(false, false, true, true, false)),
            owner = "hr", unitsPerChunk = 4)
        val p = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow()
        assertEquals(listOf(listOf("b1", "b2"), listOf("b3", "b4"), listOf("b5")), p.chunks.map { it.blobs })
        assertEquals(listOf(false, true, false), p.chunks.map { it.private })
        assertEquals("hr", p.task(p.chunks[1]).owner)
        assertNull(p.task(p.chunks[0]).owner)
    }

    @Test fun opsWithoutAMergeKindDoNotSplitWithinAnInput() {
        assertTrue(ChunkPlanner().plan(SplitJob("j", Op.GEN_TEXT, empty, input = SplitInput.Text("A b. C d.")), emptyList(), "me").isFailure)
        assertTrue(ChunkPlanner().plan(SplitJob("j", Op.CLUSTER, empty, input = items(5)), emptyList(), "me").isFailure)
        assertEquals(MergeKind.VOTE, ChunkPlanner().plan(SplitJob("j", Op.TEXT_CLASSIFY, empty,
            input = SplitInput.FanOut(listOf(empty, empty, empty))), emptyList(), "me").getOrThrow().kind)
        assertNull(Mergeability.of(Op.JOIN))
        assertEquals(MergeKind.MONOID, Mergeability.chunkKind(Op.AGG))
    }

    // ------------------------------------------------------------------ spec 10.3 / 10.5 additions

    @Test fun chunkIdsCarryTheModelHashAndTheCapabilityComesFromTheRegistry() {
        val job = SplitJob("j", Op.OCR, empty, "ocr-v1", items(4), unitsPerChunk = 2)
        assertEquals("ocr", job.cap)
        assertEquals("llm.json", SplitJob("x", Op.FIELDS, empty, input = items(1)).cap)
        assertEquals("vlm", SplitJob("x", Op.VLM_ASK, empty, input = items(1), capability = "vlm").cap)
        val byId = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow().chunks
        val byHash = ChunkPlanner().plan(job.copy(modelSha256 = "abc123"), emptyList(), "me").getOrThrow().chunks
        val sameHash = ChunkPlanner().plan(job.copy(modelId = "other", modelSha256 = "abc123"), emptyList(), "me").getOrThrow().chunks
        assertNotEquals(byId.map { it.chunkId }, byHash.map { it.chunkId })
        assertEquals(byHash.map { it.chunkId }, sameHash.map { it.chunkId })          // the build hash, not the id, names the model
        assertEquals("OCR@abc123", job.copy(modelSha256 = "abc123").opKey)
        assertEquals(2, ChunkPlanner().plan(SplitJob("l", Op.TEXT_CLASSIFY, empty, input = items(4)), emptyList(), "me").getOrThrow().replicas)
        assertEquals(1, ChunkPlanner().plan(job, emptyList(), "me").getOrThrow().replicas)
    }

    @Test fun readySetFromPlacementDefinesTheEligiblePhones() {
        val job = SplitJob("j", Op.IQA, empty, input = items(40))
        val profiles = listOf(phone("a"), phone("b"), phone("c"), phone("me"))
        val byProfile = ChunkPlanner().plan(job, profiles, "me").getOrThrow()
        assertEquals(listOf("a", "b", "c", "me"), byProfile.eligible)
        val ready = ChunkPlanner().plan(job, profiles, "me", ready = setOf("c")).getOrThrow()
        assertEquals(listOf("c", "me"), ready.eligible)                        // MODEL_READY phones plus the orchestrator
        assertTrue(ready.chunks.size >= 4 && byProfile.chunks.size >= 8)
    }

    @Test fun expectedMsFallsBackFromJobToProfileToCatalogToDefault() {
        val job = SplitJob("j", Op.OCR, empty, "m", items(1000))
        val profiles = listOf(phone("bench", 50.0), phone("plain"), phone("me"))
        val p = ChunkPlanner().plan(job, profiles, "me", unitMsOf = { n, cap -> if (n == "plain" && cap == "ocr") 300.0 else null }).getOrThrow()
        assertEquals(50.0, p.unitMs["bench"]!!, 0.0)                           // profile p50 for the model
        assertEquals(300.0, p.unitMs["plain"]!!, 0.0)                          // catalog unitMsP50 for the capability
        assertEquals(SplitConstants.UNIT_MS_OTHER, p.unitMs["me"]!!, 0.0)      // kind default
        val pinned = ChunkPlanner().plan(job.copy(unitMs = 7.0), profiles, "me").getOrThrow()
        assertTrue(pinned.unitMs.values.all { it == 7.0 })
        assertEquals(SplitConstants.UNIT_MS_LLM, ChunkPlanner.defaultUnitMs(Op.SUMMARIZE, items(1)), 0.0)
        assertEquals(SplitConstants.UNIT_MS_AUDIO_S, ChunkPlanner.defaultUnitMs(Op.ASR, SplitInput.Audio(1000)), 0.0)
    }

    @Test fun recutHalvesAtAUnitBoundaryWithTheIdsOfAFreshCut() {
        val job = SplitJob("j", Op.EMBED, empty, "m", items(10), unitsPerChunk = 4)
        val p = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow()
        val kids = p.recut(p.chunks[0], p.chunks.size)!!
        assertEquals(listOf(3, 4), kids.map { it.index })
        assertEquals(listOf(0, 0), kids.map { it.recutOf })
        assertEquals(listOf(0L to 2L, 2L to 4L), kids.map { it.ownFrom to it.ownTo })
        val fresh = ChunkPlanner().plan(job.copy(unitsPerChunk = 2), emptyList(), "me").getOrThrow().chunks
        assertEquals(fresh.take(2).map { it.chunkId }, kids.map { it.chunkId })   // a memo hit for the half-size cut is a hit here too
        assertNull(p.recut(p.chunks[2].copy(units = 1, ownTo = p.chunks[2].ownFrom + 1), 9))
        // text keeps the overlap rule; audio halves at a second boundary; VAD segments and fan-outs are never re-cut
        val text = ChunkPlanner().plan(SplitJob("t", Op.TEXT_CLASSIFY, empty, input = SplitInput.Text((1..8).joinToString(" ") { "Sentence $it." }), unitsPerChunk = 4), emptyList(), "me").getOrThrow()
        val tk = text.recut(text.chunks[1], 2)!!
        assertEquals(listOf(1L, 1L), tk.map { it.overlapBefore })
        assertEquals(listOf(4L to 6L, 6L to 8L), tk.map { it.ownFrom to it.ownTo })
        val audio = ChunkPlanner().plan(SplitJob("a", Op.ASR, empty, input = SplitInput.Audio(20_000, "rec")), emptyList(), "me").getOrThrow()
        assertEquals(listOf(0L to 10_000L, 10_000L to 20_000L), audio.chunks.map { it.ownFrom to it.ownTo })   // 2 chunks for 1 phone
        assertEquals(listOf(0L to 5_000L, 5_000L to 10_000L), audio.recut(audio.chunks[0], 2)!!.map { it.ownFrom to it.ownTo })
        val vad = ChunkPlanner().plan(SplitJob("v", Op.ASR, empty, input = SplitInput.Audio(20_000, "rec", segments = listOf(listOf(0L, 20_000L)))), emptyList(), "me").getOrThrow()
        assertNull(vad.recut(vad.chunks[0], 1))
        val fan = ChunkPlanner().plan(SplitJob("f", Op.TEXT_CLASSIFY, empty, input = SplitInput.FanOut(listOf(empty, empty))), emptyList(), "me").getOrThrow()
        assertNull(fan.recut(fan.chunks[0], 2))
    }
}
