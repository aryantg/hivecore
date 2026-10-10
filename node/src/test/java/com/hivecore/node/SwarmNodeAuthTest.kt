package com.hivecore.node

import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PlanBody
import com.hivecore.mesh.TaskBody
import com.hivecore.trust.PlanAuthoriser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10 (spec 6.5) through `SwarmNode.onPlanSeen` / `taskAuthoriser` wired to the trust track's [PlanAuthoriser], as the
 * app wires them: a TASK with no PLAN behind it is dropped and counted; composite plans and split chunks (the split
 * commander's PLAN with the job template and the task ids) are authorised.
 */
class SwarmNodeAuthTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    @Test fun unauthorisedTaskIsDroppedAndCountedWhilePlannedWorkRuns() = runTest {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val auth = PlanAuthoriser(clock = h.now)
        val dropped = ArrayList<Pair<String, String>>()
        hr.swarm.onPlanSeen = auth::onPlanSeen
        hr.swarm.taskAuthoriser = auth::authorise
        hr.swarm.onUnauthorisedTask = { from, why -> dropped += from to why }
        h.start()

        // Sales sends HR a TASK that no PLAN announced.
        val rogue = TaskBody("p-rogue", TaskSpec("x", Op.EXTRACT, buildJsonObject { put("q", "anything") }), emptyMap(), 4_000, "m1", sales.nodeId, h.now(), "n-rogue")
        sales.swarm.sendTo(hr.nodeId, MsgType.TASK, MeshJson.encode(rogue))
        testScheduler.runCurrent()
        assertEquals(1, dropped.size)
        assertTrue(dropped.single().second, dropped.single().first == sales.nodeId && dropped.single().second.contains("no PLAN"))
        assertEquals(1, auth.unauthorisedInLastMinute(sales.nodeId))
        assertEquals(0, hr.extract.runs)
        assertTrue(sales.got(MsgType.TASK_RESULT).none { MeshJson.decode<com.hivecore.mesh.TaskResultBody>(it.body).planId == "p-rogue" })

        // A composite plan from Sales: the PLAN is recorded first, so HR runs its share.
        val a = h.ask(sales, "Key points from the onboarding checklist", h.extractOnly()).answer
        assertEquals(1, a.plan!!.verified)
        assertEquals(1, dropped.size)

        // A split job from Sales: HR authorises chunk TASKs against the split commander's PLAN (template + task ids).
        listOf(hr, sales, accounts).forEach { it.runner.register(ResumeMeshTest.HashEmbed(1)) }
        val items = (0 until 30).map { i -> buildJsonObject { put("id", "t$i"); put("text", "item $i") } }
        val p = sales.swarm.runJob(SplitJob("emb", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(items), chunks = 6)).toList().last()
        testScheduler.runCurrent()
        assertTrue(p.error, p.done && !p.partial)
        assertEquals(1, dropped.size)                                                       // no chunk was dropped
        val plans = hr.got(MsgType.PLAN).map { MeshJson.decode<PlanBody>(it.body) }.filter { it.planId.startsWith("emb#") }
        assertEquals(1, plans.size)
        assertEquals(Op.EMBED, plans.single().dag.tasks.single().op)
        assertTrue(plans.single().chunkIds.containsAll((0 until 6).map { "c$it.a1" }))
        assertTrue(hr.got(MsgType.TASK).any { MeshJson.decode<TaskBody>(it.body).planId.startsWith("emb#") })
        assertTrue(p.nodes.any { it.node == hr.nodeId && it.chunks > 0 })
    }
}
