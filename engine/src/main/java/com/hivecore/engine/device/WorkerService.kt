package com.hivecore.engine.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hivecore.core.SLog

/**
 * The worker foreground service (spec 4.5, 5.5): started when the user opts in, stopped on opt-out. Its only job is
 * to keep the process alive under OriginOS so the mesh transports and the relay socket stay open; the work itself
 * runs in the task runner the app owns. Type `connectedDevice | dataSync`; the notification is the one line the
 * system requires. The app sets [keepalive] before opting in (the integration wave wires the transports in).
 *
 * Manifest (app module): `<service android:name="com.hivecore.engine.device.WorkerService"
 * android:foregroundServiceType="connectedDevice|dataSync" android:exported="false" />` plus the
 * `FOREGROUND_SERVICE*` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permissions already declared.
 */
class WorkerService : Service() {

    /** What the service keeps alive while it runs: start on create, stop on destroy. */
    interface Keepalive { fun start(); fun stop() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, notification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            SLog.e(TAG, "startForeground", e)
        }
        runCatching { keepalive?.start() }.onFailure { SLog.e(TAG, "keepalive start", it) }
        SLog.d(TAG, "worker service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { keepalive?.stop() }.onFailure { SLog.e(TAG, "keepalive stop", it) }
        SLog.d(TAG, "worker service stopped")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "hivecore.worker"
        const val NOTIFICATION_ID = 4201
        private const val TAG = "WorkerService"

        /** Set by the app before worker mode is switched on: the mesh and relay socket holders. */
        @Volatile var keepalive: Keepalive? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, WorkerService::class.java))
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, WorkerService::class.java))
        }

        fun isBatteryExempt(context: Context): Boolean =
            runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }
                .getOrDefault(false)

        /** The system dialog for the exemption (spec 4.5); false when no activity can show it. */
        fun requestBatteryExemption(context: Context): Boolean = runCatching {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        }.getOrElse { SLog.w(TAG, "battery exemption dialog: ${it.message}"); false }

        /** The [WorkerServiceControl] over this service, for [WorkerModeController]. */
        fun control(context: Context): WorkerServiceControl = object : WorkerServiceControl {
            private val app = context.applicationContext
            override fun startService() = start(app)
            override fun stopService() = stop(app)
            override val batteryExempt: Boolean get() = isBatteryExempt(app)
            override fun requestBatteryExemption(): Boolean = requestBatteryExemption(app)
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Worker mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "HiveCore is available to the other phones in your swarm while this phone is idle."
                setShowBadge(false)
            })
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("HiveCore worker mode")
                .setContentText("Available to your swarm while idle; picking the phone up pauses it.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }
}
