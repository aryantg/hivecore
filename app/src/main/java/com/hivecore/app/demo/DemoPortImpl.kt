package com.hivecore.app.demo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.hivecore.app.HiveCoreGraph
import com.hivecore.cloud.ConsentRequest
import com.hivecore.core.FeedKind
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.NodeRole
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import com.hivecore.engine.device.WorkerService
import com.hivecore.node.data.DataStore
import com.hivecore.ui.ports.DemoKeys
import com.hivecore.ui.ports.DemoPort
import com.hivecore.ui.ports.PermissionItem
import com.hivecore.ui.ports.ResetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The presenter's controls over the real phone: role and seed data, sample photos, the approval and consent
 * previews, the permission checklist, the reset and the restart. [graph] is read lazily (the graph is built after
 * the ports).
 *
 * @param store the data store (null when the wiring fell back to fakes: seeding then fails with a clear message)
 */
class DemoPortImpl(
    private val app: Context,
    private val prefs: KeyValuePrefs,
    private val store: DataStore?,
    private val db: SqlDb?,
    private val stateDb: SqlDb?,
    override val debugBuild: Boolean,
    private val needsAllFiles: Boolean,
    private val graph: () -> HiveCoreGraph,
) : DemoPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _permissions = MutableStateFlow<List<PermissionItem>>(emptyList())
    override val permissions: StateFlow<List<PermissionItem>> = _permissions

    /** The activity's runtime-permission prompt; set by `MainActivity` (system pages open without it). */
    @Volatile var requestRuntime: ((Array<String>) -> Unit)? = null

    override fun refreshPermissions() {
        val keys = runCatching { graph().ports.approvalKeys }.getOrNull()
        _permissions.value = PermissionsChecklist.items(PermissionsChecklist.read(app, needsAllFiles, keys?.exists()))
    }

    override fun openPermission(id: String) {
        val runtime = PermissionsChecklist.RUNTIME[id]
        val ask = requestRuntime
        if (runtime != null && ask != null) { ask(runtime); return }
        val pkg = Uri.parse("package:${app.packageName}")
        val intent = when (id) {
            "all_files" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
            "battery" -> { WorkerService.requestBatteryExemption(app); return }
            "full_screen" -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            "fingerprint" -> runCatching { graph().ports.approvalKeys?.enrollIntent() }.getOrNull() ?: Intent(Settings.ACTION_SECURITY_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { SLog.w(TAG, "open $id: ${it.message}"); runCatching { app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    override suspend fun setRoleAndSeed(role: NodeRole): Result<String> = runCatching {
        val s = store ?: error("the data store is not running on this build")
        prefs.putString(DemoKeys.NODE_ROLE, role.name)
        withContext(Dispatchers.IO) { s.seedNow(role) }
        afterSeed()
        "This phone is ${label(role)}: ${WHAT[role]} loaded"
    }

    override suspend fun seedAll(): Result<String> = runCatching {
        val s = store ?: error("the data store is not running on this build")
        withContext(Dispatchers.IO) { NodeRole.entries.forEach { s.seedNow(it) } }
        afterSeed()
        "Sample data for HR, Sales and Accounts loaded on this phone"
    }

    private fun afterSeed() {
        val g = graph()
        g.data.refresh()
        g.ports.rebroadcastHello?.let { re -> scope.launch { runCatching { re() } } }
        g.feed.post(FeedKind.DEMO, "Seed data loaded", "role ${prefs.getString(DemoKeys.NODE_ROLE, null) ?: "SALES"}")
    }

    override suspend fun generateSamplePhotos(n: Int): Result<List<String>> = runCatching {
        withContext(Dispatchers.IO) { SampleInvoices.generate(app, n) }.also { graph().feed.post(FeedKind.DEMO, "Drew ${it.size} sample invoice photos") }
    }

    override suspend fun previewApproval(): Result<String> = runCatching {
        val store = graph().ports.approval ?: error("the approval screen needs the mesh flag")
        val id = "demo-plan-${System.currentTimeMillis()}"
        scope.launch {
            val r = runCatching { store.requestPlan(id, "Sales", APPROVAL_DESCRIPTION, "9f2c41ab77e0d3c5a1b2c3d4", listOf("h1", "h2", "h3")) }
            graph().feed.post(FeedKind.APPROVAL, "Sample approval: ${r.getOrNull()?.let { it::class.simpleName?.lowercase() } ?: "failed"}")
        }
        if (prefs.getBoolean(DemoKeys.DEBUG_AUTO_APPROVE, false)) "Sample request sent; auto-approve is on, so it was approved at once"
        else "Sample request sent: the approval notification and the banner are up"
    }

    override suspend fun previewConsent(): Result<String> = runCatching {
        val consent = graph().consent ?: error("the consent sheet needs the cloud flag")
        scope.launch {
            val d = runCatching { consent.ask(ConsentRequest("demo-job", "e1", "openrouter/qwen-72b", 12_288, 3, "released", "disagreement", 1.20)) }
            graph().feed.post(FeedKind.CLOUD, "Sample consent: ${d.getOrNull()?.name?.lowercase()?.replace('_', ' ') ?: "dismissed"}")
        }
        "The consent sheet is open"
    }

    override suspend fun resetDemoState(options: ResetOptions): Result<String> = runCatching {
        val lines = withContext(Dispatchers.IO) { DemoReset(app.filesDir, app.cacheDir, prefs, db, stateDb).run(options) }.toMutableList()
        val g = graph()
        if (options.askHistory) withContext(Dispatchers.Main) { g.ask.clearHistory() }
        if (options.pairing) {
            val peers = runCatching { g.settingsPort.knownPeers() }.getOrDefault(emptyList())
            peers.forEach { p -> runCatching { g.settingsPort.forgetPeer(p.nodeId) } }
            lines += "pairing: ${peers.size} phones forgotten"
        }
        g.feed.clear()
        "Reset done. Restarting…"
    }

    /** Relaunches the app in a fresh process, so the graph (and the in-memory job list) is rebuilt. */
    override fun restart() {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        app.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val TAG = "DemoPort"
        private fun label(r: NodeRole) = when (r) { NodeRole.HR -> "HR"; NodeRole.SALES -> "Sales"; NodeRole.ACCOUNTS -> "Accounts" }
        private val WHAT = mapOf(NodeRole.HR to "the employees table and the onboarding checklist", NodeRole.SALES to "the sales table and the Q3 review deck",
            NodeRole.ACCOUNTS to "the invoices table")

        /** The plan consent screen's format (PlanConsentScreen.parse): a header, numbered figures, a closing note. */
        const val APPROVAL_DESCRIPTION = "Sales' phone asks for 3 figures from your employees table:\n" +
            "1. Count of employees by region\n2. Average commission by region (confidential: commission)\n" +
            "3. Average commission overall (confidential: commission)\n" +
            "One fingerprint approves all 3 figures; nothing else."
    }
}
