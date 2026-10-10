package com.hivecore.engine.device

import com.hivecore.core.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The owner always wins (spec P7, 11.3): screen-on (unlocked), unlock or app-to-foreground calls
 * `TaskRunner.yieldAll()` at once, so the yield lands well within the 1 s the phase 2 gate allows; once the phone is
 * idle again (screen off for 60 s with the app in the background, 4.5) [resumeWorkers] lets worker tasks in again.
 *
 * Pure: it is driven by [onPresence] (a [Presence] snapshot) and [tick] (the policy heartbeat, for the 60 s mark).
 * [bind] collects a presence flow; [hook] takes [PresenceMonitor]'s synchronous owner-active callback as well, so
 * on a phone the yield does not wait for a flow collector to be scheduled. [lastLatencyMs] records how long the
 * last yield took after the event it answered.
 */
class OwnerYield(
    private val yieldAll: () -> Unit,
    private val resumeWorkers: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    val idleAfterMs: Long = Eligibility.SCREEN_OFF_IDLE_MS,
) {
    @Volatile private var prev: Presence? = null
    @Volatile private var yielding = false

    @Volatile var yields: Int = 0
        private set
    @Volatile var lastYieldAt: Long? = null
        private set
    @Volatile var lastLatencyMs: Long? = null
        private set

    /** True between a yield and the next resume (the runner refuses worker tasks meanwhile). */
    val isYielding: Boolean get() = yielding

    /** Feeds one presence snapshot that happened at [eventAt]; returns true when it made the runner yield. */
    @Synchronized
    fun onPresence(p: Presence, eventAt: Long = clock()): Boolean {
        val was = prev
        prev = p
        if (ownerActive(was, p)) { fire(eventAt); return true }
        tick(clock())
        return false
    }

    /** The owner-active signal straight from the monitor (synchronous, on the receiver's thread). */
    @Synchronized
    fun ownerActive(eventAt: Long = clock()) { fire(eventAt) }

    /** Called at least every 15 s: resumes workers once the phone has been idle for [idleAfterMs]. */
    @Synchronized
    fun tick(nowMs: Long) {
        val p = prev ?: return
        if (!yielding) return
        val off = p.screenOffSinceMs
        if (!p.screenOn && !p.appForeground && off != null && nowMs - off >= idleAfterMs) {
            yielding = false
            SLog.d(TAG, "idle again: workers resume")
            runCatching(resumeWorkers).onFailure { SLog.e(TAG, "resumeWorkers", it) }
        }
    }

    private fun fire(eventAt: Long) {
        runCatching(yieldAll).onFailure { SLog.e(TAG, "yieldAll", it) }
        val now = clock()
        yielding = true
        yields++
        lastYieldAt = now
        lastLatencyMs = maxOf(0L, now - eventAt)
        SLog.d(TAG, "owner active: yielded in ${lastLatencyMs} ms")
    }

    fun bind(presence: Flow<Presence>, scope: CoroutineScope): Job = scope.launch { presence.collect { onPresence(it) } }

    /** Wires the monitor's synchronous callback and its presence flow. */
    fun hook(monitor: PresenceMonitor, scope: CoroutineScope): Job {
        monitor.onOwnerActive += { ownerActive() }
        return bind(monitor.presence, scope)
    }

    companion object {
        private const val TAG = "OwnerYield"

        /** Screen on and unlocked after being off or locked, or the app coming to the foreground. */
        fun ownerActive(prev: Presence?, now: Presence): Boolean {
            val unlocked = now.screenOn && !now.locked && (prev == null || !prev.screenOn || prev.locked)
            val foreground = now.appForeground && prev?.appForeground != true
            return unlocked || foreground
        }
    }
}
