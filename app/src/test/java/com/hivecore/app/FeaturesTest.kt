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

    /** Every flag combination: the graph builds, and every screen that combination makes real has a view model behind it. */
    @Test fun graphBuildsTheScreensOfEveryFlagCombination() = runTest {
        val all = listOf("mesh", "runtime", "split", "graph", "cloud", "remote", "state", "hardening", "decide")
        for (mask in 0 until (1 shl all.size)) {
            val flags = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val g = HiveCoreGraph.inMemory(backgroundScope, flags)
            val screens = Features.screens(g, flags)
            assertEquals(Features.screensFor(flags), screens.keys)
            for (s in screens.keys) when (s) {
                Screen.ASK -> g.ask; Screen.JOBS -> g.jobs; Screen.SWARM -> g.swarm
                Screen.MODELS -> g.models; Screen.DATA -> g.data; Screen.SETTINGS -> g.settings
            }
            assertEquals("cloud" in flags, g.consent != null)
            runCurrent()
        }
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
