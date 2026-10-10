package com.hivecore.engine

import com.hivecore.core.LinkKind
import com.hivecore.core.Link
import com.hivecore.core.LlmBackend
import com.hivecore.core.MemoryPrefs
import com.hivecore.core.Profile
import com.hivecore.core.fakes.FakeDeviceState
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.engine.device.Presence
import com.hivecore.engine.device.WorkerPolicyImpl
import com.hivecore.engine.placement.CooldownTable
import com.hivecore.engine.placement.ModelReqHandler
import com.hivecore.engine.placement.ProfileFixtures
import com.hivecore.engine.split.SplitCommander
import com.hivecore.mesh.BeaconCadence
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ProfileBody
import com.hivecore.runtime.fakes.FakeCapabilityPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 4.6 / 5.5 / 20.2 wiring: the beacon's cadence follows the link classes of its peers, a received profile's
 * `link` is the session layer's measurement, and capability cooldowns from the split commander and from placement
 * reach the profile through the worker policy.
 */
class BeaconCadenceWiringTest {

    @Test fun cadenceAndStaleWindowFollowThePeersLinkClasses() = runTest {
        var now = 1_000_000L
        val links = mutableMapOf("lan" to Link.defaultFor(LinkKind.LAN), "wan" to Link(LinkKind.WAN_RELAY, 800, 120, metered = true))
        val sent = ArrayList<Long>()
        val b = ProfileBeacon("me", FakeWorkerPolicy(Profile("me", 0L)), { _, _ -> sent += now }, clock = { now }, linkOf = { links[it] })
        assertEquals(BeaconCadence.LAN_INTERVAL_MS, b.currentIntervalMs())                 // no peers: LAN cadence
        b.onProfile("lan", MeshJson.encode(ProfileBody(Profile("lan", ts = 10))))
        assertEquals(15_000L, b.currentIntervalMs())
        assertEquals(45_000L, b.staleMsOf("lan"))
        b.onProfile("wan", MeshJson.encode(ProfileBody(Profile("wan", ts = 10, link = Link.defaultFor(LinkKind.LAN)))))
        // the receiver's measurement wins over the sender's claim, and the WAN peer sets the cadence of the whole beacon
        assertEquals(LinkKind.WAN_RELAY, b.profileOf("wan")!!.link!!.kind)
        assertEquals(true, b.profileOf("wan")!!.link!!.metered)
        assertEquals(30_000L, b.currentIntervalMs())
        assertEquals(90_000L, b.staleMsOf("wan"))
        now += 45_001
        assertEquals(listOf("me", "wan"), b.snapshot().map { it.nodeId })                   // the LAN peer missed three beats
        assertNull(b.profileOf("lan"))
        now += 45_000
        assertEquals(listOf("me"), b.snapshot().map { it.nodeId })                          // 90 s: the WAN peer too
        assertEquals(15_000L, b.currentIntervalMs())
    }

    @Test fun heartbeatSlowsDownOnceAWanPeerIsSeen() = runTest {
        val sent = ArrayList<Long>()
        val links = mutableMapOf<String, Link>()
        val b = ProfileBeacon("me", FakeWorkerPolicy(Profile("me", 0L)), { _, _ -> sent += testScheduler.currentTime },
            clock = { testScheduler.currentTime }, linkOf = { links[it] })
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            b.start(scope)
            runCurrent()
            assertEquals(listOf(0L), sent)
            advanceTimeBy(15_001)
            assertEquals(listOf(0L, 15_000L), sent)
            links["wan"] = Link(LinkKind.WAN_DIRECT, 2000, 80)
            b.onProfile("wan", MeshJson.encode(ProfileBody(Profile("wan", ts = 1))))
            advanceTimeBy(15_000)                                                            // the beat already scheduled fires at 30 s
            assertEquals(listOf(0L, 15_000L, 30_000L), sent)
            advanceTimeBy(30_000)                                                            // then every 30 s, not 15: nothing at 45 s
            assertEquals(listOf(0L, 15_000L, 30_000L, 60_000L), sent)
        } finally { scope.cancel() }
    }

    @Test fun cooldownsFromTheSplitCommanderAndPlacementReachTheProfile() = runTest {
        val now = { 9_000_000L + testScheduler.currentTime }
        val table = CooldownTable(now)
        val pool = FakeCapabilityPool(ProfileFixtures.catalog)
        val policy = WorkerPolicyImpl("b2", FakeDeviceState(), MutableStateFlow(Presence(screenOn = true, batteryPct = 80)), FakeLlmEngine(LlmBackend.NPU),
            MemoryPrefs(), MutableStateFlow(0), backgroundScope, pool = pool, clock = now, catalog = { ProfileFixtures.catalog }, cooldowns = table)
        runCurrent()
        assertTrue(policy.profile.value.cooldowns.isEmpty())
        // the split commander's cooldown hook (a TASK_RESULT of class oom on this phone)
        val sc = SplitCommander("b2", profiles = { emptyList() }, transmit = { _, _, _ -> Result.success(Unit) }, clock = now,
            onCooldown = { n, c, until -> table.mark(n, c, until) })
        sc.cooldown("b2", "embed")
        sc.cooldown("a1", "embed")                                                          // another phone's: not ours to publish
        policy.recompute()
        assertEquals(listOf("embed"), policy.profile.value.cooldowns.map { it.capability })
        assertEquals(now() + 600_000, policy.profile.value.cooldowns.single().untilTs)
        // placement's table (this phone's own failed load)
        pool.fail["qwen3vl"] = "oom"
        val handler = ModelReqHandler("b2", ProfileFixtures.catalog, pool, backgroundScope, cooldowns = table)
        val (type, _) = handler.process(ModelReqBody("p1", "vlm", "qwen3vl", ProfileFixtures.SHA_VLM, 10_000, "b2", now(), "n1"), "b2")
        assertEquals(MsgType.MODEL_FAIL, type)
        policy.recompute()
        assertEquals(listOf("embed", "vlm"), policy.profile.value.cooldowns.map { it.capability })
    }
}
