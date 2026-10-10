package com.hivecore.ui

import com.hivecore.core.MemoryPrefs
import com.hivecore.core.NodeRole
import com.hivecore.ui.ports.DemoKeys
import com.hivecore.ui.ports.ResetOptions
import com.hivecore.ui.ports.fakes.FakeDemoPort
import com.hivecore.ui.ports.fakes.FakeSettingsPort
import com.hivecore.ui.vm.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings > Demo: the presenter's switches persist under the keys the app reads, and the actions reach the port. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoSettingsTest {
    private val prefs = MemoryPrefs()
    private val demo = FakeDemoPort()

    @Test fun noDemoPortNoDemoGroup() = runTest {
        assertNull(SettingsViewModel(prefs, FakeSettingsPort(), backgroundScope).state.value.demo)
    }

    @Test fun switchesPersistUnderTheSharedKeys() = runTest {
        val vm = SettingsViewModel(prefs, FakeSettingsPort(), backgroundScope, demo)
        vm.setAutoApprove(true); vm.setKeepScreenOn(true); vm.setLargeText(true); vm.setHideHashes(true); vm.setDemoSpeed(30)
        assertEquals(true, prefs.getBoolean(DemoKeys.DEBUG_AUTO_APPROVE, false))
        assertEquals("debug_auto_approve", DemoKeys.DEBUG_AUTO_APPROVE)
        assertEquals("node_role", DemoKeys.NODE_ROLE)
        val d = vm.state.value.demo!!
        assertTrue(d.keepScreenOn && d.largeText && d.hideHashes && d.autoApprove)
        assertEquals(30, d.speedX10)
    }

    @Test fun demoModeRestartsTheApp() = runTest {
        val vm = SettingsViewModel(prefs, FakeSettingsPort(), backgroundScope, demo)
        vm.setDemoMode(true)
        assertEquals(true, prefs.getBoolean(DemoKeys.DEMO_MODE, false))
        assertEquals("restart", demo.calls.last())
    }

    @Test fun actionsReachThePortAndReport() = runTest {
        val vm = SettingsViewModel(prefs, FakeSettingsPort(), backgroundScope, demo)
        var photos: List<String>? = null
        vm.onPhotosReady = { photos = it }
        vm.setRole(NodeRole.HR); settle()
        assertEquals(NodeRole.HR, vm.state.value.demo!!.role)
        assertEquals("Seeded HR", vm.state.value.message)
        vm.generatePhotos(24); settle()
        assertEquals(24, photos!!.size)
        vm.previewApproval(); settle()
        vm.setResetOptions(ResetOptions(pairing = true)); vm.resetDemo(); settle()
        assertEquals(listOf("seed HR", "photos 24", "approval", "reset ${ResetOptions(pairing = true)}", "restart"), demo.calls.filter { it != "refresh" })
        assertNull("nothing left running", vm.state.value.demo!!.working)
    }

    @Test fun permissionsFlowIntoTheState() = runTest {
        val vm = SettingsViewModel(prefs, FakeSettingsPort(), backgroundScope, demo)
        settle()
        assertEquals(1, vm.state.value.demo!!.missingPermissions)
        demo.permissionsState.value = demo.permissionsState.value.map { it.copy(granted = true) }; settle()
        assertEquals(0, vm.state.value.demo!!.missingPermissions)
    }
}
