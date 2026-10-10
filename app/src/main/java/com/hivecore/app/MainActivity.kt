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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hivecore.ui.design.LocalShowHashes

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
        SwarmIntegration.bind(this, graph)
        (graph.ports.demoPort as? com.hivecore.app.demo.DemoPortImpl)?.requestRuntime = { perms -> permissions.launch(perms) }
        if (savedInstanceState == null) requestRuntimePermissions()
        val extras = demoExtras(graph)
        setContent {
            HiveCoreTheme {
                val nav = rememberHiveNav()
                var showFeed by remember { mutableStateOf(false) }
                val events by graph.feed.events.collectAsStateWithLifecycle()
                val unread by graph.feed.unread.collectAsStateWithLifecycle()
                val settings by graph.settings.state.collectAsStateWithLifecycle()
                val jobs by graph.jobs.state.collectAsStateWithLifecycle()
                val demo = settings.demo
                // presenter helpers: the screen stays on while demoing or while a job runs
                val keepOn = demo != null && (demo.keepScreenOn || demo.demoMode) || jobs.running
                LaunchedEffect(keepOn) {
                    if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                LaunchedEffect(Unit) { graph.settings.onPhotosReady = { paths -> graph.jobs.selectRecipe("photo_text"); graph.jobs.addItems(paths); nav.go(com.hivecore.ui.Screen.JOBS) } }
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides if (demo?.largeText == true) Density(density.density, density.fontScale * LARGE_TEXT) else density,
                    LocalShowHashes provides (demo?.hideHashes != true),
                ) {
                HiveCoreShell(
                    registry = ScreenRegistry(Features.screens(graph, extras = extras)),
                    nav = nav,
                    status = Features.shellStatus(graph, demo = demo?.demoMode == true).copy(unreadEvents = unread, lastEvent = events.firstOrNull()?.let { feedLine(it) }),
                    onOpenFeed = { showFeed = true; graph.feed.markRead() },
                    // spec 17: the pending-approval card is the fallback when the notification does not surface
                    banner = { graph.ports.approval?.let { ApprovalBanner(it, this@MainActivity) } },
                    onTryInChat = { prompt -> graph.ask.setRequest(prompt) },
                )
                graph.consent?.let { ConsentSheetHost(it) }
                if (showFeed) FeedSheet(events, onClear = { graph.feed.clear() }, onDismiss = { showFeed = false; graph.feed.markRead() })
                }
            }
        }
        handleInvite(intent, graph)
    }

    /** The demo shortcuts placed on the screens: sample photos on Jobs, the role picker on Data. */
    private fun demoExtras(graph: HiveCoreGraph): ScreenExtras {
        val demo = graph.ports.demoPort ?: return ScreenExtras()
        return ScreenExtras(
            samplePhotos = { n -> graph.scope.launch { demo.generateSamplePhotos(n).onSuccess { graph.jobs.selectRecipe("photo_text"); graph.jobs.addItems(it) } } },
            roleControl = { RoleCard(graph) },
        )
    }

    override fun onResume() {
        super.onResume()
        HiveCoreGraph.get(this).ports.demoPort?.refreshPermissions()
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

private const val LARGE_TEXT = 1.15f

/** Data page: "this phone is HR / Sales / Accounts"; picking one loads that role's sample data. */
@androidx.compose.runtime.Composable
private fun RoleCard(graph: HiveCoreGraph) {
    val s by graph.settings.state.collectAsStateWithLifecycle()
    val d = s.demo ?: return
    com.hivecore.ui.design.HiveCard {
        androidx.compose.material3.Text("This phone is", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        com.hivecore.ui.design.SegmentedChoice(com.hivecore.core.NodeRole.entries, d.role,
            label = { when (it) { com.hivecore.core.NodeRole.HR -> "HR"; com.hivecore.core.NodeRole.SALES -> "Sales"; com.hivecore.core.NodeRole.ACCOUNTS -> "Accounts" } },
            onSelect = graph.settings::setRole, enabled = d.working == null)
        d.working?.let { androidx.compose.material3.Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
    }
}
