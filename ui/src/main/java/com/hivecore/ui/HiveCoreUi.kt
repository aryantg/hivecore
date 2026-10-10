package com.hivecore.ui

import androidx.compose.runtime.Composable
import com.hivecore.ui.design.EmptyState
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.icon

/**
 * The six core screens (spec section 17). One activity, a bottom bar. Ask is the chat Home ([navLabel] "Home");
 * the other five are capability pages.
 */
enum class Screen(val title: String, val navLabel: String, val blurb: String) {
    ASK("Ask", "Home", "Ask in plain words; the plan, the LOCAL / CLOUD choice and the answer with provenance appear in the conversation."),
    JOBS("Jobs", "Jobs", "Live chunk grid, per-phone load, bytes moved, speed-up, resume."),
    SWARM("Swarm", "Swarm", "Members, link kind, battery, loaded model, pairing by QR or invite link, worker opt-in."),
    MODELS("Models", "Models", "Capability catalog, installed and loaded state, BENCH p50."),
    DATA("Data", "Data", "Tables, documents and media visible to the swarm; private / released state."),
    SETTINGS("Settings", "Settings", "Cloud policy, OpenRouter key, caps, relay address, advanced tuning."),
}

/** Per-screen content providers; the app wires real screens in as each phase lands behind its feature flag. */
class ScreenRegistry(private val screens: Map<Screen, @Composable () -> Unit> = emptyMap()) {
    @Composable fun content(s: Screen) = (screens[s] ?: { Placeholder(s) })()
}

/** A screen whose phase is not built into this APK. */
@Composable
fun Placeholder(s: Screen) {
    HiveScreen(s) {
        item("placeholder") { EmptyState("${s.title} is not in this build", s.blurb + "\n\nBuild with -PhiveFeatures=all to turn it on.", icon = s.icon()) }
    }
}
