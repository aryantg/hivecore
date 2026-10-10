package com.hivecore.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/** What a card is about; picks its container and content colours. */
enum class CardTone { Plain, Tonal, Accent, Answer, Ok, Warn, Refusal, Cloud }

@Composable
fun cardColors(tone: CardTone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val x = HiveTheme.colors
    return when (tone) {
        CardTone.Plain -> cs.surfaceContainerLow to cs.onSurface
        CardTone.Tonal -> cs.surfaceContainerHigh to cs.onSurface
        CardTone.Accent -> cs.primaryContainer to cs.onPrimaryContainer
        CardTone.Answer -> x.answerContainer to x.onAnswerContainer
        CardTone.Ok -> x.okContainer to x.onOkContainer
        CardTone.Warn -> x.warnContainer to x.onWarnContainer
        CardTone.Refusal -> x.refusalContainer to x.onRefusalContainer
        CardTone.Cloud -> x.cloudContainer to x.onCloudContainer
    }
}

/**
 * The only card. [selected] draws the primary border (recipe and mode choices); [enabled] = false dims it; [semantics]
 * carries `stateDescription` / merged descriptions so selectable cards stay accessible.
 */
@Composable
fun HiveCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Plain,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = HiveShape.card,
    contentPadding: Dp = HiveSpacing.card,
    semantics: (SemanticsPropertyReceiver.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (bg, fg) = cardColors(tone)
    val border = when {
        selected -> BorderStroke(HiveSpacing.borderSelected, MaterialTheme.colorScheme.primary)
        tone == CardTone.Plain -> BorderStroke(HiveSpacing.border, MaterialTheme.colorScheme.outlineVariant)
        else -> null
    }
    var m = modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)
    if (semantics != null) m = m.semantics(properties = semantics)
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, modifier = m, shape = shape, color = bg, contentColor = fg, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = m, shape = shape, color = bg, contentColor = fg, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

internal const val DISABLED_ALPHA = 0.55f
