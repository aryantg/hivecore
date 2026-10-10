package com.hivecore.engine.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PresenceTracker]: when the owner counts as active (R8) and how screen-off time is tracked. */
class EligibilityPresenceTest {
    @Test fun lockedScreenOnIsNotOwnerUntilUserPresent() {
        val t = PresenceTracker()
        t.screenOff(1_000, locked = true)
        assertEquals(1_000L, t.state.screenOffSinceMs)
        assertFalse(t.screenOn(2_000, locked = true))          // a notification lit the screen
        assertNull(t.state.screenOffSinceMs)
        assertTrue(t.userPresent())
        assertFalse(t.state.locked)
    }

    @Test fun unlockedScreenOnIsOwner() {
        val t = PresenceTracker()
        t.screenOff(1_000, locked = false)
        assertTrue(t.screenOn(2_000, locked = false))
        assertFalse(t.screenOn(3_000, locked = false))        // already on: no second yield
    }

    @Test fun repeatedScreenOffKeepsFirstTimestamp() {
        val t = PresenceTracker()
        t.screenOff(1_000, locked = true)
        t.screenOff(5_000, locked = true)
        assertEquals(1_000L, t.state.screenOffSinceMs)
    }

    @Test fun appForegroundFiresOnlyOnTransition() {
        val t = PresenceTracker()
        assertTrue(t.appForeground(true))
        assertFalse(t.appForeground(true))
        assertFalse(t.appForeground(false))
        assertTrue(t.appForeground(true))
    }
}
