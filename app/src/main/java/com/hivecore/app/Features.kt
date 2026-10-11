package com.hivecore.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.BuildConfig
import com.hivecore.ui.Screen
import com.hivecore.ui.screens.AskRoute
import com.hivecore.ui.screens.DataRoute
import com.hivecore.ui.screens.JobsRoute
import com.hivecore.ui.screens.ModelsRoute
import com.hivecore.ui.screens.SettingsRoute
import com.hivecore.ui.screens.SwarmRoute
import com.hivecore.ui.shell.LocalHiveNav
import com.hivecore.ui.shell.ShellStatus

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
    /** Decision-driven chunking (docs/plans/decision-chunking.md); needs `runtime` for the model. Default off until its gate passes. */
    val decide: Boolean get() = BuildConfig.FEATURE_DECIDE

    fun enabled(): List<String> = listOfNotNull(
        "mesh".takeIf { meshTrust }, "runtime".takeIf { runtime }, "split".takeIf { split }, "graph".takeIf { graph },
        "remote".takeIf { remote }, "state".takeIf { state }, "cloud".takeIf { cloud }, "hardening".takeIf { hardening },
        "decide".takeIf { decide },
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

    /**
     * Real screens per flag over [graph]'s view models; a screen without its phase keeps the placeholder. Cross-screen
     * moves (an answer's job, a suggestion that needs photos) go through the shell's navigation.
     */
    fun screens(graph: HiveCoreGraph, flags: Collection<String> = enabled(), extras: ScreenExtras = ScreenExtras()): Map<Screen, @Composable () -> Unit> {
        val on = screensFor(flags)
        val all: Map<Screen, @Composable () -> Unit> = mapOf(
            Screen.ASK to {
                val nav = LocalHiveNav.current
                val credits by graph.credits.state.collectAsStateWithLifecycle()
                AskRoute(graph.ask,
                    onOpenJob = { id -> graph.jobs.openHistory(id); nav?.go(Screen.JOBS) },
                    onOpenRecipe = { id -> graph.jobs.selectRecipe(id); nav?.go(Screen.JOBS) },
                    statusLine = greetingLine(shellStatus(graph, flags), credits.balanceLine))
            },
            Screen.JOBS to { JobsRoute(graph.jobs, onPickFiles = rememberFilePicker { graph.jobs.addItems(it) }, onSamplePhotos = extras.samplePhotos) },
            Screen.SWARM to { SwarmRoute(graph.swarm, graph.credits) },
            Screen.MODELS to { ModelsRoute(graph.models) },
            Screen.DATA to { DataRoute(graph.data, roleControl = extras.roleControl) },
            Screen.SETTINGS to { SettingsRoute(graph.settings, cloud = "cloud" in flags, demoSection = extras.demoSection) },
        )
        return all.filterKeys { it in on }
    }

    /** What the top bar reports, from the view models whose flag is on (a fake swarm never reaches the chip). */
    @Composable
    fun shellStatus(graph: HiveCoreGraph, flags: Collection<String> = enabled(), demo: Boolean = false): ShellStatus {
        val phones = if ("mesh" in flags) {
            val s by graph.swarm.state.collectAsStateWithLifecycle()
            s.tiles.takeIf { it.isNotEmpty() }?.let { t -> t.count { it.online } to t.size }
        } else null
        val big = if ("runtime" in flags) {
            val m by graph.models.state.collectAsStateWithLifecycle()
            m.big.takeIf { it != "none" }
        } else null
        val running = if ("split" in flags) {
            val j by graph.jobs.state.collectAsStateWithLifecycle()
            j.running
        } else false
        return ShellStatus(phonesOnline = phones?.first, phonesTotal = phones?.second, bigModel = big, jobRunning = running, demo = demo)
    }

    /**
     * "3 of 3 phones ready · qwen3-4b loaded · 120 points · private data stays on its phone" under the Home greeting;
     * null when neither a swarm, a model nor a points balance ([points], e.g. "120 points") is there to report.
     */
    fun greetingLine(s: ShellStatus, points: String? = null): String? {
        val parts = listOfNotNull(
            s.phonesTotal?.let { "${s.phonesOnline ?: 0} of $it ${if (it == 1) "phone" else "phones"} ready" },
            s.bigModel?.let { "$it loaded" },
            points,
        )
        return if (parts.isEmpty()) null else (parts + "private data stays on its phone").joinToString(" · ")
    }
}

/** Optional pieces the app adds to the screens (the demo shortcuts land here). */
class ScreenExtras(
    val samplePhotos: ((Int) -> Unit)? = null,
    val roleControl: (@Composable () -> Unit)? = null,
    val demoSection: (@Composable () -> Unit)? = null,
)
