package com.hivecore.ui

import com.hivecore.core.CapabilityState
import com.hivecore.core.Cooldown
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.LlmBackend
import com.hivecore.core.Profile
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.ui.ports.JoinRequestView
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.RosterView
import com.hivecore.ui.ports.fakes.FakeSwarmPort
import com.hivecore.ui.vm.SwarmViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SwarmViewModelTest {
    private val now = 1_000_000_000L
    private val idleSelf = Profile("self", now, LlmBackend.NPU, 17.0, idle = true, charging = false, batteryPct = 80, batteryTempC = 30f, thermal = 0,
        workerOptIn = true, eligible = true, big = "llm", warm = listOf("qwen3-4b"))
    private val sales = Profile("sales", now - 5_000, LlmBackend.NPU, 18.0, idle = true, charging = true, batteryPct = 60, thermal = 0,
        workerOptIn = true, eligible = true, heavyOk = true, queueDepth = 3, big = "llm", warm = listOf("whisper-base"), link = Link.defaultFor(LinkKind.P2P),
        capabilities = listOf(CapabilityState("llm.json", "qwen3-4b-q4", "a1", warm = true)))
    private val accounts = Profile("accounts", now - 20_000, LlmBackend.GPU, 6.0, idle = true, batteryPct = 30, thermal = 2,
        workerOptIn = true, eligible = false, link = Link.defaultFor(LinkKind.WAN_RELAY), cooldowns = listOf(Cooldown("llm.json", now + 300_000)))
    private val hr = Profile("hr", now - 120_000, LlmBackend.NPU, 10.0, idle = true, batteryPct = 90, workerOptIn = true, eligible = true, link = Link.defaultFor(LinkKind.LAN))

    private val roster = RosterView("g1", 3, "self", listOf(MemberView("self", "This phone", admin = true), MemberView("sales", "Sales"), MemberView("accounts", "Accounts"), MemberView("hr", "HR")))
    private val port = FakeSwarmPort("self", roster, listOf(sales, accounts, hr), clock = { now })

    private fun TestScope.vm(policy: FakeWorkerPolicy?) = SwarmViewModel(port, policy, backgroundScope, clock = { now })

    @Test fun tilesSelfFirstThenOnlinePeersByNameThenOffline() = runTest {
        val vm = vm(FakeWorkerPolicy(idleSelf))
        settle()
        val s = vm.state.value
        assertTrue(s.wired); assertTrue(s.isAdmin); assertTrue(s.optIn); assertTrue(s.eligibleNow)
        assertEquals("Roster v3 · 4 members · admin This phone", s.rosterLine)
        assertEquals("Eligible now: other phones' work can run here.", s.eligibilityLine)
        assertEquals(listOf("This phone", "Accounts", "Sales", "HR"), s.tiles.map { it.name })
        val salesTile = s.tiles.first { it.nodeId == "sales" }
        assertEquals("online via Nearby", salesTile.presence)
        assertEquals("60 % ⚡", salesTile.battery)
        assertEquals("qwen3-4b-q4 (big slot)", salesTile.bigModel)
        assertEquals(3, salesTile.queueDepth)
        assertEquals("Eligible · heavy work OK", salesTile.status)
        val acc = s.tiles.first { it.nodeId == "accounts" }
        assertEquals("online via relay", acc.presence)
        assertTrue(acc.batteryWarn); assertTrue(acc.thermalWarn)
        assertEquals("no big model", acc.bigModel)
        assertEquals(listOf("llm.json until ${Format.clock(now + 300_000)}"), acc.cooldowns)
        assertEquals("Not eligible: battery 30 % (needs 40 % or the charger); the phone is hot (thermal 2, needs 1 or less)", acc.status)
        val hrTile = s.tiles.first { it.nodeId == "hr" }
        assertFalse(hrTile.online)
        assertEquals("offline (last seen 2 min ago)", hrTile.presence)
    }

    @Test fun reasonsFollowTheProfileLiveAndOptInGoesThroughThePolicy() = runTest {
        val policy = FakeWorkerPolicy(idleSelf.copy(workerOptIn = false, eligible = false))
        val vm = vm(policy)
        var exemptions = 0
        vm.requestBatteryExemption = { exemptions++ }
        settle()
        assertFalse(vm.state.value.optIn)
        assertEquals(listOf("sharing is off"), vm.state.value.reasons)
        assertEquals("Not sharing", vm.state.value.tiles.first().status)
        vm.setOptIn(true); settle()
        assertTrue(policy.optIn.value)
        assertEquals(1, exemptions)
        assertTrue(vm.state.value.eligibleNow)
        policy.set(idleSelf.copy(idle = false, eligible = false, batteryTempC = 39f, inCall = true)); settle()
        assertEquals(listOf("the phone is in use (needs the screen off for 60 s, or the charger)", "battery at 39 °C (needs under 38 °C)", "in a call"), vm.state.value.reasons)
    }

    @Test fun unwiredPolicyExplains() = runTest {
        val vm = vm(null)
        settle()
        assertFalse(vm.state.value.wired)
        vm.setOptIn(true)
        assertEquals("The swarm is not running on this phone yet", vm.state.value.message)
        assertEquals(listOf("Accounts", "Sales", "HR"), vm.state.value.tiles.map { it.name })
    }

    @Test fun showQrAndInviteLinkMintInvites() = runTest {
        val vm = vm(FakeWorkerPolicy(idleSelf))
        val shared = mutableListOf<String>(); val copied = mutableListOf<String>()
        vm.shareLink = { shared += it }; vm.copyLink = { copied += it }
        vm.showInvite(asLink = false); settle()
        val qr = vm.state.value.pairing as SwarmViewModel.Pairing.ShowInvite
        assertFalse(qr.asLink)
        assertTrue(qr.invite.link.startsWith("swarm://join?g=g1&"))
        assertEquals(now + 600_000, qr.invite.expiresTs)
        vm.copyInvite()
        assertEquals(listOf(qr.invite.link), copied)
        vm.showInvite(asLink = true); settle()
        assertEquals(1, shared.size)
        vm.closePairing()
        assertEquals(SwarmViewModel.Pairing.None, vm.state.value.pairing)
    }

    @Test fun scanFallsBackToPasteAndJoinThenConfirmSasPinsThePeer() = runTest {
        val vm = vm(FakeWorkerPolicy(idleSelf))
        settle()
        vm.scan()
        assertTrue(vm.state.value.pairing is SwarmViewModel.Pairing.EnterLink)
        vm.setLink("http://nope"); vm.join(); settle()
        assertEquals("That is not an invite link (swarm://join?…)", (vm.state.value.pairing as SwarmViewModel.Pairing.EnterLink).error)
        vm.setLink("swarm://join?g=g1&k=abc&t=tok"); vm.join(); settle()
        assertEquals(SwarmViewModel.Pairing.None, vm.state.value.pairing)
        val sas = vm.state.value.sas.single()
        assertEquals("482 913", sas.digits)
        assertEquals("Compare the 6 digits with Sales", vm.state.value.message)
        vm.confirmSas(sas, matches = true); settle()
        assertEquals(listOf(sas.handshakeId to true), port.confirmed)
        assertTrue(vm.state.value.sas.isEmpty())
        assertEquals("Paired with Sales", vm.state.value.message)

        var scans = 0
        vm.openScanner = { scans++ }
        vm.scan()
        assertEquals(1, scans)

        port.failNext = IllegalStateException("token expired")
        vm.join("swarm://join?g=g1&k=abc&t=old"); settle()
        assertEquals("Could not join: token expired", (vm.state.value.pairing as SwarmViewModel.Pairing.EnterLink).error)
    }

    @Test fun adminActionsGoThroughThePortAndBumpTheRoster() = runTest {
        val vm = vm(FakeWorkerPolicy(idleSelf))
        port.joinsState.value = listOf(JoinRequestView("ops", "Ops", now)); settle()
        assertEquals(1, vm.state.value.pendingJoins.size)
        vm.approve("ops"); settle()
        assertEquals(listOf("ops"), port.approved)
        assertTrue(vm.state.value.pendingJoins.isEmpty())
        assertEquals(4, vm.state.value.roster.version)
        assertEquals("Approved Ops", vm.state.value.message)
        vm.revoke("accounts"); settle()
        assertEquals(listOf("accounts"), port.revoked)
        assertNull(vm.state.value.tiles.firstOrNull { it.nodeId == "accounts" })
        vm.handOver("sales"); settle()
        assertEquals("sales", vm.state.value.roster.adminId)
        assertFalse(vm.state.value.isAdmin)
        assertEquals("Sales is the admin now", vm.state.value.message)
        port.failNext = IllegalStateException("not the admin")
        vm.revoke("hr"); settle()
        assertEquals("Could not revoke: not the admin", vm.state.value.message)
    }
}
