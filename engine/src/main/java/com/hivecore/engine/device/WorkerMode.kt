package com.hivecore.engine.device

import com.hivecore.core.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What worker mode needs from the platform (spec 4.5): a foreground service to start and stop, and the
 * battery-optimisation exemption to ask for once. [WorkerService.control] is the Android implementation; tests
 * use a fake.
 */
interface WorkerServiceControl {
    fun startService()
    fun stopService()
    val batteryExempt: Boolean
    /** Opens the system dialog for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; returns false when it cannot be shown. */
    fun requestBatteryExemption(): Boolean
}

/**
 * Worker mode (spec 4.5): off by default, opted in per phone. Opt-in starts the foreground service (OriginOS kills
 * background work otherwise) and asks for the battery-optimisation exemption once per opt-in; opt-out stops the
 * service. [bind] follows `WorkerPolicy.optIn`; [apply] is the pure step. A phone that is not opted in is still a
 * full owner and orchestrator: nothing here touches the mesh, which the service only keeps alive.
 */
class WorkerModeController(
    private val control: WorkerServiceControl,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var running: Boolean = false
        private set
    @Volatile var exemptionAskedAt: Long? = null
        private set

    /** Applies the opt-in state; idempotent. */
    @Synchronized
    fun apply(optIn: Boolean) {
        if (optIn) {
            if (!running) {
                runCatching { control.startService() }.onFailure { SLog.e(TAG, "start worker service", it) }
                running = true
            }
            if (!control.batteryExempt && exemptionAskedAt == null) {
                val shown = runCatching { control.requestBatteryExemption() }.getOrElse { SLog.e(TAG, "battery exemption", it); false }
                exemptionAskedAt = clock()
                SLog.d(TAG, if (shown) "asked for the battery-optimisation exemption" else "battery exemption dialog unavailable")
            }
        } else {
            if (running) {
                runCatching { control.stopService() }.onFailure { SLog.e(TAG, "stop worker service", it) }
                running = false
            }
            exemptionAskedAt = null
        }
    }

    fun bind(optIn: StateFlow<Boolean>, scope: CoroutineScope): Job = scope.launch { optIn.collect { apply(it) } }

    private companion object { const val TAG = "WorkerMode" }
}
