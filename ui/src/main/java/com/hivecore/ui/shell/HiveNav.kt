package com.hivecore.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.hivecore.ui.Screen

/** The app's navigation: which of the six screens is on top. Hoisted so any screen can send the user elsewhere. */
class HiveNav(initial: Screen = Screen.ASK) {
    var current: Screen by mutableStateOf(initial)
        private set

    fun go(s: Screen) { current = s }

    companion object {
        val Saver: Saver<HiveNav, String> = Saver(save = { it.current.name }, restore = { HiveNav(Screen.valueOf(it)) })
    }
}

@Composable
fun rememberHiveNav(start: Screen = Screen.ASK): HiveNav = rememberSaveable(saver = HiveNav.Saver) { HiveNav(start) }

/** Null outside the shell (previews, tests); screens then skip cross-screen navigation. */
val LocalHiveNav = staticCompositionLocalOf<HiveNav?> { null }
