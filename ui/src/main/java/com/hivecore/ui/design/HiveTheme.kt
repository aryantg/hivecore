package com.hivecore.ui.design

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview

/** The one theme root. Every activity wraps its content in it; nothing else builds a colour scheme. */
@Composable
fun HiveCoreTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHiveColors provides if (darkTheme) HiveDarkExtras else HiveLightExtras) {
        MaterialTheme(colorScheme = if (darkTheme) HiveDarkScheme else HiveLightScheme, typography = HiveTypography, shapes = HiveShapes, content = content)
    }
}

/** Presenter switch: false hides plan and result hashes (Settings > Demo > Hide hashes). */
val LocalShowHashes = androidx.compose.runtime.staticCompositionLocalOf { true }

object HiveTheme {
    val colors: HiveColors @Composable get() = LocalHiveColors.current
}

/** Light and dark previews of a screen: every screen file under `screens` carries at least one. */
@Preview(name = "light", showBackground = true, widthDp = 400)
@Preview(name = "dark", showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class HivePreviews
