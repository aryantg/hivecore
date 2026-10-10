package com.hivecore.app

import androidx.compose.runtime.Composable
import com.hivecore.BuildConfig
import com.hivecore.ui.Screen

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

    /** Real screens per flag; a screen without its phase shows the placeholder. */
    fun screens(): Map<Screen, @Composable () -> Unit> = emptyMap()
}
