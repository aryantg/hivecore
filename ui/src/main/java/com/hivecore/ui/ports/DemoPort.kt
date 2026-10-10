package com.hivecore.ui.ports

import com.hivecore.core.NodeRole
import kotlinx.coroutines.flow.StateFlow

// The presenter's controls (Settings > Demo). The app implements [DemoPort] over the real stores, the approval
// store, the consent sheet and the system pages; [com.hivecore.ui.ports.fakes.FakeDemoPort] drives the JVM tests.

/** One permission or system setting the demo needs, with whether it is granted and what opens it. */
data class PermissionItem(val id: String, val title: String, val detail: String, val granted: Boolean)

/** What "Reset demo state" clears. Pairing is kept unless asked: re-pairing on stage takes time. */
data class ResetOptions(
    val jobs: Boolean = true,
    val pickedFiles: Boolean = true,
    val askHistory: Boolean = true,
    val importedData: Boolean = true,
    val pairing: Boolean = false,
)

interface DemoPort {
    /** Debug builds only show the auto-approve switch. */
    val debugBuild: Boolean

    val permissions: StateFlow<List<PermissionItem>>
    fun refreshPermissions()
    /** Opens the system page (or the runtime prompt) for [id]. */
    fun openPermission(id: String)

    /** Writes this phone's role and loads its seed tables and documents; returns a one-line summary. */
    suspend fun setRoleAndSeed(role: NodeRole): Result<String>
    /** Seeds every role on this phone (a one-phone Ask demo needs Sales and HR tables together). */
    suspend fun seedAll(): Result<String>

    /** Draws [n] invoice-like photos on the device (nothing is committed to the repository) and returns their paths. */
    suspend fun generateSamplePhotos(n: Int): Result<List<String>>

    /** Puts a sample plan request on the approval screen (notification, banner, fingerprint), as an owner phone would see it. */
    suspend fun previewApproval(): Result<String>
    /** Shows the cloud consent sheet for a sample step. */
    suspend fun previewConsent(): Result<String>

    suspend fun resetDemoState(options: ResetOptions): Result<String>

    /** Restarts the app (after switching demo mode, or after a reset). */
    fun restart()
}

/** Keys the demo controls write to `KeyValuePrefs`. */
object DemoKeys {
    const val DEMO_MODE = "demo_mode"
    /** 10 = 1×, 30 = 3× (rehearsals) */
    const val DEMO_SPEED_X10 = "demo_speed_x10"
    /** The same key AppWiring reads for the role. */
    const val NODE_ROLE = "node_role"
    /** The same key AppWiring's ApprovalStore reads (debug builds only). */
    const val DEBUG_AUTO_APPROVE = "debug_auto_approve"
    const val KEEP_SCREEN_ON = "demo_keep_screen_on"
    const val LARGE_TEXT = "demo_large_text"
    const val HIDE_HASHES = "demo_hide_hashes"
    /** The same key the worker policy reads for "share this phone when idle". */
    const val WORKER_OPT_IN = "worker_opt_in"
}
