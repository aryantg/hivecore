package com.hivecore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The six core screens (spec section 17). One activity, a bottom bar. */
enum class Screen(val title: String, val glyph: String, val blurb: String) {
    ASK("Ask", "?", "Request input, plan panel, LOCAL / CLOUD choice, answer with provenance."),
    JOBS("Jobs", "#", "Live chunk grid, per-phone load, bytes moved, speed-up, resume."),
    SWARM("Swarm", "*", "Members, link kind, battery, loaded model, pairing by QR or invite link, worker opt-in."),
    MODELS("Models", "M", "Capability catalog, installed and loaded state, BENCH p50."),
    DATA("Data", "D", "Tables, documents and media visible to the swarm; private / released state."),
    SETTINGS("Settings", "=", "Cloud policy, OpenRouter key, caps, relay address, advanced tuning."),
}

/** Per-screen content providers; the app wires real screens in as each phase lands behind its feature flag. */
class ScreenRegistry(private val screens: Map<Screen, @Composable () -> Unit> = emptyMap()) {
    @Composable fun content(s: Screen) = (screens[s] ?: { Placeholder(s) })()
}

@Composable
fun HiveCoreTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun HiveCoreShell(registry: ScreenRegistry = ScreenRegistry(), start: Screen = Screen.ASK) {
    var current by rememberSaveable { mutableStateOf(start) }
    HiveCoreTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach { s ->
                        NavigationBarItem(
                            selected = current == s,
                            onClick = { current = s },
                            icon = { Text(s.glyph) },
                            label = { Text(s.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { registry.content(current) }
        }
    }
}

@Composable
fun Placeholder(s: Screen) {
    Text(s.title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(s.blurb, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Text("This screen lands with its phase; the engine behind it is tested on the JVM first.", style = MaterialTheme.typography.bodySmall)
}
