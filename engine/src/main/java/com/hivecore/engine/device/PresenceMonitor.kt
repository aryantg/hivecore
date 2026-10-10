package com.hivecore.engine.device

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.hivecore.core.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Whether the owner is using the phone (swarm plan §6.1, R8). */
data class Presence(
    val screenOn: Boolean = true,
    /** When the screen went off; null while it is on. */
    val screenOffSinceMs: Long? = null,
    /** Keyguard showing (locked or swipe screen). */
    val locked: Boolean = false,
    val appForeground: Boolean = false,
    /** AudioManager mode is IN_CALL or IN_COMMUNICATION. */
    val inCall: Boolean = false,
    /** Battery level 0–100 (from the sticky ACTION_BATTERY_CHANGED); DeviceState has no level. */
    val batteryPct: Int = 0,
)

/**
 * Pure state machine behind [PresenceMonitor]: applies events to a [Presence] and says when the owner just became
 * active (screen on and unlocked, user present, or app to foreground), which must fire `TaskRunner.yieldAll()`.
 */
class PresenceTracker(initial: Presence = Presence()) {
    @Volatile var state: Presence = initial
        private set

    /** Returns true when this event means the owner picked the phone up. */
    @Synchronized
    fun screenOn(nowMs: Long, locked: Boolean): Boolean {
        val wasOff = !state.screenOn
        state = state.copy(screenOn = true, screenOffSinceMs = null, locked = locked)
        // No keyguard (or already dismissed): screen on is the owner.
        return wasOff && !locked
    }

    @Synchronized
    fun screenOff(nowMs: Long, locked: Boolean) {
        state = state.copy(screenOn = false, screenOffSinceMs = state.screenOffSinceMs ?: nowMs, locked = locked)
    }

    /** ACTION_USER_PRESENT: keyguard dismissed. Always owner activity. */
    @Synchronized
    fun userPresent(): Boolean {
        state = state.copy(screenOn = true, screenOffSinceMs = null, locked = false)
        return true
    }

    @Synchronized
    fun appForeground(on: Boolean): Boolean {
        val was = state.appForeground
        state = state.copy(appForeground = on)
        return on && !was
    }

    @Synchronized
    fun inCall(on: Boolean) { state = state.copy(inCall = on) }

    @Synchronized
    fun batteryPct(pct: Int) { state = state.copy(batteryPct = pct) }

    @Synchronized
    fun locked(on: Boolean) { state = state.copy(locked = on) }
}

/**
 * Android side of presence: ACTION_SCREEN_ON/OFF/USER_PRESENT receivers, KeyguardManager, the AudioManager mode
 * (no new permissions) and the battery level. Thin and untested; the logic is [PresenceTracker].
 *
 * Wiring: call [start] once (Graph.init), [setAppForeground] from MainActivity onStart/onStop, and add
 * `{ taskRunner.yieldAll() }` to [onOwnerActive]. [refresh] re-reads polled values (the WorkerPolicy tick calls it).
 */
class PresenceMonitor(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val tracker = PresenceTracker()
    private val state = MutableStateFlow(tracker.state)
    val presence: StateFlow<Presence> = state

    /** Fired on the caller's thread (main for receivers) when the owner picks the phone up. */
    val onOwnerActive: MutableList<() -> Unit> = CopyOnWriteArrayList()

    @Volatile private var started = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = clock()
            val active = when (i.action) {
                Intent.ACTION_SCREEN_ON -> tracker.screenOn(now, keyguardLocked())
                Intent.ACTION_SCREEN_OFF -> { tracker.screenOff(now, keyguardLocked()); false }
                Intent.ACTION_USER_PRESENT -> tracker.userPresent()
                else -> false
            }
            publish(active, i.action ?: "?")
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { onBattery(i); publish(false, "battery") }
    }
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        tracker.inCall(isCallMode(mode)); publish(false, "mode")
    }
    private val direct = Executor { it.run() }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            val now = clock()
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) tracker.screenOn(now, keyguardLocked()) else tracker.screenOff(now, keyguardLocked())
        } catch (e: Exception) { SLog.e(TAG, "initial screen state", e) }
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(app, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) { SLog.e(TAG, "screen receiver", e) }
        try {
            val sticky = ContextCompat.registerReceiver(app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            if (sticky != null) onBattery(sticky)
        } catch (e: Exception) { SLog.e(TAG, "battery receiver", e) }
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            tracker.inCall(isCallMode(am.mode))
            am.addOnModeChangedListener(direct, modeListener)
        } catch (e: Exception) { SLog.e(TAG, "audio mode listener", e) }
        state.value = tracker.state
        SLog.d(TAG, "started: ${tracker.state}")
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(screenReceiver) }
        runCatching { app.unregisterReceiver(batteryReceiver) }
        runCatching { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).removeOnModeChangedListener(modeListener) }
        SLog.d(TAG, "stopped")
    }

    /** MainActivity onStart(true) / onStop(false). Coming to the foreground is owner activity. */
    fun setAppForeground(on: Boolean) = publish(tracker.appForeground(on), if (on) "foreground" else "background")

    /** Re-reads keyguard and audio mode (polled values; cheap). */
    fun refresh() {
        runCatching { tracker.locked(keyguardLocked()) }
        runCatching { tracker.inCall(isCallMode((app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode)) }
        state.value = tracker.state
    }

    private fun publish(ownerActive: Boolean, why: String) {
        state.value = tracker.state
        if (ownerActive) {
            SLog.d(TAG, "owner active ($why)")
            for (cb in onOwnerActive) runCatching { cb() }.onFailure { SLog.e(TAG, "onOwnerActive callback", it) }
        }
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) tracker.batteryPct(level * 100 / scale)
    }

    private fun keyguardLocked(): Boolean =
        try { (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked } catch (_: Exception) { false }

    private companion object {
        const val TAG = "Presence"
        fun isCallMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
