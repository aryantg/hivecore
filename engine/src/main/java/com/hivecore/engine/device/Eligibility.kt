package com.hivecore.engine.device

/**
 * Worker eligibility and the heavy-work gate (swarm plan §6.1). Pure, so the PROFILE heartbeat, the Settings
 * text and the tests agree on one definition.
 *
 * Thermal is the PowerManager status: 0 NONE, 1 LIGHT, 2 MODERATE … 6 SHUTDOWN.
 */
data class Signals(
    val optIn: Boolean,
    /** When the screen went off; null while it is on. */
    val screenOffSinceMs: Long?,
    val charging: Boolean,
    val appForeground: Boolean,
    val batteryPct: Int,
    val batteryTempC: Float?,
    val thermal: Int,
    val inCall: Boolean,
    val nowMs: Long,
)

object Eligibility {
    const val SCREEN_OFF_IDLE_MS = 60_000L
    const val MIN_BATTERY_PCT = 40
    // The thermal limits are the spec's (§6.1) unless a demo build relaxed them at start-up ([relaxForDemo]).
    @Volatile var MAX_THERMAL = 1
        private set
    /** Worker eligibility needs the battery strictly under this. */
    @Volatile var MAX_BATTERY_TEMP_C = 38f
        private set
    /** Heavy work stops at or above this battery temperature. */
    @Volatile var HEAVY_STOP_TEMP_C = 40f
        private set
    /** Heavy work stops at or above this thermal status. */
    @Volatile var HEAVY_STOP_THERMAL = 2
        private set

    /**
     * Demo builds (`-PhiveDemoThermal`): phones stay eligible up to SEVERE (3) and a 45 °C battery; heavy work still
     * stops at CRITICAL (4) or 47 °C. Not for a gate run: the spec limits are the defaults.
     */
    fun relaxForDemo() { MAX_THERMAL = 3; MAX_BATTERY_TEMP_C = 45f; HEAVY_STOP_TEMP_C = 47f; HEAVY_STOP_THERMAL = 4 }

    /** Back to the spec limits. */
    fun specLimits() { MAX_THERMAL = 1; MAX_BATTERY_TEMP_C = 38f; HEAVY_STOP_TEMP_C = 40f; HEAVY_STOP_THERMAL = 2 }
}

/** Screen off for at least 60 s, or on the charger with the app not in the foreground. */
fun idle(s: Signals): Boolean {
    val off = s.screenOffSinceMs
    val screenIdle = off != null && s.nowMs - off >= Eligibility.SCREEN_OFF_IDLE_MS
    return screenIdle || (s.charging && !s.appForeground)
}

/**
 * optIn && idle && (batteryPct ≥ 40 || charging) && thermal ≤ 1 && (batteryTempC == null || < 38) && !inCall.
 */
fun eligible(s: Signals): Boolean =
    s.optIn &&
        idle(s) &&
        (s.batteryPct >= Eligibility.MIN_BATTERY_PCT || s.charging) &&
        s.thermal <= Eligibility.MAX_THERMAL &&
        (s.batteryTempC == null || s.batteryTempC < Eligibility.MAX_BATTERY_TEMP_C) &&
        !s.inCall

/** True when heavy work must stop now: thermal ≥ 2 or battery temperature ≥ 40 °C. */
fun heavyStop(thermal: Int, batteryTempC: Float?): Boolean =
    thermal >= Eligibility.HEAVY_STOP_THERMAL || (batteryTempC != null && batteryTempC >= Eligibility.HEAVY_STOP_TEMP_C)

/**
 * Heavy class gate (GEN_IMAGE, UPSCALE, long ASR, video DETECT, SUPERRES): eligible, on the charger, thermal ≤ 1,
 * not past the stop limits, and [duty] (if given) not in its cool-down pause.
 */
fun heavyOk(s: Signals, duty: DutyCycle? = null): Boolean =
    eligible(s) &&
        s.charging &&
        s.thermal <= Eligibility.MAX_THERMAL &&
        !heavyStop(s.thermal, s.batteryTempC) &&
        (duty == null || !duty.pausing(s.nowMs))

/**
 * Heavy-work duty cycle (§6.1): after [workMs] of accumulated NPU time, pause for [pauseMs]. Busy time recorded
 * during a pause does not count towards the next window. Thread-safe; the clock is passed in, so it is testable.
 */
class DutyCycle(val workMs: Long = 120_000L, val pauseMs: Long = 30_000L) {
    private var busyMs = 0L
    private var pauseUntilMs = Long.MIN_VALUE
    private var runningSinceMs: Long? = null

    /** Accumulated NPU time in the current window (excluding an open [begin] span). */
    @get:Synchronized val accumulatedMs: Long get() = busyMs

    /** Adds [ms] of finished NPU time ending at [nowMs]; may start a pause. */
    @Synchronized
    fun record(ms: Long, nowMs: Long) {
        if (ms <= 0) return
        if (nowMs < pauseUntilMs) return
        busyMs += ms
        if (busyMs >= workMs) {
            busyMs = 0
            pauseUntilMs = nowMs + pauseMs
        }
    }

    /** Marks the start of an NPU span; close it with [end]. */
    @Synchronized
    fun begin(nowMs: Long) { if (runningSinceMs == null) runningSinceMs = nowMs }

    /** Closes the span opened by [begin] and records its length. */
    @Synchronized
    fun end(nowMs: Long) {
        val start = runningSinceMs ?: return
        runningSinceMs = null
        record(nowMs - start, nowMs)
    }

    /** True during a cool-down pause, or when an open span has already used up the window. */
    @Synchronized
    fun pausing(nowMs: Long): Boolean {
        if (nowMs < pauseUntilMs) return true
        val open = runningSinceMs?.let { nowMs - it } ?: 0L
        return busyMs + open >= workMs
    }

    /** Milliseconds left in the current pause (0 when not pausing on the clock). */
    @Synchronized
    fun pauseRemainingMs(nowMs: Long): Long = if (nowMs < pauseUntilMs) pauseUntilMs - nowMs else 0L

    @Synchronized
    fun reset() { busyMs = 0; pauseUntilMs = Long.MIN_VALUE; runningSinceMs = null }
}
