package com.hivecore.ui.design

import androidx.compose.ui.unit.dp

/** The one place sizes and gaps live (the design guard allows only 0, 1 and 2 dp literals elsewhere). */
object HiveSpacing {
    val xxs = 2.dp; val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp; val xxl = 32.dp; val xxxl = 48.dp
    val screenH = 16.dp; val screenV = 12.dp; val listGap = 8.dp; val sectionTop = 20.dp
    val card = 14.dp; val cardTight = 12.dp; val hero = 20.dp
    val icon = 20.dp; val iconSm = 16.dp; val iconLg = 24.dp; val iconXl = 40.dp; val iconHero = 56.dp
    val avatar = 36.dp; val avatarSm = 28.dp; val avatarLg = 48.dp; val touch = 48.dp; val button = 40.dp; val send = 44.dp
    val chunk = 30.dp; val chunkGap = 4.dp; val swatch = 10.dp; val dot = 8.dp; val stepDot = 22.dp; val stepLine = 14.dp
    val border = 1.dp; val borderSelected = 2.dp; val progressThin = 6.dp; val spinnerSm = 12.dp; val spinner = 18.dp
    val suggestionMinHeight = 96.dp; val composerMinHeight = 52.dp; val fabClearance = 88.dp
    val iconXs = 14.dp; val pillH = 10.dp; val presence = 11.dp; val maxContent = 720.dp; val tableCell = 140.dp; val slideThumb = 168.dp
    const val QR_FRACTION = 0.8f
    const val BUBBLE_MAX_FRACTION = 0.82f
}
