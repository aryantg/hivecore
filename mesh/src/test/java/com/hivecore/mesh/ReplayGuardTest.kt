package com.hivecore.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGuardTest {
    private var clock = 1_000_000_000_000L
    private val guard = ReplayGuard(capacity = 3, windowMs = 10 * 60_000L) { clock }

    @Test fun sameNonceIsRejected() {
        assertNull(guard.check("hr", "n1", clock))
        assertEquals("replayed nonce", guard.check("hr", "n1", clock))
        assertNull(guard.check("sales", "n1", clock))   // the pair is keyed by requester too
    }

    @Test fun staleAndFutureTimestampsAreRejected() {
        assertNotNull(guard.check("hr", "old", clock - 11 * 60_000L))
        assertNotNull(guard.check("hr", "future", clock + 11 * 60_000L))
        assertNull(guard.check("hr", "edge", clock - 10 * 60_000L))
        assertNull(guard.check("hr", "edge2", clock + 10 * 60_000L))
        assertEquals(0, guard.check("hr", "old", clock)?.let { 1 } ?: 0)   // a rejected-by-ts nonce was never recorded
    }

    @Test fun oldestNonceIsEvictedAtCapacity() {
        for (i in 1..4) assertNull(guard.check("hr", "n$i", clock))
        assertEquals(3, guard.size())
        assertNull(guard.check("hr", "n1", clock))     // evicted, so it looks fresh again
        assertNotNull(guard.check("hr", "n4", clock))  // still remembered
    }
}
