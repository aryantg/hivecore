package com.hivecore.engine.split

import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.MergeKind
import com.hivecore.core.Op
import com.hivecore.engine.ComparatorImpl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkQueueTest {
    private var now = 0L
    private fun plan(n: Int, private: Boolean = false, owner: String? = null, op: Op = Op.IQA, per: Int = 1, blobs: Boolean = false) = ChunkPlanner().plan(
        SplitJob("j", op, JsonObject(emptyMap()),
            input = if (blobs) SplitInput.Blobs((0 until n).map { "b$it" }, sizes = (0 until n).map { 1_000_000L }, private = (0 until n).map { private })
                    else SplitInput.Items((0 until n).map { JsonPrimitive(it) }, private = private),
            unitsPerChunk = per, unitMs = 1000.0, owner = owner), emptyList(), "me").getOrThrow()
    private fun queue(n: Int, nodes: List<String>, private: Boolean = false, owner: String? = null, op: Op = Op.IQA, per: Int = 1, blobs: Boolean = false,
                      links: Map<String, Link> = emptyMap(), warm: Set<String> = emptySet(), allowMobileData: Boolean = false) =
        WorkQueue(plan(n, private, owner, op, per, blobs), nodes, { now }, ComparatorImpl(), warm = { it in warm },
            linkOf = { links[it] ?: Link.defaultFor(LinkKind.LAN) }, allowMobileData = allowMobileData)
    private fun out(i: Int) = JsonPrimitive("r$i")
    private val relay = Link(LinkKind.WAN_RELAY, 500, 300)
    private val bt = Link(LinkKind.BT, 1_200, 50)
    private val wanDirect = Link(LinkKind.WAN_DIRECT, 20_000, 100)
    private val metered = Link(LinkKind.WAN_DIRECT, 20_000, 100, metered = true)

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
        // section 14 "private owner leaves": every unfinished private chunk fails and the job ends partial with the reason
        assertTrue(q.finished)
        assertTrue(q.partial)
        assertTrue(q.partialReason!!, q.partialReason!!.contains("phone that left"))
    }

    // ------------------------------------------------------------------ section 11.2 link-aware fill and pick

    @Test fun fillOrderIsWarmThenLanBeforeWanThenLeastLoadedThenId() {
        val q = queue(4, listOf("wan", "lan", "warmwan", "me"), per = 1,
            links = mapOf("wan" to wanDirect, "warmwan" to wanDirect), warm = setOf("warmwan"))
        val first = q.fill()
        // 4 chunks over 4 phones at one each: the order of the first pass is the fill order
        assertEquals(listOf("warmwan", "me", "lan", "wan"), first.map { it.node })
    }

    @Test fun deadlineIsThreeTimesExpectedPlusTwoRoundTrips() {
        val q = queue(4, listOf("me", "far"), per = 2, links = mapOf("far" to relay))
        val far = q.fill().first { it.node == "far" }
        assertEquals((3.0 * 2000 + 2 * 300).toLong(), far.deadlineMs)        // 2 s expected, 300 ms rtt
        val q2 = queue(2, listOf("me", "near"), per = 1)
        val near = q2.fill().single { it.node == "near" }
        assertEquals(SplitConstants.MIN_DEADLINE_MS, near.deadlineMs)          // floor of 4 s (3 s + 20 ms)
    }

    @Test fun blobChunksNeverGoOverBluetoothOrTheRelayButTextChunksDo() {
        val q = queue(4, listOf("me", "bt", "relay"), blobs = true, links = mapOf("bt" to bt, "relay" to relay))
        val first = q.fill()
        assertEquals(setOf("me"), first.map { it.node }.toSet())
        first.forEach { q.onResult("me", it.chunk.index, it.attempt, out(it.chunk.index), 10) }
        assertEquals(setOf("me"), q.fill().map { it.node }.toSet())
        // the same phones take inline text chunks
        val t = queue(6, listOf("me", "bt", "relay"), links = mapOf("bt" to bt, "relay" to relay))
        assertEquals(setOf("me", "bt", "relay"), t.fill().map { it.node }.toSet())
    }

    @Test fun meteredWanPhoneGetsNoBlobsUnlessMobileDataIsAllowed() {
        // the owner is busy enough that moving 1 MB over a 20 Mbit/s direct link pays off
        val blocked = queue(8, listOf("me", "mobile"), blobs = true, links = mapOf("mobile" to metered))
        assertEquals(setOf("me"), blocked.fill().map { it.node }.toSet())
        val allowed = queue(8, listOf("me", "mobile"), blobs = true, links = mapOf("mobile" to metered), allowMobileData = true)
        assertTrue(allowed.fill().any { it.node == "mobile" })
    }

    @Test fun heavyChunksNeverGoOverBluetoothOrTheRelay() {
        val q = queue(6, listOf("me", "bt", "relay", "lan"), op = Op.ASR, links = mapOf("bt" to bt, "relay" to relay))
        // ASR over items: text-only chunks, but a HEAVY op still stays off Bluetooth and the relay (section 11.2)
        assertEquals(setOf("me", "lan"), q.fill().map { it.node }.toSet())
    }

    @Test fun transferRuleUsesTheLinkRoundTrip() {
        // 1 MB over the relay: 16 s of transfer + 600 ms rtt against a 1 s local run: never moves. Over LAN at 100 Mbit/s it does.
        val slow = queue(4, listOf("me", "far"), blobs = true, links = mapOf("far" to Link(LinkKind.WAN_DIRECT, 500, 300)))
        assertEquals(setOf("me"), slow.fill().map { it.node }.toSet())
        val fast = queue(4, listOf("me", "near"), blobs = true, links = mapOf("near" to Link(LinkKind.LAN, 100_000, 5)))
        assertTrue(fast.fill().any { it.node == "near" })
    }

    @Test fun stragglerDuplicatePrefersALanPhone() {
        val q = queue(1, listOf("slow", "lan", "wan"), links = mapOf("wan" to wanDirect, "slow" to wanDirect))
        val x = q.fill().single()
        assertEquals("lan", x.node)   // LAN is filled first
        now = 5_000
        val dup = q.fill().single()
        assertTrue(dup.duplicate)
        assertEquals("slow", dup.node) // ties between the two WAN phones go by id
    }

    // ------------------------------------------------------------------ section 14 faults

    @Test fun threeHashMismatchesRemoveThePhone() {
        val q = queue(6, listOf("me", "bad"))
        repeat(2) {
            val a = q.fill().first { it.node == "bad" }
            assertFalse(q.onHashMismatch("bad", a.chunk.index, a.attempt))
        }
        val a = q.fill().first { it.node == "bad" }
        assertTrue(q.onHashMismatch("bad", a.chunk.index, a.attempt))
        assertFalse("bad" in q.nodes)
        // nothing is lost: every chunk is still queued or running on "me", and the three mismatched ones go first
        val cells = q.progress.value.chunks
        assertEquals(6, cells.count { it.status == ChunkStatus.QUEUED || it.status == ChunkStatus.RUNNING })
        assertEquals(4, q.progress.value.reassigned)          // three mismatches plus the chunk in flight when it was removed
        assertTrue(q.fill().all { it.node == "me" })
    }

    @Test fun threeMissedDeadlinesDropThePhone() {
        val q = queue(6, listOf("me", "late"))
        var drop = false
        repeat(3) {
            val a = q.fill().first { it.node == "late" }
            drop = q.onMiss("late", a.chunk.index, a.attempt)
        }
        assertTrue(drop)
    }

    @Test fun chunkFailedOnTwoPhonesIsRecutOnceAndTheParentTakesTheConcatenation() {
        val q = queue(8, listOf("a", "b", "me"), per = 4)        // chunk 0 = items 0..3, chunk 1 = items 4..7
        val first = q.fill()
        val c0a = first.first { it.chunk.index == 0 }
        q.onFault(c0a.node, 0, c0a.attempt, "error: too big")
        val c0b = q.fill().first { it.chunk.index == 0 }
        assertTrue(c0b.node != c0a.node)
        q.onFault(c0b.node, 0, c0b.attempt, "error: too big")
        assertEquals(1, q.recutCount)
        val cells = q.progress.value.chunks
        assertEquals(4, cells.size)
        assertTrue(cells[0].recut)
        assertEquals(listOf(0, 0), cells.drop(2).map { it.recutOf })
        assertEquals(listOf(2, 2), cells.drop(2).map { it.units })
        assertEquals(listOf("items 1–2", "items 3–4"), cells.drop(2).map { it.label })
        // the halves run; their outputs are concatenated into the parent's slot
        val kids = q.fill().filter { it.chunk.recutOf == 0 }
        assertEquals(2, kids.size)
        kids.forEach { q.onResult(it.node, it.chunk.index, it.attempt, JsonArray(it.chunk.params["texts"]!!.jsonArray.map { x -> JsonPrimitive("r${x.jsonPrimitive.content}") }), 10) }
        assertEquals(JsonArray((0 until 4).map { JsonPrimitive("r$it") }), q.output(0))
        assertEquals(ChunkStatus.DONE, q.progress.value.chunks[0].status)
        val c1 = (first + kids).first { it.chunk.index == 1 }
        q.onResult(c1.node, 1, c1.attempt, JsonArray((4 until 8).map { JsonPrimitive("r$it") }), 10)
        assertTrue(q.done)
        assertEquals(2, q.outputsInOrder().size)
    }

    @Test fun afterMaxAttemptsTheChunkFailsAndTheJobEndsPartialWithAReason() {
        val plan = ChunkPlanner().plan(SplitJob("j", Op.IQA, JsonObject(emptyMap()), input = SplitInput.Items((0 until 2).map { JsonPrimitive(it) }),
            unitsPerChunk = 1, unitMs = 1000.0, merge = MergeKind.VOTE), emptyList(), "me").getOrThrow()   // VOTE: no re-cut
        val q = WorkQueue(plan, listOf("a", "b", "me"), { now }, ComparatorImpl())
        var tries = 0
        while (!q.finished && tries < 20) {
            for (a in q.fill()) if (a.chunk.index == 0) q.onFault(a.node, 0, a.attempt, "error: boom") else q.onResult(a.node, 1, a.attempt, out(1), 10)
            tries++
        }
        assertTrue(q.finished)
        assertFalse(q.done)
        assertTrue(q.partial)
        assertEquals(SplitConstants.MAX_ATTEMPTS, q.progress.value.chunks[0].attempts)
        assertTrue(q.partialReason!!, q.partialReason!!.startsWith("chunk 0 (items 1) failed 6 times"))
        assertEquals(1, q.outputsInOrder().size)
    }

    @Test fun yieldIsAReassignmentNotAFault() {
        val q = queue(1, listOf("a", "b"))
        val x = q.fill().single()
        q.onFault(x.node, 0, x.attempt, "yielded", "yielded")
        val y = q.fill().single()
        q.onFault(y.node, 0, y.attempt, "yielded", "yielded")
        assertEquals(0, q.recutCount)
        assertEquals(2, q.progress.value.reassigned)
        // the yielding phone may take the chunk again once idle
        assertTrue(q.fill().isNotEmpty())
    }

    @Test fun strandedChunksFailSoTheJobEndsPartial() {
        val q = queue(2, listOf("me", "bt"), blobs = true, links = mapOf("bt" to bt))
        q.removeNode("me")
        assertTrue(q.fill().isEmpty())
        q.failStranded()
        assertTrue(q.finished && q.partial)
        assertTrue(q.partialReason!!.contains("no phone can take it"))
    }

    // ------------------------------------------------------------------ section 12.1 replicas

    private fun llmQueue(n: Int, nodes: List<String>, per: Int = 1) =
        WorkQueue(plan(n, op = Op.TEXT_CLASSIFY, per = per), nodes, { now }, ComparatorImpl())

    @Test fun llmChunksRunOnTwoPhonesAtOnceAndAgreeingReplicasAreVerified() {
        val q = llmQueue(2, listOf("a", "b", "me"))
        assertEquals(2, q.replicas)
        val first = q.fill()
        // chunk 0 on two phones, chunk 1 on two phones; every phone holds at most 2
        assertEquals(mapOf(0 to 2, 1 to 2), first.groupingBy { it.chunk.index }.eachCount())
        assertEquals(2, first.map { it.node to it.chunk.index }.groupBy({ it.second }, { it.first }).values.map { it.toSet().size }.min())
        val (x, y) = first.filter { it.chunk.index == 0 }
        assertEquals(WorkQueue.Outcome.AwaitingReplica, q.onResult(x.node, 0, x.attempt, out(0), 10))
        val r = q.onResult(y.node, 0, y.attempt, out(0), 10)
        assertEquals(WorkQueue.Outcome.Accepted(emptyList(), true), r)
        assertEquals(true, q.progress.value.chunks[0].verified)
    }

    @Test fun replicaDisagreementBringsAThirdPhoneAndNoAgreeingPairIsAVerificationFailure() {
        val q = llmQueue(1, listOf("a", "b", "c"))
        val (x, y) = q.fill()
        q.onResult(x.node, 0, x.attempt, JsonPrimitive("x"), 10)
        assertEquals(WorkQueue.Outcome.AwaitingReplica, q.onResult(y.node, 0, y.attempt, JsonPrimitive("y"), 10))
        assertEquals(1, q.progress.value.mismatches)
        val z = q.fill().single()
        assertEquals("c", z.node)
        q.onResult("c", 0, z.attempt, JsonPrimitive("z"), 10)
        val vf = q.takeVerificationFailures().single()
        assertEquals(0, vf.index)
        assertEquals(3, vf.outputs.size)
        assertTrue(vf.reason, vf.reason.contains("3 phones disagreed"))
        assertTrue(q.takeVerificationFailures().isEmpty())
        assertFalse(q.finished)
        // the commander resolves it: a cloud answer takes the slot and is badged
        q.acceptExternal(0, JsonPrimitive("cloud"), "cloud:fake/model")
        assertTrue(q.done)
        assertEquals("fake/model", q.progress.value.chunks[0].cloud)
        assertEquals(listOf("fake/model"), q.progress.value.cloud)
    }

    @Test fun thirdReplicaAgreeingWithOneOfTwoSettlesTheChunk() {
        val q = llmQueue(1, listOf("a", "b", "c"))
        val (x, y) = q.fill()
        q.onResult(x.node, 0, x.attempt, JsonPrimitive("x"), 10)
        q.onResult(y.node, 0, y.attempt, JsonPrimitive("y"), 10)
        val z = q.fill().single()
        val r = q.onResult("c", 0, z.attempt, JsonPrimitive("y"), 10)
        assertTrue(r is WorkQueue.Outcome.Accepted)
        assertEquals(JsonPrimitive("y"), q.output(0))
        assertTrue(q.takeVerificationFailures().isEmpty())
    }

    @Test fun onlyOnePhoneAvailableGivesTheAnswerMarkedNotVerified() {
        val q = llmQueue(1, listOf("me"))
        val x = q.fill().single()
        val r = q.onResult("me", 0, x.attempt, out(0), 10)
        assertEquals(WorkQueue.Outcome.Accepted(emptyList(), false), r)
        assertTrue(q.isUnverified(0))
        assertEquals(1, q.progress.value.unverified)
        assertEquals(false, q.progress.value.chunks[0].verified)
    }

    @Test fun secondPhoneLeavingBeforeItsReplicaSettlesTheChunkUnverified() {
        val q = llmQueue(1, listOf("a", "b"))
        val (x, y) = q.fill()
        q.onResult(x.node, 0, x.attempt, out(0), 10)
        q.removeNode(y.node)
        assertTrue(q.done)
        assertTrue(q.isUnverified(0))
    }

    @Test fun threeSchemaFailuresOnTwoPhonesAreAVerificationFailure() {
        val q = queue(1, listOf("a", "b", "c"))
        val a = q.fill().single()
        assertTrue(q.onSchemaFault(a.node, 0, a.attempt, "missing total"))
        val b = q.fill().single()
        assertTrue(b.node != a.node)                         // a chunk that failed here is offered elsewhere first
        q.onSchemaFault(b.node, 0, b.attempt, "missing total")
        val c = q.fill().single()
        assertTrue(c.node != a.node && c.node != b.node)
        q.onSchemaFault(c.node, 0, c.attempt, "missing total")
        val vf = q.takeVerificationFailures().single()
        assertTrue(vf.reason, vf.reason.contains("schema validation failed 3 times on 3 phones"))
        q.failVerification(0, "${vf.reason}; no cloud")
        assertTrue(q.finished && q.partial)
        assertNull(q.output(0))
    }
}
