package com.hivecore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Colours beyond the Material scheme: the answer / refusal cards, the ok / warn / cloud badges, confidential marks. */
@Immutable
data class HiveColors(
    val answerContainer: Color, val onAnswerContainer: Color,
    val refusalContainer: Color, val onRefusalContainer: Color,
    val okBadge: Color, val onOkBadge: Color,
    val warnBadge: Color, val onWarnBadge: Color,
    val cloudBadge: Color, val onCloudBadge: Color,
    val confidential: Color,
)

private val LightExtras = HiveColors(
    answerContainer = Color(0xFFDDEFD9), onAnswerContainer = Color(0xFF0F2E12),
    refusalContainer = Color(0xFFF9DEDC), onRefusalContainer = Color(0xFF410E0B),
    okBadge = Color(0xFFCFE8D0), onOkBadge = Color(0xFF0F2E12),
    warnBadge = Color(0xFFFFE0B2), onWarnBadge = Color(0xFF4A2A00),
    cloudBadge = Color(0xFFD6E4FF), onCloudBadge = Color(0xFF002E6A),
    confidential = Color(0xFFB3261E),
)

private val DarkExtras = HiveColors(
    answerContainer = Color(0xFF1E3A22), onAnswerContainer = Color(0xFFCDEBCB),
    refusalContainer = Color(0xFF5C1A16), onRefusalContainer = Color(0xFFF9DEDC),
    okBadge = Color(0xFF2B4D2E), onOkBadge = Color(0xFFCDEBCB),
    warnBadge = Color(0xFF5A3A00), onWarnBadge = Color(0xFFFFE0B2),
    cloudBadge = Color(0xFF1F3F75), onCloudBadge = Color(0xFFD6E4FF),
    confidential = Color(0xFFF2B8B5),
)

val LocalHiveColors = staticCompositionLocalOf { LightExtras }

object HiveTheme {
    val colors: HiveColors @Composable get() = LocalHiveColors.current
}

@Composable
fun HiveCoreTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalHiveColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
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
            Column(Modifier.fillMaxSize().padding(padding)) { registry.content(current) }
        }
    }
}

@Composable
fun Placeholder(s: Screen) {
    Column(Modifier.padding(16.dp)) {
        Text(s.title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(s.blurb, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text("This screen lands with its phase; the engine behind it is tested on the JVM first.", style = MaterialTheme.typography.bodySmall)
    }
}
