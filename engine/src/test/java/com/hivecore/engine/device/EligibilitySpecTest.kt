package com.hivecore.engine.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of spec 4.5 and the constants of 20.2, line by line, over [Signals]. */
class EligibilitySpecTest {
    private val now = 5_000_000L
    private fun idleOnBattery() = Signals(optIn = true, screenOffSinceMs = now - 60_000, charging = false, appForeground = false,
        batteryPct = 40, batteryTempC = 37.9f, thermal = 1, inCall = false, nowMs = now)
    private fun onCharger() = idleOnBattery().copy(charging = true, screenOffSinceMs = null, batteryPct = 5)

    @Test fun constantsMatchTheSpec() {
        assertEquals(60_000L, Eligibility.SCREEN_OFF_IDLE_MS)
        assertEquals(40, Eligibility.MIN_BATTERY_PCT)
        assertEquals(1, Eligibility.MAX_THERMAL)
        assertEquals(38f, Eligibility.MAX_BATTERY_TEMP_C)
        assertEquals(40f, Eligibility.HEAVY_STOP_TEMP_C)
        assertEquals(2, Eligibility.HEAVY_STOP_THERMAL)
        val d = DutyCycle()
        assertEquals(120_000L, d.workMs); assertEquals(30_000L, d.pauseMs)
    }

    @Test fun idleIsScreenOffSixtySecondsOrChargingWithTheAppInTheBackground() {
        assertTrue(idle(idleOnBattery()))
        assertFalse(idle(idleOnBattery().copy(screenOffSinceMs = now - 59_999)))
        assertTrue(idle(onCharger()))
        assertFalse(idle(onCharger().copy(appForeground = true)))
        assertTrue(idle(onCharger().copy(appForeground = true, screenOffSinceMs = now - 60_000)))   // the screen-off branch still holds
        assertFalse(idle(idleOnBattery().copy(screenOffSinceMs = null)))
    }

    @Test fun eligibleIsEveryConjunctOfTheSpecLine() {
        val base = idleOnBattery()
        assertTrue(eligible(base))                                       // at the exact boundaries: 40 %, thermal 1, 37.9 C
        assertFalse(eligible(base.copy(optIn = false)))
        assertFalse(eligible(base.copy(screenOffSinceMs = now - 59_999)))
        assertFalse(eligible(base.copy(batteryPct = 39)))
        assertTrue(eligible(base.copy(batteryPct = 0, charging = true)))
        assertFalse(eligible(base.copy(thermal = 2)))
        assertFalse(eligible(base.copy(batteryTempC = 38f)))
        assertFalse(eligible(base.copy(inCall = true)))
        assertTrue(eligible(onCharger()))                                // charging covers both idle and the battery floor
    }

    @Test fun heavyOkNeedsChargerCoolBatteryAndNoDutyPause() {
        val s = onCharger().copy(batteryTempC = 37.9f, thermal = 1)
        assertTrue(heavyOk(s))
        assertFalse(heavyOk(s.copy(batteryTempC = 38f)))                 // eligibility ends at 38 C, before the 40 C heavy stop
        assertTrue(heavyStop(0, 40f)); assertFalse(heavyStop(0, 39.9f)); assertTrue(heavyStop(2, 20f))
        assertFalse(heavyOk(s.copy(charging = false, batteryPct = 90, screenOffSinceMs = now - 60_000)))   // eligible but on battery
        assertFalse(heavyOk(s.copy(thermal = 2)))
        assertFalse(heavyOk(s.copy(inCall = true)))
        assertFalse(heavyOk(s.copy(optIn = false)))
        val duty = DutyCycle()
        duty.record(120_000, now)                                        // 2 min of NPU work → 30 s pause
        assertFalse(heavyOk(s, duty))
        assertTrue(eligible(s))                                          // the pause only gates HEAVY
        assertFalse(heavyOk(s.copy(nowMs = now + 29_999), duty))
        assertTrue(heavyOk(s.copy(nowMs = now + 30_000), duty))
    }

    @Test fun dutyCycleRollsOverWindows() {
        val d = DutyCycle()
        var t = 0L
        repeat(3) {
            d.record(120_000, t); assertTrue(d.pausing(t)); assertEquals(30_000L, d.pauseRemainingMs(t))
            t += 30_000
            assertFalse(d.pausing(t))
        }
        d.reset(); assertFalse(d.pausing(t)); assertEquals(0L, d.accumulatedMs)
    }
}
