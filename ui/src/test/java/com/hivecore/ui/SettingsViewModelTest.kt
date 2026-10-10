package com.hivecore.ui

import com.hivecore.core.MemoryPrefs
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.SettingsKeys
import com.hivecore.ui.ports.fakes.FakeSettingsPort
import com.hivecore.ui.vm.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val prefs = MemoryPrefs()
    private val port = FakeSettingsPort(listOf(MemberView("sales", "Sales"), MemberView("accounts", "Accounts")))

    private fun TestScope.vm() = SettingsViewModel(prefs, port, backgroundScope)

    @Test fun defaultsMatchTheSpecAndEverySettingPersistsThroughPrefs() = runTest {
        val vm = vm()
        val s = vm.state.value
        assertEquals(CloudPolicy.ASK, s.policy)
        assertEquals(20, s.capJobInr); assertEquals(500, s.capMonthInr)
        assertFalse(s.schemaSharing); assertFalse(s.mobileDataBlobs)
        assertEquals("", s.relayAddress)
        assertEquals(15, s.heartbeatLanS); assertEquals(30, s.heartbeatWanS)
        assertEquals(4, s.deadlineFloorS); assertEquals(3.0, s.deadlineFactor, 0.0)
        assertEquals("No key: the CLOUD option is unavailable", s.keyLine)

        vm.setPolicy(CloudPolicy.FASTEST)
        vm.setCapJob(50); vm.setCapMonth(-5)
        vm.setSchemaSharing(true); vm.setMobileDataBlobs(true)
        vm.setRelayAddress(" relay.example.invalid:443 ")
        vm.setHeartbeatLan(20); vm.setHeartbeatWan(1_000); vm.setDeadlineFloor(6); vm.setDeadlineFactor(2.5)
        assertEquals("fastest", prefs.getString(SettingsKeys.CLOUD_POLICY, null))
        assertEquals(50, prefs.getInt(SettingsKeys.CAP_JOB_INR, 0))
        assertEquals(0, prefs.getInt(SettingsKeys.CAP_MONTH_INR, -1))
        assertEquals(25, prefs.getInt(SettingsKeys.DEADLINE_FACTOR_X10, 0))

        val fresh = vm().state.value
        assertEquals(CloudPolicy.FASTEST, fresh.policy)
        assertEquals(50, fresh.capJobInr); assertEquals(0, fresh.capMonthInr)
        assertTrue(fresh.schemaSharing); assertTrue(fresh.mobileDataBlobs)
        assertEquals("relay.example.invalid:443", fresh.relayAddress)
        assertEquals(20, fresh.heartbeatLanS); assertEquals(300, fresh.heartbeatWanS)
        assertEquals(6, fresh.deadlineFloorS); assertEquals(2.5, fresh.deadlineFactor, 0.0)

        vm.resetTuning()
        assertEquals(15, vm.state.value.heartbeatLanS); assertEquals(3.0, vm.state.value.deadlineFactor, 0.0)
    }

    @Test fun keyIsWriteOnlyAndNeverInPrefs() = runTest {
        val vm = vm()
        vm.setKeyDraft("sk-or-v1-abcdef0123456789")
        assertTrue(vm.state.value.canSaveKey)
        vm.saveKey()
        val s = vm.state.value
        assertTrue(s.hasKey)
        assertEquals("…6789", s.keyHint)
        assertEquals("Key stored (ends …6789)", s.keyLine)
        assertEquals("", s.keyDraft)
        assertTrue(port.keyEquals("sk-or-v1-abcdef0123456789"))
        assertTrue("the key never reaches prefs", prefs.map.values.none { it.toString().contains("abcdef0123456789") })
        assertFalse(s.toString().contains("abcdef0123456789"))

        vm.setKeyDraft("short"); vm.saveKey()
        assertEquals("Key not stored: the key is too short", vm.state.value.message)
        assertTrue(vm.state.value.hasKey)
        vm.clearKey()
        assertFalse(vm.state.value.hasKey); assertNull(vm.state.value.keyHint)
    }

    @Test fun exportLedgerAndForgetPeerGoThroughThePort() = runTest {
        val vm = vm()
        assertEquals(listOf("Sales", "Accounts"), vm.state.value.peers.map { it.name })
        vm.exportLedger(); runCurrent()
        assertEquals("/files/ledger-export-1.json", vm.state.value.lastExport)
        assertEquals("Ledger written to /files/ledger-export-1.json", vm.state.value.message)
        vm.forgetPeer("sales"); runCurrent()
        assertEquals(listOf("sales"), port.forgotten)
        assertEquals(listOf("Accounts"), vm.state.value.peers.map { it.name })
        assertEquals("Forgot Sales", vm.state.value.message)
        port.failNext = IllegalStateException("no ledger yet")
        vm.exportLedger(); runCurrent()
        assertEquals("Could not export the ledger: no ledger yet", vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }
}
