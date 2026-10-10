package com.hivecore.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

/** How a step's dot is drawn: its number, done, in progress, failed or skipped. */
enum class StepMark { Number, Done, Active, Failed, Skipped }

data class StepItem(
    val title: String,
    val detail: String? = null,
    val cloud: Boolean = false,
    val verified: Boolean? = null,
    val mark: StepMark = StepMark.Number,
    val a11y: String? = null,
)

/** Numbered steps joined by a line: plan steps, approval figures, a worker's chunk timeline. */
@Composable
fun StepList(steps: List<StepItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, s ->
            val last = i == steps.lastIndex
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = s.a11y ?: ("Step ${i + 1}, ${s.title}" + (s.detail?.let { ", $it" } ?: ""))
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(targetState = s.mark, label = "step-$i") { m -> StepDot(i + 1, m) }
                    if (!last) Box(Modifier.width(HiveSpacing.xxs).height(HiveSpacing.stepLine).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Spacer(Modifier.width(HiveSpacing.md))
                Column(Modifier.weight(1f).padding(top = HiveSpacing.xxs, bottom = if (last) HiveSpacing.xxs else HiveSpacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false),
                            fontWeight = if (s.mark == StepMark.Active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (s.mark == StepMark.Skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (s.verified == true) { Spacer(Modifier.width(HiveSpacing.xs)); Icon(HiveIcons.check, contentDescription = "verified", tint = HiveTheme.colors.ok, modifier = Modifier.size(HiveSpacing.iconSm)) }
                        if (s.cloud) { Spacer(Modifier.width(HiveSpacing.sm)); StatusPill("cloud", Tone.Cloud, icon = HiveIcons.cloud) }
                    }
                    if (s.detail != null) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepDot(n: Int, mark: StepMark) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (mark) {
        StepMark.Done -> cs.primary to cs.onPrimary
        StepMark.Failed -> cs.error to cs.onError
        StepMark.Active -> cs.primaryContainer to cs.primary
        StepMark.Skipped, StepMark.Number -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    Box(Modifier.size(HiveSpacing.stepDot).clip(HiveShape.circle).background(bg), contentAlignment = Alignment.Center) {
        when (mark) {
            StepMark.Active -> CircularProgressIndicator(Modifier.size(HiveSpacing.spinner), strokeWidth = HiveSpacing.xxs, color = fg)
            StepMark.Done -> Icon(HiveIcons.check, contentDescription = null, tint = fg, modifier = Modifier.size(HiveSpacing.iconXs))
            StepMark.Failed -> Text("!", style = HiveTextStyles.stepDot, color = fg)
            StepMark.Skipped -> Text("–", style = HiveTextStyles.stepDot, color = fg)
            StepMark.Number -> Text(n.toString(), style = HiveTextStyles.stepDot, color = fg)
        }
    }
}
