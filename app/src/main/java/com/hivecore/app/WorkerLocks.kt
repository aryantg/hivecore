package com.hivecore.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.hivecore.core.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a worker phone working with its screen off (phone gate finding: with no lock the sleeping workers ran OCR at
 * 200-900 ms a photo against 130 ms awake, and photos reached them at about 1 MB/s). A partial wake lock is held while
 * the task runner has work queued or running; a low-latency Wi-Fi lock while this phone advertises itself eligible, so
 * the photos of the next chunk arrive at full speed. Both are released as soon as the condition ends.
 */
class WorkerLocks(context: Context) {
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hivecore:worker").apply { setReferenceCounted(false) }
    private val wifi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "hivecore:worker").apply { setReferenceCounted(false) }

    fun bind(queueDepth: StateFlow<Int>, eligible: kotlinx.coroutines.flow.Flow<Boolean>, scope: CoroutineScope) {
        scope.launch {
            queueDepth.map { it > 0 }.distinctUntilChanged().collect { busy ->
                runCatching { if (busy) wake.acquire(WAKE_TIMEOUT_MS) else if (wake.isHeld) wake.release() }
                    .onFailure { SLog.w(TAG, "wake lock: ${it.message}") }
            }
        }
        scope.launch {
            eligible.distinctUntilChanged().collect { on ->
                runCatching { if (on) wifi.acquire() else if (wifi.isHeld) wifi.release() }
                    .onFailure { SLog.w(TAG, "wifi lock: ${it.message}") }
                SLog.d(TAG, "wifi lock ${if (on) "held" else "released"}")
            }
        }
    }

    private companion object {
        const val TAG = "WorkerLocks"
        /** A safety cap: a stuck queue never keeps the CPU awake for longer than this. */
        const val WAKE_TIMEOUT_MS = 10 * 60_000L
    }
}
