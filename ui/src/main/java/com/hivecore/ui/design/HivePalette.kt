package com.hivecore.ui.design

import androidx.compose.ui.graphics.Color

/**
 * The one place raw colours live ("honey amber on slate"). Everything else reads the Material colour scheme or
 * [HiveColors]. Light and dark are tuned separately; every text pair clears 4.5:1.
 */
internal object HivePalette {
    // ---- light ----
    val lPrimary = Color(0xFF8A5A00); val lOnPrimary = Color(0xFFFFFFFF)
    val lPrimaryContainer = Color(0xFFFFDDB0); val lOnPrimaryContainer = Color(0xFF2C1700)
    val lSecondary = Color(0xFF5B6470); val lOnSecondary = Color(0xFFFFFFFF)
    val lSecondaryContainer = Color(0xFFDFE3EB); val lOnSecondaryContainer = Color(0xFF171C23)
    val lTertiary = Color(0xFF006A6A); val lOnTertiary = Color(0xFFFFFFFF)
    val lTertiaryContainer = Color(0xFFA8EFEE); val lOnTertiaryContainer = Color(0xFF002020)
    val lError = Color(0xFFB3261E); val lOnError = Color(0xFFFFFFFF)
    val lErrorContainer = Color(0xFFF9DEDC); val lOnErrorContainer = Color(0xFF410E0B)
    val lSurface = Color(0xFFFBF8F3); val lOnSurface = Color(0xFF1B1C1A)
    val lSurfaceDim = Color(0xFFDCD8D0)
    val lContainerLowest = Color(0xFFFFFFFF); val lContainerLow = Color(0xFFF5F1EA); val lContainer = Color(0xFFEFEBE3)
    val lContainerHigh = Color(0xFFE9E4DC); val lContainerHighest = Color(0xFFE3DED5)
    val lSurfaceVariant = Color(0xFFE8E2D8); val lOnSurfaceVariant = Color(0xFF4E4639)
    val lOutline = Color(0xFF7E766A); val lOutlineVariant = Color(0xFFD0C8BC)
    val lInverseSurface = Color(0xFF30312E); val lInverseOnSurface = Color(0xFFF2F0EB); val lInversePrimary = Color(0xFFFFB956)

    // ---- dark ----
    val dPrimary = Color(0xFFFFB956); val dOnPrimary = Color(0xFF4A2B00)
    val dPrimaryContainer = Color(0xFF6A4300); val dOnPrimaryContainer = Color(0xFFFFDDB0)
    val dSecondary = Color(0xFFC3CBD6); val dOnSecondary = Color(0xFF2C333D)
    val dSecondaryContainer = Color(0xFF434B55); val dOnSecondaryContainer = Color(0xFFDFE3EB)
    val dTertiary = Color(0xFF4DD9D7); val dOnTertiary = Color(0xFF003737)
    val dTertiaryContainer = Color(0xFF004F4F); val dOnTertiaryContainer = Color(0xFFA8EFEE)
    val dError = Color(0xFFF2B8B5); val dOnError = Color(0xFF601410)
    val dErrorContainer = Color(0xFF8C1D18); val dOnErrorContainer = Color(0xFFF9DEDC)
    val dSurface = Color(0xFF121418); val dOnSurface = Color(0xFFE3E5E9)
    val dSurfaceBright = Color(0xFF383C43)
    val dContainerLowest = Color(0xFF0C0E11); val dContainerLow = Color(0xFF1A1D22); val dContainer = Color(0xFF1E2228)
    val dContainerHigh = Color(0xFF282D34); val dContainerHighest = Color(0xFF33383F)
    val dSurfaceVariant = Color(0xFF3F4650); val dOnSurfaceVariant = Color(0xFFC0C7D1)
    val dOutline = Color(0xFF8A919B); val dOutlineVariant = Color(0xFF3F4650)
    val dInverseSurface = Color(0xFFE3E5E9); val dInverseOnSurface = Color(0xFF2F3238); val dInversePrimary = Color(0xFF8A5A00)

    // ---- extras ----
    val lAnswer = Color(0xFFFFEFD6); val lOnAnswer = Color(0xFF3A2400)
    val dAnswer = Color(0xFF3A2A10); val dOnAnswer = Color(0xFFFFE2BD)
    val lOkContainer = Color(0xFFD3EBD2); val lOnOk = Color(0xFF0B2E12); val lOk = Color(0xFF2E7D32)
    val dOkContainer = Color(0xFF1F3D24); val dOnOk = Color(0xFFBFE6C0); val dOk = Color(0xFF7BD389)
    val lWarnContainer = Color(0xFFFFD9C6); val lOnWarn = Color(0xFF5A1F00); val lWarn = Color(0xFFB4530A)
    val dWarnContainer = Color(0xFF5A2A10); val dOnWarn = Color(0xFFFFD9C6); val dWarn = Color(0xFFFFB68E)
    val lDuplicate = Color(0xFFE6DDFF); val lOnDuplicate = Color(0xFF22165A)
    val dDuplicate = Color(0xFF3B2F6B); val dOnDuplicate = Color(0xFFE6DDFF)
    val black = Color(0xFF000000); val white = Color(0xFFFFFFFF)
}
