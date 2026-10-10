package com.hivecore.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClockTest {
    private var local = 1_000_000L

    @Test fun unsyncedIsLocalTime() {
        val c = RelayClock({ local })
        assertFalse(c.synced)
        assertEquals(local, c.now())
    }

    @Test fun oneWaySampleSetsOffsetUntilARoundTripArrives() {
        val c = RelayClock({ local })
        val threeHours = 3 * 3_600_000L
        c.syncOneWay(local + threeHours)
        assertTrue(c.synced)
        assertEquals(threeHours, c.offsetMs)
        assertEquals(local + threeHours, c.now())
        // A round trip of 200 ms whose server time sits in the middle: offset = serverTs - midpoint.
        val sentAt = local
        local += 200
        c.syncRoundTrip(serverTs = sentAt + 100 + threeHours + 5, sentAt = sentAt, receivedAt = local)
        assertEquals(threeHours + 5, c.offsetMs)
        assertEquals(200, c.sampleRttMs)
        // Later one-way samples no longer override a round-trip sample.
        c.syncOneWay(local + 999_999)
        assertEquals(threeHours + 5, c.offsetMs)
    }

    @Test fun slowRoundTripDoesNotReplaceAFastOne() {
        val c = RelayClock({ local }, maxRttMs = 1_000)
        c.syncRoundTrip(serverTs = local + 50, sentAt = local, receivedAt = local + 100)
        assertEquals(0L, c.offsetMs)
        c.syncRoundTrip(serverTs = local + 10_000, sentAt = local, receivedAt = local + 5_000)
        assertEquals(0L, c.offsetMs)
        c.reset()
        assertFalse(c.synced)
    }
}
