package com.hivecore.app.demo

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.hivecore.engine.device.WorkerService
import com.hivecore.ui.ports.PermissionItem

/** What the demo needs from the system, as booleans (null = not applicable on this build or phone). */
data class PermState(
    val camera: Boolean,
    val nearby: Boolean,
    val bluetooth: Boolean,
    val notifications: Boolean,
    val allFiles: Boolean?,
    val batteryExempt: Boolean,
    val fullScreenIntent: Boolean?,
    val fingerprint: Boolean?,
)

/** The demo's permission checklist: pure wording over [PermState] (JVM-tested) plus the Android reads. */
object PermissionsChecklist {
    val RUNTIME: Map<String, Array<String>> = mapOf(
        "camera" to arrayOf(Manifest.permission.CAMERA),
        "nearby" to arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
        "bluetooth" to arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
        "notifications" to arrayOf(Manifest.permission.POST_NOTIFICATIONS),
    )

    fun items(s: PermState): List<PermissionItem> = listOfNotNull(
        PermissionItem("camera", "Camera", "Scan another phone's pairing QR code", s.camera),
        PermissionItem("nearby", "Nearby devices", "Find phones on the same Wi-Fi", s.nearby),
        PermissionItem("bluetooth", "Bluetooth", "Reach phones when Wi-Fi isolates them", s.bluetooth),
        PermissionItem("notifications", "Notifications", "Approval requests from other phones", s.notifications),
        s.fullScreenIntent?.let { PermissionItem("full_screen", "Full-screen alerts", "The approval screen opens over the lock screen", it) },
        s.allFiles?.let { PermissionItem("all_files", "All files access", "Read model bundles from Download/HiveCoreModels", it) },
        PermissionItem("battery", "Unrestricted battery", "Keeps this phone working for others with the screen off", s.batteryExempt),
        s.fingerprint?.let { PermissionItem("fingerprint", "Fingerprint", "Approve another phone's plan with one touch", it) },
    )

    fun read(ctx: Context, needsAllFiles: Boolean, approvalKeyReady: Boolean?): PermState {
        fun has(vararg p: String) = p.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return PermState(
            camera = has(*RUNTIME.getValue("camera")),
            nearby = has(*RUNTIME.getValue("nearby")),
            bluetooth = has(*RUNTIME.getValue("bluetooth")),
            notifications = has(*RUNTIME.getValue("notifications")),
            allFiles = if (needsAllFiles) Environment.isExternalStorageManager() else null,
            batteryExempt = WorkerService.isBatteryExempt(ctx),
            fullScreenIntent = if (Build.VERSION.SDK_INT >= 34) nm?.canUseFullScreenIntent() == true else null,
            fingerprint = approvalKeyReady,
        )
    }
}
