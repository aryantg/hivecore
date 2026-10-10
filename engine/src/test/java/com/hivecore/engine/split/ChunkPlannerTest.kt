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
}
