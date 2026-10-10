package com.hivecore.core

import com.hivecore.core.fakes.FakeBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTransportTest {
    @Test fun threeNodeBusDeliversAndTracksConnections() = runTest {
        val bus = FakeBus()
        val hr = bus.node("hr"); val sales = bus.node("sales"); val accounts = bus.node("accounts")
        hr.start("HR|aaaa0000"); sales.start("SALES|bbbb0000"); accounts.start("ACCOUNTS|cccc0000")

        assertEquals(setOf("sales", "accounts"), hr.connected.value.map { it.endpointId }.toSet())
        assertEquals("HR|aaaa0000", sales.connected.value.first { it.endpointId == "hr" }.displayName)

        // Collect on the flow as a router would, and also assert on the recorded list.
        val seenBySales = ArrayList<Inbound>()
        val job: Job = launch { sales.inbound.collect { seenBySales.add(it) } }
        testScheduler.runCurrent()   // subscribe before sending: a replay-0 SharedFlow drops emissions with no collector

        val job1 = "JOB-1".toByteArray()
        assertTrue(hr.send(sales.endpoint, job1).isSuccess)
        assertTrue(hr.send(accounts.endpoint, job1).isSuccess)
        assertTrue(accounts.send(sales.endpoint, "RESULT".toByteArray()).isSuccess)
        testScheduler.advanceUntilIdle()

        assertEquals(2, sales.received.size)
        assertEquals("hr", sales.received[0].endpoint.endpointId)
        assertEquals("JOB-1", String(sales.received[0].bytes))
        assertEquals("accounts", sales.received[1].endpoint.endpointId)
        assertEquals(1, accounts.received.size)
        assertEquals(0, hr.received.size)
        assertEquals(2, seenBySales.size)
        job.cancel()
    }

    @Test fun deadNodeIsUnreachableAndDropsOutOfConnected() = runTest {
        val bus = FakeBus()
        val hr = bus.node("hr"); val sales = bus.node("sales"); val accounts = bus.node("accounts")
        hr.start("HR"); sales.start("SALES"); accounts.start("ACCOUNTS")
        bus.setOffline("accounts", true)
        assertEquals(listOf("sales"), hr.connected.value.map { it.endpointId })
        assertTrue(hr.send(accounts.endpoint, "x".toByteArray()).isFailure)
        bus.setOffline("accounts", false)
        assertEquals(2, hr.connected.value.size)
        accounts.stop()
        assertEquals(1, hr.connected.value.size)
        assertFalse(hr.send(accounts.endpoint, "x".toByteArray()).isSuccess)
    }

    @Test fun pairingModeAndRequests() = runTest {
        val bus = FakeBus()
        val hr = bus.node("hr"); val sales = bus.node("sales")
        hr.start("HR"); sales.start("SALES")
        hr.setPairingMode(true)
        assertTrue(hr.pairingMode)
        val req = PairingRequest(sales.endpoint, "123456")
        val got = ArrayList<PairingRequest>()
        val job = launch { hr.pairingRequests.collect { got.add(it) } }
        testScheduler.runCurrent()
        assertTrue(hr.raisePairing(req))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(req), got)
        hr.acceptPairing(req, true)
        assertEquals(req to true, hr.pairingDecisions.single())
        job.cancel()
    }
}
