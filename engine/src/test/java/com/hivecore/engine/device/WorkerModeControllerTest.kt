package com.hivecore.engine.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worker mode (spec 4.5): opt-in starts the service and asks for the battery exemption once; opt-out stops it. */
class WorkerModeControllerTest {
    private class Control(override var batteryExempt: Boolean = false) : WorkerServiceControl {
        val log = ArrayList<String>()
        override fun startService() { log += "start" }
        override fun stopService() { log += "stop" }
        override fun requestBatteryExemption(): Boolean { log += "exempt?"; return true }
    }

    @Test fun optInStartsAndAsksOnceOptOutStops() {
        val c = Control()
        val m = WorkerModeController(c, clock = { 42L })
        assertFalse(m.running)
        m.apply(true)
        assertEquals(listOf("start", "exempt?"), c.log); assertTrue(m.running); assertEquals(42L, m.exemptionAskedAt)
        m.apply(true)                                                     // idempotent: no second start, no second dialog
        assertEquals(listOf("start", "exempt?"), c.log)
        m.apply(false)
        assertEquals(listOf("start", "exempt?", "stop"), c.log); assertFalse(m.running); assertNull(m.exemptionAskedAt)
        m.apply(false)
        assertEquals(3, c.log.size)
        m.apply(true)                                                     // a new opt-in may ask again while not exempt
        assertEquals(listOf("start", "exempt?", "stop", "start", "exempt?"), c.log)
    }

    @Test fun neverAsksWhenAlreadyExemptAndFollowsTheOptInFlow() = runTest {
        val c = Control(batteryExempt = true)
        val m = WorkerModeController(c)
        val optIn = MutableStateFlow(false)
        m.bind(optIn, backgroundScope)
        runCurrent()
        assertTrue(c.log.isEmpty())
        optIn.value = true; runCurrent()
        assertEquals(listOf("start"), c.log)
        optIn.value = false; runCurrent()
        assertEquals(listOf("start", "stop"), c.log)
    }

    @Test fun aFailingServiceCallDoesNotBreakTheController() {
        val c = object : WorkerServiceControl {
            override fun startService() = throw IllegalStateException("no foreground service allowed")
            override fun stopService() {}
            override val batteryExempt = true
            override fun requestBatteryExemption() = false
        }
        val m = WorkerModeController(c)
        m.apply(true)
        assertTrue(m.running)
        m.apply(false)
        assertFalse(m.running)
    }
}
