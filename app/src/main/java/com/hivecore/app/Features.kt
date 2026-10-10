package com.hivecore.app

import androidx.compose.runtime.Composable
import com.hivecore.BuildConfig
import com.hivecore.ui.Screen
import com.hivecore.ui.screens.AskRoute
import com.hivecore.ui.screens.DataRoute
import com.hivecore.ui.screens.JobsRoute
import com.hivecore.ui.screens.ModelsRoute
import com.hivecore.ui.screens.SettingsRoute
import com.hivecore.ui.screens.SwarmRoute

/**
 * Spec section 19: every phase lands behind a flag that defaults off until its phone gate passed. The flags are
 * BuildConfig fields set from `-PhiveFeatures=...`; this object is the one place the wiring consults them.
 */
object Features {
    val meshTrust: Boolean get() = BuildConfig.FEATURE_MESH_TRUST
    val runtime: Boolean get() = BuildConfig.FEATURE_RUNTIME
    val split: Boolean get() = BuildConfig.FEATURE_SPLIT
    val graph: Boolean get() = BuildConfig.FEATURE_GRAPH
    val remote: Boolean get() = BuildConfig.FEATURE_REMOTE
    val state: Boolean get() = BuildConfig.FEATURE_STATE
    val cloud: Boolean get() = BuildConfig.FEATURE_CLOUD
    val hardening: Boolean get() = BuildConfig.FEATURE_HARDENING

    fun enabled(): List<String> = listOfNotNull(
        "mesh".takeIf { meshTrust }, "runtime".takeIf { runtime }, "split".takeIf { split }, "graph".takeIf { graph },
        "remote".takeIf { remote }, "state".takeIf { state }, "cloud".takeIf { cloud }, "hardening".takeIf { hardening },
    )

    /**
     * Which screens are real for a set of flags (spec section 17 per phase): split → Jobs, mesh → Swarm, runtime →
     * Models, graph → Ask and Data; Settings is always real (its cloud section needs the cloud flag). Pure, for tests.
     */
    fun screensFor(flags: Collection<String>): Set<Screen> = buildSet {
        add(Screen.SETTINGS)
        if ("split" in flags) add(Screen.JOBS)
        if ("mesh" in flags) add(Screen.SWARM)
        if ("runtime" in flags) add(Screen.MODELS)
        if ("graph" in flags) { add(Screen.ASK); add(Screen.DATA) }
    }

    /** Real screens per flag over [graph]'s view models; a screen without its phase keeps the placeholder. */
    fun screens(graph: HiveCoreGraph, flags: Collection<String> = enabled()): Map<Screen, @Composable () -> Unit> {
        val on = screensFor(flags)
        val all: Map<Screen, @Composable () -> Unit> = mapOf(
            Screen.ASK to { AskRoute(graph.ask) },
            Screen.JOBS to { JobsRoute(graph.jobs, onPickFiles = rememberFilePicker { graph.jobs.addItems(it) }) },
            Screen.SWARM to { SwarmRoute(graph.swarm) },
            Screen.MODELS to { ModelsRoute(graph.models) },
            Screen.DATA to { DataRoute(graph.data) },
            Screen.SETTINGS to { SettingsRoute(graph.settings, cloud = "cloud" in flags) },
        )
        return all.filterKeys { it in on }
    }
}
