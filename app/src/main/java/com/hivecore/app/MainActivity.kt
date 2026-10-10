package com.hivecore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hivecore.app.approval.ApprovalBanner
import com.hivecore.core.SLog
import com.hivecore.ui.ScreenRegistry
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.shell.HiveCoreShell
import com.hivecore.ui.shell.rememberHiveNav
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.screens.FeedSheet
import com.hivecore.ui.screens.feedLine

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = HiveCoreGraph.get(this)
        if (savedInstanceState == null) requestRuntimePermissions()
        setContent {
            HiveCoreTheme {
                val nav = rememberHiveNav()
                var showFeed by remember { mutableStateOf(false) }
                val events by graph.feed.events.collectAsStateWithLifecycle()
                val unread by graph.feed.unread.collectAsStateWithLifecycle()
                HiveCoreShell(
                    registry = ScreenRegistry(Features.screens(graph)),
                    nav = nav,
                    status = Features.shellStatus(graph).copy(unreadEvents = unread, lastEvent = events.firstOrNull()?.let { feedLine(it) }),
                    onOpenFeed = { showFeed = true; graph.feed.markRead() },
                    // spec 17: the pending-approval card is the fallback when the notification does not surface
                    banner = { graph.ports.approval?.let { ApprovalBanner(it, this@MainActivity) } },
                    onTryInChat = { prompt -> graph.ask.setRequest(prompt) },
                )
                graph.consent?.let { ConsentSheetHost(it) }
                if (showFeed) FeedSheet(events, onClear = { graph.feed.clear() }, onDismiss = { showFeed = false; graph.feed.markRead() })
            }
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
