package com.hivecore.ui.ports.fakes

import com.hivecore.core.NodeRole
import com.hivecore.ui.ports.DemoPort
import com.hivecore.ui.ports.PermissionItem
import com.hivecore.ui.ports.ResetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records what the Demo group asked for; JVM tests and builds without the real wiring use it. */
class FakeDemoPort(override val debugBuild: Boolean = true) : DemoPort {
    val calls = mutableListOf<String>()
    val permissionsState = MutableStateFlow(listOf(
        PermissionItem("camera", "Camera", "Scan a pairing QR code", granted = true),
        PermissionItem("all_files", "All files access", "Read model bundles from Download/HiveCoreModels", granted = false),
    ))
    override val permissions: StateFlow<List<PermissionItem>> = permissionsState
    override fun refreshPermissions() { calls += "refresh" }
    override fun openPermission(id: String) { calls += "open $id" }
    override suspend fun setRoleAndSeed(role: NodeRole): Result<String> { calls += "seed $role"; return Result.success("Seeded $role") }
    override suspend fun seedAll(): Result<String> { calls += "seedAll"; return Result.success("Seeded HR, SALES, ACCOUNTS") }
    override suspend fun generateSamplePhotos(n: Int): Result<List<String>> { calls += "photos $n"; return Result.success(List(n) { "/cache/picked/sample_%04d.png".format(it + 1) }) }
    override suspend fun previewApproval(): Result<String> { calls += "approval"; return Result.success("Approval request sent to this phone") }
    override suspend fun previewConsent(): Result<String> { calls += "consent"; return Result.success("Consent: keep local") }
    override suspend fun resetDemoState(options: ResetOptions): Result<String> { calls += "reset $options"; return Result.success("Reset") }
    override fun restart() { calls += "restart" }
}
