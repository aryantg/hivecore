package com.hivecore.app

import com.hivecore.ui.Screen
import com.hivecore.ui.ports.CloudPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec section 17 / 19: which screen is real under which flag, and the graph builds every view model over the fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeaturesTest {
    @Test fun screensPerFlag() {
        assertEquals(setOf(Screen.SETTINGS), Features.screensFor(emptyList()))
        assertEquals(setOf(Screen.SETTINGS, Screen.JOBS), Features.screensFor(listOf("split")))
        assertEquals(setOf(Screen.SETTINGS, Screen.SWARM), Features.screensFor(listOf("mesh")))
        assertEquals(setOf(Screen.SETTINGS, Screen.MODELS), Features.screensFor(listOf("runtime")))
        assertEquals(setOf(Screen.SETTINGS, Screen.ASK, Screen.DATA), Features.screensFor(listOf("graph")))
        assertEquals(Screen.entries.toSet(), Features.screensFor(listOf("mesh", "runtime", "split", "graph", "cloud")))
    }

    @Test fun graphBuildsEveryViewModelOverTheFakes() = runTest {
        val g = HiveCoreGraph.inMemory(backgroundScope)
        g.ask; g.jobs; g.swarm; g.models; g.data; g.settings
        runCurrent()
        assertEquals(CloudPolicy.ASK, g.ask.state.value.policy)
        assertTrue(g.jobs.state.value.history.isEmpty())
        assertTrue(g.swarm.state.value.wired)
        assertEquals(6, g.models.state.value.catalog.size)
        assertTrue(g.data.state.value.loading)
        assertEquals(2, g.settings.state.value.peers.size)
    }
}
