package com.hivecore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hivecore.app.approval.ApprovalBanner
import com.hivecore.core.SLog
import com.hivecore.ui.HiveCoreShell
import com.hivecore.ui.ScreenRegistry
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        r.filterValues { !it }.keys.takeIf { it.isNotEmpty() }?.let { SLog.w("Main", "permissions not granted: ${it.joinToString { p -> p.substringAfterLast('.') }}") }
    }

    /**
     * Phone gate finding: Nearby, Bluetooth, the camera and notifications are runtime permissions, and the shared
     * model root `Download/HiveCoreModels` needs "All files access" on Android 11+. Asked once at the first launch;
     * a refusal only disables that transport or the shared root (the app's own `files/models` always works).
     */
    private fun requestRuntimePermissions() {
        val wanted = listOf(
            android.Manifest.permission.NEARBY_WIFI_DEVICES, android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.CAMERA, android.Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        if (Features.runtime && !android.os.Environment.isExternalStorageManager()) runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName")))
        }.onFailure { SLog.w("Main", "all files access: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = HiveCoreGraph.get(this)
        if (savedInstanceState == null) requestRuntimePermissions()
        setContent {
            androidx.compose.foundation.layout.Box {
                HiveCoreShell(registry = ScreenRegistry(Features.screens(graph)))
                // spec 17: the pending-approval card is the fallback when the notification does not surface
                graph.ports.approval?.let { androidx.compose.foundation.layout.Column { ApprovalBanner(it, this@MainActivity) } }
            }
            graph.consent?.let { ConsentSheetHost(it) }
        }
        handleInvite(intent, graph)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent, HiveCoreGraph.get(this))
    }

    /** `swarm://join?...` (spec 6.2): the invite link lands on the Swarm screen's join path. */
    private fun handleInvite(intent: Intent?, graph: HiveCoreGraph) {
        val link = intent?.dataString?.takeIf { it.startsWith("swarm://join") } ?: return
        SLog.d("Main", "invite link received")
        graph.scope.launch { graph.swarmPort.join(link).onFailure { SLog.w("Main", "join: ${it.message}") } }
    }
}
