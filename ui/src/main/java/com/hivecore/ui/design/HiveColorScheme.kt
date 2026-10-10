package com.hivecore.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.hivecore.ui.design.HivePalette as P

internal val HiveLightScheme: ColorScheme = lightColorScheme(
    primary = P.lPrimary, onPrimary = P.lOnPrimary, primaryContainer = P.lPrimaryContainer, onPrimaryContainer = P.lOnPrimaryContainer,
    inversePrimary = P.lInversePrimary,
    secondary = P.lSecondary, onSecondary = P.lOnSecondary, secondaryContainer = P.lSecondaryContainer, onSecondaryContainer = P.lOnSecondaryContainer,
    tertiary = P.lTertiary, onTertiary = P.lOnTertiary, tertiaryContainer = P.lTertiaryContainer, onTertiaryContainer = P.lOnTertiaryContainer,
    error = P.lError, onError = P.lOnError, errorContainer = P.lErrorContainer, onErrorContainer = P.lOnErrorContainer,
    background = P.lSurface, onBackground = P.lOnSurface, surface = P.lSurface, onSurface = P.lOnSurface,
    surfaceVariant = P.lSurfaceVariant, onSurfaceVariant = P.lOnSurfaceVariant, surfaceTint = P.lPrimary,
    inverseSurface = P.lInverseSurface, inverseOnSurface = P.lInverseOnSurface,
    outline = P.lOutline, outlineVariant = P.lOutlineVariant, scrim = P.black,
    surfaceBright = P.lSurface, surfaceDim = P.lSurfaceDim,
    surfaceContainerLowest = P.lContainerLowest, surfaceContainerLow = P.lContainerLow, surfaceContainer = P.lContainer,
    surfaceContainerHigh = P.lContainerHigh, surfaceContainerHighest = P.lContainerHighest,
)

internal val HiveDarkScheme: ColorScheme = darkColorScheme(
    primary = P.dPrimary, onPrimary = P.dOnPrimary, primaryContainer = P.dPrimaryContainer, onPrimaryContainer = P.dOnPrimaryContainer,
    inversePrimary = P.dInversePrimary,
    secondary = P.dSecondary, onSecondary = P.dOnSecondary, secondaryContainer = P.dSecondaryContainer, onSecondaryContainer = P.dOnSecondaryContainer,
    tertiary = P.dTertiary, onTertiary = P.dOnTertiary, tertiaryContainer = P.dTertiaryContainer, onTertiaryContainer = P.dOnTertiaryContainer,
    error = P.dError, onError = P.dOnError, errorContainer = P.dErrorContainer, onErrorContainer = P.dOnErrorContainer,
    background = P.dSurface, onBackground = P.dOnSurface, surface = P.dSurface, onSurface = P.dOnSurface,
    surfaceVariant = P.dSurfaceVariant, onSurfaceVariant = P.dOnSurfaceVariant, surfaceTint = P.dPrimary,
    inverseSurface = P.dInverseSurface, inverseOnSurface = P.dInverseOnSurface,
    outline = P.dOutline, outlineVariant = P.dOutlineVariant, scrim = P.black,
    surfaceBright = P.dSurfaceBright, surfaceDim = P.dSurface,
    surfaceContainerLowest = P.dContainerLowest, surfaceContainerLow = P.dContainerLow, surfaceContainer = P.dContainer,
    surfaceContainerHigh = P.dContainerHigh, surfaceContainerHighest = P.dContainerHighest,
)

/** Colours beyond the Material scheme: answers, refusals, ok / warn / cloud states, duplicated chunks, confidential marks, QR. */
@Immutable
data class HiveColors(
    val answerContainer: Color, val onAnswerContainer: Color,
    val refusalContainer: Color, val onRefusalContainer: Color,
    val okContainer: Color, val onOkContainer: Color, val ok: Color,
    val warnContainer: Color, val onWarnContainer: Color, val warn: Color,
    val cloudContainer: Color, val onCloudContainer: Color, val cloud: Color,
    val duplicateContainer: Color, val onDuplicateContainer: Color,
    val confidential: Color,
    val qrDark: Color, val qrLight: Color,
)

internal val HiveLightExtras = HiveColors(
    answerContainer = P.lAnswer, onAnswerContainer = P.lOnAnswer,
    refusalContainer = P.lErrorContainer, onRefusalContainer = P.lOnErrorContainer,
    okContainer = P.lOkContainer, onOkContainer = P.lOnOk, ok = P.lOk,
    warnContainer = P.lWarnContainer, onWarnContainer = P.lOnWarn, warn = P.lWarn,
    cloudContainer = P.lTertiaryContainer, onCloudContainer = P.lOnTertiaryContainer, cloud = P.lTertiary,
    duplicateContainer = P.lDuplicate, onDuplicateContainer = P.lOnDuplicate,
    confidential = P.lError, qrDark = P.black, qrLight = P.white,
)

internal val HiveDarkExtras = HiveColors(
    answerContainer = P.dAnswer, onAnswerContainer = P.dOnAnswer,
    refusalContainer = P.dErrorContainer, onRefusalContainer = P.dOnErrorContainer,
    okContainer = P.dOkContainer, onOkContainer = P.dOnOk, ok = P.dOk,
    warnContainer = P.dWarnContainer, onWarnContainer = P.dOnWarn, warn = P.dWarn,
    cloudContainer = P.dTertiaryContainer, onCloudContainer = P.dOnTertiaryContainer, cloud = P.dTertiary,
    duplicateContainer = P.dDuplicate, onDuplicateContainer = P.dOnDuplicate,
    confidential = P.dError, qrDark = P.black, qrLight = P.white,
)

val LocalHiveColors = staticCompositionLocalOf { HiveLightExtras }

/** The chunk grid's states, kept free of engine types so the design package stays domain-neutral. */
enum class ChunkTone { Queued, Running, Done, Duplicated, Failed, Cloud }

@Composable
fun chunkColors(tone: ChunkTone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val x = LocalHiveColors.current
    return when (tone) {
        ChunkTone.Queued -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        ChunkTone.Running -> cs.primaryContainer to cs.onPrimaryContainer
        ChunkTone.Done -> x.okContainer to x.onOkContainer
        ChunkTone.Duplicated -> x.duplicateContainer to x.onDuplicateContainer
        ChunkTone.Failed -> cs.errorContainer to cs.onErrorContainer
        ChunkTone.Cloud -> x.cloudContainer to x.onCloudContainer
    }
}
