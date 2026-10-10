package com.hivecore.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The one place text sizes live. System font, tuned weights and tracking. */
private fun style(size: Int, line: Int, weight: FontWeight, trackingSp: Double, family: FontFamily = FontFamily.Default, tnum: Boolean = false) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = trackingSp.sp,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

internal val HiveTypography = Typography(
    displayLarge = style(48, 56, FontWeight.Bold, -1.0),
    displayMedium = style(42, 50, FontWeight.Bold, -0.75),
    displaySmall = style(36, 44, FontWeight.Bold, -0.5),
    headlineLarge = style(30, 36, FontWeight.SemiBold, -0.5),
    headlineMedium = style(26, 32, FontWeight.SemiBold, -0.25),
    headlineSmall = style(22, 28, FontWeight.SemiBold, 0.0),
    titleLarge = style(20, 26, FontWeight.SemiBold, 0.0),
    titleMedium = style(16, 22, FontWeight.SemiBold, 0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.15),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.1),
    bodySmall = style(12, 16, FontWeight.Normal, 0.2),
    labelLarge = style(14, 20, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.3),
    labelSmall = style(11, 16, FontWeight.Medium, 0.4),
)

/** Styles Material's scale lacks: big numbers, the greeting, SAS digits, hashes and the chunk grid's marks. */
object HiveTextStyles {
    val stat = style(40, 44, FontWeight.Bold, -1.0, tnum = true)
    val statSmall = style(26, 30, FontWeight.Bold, -0.5, tnum = true)
    val greeting = style(28, 34, FontWeight.SemiBold, -0.5)
    val digits = style(40, 48, FontWeight.Bold, 4.0, FontFamily.Monospace, tnum = true)
    val mono = style(13, 18, FontWeight.Normal, 0.0, FontFamily.Monospace)
    val monoSmall = style(11, 16, FontWeight.Normal, 0.0, FontFamily.Monospace)
    val chunkInitial = style(13, 13, FontWeight.SemiBold, 0.0)
    val chunkMark = style(8, 8, FontWeight.Bold, 0.0)
    val chunkBang = style(9, 9, FontWeight.Bold, 0.0)
    val stepDot = style(12, 12, FontWeight.Bold, 0.0)
    val count = style(10, 10, FontWeight.Bold, 0.0, tnum = true)
}
