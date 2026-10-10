package com.hivecore.engine.placement

import com.hivecore.core.Profile
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.PlacementRow
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelFailBody
import com.hivecore.mesh.ModelReadyBody
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.MsgType
import com.hivecore.runtime.fakes.FakeCapabilityPool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orchestrator side of placement (spec 8.3) over plain lambdas, with the node side closing the loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacementCoordinatorTest {
    private class Sent(val to: String, val type: MsgType, val body: ModelReqBody)

    private class Journal : JobJournal by JobJournal.None {
        val rows = ArrayList<PlacementRow>()
        override fun placement(row: PlacementRow) { rows += row }
    }

    private class Rig(scope: TestScope, var profiles: List<Profile> = ProfileFixtures.all, val sendOk: (String) -> Boolean = { true }) {
        val now: () -> Long = { ProfileFixtures.T0 + scope.testScheduler.currentTime }
        val sent = ArrayList<Sent>()
        val cooldowns = CooldownTable(now)
        val journal = Journal()
        val opened = ArrayList<String>(); val added = ArrayList<String>(); val gaps = ArrayList<String>()
        var n = 0
        val co = PlacementCoordinator("job-1", "plan-1", "a1", ProfileFixtures.catalog, cooldowns, { profiles },
            send = { to, type, body -> sent += Sent(to, type, MeshJson.decode(body)); if (sendOk(to)) Result.success(Unit) else Result.failure(IllegalStateException("$to unreachable")) },
            clock = now, journal = journal, nonce = { "n${n++}" }).apply {
            onOpen = { c, node -> opened += "$c@$node" }
            onAdded = { c, node -> added += "$c@$node" }
            onGap = { c, _ -> gaps += c }
        }
        fun ready(node: String, modelId: String, loadMs: Long = 100, warm: Boolean = false) =
            MeshJson.encode(ModelReadyBody("plan-1", node, modelId, loadMs, warm))
        fun fail(node: String, modelId: String, cls: String) = MeshJson.encode(ModelFailBody("plan-1", node, modelId, cls, "test"))
    }

    @Test fun sendsOneModelReqPerAssignmentWithTheSpecDeadline() = runTest {
        val r = Rig(this)
        val out = r.co.request(mapOf("vlm" to 2, "llm.json" to 3))
        assertEquals(3, out.assignments.size)
        val byCap = r.sent.groupBy { it.body.capability }
        val vlm = byCap.getValue("vlm").single()
        assertEquals("b2", vlm.to); assertEquals(MsgType.MODEL_REQ, vlm.type)
        assertEquals(24_000L, vlm.body.deadlineMs)                                  // 3 × 8000 > 10 s
        assertEquals("a1", vlm.body.commander); assertEquals(ProfileFixtures.SHA_VLM, vlm.body.sha256)
        assertEquals(setOf("a1", "c3"), byCap.getValue("llm.json").map { it.to }.toSet())
        assertEquals(18_000L, byCap.getValue("llm.json").first().body.deadlineMs)
        assertEquals(10_000L, PlacementCoordinator.deadlineMs(ProfileFixtures.DET))  // floor
        assertEquals(setOf("n0", "n1", "n2"), r.sent.map { it.body.nonce }.toSet())
        assertEquals(3, r.journal.rows.count { it.state == "requested" })
        assertTrue(r.co.anyPending); assertEquals(setOf("a1", "c3"), r.co.pending("llm.json"))
    }

    @Test fun firstReadyOpensTheQueueLaterOnesAddPhones() = runTest {
        val r = Rig(this)
        r.co.request(mapOf("llm.json" to 3))
        assertTrue(r.co.onMessage(MsgType.MODEL_READY, "c3", r.ready("c3", "qwen3-4b-npu", 0, true)))
        assertEquals(listOf("llm.json@c3"), r.opened); assertTrue(r.added.isEmpty())
        assertTrue(r.co.onMessage(MsgType.MODEL_READY, "a1", r.ready("a1", "qwen3-4b-npu", 0, true)))
        assertEquals(listOf("llm.json@a1"), r.added)
        assertEquals(setOf("a1", "c3"), r.co.ready("llm.json"))
        assertFalse(r.co.anyPending); assertNull(r.co.nextWake())
        assertEquals(mapOf("llm.json" to mapOf("c3" to "qwen3-4b-npu", "a1" to "qwen3-4b-npu")), r.co.snapshot())
        assertEquals(2, r.journal.rows.count { it.state == "ready" })
        // not ours: another plan, a forged node, an unknown build
        assertFalse(r.co.onMessage(MsgType.MODEL_READY, "a1", MeshJson.encode(ModelReadyBody("plan-9", "a1", "qwen3-4b-npu", 0, true))))
        assertFalse(r.co.onMessage(MsgType.MODEL_READY, "a1", r.ready("c3", "qwen3-4b-npu")))
        assertFalse(r.co.onMessage(MsgType.TASK_RESULT, "a1", "{}"))
    }

    @Test fun aFailCoolsThePhoneAndResolvesOnOtherPhones() = runTest {
        val r = Rig(this)
        r.co.request(mapOf("vlm" to 1))
        assertEquals(listOf("b2"), r.sent.map { it.to })
        assertTrue(r.co.onMessage(MsgType.MODEL_FAIL, "b2", r.fail("b2", "qwen3vl", "oom")))
        assertTrue(r.cooldowns.isDown("b2", "vlm"))
        assertEquals(listOf("b2", "a1"), r.sent.map { it.to })                       // re-solved: a1 swaps its LLM out
        assertEquals("qwen3vl", r.sent.last().body.modelId)
        assertTrue(r.journal.rows.any { it.nodeId == "b2" && it.state == "failed:oom" })
        assertTrue(r.co.failures.single().contains("b2 could not load qwen3vl for vlm (oom"))
        assertTrue(r.gaps.isEmpty())
        r.co.onMessage(MsgType.MODEL_FAIL, "a1", r.fail("a1", "qwen3vl", "model_load"))
        assertEquals(listOf("b2", "a1", "c3"), r.sent.map { it.to })                 // the remote phone is asked last
        assertTrue(r.gaps.isEmpty())
        r.co.onMessage(MsgType.MODEL_FAIL, "c3", r.fail("c3", "qwen3vl", "thermal"))
        assertEquals(listOf("vlm"), r.gaps)                                            // nobody left to ask
        assertEquals(3, r.sent.size); assertEquals(3, r.co.failures.size)
    }

    @Test fun aMissedDeadlineIsARuntimeFailureWithCooldown() = runTest {
        val r = Rig(this)
        r.co.request(mapOf("vlm" to 1))
        val watcher = r.co.watch(backgroundScope)
        assertEquals(ProfileFixtures.T0 + 24_000, r.co.nextWake())
        advanceTimeBy(23_999); runCurrent()
        assertEquals(1, r.sent.size); assertTrue(r.cooldowns.all().isEmpty())
        advanceTimeBy(2); runCurrent()
        assertTrue(r.cooldowns.isDown("b2", "vlm"))
        assertTrue(r.journal.rows.any { it.state == "failed:runtime" })
        assertEquals(listOf("b2", "a1"), r.sent.map { it.to })                       // re-solved on a1
        assertEquals(ProfileFixtures.T0 + 24_000 + 24_000, r.co.nextWake())          // expired and re-asked at the deadline
        r.co.onMessage(MsgType.MODEL_READY, "a1", r.ready("a1", "qwen3vl", 7900))
        assertEquals(listOf("vlm@a1"), r.opened)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(watcher.isCompleted)                                               // nothing pending: the watcher ends
        assertNull(r.co.nextWake())
    }

    @Test fun unsentRequestsFailWithoutCooldownAndReadyAfterFallbackCounts() = runTest {
        val r = Rig(this, sendOk = { it != "c3" })
        r.co.request(mapOf("llm.json" to 3))
        assertTrue(r.sent.any { it.to == "c3" })
        assertFalse(r.cooldowns.isDown("c3", "llm.json"))
        assertEquals(setOf("a1"), r.co.pending("llm.json"))
        assertTrue(r.co.failures.single().startsWith("c3 could not load"))
        // the node fell back to the llama_cpp build: still a READY for llm.json
        assertTrue(r.co.onMessage(MsgType.MODEL_READY, "a1", r.ready("a1", "qwen2.5-coder-1.5b-instruct-q4_0", 2900)))
        assertEquals("qwen2.5-coder-1.5b-instruct-q4_0", r.co.readyModel("llm.json", "a1"))
        assertEquals(listOf("llm.json@a1"), r.opened)
        r.co.drop("a1", "llm.json")
        assertTrue(r.co.ready("llm.json").isEmpty())
    }

    @Test fun endToEndWithTheNodeHandlerOverLambdas() = runTest {
        val pools = ProfileFixtures.all.associate { it.nodeId to FakeCapabilityPool(ProfileFixtures.catalog) }
        pools.getValue("b2").preload(ProfileFixtures.VLM); pools.getValue("a1").preload(ProfileFixtures.LLM); pools.getValue("c3").preload(ProfileFixtures.LLM)
        pools.getValue("b2").fail["sd15"] = "oom"
        val now: () -> Long = { ProfileFixtures.T0 + testScheduler.currentTime }
        val cooldowns = CooldownTable(now)
        lateinit var co: PlacementCoordinator
        val handlers = pools.mapValues { (id, pool) -> ModelReqHandler(id, ProfileFixtures.catalog, pool, backgroundScope, CooldownTable(now)) }
        val opened = ArrayList<String>(); val gaps = ArrayList<String>()
        co = PlacementCoordinator("job-2", "plan-2", "a1", ProfileFixtures.catalog, cooldowns, { ProfileFixtures.all },
            send = { to, _, body ->
                handlers.getValue(to).handle(MeshJson.decode(body), "a1") { t, b -> co.onMessage(t, to, b); Result.success(Unit) }
                Result.success(Unit)
            }, clock = now).apply { onOpen = { c, n -> opened += "$c@$n" }; onGap = { c, _ -> gaps += c } }
        co.request(mapOf("llm.json" to 2, "vlm" to 1, "gen.image" to 2, "detect" to 50))
        runCurrent()
        assertEquals(setOf("llm.json", "vlm", "detect"), opened.map { it.substringBefore("@") }.toSet())
        assertEquals(setOf("c3"), co.ready("vlm"))                                   // b2's one big slot was promised to sd15 (placed first, largest demand)
        assertEquals(setOf("c3"), co.ready("detect"))                                // a1 and b2 are busy with their big models; c3 finishes all 50 in 2 s
        assertEquals(listOf("gen.image"), gaps)                                        // b2's sd15 is out of memory, nobody else is heavyOk
        assertTrue(cooldowns.isDown("b2", "gen.image"))
        assertEquals(setOf("a1"), co.ready("llm.json"))                               // two units: a second phone would not finish one
        assertFalse(co.anyPending)
    }
}
