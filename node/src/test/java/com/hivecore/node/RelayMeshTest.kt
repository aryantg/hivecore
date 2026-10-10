package com.hivecore.node

import com.hivecore.core.LinkKind
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.fakes.FakeRelay
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 5.1 / 19 phase 6 on the JVM: three phones on one LAN (FakeBus) with the relay always-on beside it, and a
 * fourth phone in another city reached through the relay only (FakeRelay). The remote phone joins the swarm, its
 * PROFILE arrives with the WAN_RELAY link the receiver measured, and it takes text chunks of a split job while the
 * LAN phones keep the rest.
 */
class RelayMeshTest {
    private fun texts(n: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", "item number $i") } }

    @Test fun remotePhoneJoinsOverTheRelayReceivesProfilesAndTakesTextChunks() = runTest {
        val relay = FakeRelay(clock = { 1_700_000_000_000L + testScheduler.currentTime })
        val h = SwarmHarness(this, relay = relay)
        try {
            val (hr, sales, accounts) = h.hrSalesAccounts()
            val remote = h.node("remote", "Remote", NodeRole.ACCOUNTS, remote = true)
            val nodes = listOf(hr, sales, accounts, remote)
            nodes.forEach { it.runner.register(ResumeMeshTest.HashEmbed(5)) }
            h.start()
            advanceTimeBy(3_000)                                                           // relay auth, sessions, HELLOs, PROFILEs
            for (n in nodes) n.swarm.beacon!!.announce()
            advanceTimeBy(500)

            // the remote phone is a member on every LAN phone's view, reached over the relay
            assertTrue(relay.online(remote.nodeId))
            assertTrue(sales.router.connectedNodeIds().contains(remote.nodeId))
            assertEquals(com.hivecore.mesh.RelayTransport.NAME, sales.router.endpointOf(remote.nodeId)!!.transport)
            val seen = sales.swarm.beacon!!.profileOf(remote.nodeId)
            assertTrue("remote profile not received", seen != null)
            assertEquals(LinkKind.WAN_RELAY, seen!!.link!!.kind)
            assertEquals(LinkKind.LAN, sales.swarm.beacon!!.profileOf(hr.nodeId)!!.link!!.kind)      // LAN peers keep the LAN cadence
            assertEquals(30_000L, sales.swarm.beacon!!.currentIntervalMs())                           // one WAN peer: the slow beat
            assertTrue(remote.swarm.beacon!!.snapshot().map { it.nodeId }.containsAll(nodes.map { it.nodeId }))

            // a text split job from Sales: the remote phone takes chunks over the relay
            val j = SplitJob("emb", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(texts(40)), chunks = 8)
            val p = sales.swarm.runJob(j, useMemo = false).toList().last()
            testScheduler.runCurrent()
            assertTrue(p.error, p.done)
            assertFalse(p.partialReason ?: "", p.partial)
            val remoteTasks = remote.got(MsgType.TASK).map { MeshJson.decode<TaskBody>(it.body) }.filter { it.planId.startsWith("emb#") }
            assertTrue("the remote phone took no chunk", remoteTasks.isNotEmpty())
            assertTrue(p.nodes.any { it.node == remote.nodeId && it.chunks > 0 })
            assertTrue(p.nodes.any { it.node != remote.nodeId && it.node != sales.nodeId && it.chunks > 0 })   // LAN phones worked too
            assertTrue(remoteTasks.all { it.task.blobs.isEmpty() })                                       // text only over the relay
        } finally { h.close() }
    }
}
