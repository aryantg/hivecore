package com.hivecore.engine.split

import com.hivecore.core.Op
import com.hivecore.engine.ComparatorImpl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkQueueTest {
    private var now = 0L
    private fun plan(n: Int, private: Boolean = false, owner: String? = null) = ChunkPlanner().plan(
        SplitJob("j", Op.IQA, JsonObject(emptyMap()), input = SplitInput.Items((0 until n).map { JsonPrimitive(it) }, private = private),
            unitsPerChunk = 1, unitMs = 1000.0, owner = owner), emptyList(), "me").getOrThrow()
    private fun queue(n: Int, nodes: List<String>, private: Boolean = false, owner: String? = null) =
        WorkQueue(plan(n, private, owner), nodes, { now }, ComparatorImpl())
    private fun out(i: Int) = JsonPrimitive("r$i")

    @Test fun eachPhoneHoldsOneRunningAndOnePrefetched() {
        val q = queue(10, listOf("a", "b", "me"))
        val first = q.fill()
        assertEquals(6, first.size)
        assertEquals(mapOf("a" to 2, "b" to 2, "me" to 2), first.groupingBy { it.node }.eachCount())
        assertTrue(q.fill().isEmpty())
        val a0 = first.first { it.node == "a" }
        assertTrue(q.onResult("a", a0.chunk.index, a0.attempt, out(a0.chunk.index), 900) is WorkQueue.Outcome.Accepted)
        val next = q.fill()
        assertEquals(listOf("a"), next.map { it.node })
        assertEquals(6, next.single().chunk.index)
        // the same result again is stale (idempotent)
        assertEquals(WorkQueue.Outcome.Stale, q.onResult("a", a0.chunk.index, a0.attempt, out(a0.chunk.index), 900))
    }

    @Test fun faultsGoBackToTheFrontAndALostPhoneCostsOnlyItsChunks() {
        val q = queue(8, listOf("a", "b"))
        val first = q.fill()
        val bs = first.filter { it.node == "b" }
        q.removeNode("b")
        val next = q.fill()
        assertTrue(next.isEmpty())                                         // a is full
        val a = first.filter { it.node == "a" }
        a.forEach { q.onResult("a", it.chunk.index, it.attempt, out(it.chunk.index), 10) }
        val again = q.fill()
        assertEquals(bs.map { it.chunk.index }.sorted(), again.map { it.chunk.index }.sorted())   // b's chunks first
        assertTrue(again.all { it.attempt == 2 })
        assertEquals(2, q.progress.value.reassigned)
    }

    @Test fun stragglerIsDuplicatedOnAnIdlePhoneAndFirstResultWins() {
        val q = queue(3, listOf("a", "b"))
        val first = q.fill()                                               // a: 0, 2   b: 1
        assertEquals(3, first.size)
        val b1 = first.single { it.node == "b" }
        val aChunks = first.filter { it.node == "a" }
        now = 500
        aChunks.forEach { q.onResult("a", it.chunk.index, it.attempt, out(it.chunk.index), 250) }
        assertTrue(q.fill().isEmpty())                                     // not yet 1.5 × expected
        now = 1_600
        val dup = q.fill().single()
        assertEquals("a", dup.node)
        assertEquals(b1.chunk.index, dup.chunk.index)
        assertTrue(dup.duplicate)
        assertEquals(ChunkStatus.DUPLICATED, q.progress.value.chunks[b1.chunk.index].status)
        assertTrue(q.onResult("a", dup.chunk.index, dup.attempt, out(dup.chunk.index), 100) is WorkQueue.Outcome.Accepted)
        assertTrue(q.done)
        assertEquals(WorkQueue.Outcome.Compared(true), q.onResult("b", b1.chunk.index, b1.attempt, out(b1.chunk.index), 1600))
        assertEquals(WorkQueue.Outcome.Stale, q.onResult("b", b1.chunk.index, b1.attempt, out(b1.chunk.index), 1600))
        assertEquals(1, q.progress.value.duplicates)
    }

    @Test fun mismatchingDuplicateIsCounted() {
        val q = queue(1, listOf("a", "b"))
        val x = q.fill().single()
        now = 5_000
        q.overdue()   // past straggle threshold but before deadline handling
        val dup = q.fill().single()
        q.onResult(dup.node, 0, dup.attempt, JsonPrimitive("x"), 10)
        assertEquals(WorkQueue.Outcome.Compared(false), q.onResult(x.node, 0, x.attempt, JsonPrimitive("y"), 10))
        assertEquals(1, q.progress.value.mismatches)
    }

    @Test fun privateChunksOnlyRunOnTheOwner() {
        val q = queue(4, listOf("a", "b", "hr"), private = true, owner = "hr")
        val first = q.fill()
        assertTrue(first.all { it.node == "hr" })
        assertEquals(2, first.size)
        q.removeNode("hr")
        assertNotNull(q.error)
        assertFalse(q.done)
    }
}
