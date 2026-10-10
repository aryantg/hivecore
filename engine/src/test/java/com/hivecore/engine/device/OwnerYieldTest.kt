package com.hivecore.engine.device

import com.hivecore.engine.TaskRunnerImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner always wins (spec 11.3): a yield within 1 s of screen-on, on a fake clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerYieldTest {
    @Test fun screenOnUnlockedYieldsAtOnceLockedDoesNot() {
        var now = 10_000L
        var yields = 0; var resumes = 0
        val y = OwnerYield({ yields++ }, { resumes++ }, { now })
        val tracker = PresenceTracker(Presence(screenOn = false, screenOffSinceMs = 0))
        y.onPresence(tracker.state)
        tracker.screenOn(now, locked = true); assertFalse(y.onPresence(tracker.state))       // a notification lit the screen
        assertEquals(0, yields)
        tracker.userPresent(); now += 3                                                        // the yield runs 3 ms after the unlock
        assertTrue(y.onPresence(tracker.state, eventAt = now - 3))
        assertEquals(1, yields); assertEquals(3L, y.lastLatencyMs); assertTrue(y.lastLatencyMs!! <= 1_000)
        assertTrue(y.isYielding)
        assertFalse(y.onPresence(tracker.state))                                              // still on: no second yield
        tracker.screenOff(now, locked = true); y.onPresence(tracker.state)
        y.tick(now + 59_999); assertEquals(0, resumes); assertTrue(y.isYielding)
        y.tick(now + 60_000); assertEquals(1, resumes); assertFalse(y.isYielding)
        tracker.screenOn(now + 70_000, locked = false); assertTrue(y.onPresence(tracker.state))
        assertEquals(2, yields)
    }

    @Test fun appToForegroundAndTheMonitorCallbackYieldToo() {
        var now = 0L
        val runner = TaskRunnerImpl("me")
        val y = OwnerYield(runner::yieldAll, runner::resumeWorkers, { now })
        val t = PresenceTracker(Presence(screenOn = true, locked = true))
        y.onPresence(t.state)
        t.appForeground(true); assertTrue(y.onPresence(t.state))
        assertTrue(runner.yielding)
        t.appForeground(false); assertFalse(y.onPresence(t.state))
        y.ownerActive(eventAt = now - 1)                                                       // PresenceMonitor.onOwnerActive path
        assertEquals(2, y.yields); assertEquals(1L, y.lastLatencyMs)
        y.tick(now); assertTrue(runner.yielding)                                               // screen still on: no resume
    }

    @Test fun boundToAFlowTheYieldLandsWithinASecondOnTheTestClock() = runTest {
        val presence = MutableStateFlow(Presence(screenOn = false, screenOffSinceMs = 0, locked = true))
        var yieldedAt: Long? = null
        val y = OwnerYield({ yieldedAt = testScheduler.currentTime }, clock = { testScheduler.currentTime })
        y.bind(presence, backgroundScope)
        advanceTimeBy(90_000); runCurrent()
        assertNull(yieldedAt)
        val screenOnAt = testScheduler.currentTime
        presence.value = Presence(screenOn = true, locked = false)
        advanceTimeBy(1_000); runCurrent()
        assertTrue("yield at $yieldedAt for screen-on at $screenOnAt", yieldedAt != null && yieldedAt!! - screenOnAt <= 1_000)
        assertEquals(1, y.yields)
    }
}
