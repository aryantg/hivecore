package com.hivecore.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec section 17: exactly six screens, in the spec's order. */
class ScreenTest {
    @Test fun sixScreensInTheSpecOrder() {
        assertEquals(listOf("Ask", "Jobs", "Swarm", "Models", "Data", "Settings"), Screen.entries.map { it.title })
        assertEquals(6, Screen.entries.size)
        assertEquals(listOf(Screen.ASK, Screen.JOBS, Screen.SWARM, Screen.MODELS, Screen.DATA, Screen.SETTINGS), Screen.entries)
    }

    @Test fun formatting() {
        assertEquals("1,265", Format.count(1265))
        assertEquals("₹0", Format.inr(0.0)); assertEquals("₹1.20", Format.inr(1.2)); assertEquals("₹500", Format.inr(500.0))
        assertEquals("1.2 GB", Format.bytes(1_300_000_000L))
        assertEquals("relay", Format.linkWord(com.hivecore.core.LinkKind.WAN_RELAY))
        assertEquals("LAN", Format.linkWord(null))
        assertEquals("3 min ago", Format.ago(200_000))
        assertEquals("—", Format.shortHash(null))
        assertEquals("1 phone", Format.plural(1, "phone")); assertEquals("3 phones", Format.plural(3, "phone"))
    }
}
