package com.hivecore.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

/** The one result or live object on a screen: a big headline, a caption, pills, a body and its actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroCard(
    headline: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    tone: CardTone = CardTone.Answer,
    icon: ImageVector? = null,
    headlineStyle: TextStyle = HiveTextStyles.stat,
    headlineDescription: String? = null,
    pills: (@Composable FlowRowScope.() -> Unit)? = null,
    primary: HiveAction? = null,
    secondaries: List<HiveAction> = emptyList(),
    body: @Composable ColumnScope.() -> Unit = {},
) {
    HiveCard(modifier, tone = tone, shape = HiveShape.hero, contentPadding = HiveSpacing.hero) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(HiveSpacing.iconLg)); Spacer(Modifier.width(HiveSpacing.sm)) }
            Text(headline, style = headlineStyle, modifier = if (headlineDescription != null) Modifier.semantics { contentDescription = headlineDescription } else Modifier)
        }
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium)
        if (pills != null) {
            Spacer(Modifier.height(HiveSpacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs), content = pills)
        }
        body()
        if (primary != null || secondaries.isNotEmpty()) {
            Spacer(Modifier.height(HiveSpacing.md))
            ActionRow(primary, secondaries)
        }
    }
}
