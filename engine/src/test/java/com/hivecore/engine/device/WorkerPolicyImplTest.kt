package com.hivecore.engine.device

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.LlmBackend
import com.hivecore.core.fakes.FakeDeviceState
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.MemoryPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerPolicyImplTest {
    private class Rig(scope: TestScope) {
        val device = FakeDeviceState()
        val presence = MutableStateFlow(Presence(screenOn = true, batteryPct = 80))
        val engine = FakeLlmEngine(LlmBackend.CPU)
        val prefs = MemoryPrefs()
        val queue = MutableStateFlow(0)
        var ticks = 0
        val policy = WorkerPolicyImpl("node-a", device, presence, engine, prefs, queue, scope.backgroundScope,
            onTick = { ticks++ }, clock = { scope.testScheduler.currentTime })
    }

    @Test fun optInDefaultsOffAndPersists() = runTest {
        val r = Rig(this)
        assertFalse(r.policy.optIn.value)
        assertFalse(r.policy.profile.value.workerOptIn)
        r.policy.setOptIn(true)
        assertEquals(true, r.prefs.map[WorkerPolicyImpl.PREF_WORKER_OPT_IN])
        assertTrue(r.policy.profile.value.workerOptIn)
        val again = WorkerPolicyImpl("node-a", r.device, r.presence, r.engine, r.prefs, r.queue, backgroundScope)
        assertTrue(again.optIn.value)
    }

    @Test fun screenOffBecomesEligibleAfterSixtySecondsViaTick() = runTest {
        val r = Rig(this)
        r.policy.setOptIn(true)
        runCurrent()
        r.presence.value = r.presence.value.copy(screenOn = false, screenOffSinceMs = testScheduler.currentTime)
        runCurrent()
        assertFalse(r.policy.profile.value.eligible)
        assertFalse(r.policy.profile.value.idle)
        advanceTimeBy(60_001)       // no new input: the 15 s tick must notice
        runCurrent()
        assertTrue(r.policy.profile.value.idle)
        assertTrue(r.policy.profile.value.eligible)
        assertFalse(r.policy.profile.value.heavyOk)      // on battery
        assertTrue(r.ticks >= 4)
    }

    @Test fun inputsFlowIntoProfile() = runTest {
        val r = Rig(this)
        r.policy.setOptIn(true)
        r.device.chargingState.value = true
        r.queue.value = 3
        runCurrent()
        val p = r.policy.profile.value
        assertEquals("node-a", p.nodeId)
        assertTrue(p.charging); assertTrue(p.idle); assertTrue(p.eligible); assertTrue(p.heavyOk)
        assertEquals(3, p.queueDepth)
        assertEquals(LlmBackend.CPU, p.backend)
        assertEquals("llm", p.big)
        assertEquals(80, p.batteryPct)

        r.device.thermalState.value = 2
        runCurrent()
        assertFalse(r.policy.profile.value.eligible)
        r.device.thermalState.value = 0
        r.presence.value = r.presence.value.copy(inCall = true)
        runCurrent()
        assertFalse(r.policy.profile.value.eligible)
        assertTrue(r.policy.profile.value.inCall)
        r.presence.value = r.presence.value.copy(inCall = false, appForeground = true)
        runCurrent()
        assertFalse(r.policy.profile.value.eligible)
    }

    @Test fun tokPerSecIsRollingMedian() = runTest {
        val r = Rig(this)
        runCurrent()
        assertNull(r.policy.profile.value.tokPerSec)
        r.engine.generate(listOf(ChatMsg("user", "hi")), GenParams())
        runCurrent()
        assertEquals(100.0, r.policy.profile.value.tokPerSec!!, 1e-9)
    }

    @Test fun npuGenerationTimeFeedsDutyCycle() = runTest {
        val r = Rig(this)
        r.engine.load(LlmBackend.NPU)
        r.policy.setOptIn(true)
        r.device.chargingState.value = true
        runCurrent()
        assertTrue(r.policy.profile.value.heavyOk)
        r.policy.dutyCycle.record(120_000, testScheduler.currentTime)
        r.policy.recompute()
        assertFalse(r.policy.profile.value.heavyOk)
        assertTrue(r.policy.profile.value.eligible)
        advanceTimeBy(30_001); runCurrent()
        assertTrue(r.policy.profile.value.heavyOk)
        r.engine.generate(listOf(ChatMsg("user", "hi")), GenParams())   // fake stats: 2 ms of NPU time
        runCurrent()
        assertEquals(2L, r.policy.dutyCycle.accumulatedMs)
    }
}
