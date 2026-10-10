package com.hivecore.engine.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EligibilityTest {
    private val now = 1_000_000L
    /** Opted in, screen off 2 min, on battery at 80 %, cool, not in a call: eligible. */
    private fun base() = Signals(optIn = true, screenOffSinceMs = now - 120_000, charging = false, appForeground = false,
        batteryPct = 80, batteryTempC = 31f, thermal = 0, inCall = false, nowMs = now)

    @Test fun baseIsEligible() = assertTrue(eligible(base()))

    @Test fun optOutIsNeverEligible() = assertFalse(eligible(base().copy(optIn = false, charging = true)))

    @Test fun screenOffThreshold() {
        assertFalse(eligible(base().copy(screenOffSinceMs = now - 59_999)))
        assertTrue(eligible(base().copy(screenOffSinceMs = now - 60_000)))
        assertFalse(eligible(base().copy(screenOffSinceMs = null)))
    }

    @Test fun chargingWithAppInBackgroundCountsAsIdle() {
        assertTrue(eligible(base().copy(screenOffSinceMs = null, charging = true, appForeground = false)))
        assertFalse(eligible(base().copy(screenOffSinceMs = null, charging = true, appForeground = true)))
    }

    @Test fun batteryLevelUnlessCharging() {
        assertFalse(eligible(base().copy(batteryPct = 39)))
        assertTrue(eligible(base().copy(batteryPct = 40)))
        assertTrue(eligible(base().copy(batteryPct = 10, charging = true)))
    }

    @Test fun thermalAndTemperature() {
        assertTrue(eligible(base().copy(thermal = 1)))
        assertFalse(eligible(base().copy(thermal = 2)))
        assertTrue(eligible(base().copy(batteryTempC = 37.9f)))
        assertFalse(eligible(base().copy(batteryTempC = 38f)))
        assertTrue(eligible(base().copy(batteryTempC = null)))
    }

    @Test fun callBlocks() = assertFalse(eligible(base().copy(inCall = true)))

    @Test fun heavyNeedsCharger() {
        assertFalse(heavyOk(base()))
        assertTrue(heavyOk(base().copy(charging = true)))
        assertFalse(heavyOk(base().copy(charging = true, thermal = 2)))
        assertFalse(heavyOk(base().copy(charging = true, optIn = false)))
    }

    @Test fun heavyStopLimits() {
        assertFalse(heavyStop(1, 39.9f))
        assertTrue(heavyStop(2, 30f))
        assertTrue(heavyStop(0, 40f))
        assertFalse(heavyStop(0, null))
    }

    @Test fun dutyCyclePausesAfterTwoMinutesForThirtySeconds() {
        val d = DutyCycle()
        val s = base().copy(charging = true)
        d.record(60_000, now)
        assertFalse(d.pausing(now)); assertTrue(heavyOk(s, d))
        d.record(60_000, now)
        assertTrue(d.pausing(now)); assertFalse(heavyOk(s, d))
        assertEquals(30_000L, d.pauseRemainingMs(now))
        assertTrue(d.pausing(now + 29_999))
        assertFalse(d.pausing(now + 30_000))
        assertEquals(0L, d.accumulatedMs)
    }

    @Test fun busyTimeDuringPauseIsNotCounted() {
        val d = DutyCycle(workMs = 1_000, pauseMs = 500)
        d.record(1_000, 0)
        d.record(900, 100)        // during the pause: ignored
        assertEquals(0L, d.accumulatedMs)
        d.record(900, 600)
        assertFalse(d.pausing(600))
    }

    @Test fun openSpanCountsTowardsTheWindow() {
        val d = DutyCycle(workMs = 1_000, pauseMs = 500)
        d.begin(0)
        assertFalse(d.pausing(999))
        assertTrue(d.pausing(1_000))
        d.end(1_200)
        assertTrue(d.pausing(1_699))
        assertFalse(d.pausing(1_700))
    }
}
