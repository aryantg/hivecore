package com.hivecore.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * What a capability page shows off: an icon, a one-sentence pitch, one live number and a prompt that tries the
 * capability in the chat.
 */
data class Capability(val icon: ImageVector, val title: String, val pitch: String, val stat: Stat? = null, val tryPrompt: String? = null)

/** "Try it in chat": fills the Home composer with a prompt and opens Home. The shell provides it. */
val LocalTryInChat = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun CapabilityHeader(c: Capability, modifier: Modifier = Modifier) {
    val onTry = LocalTryInChat.current
    HiveCard(modifier, tone = CardTone.Accent, shape = HiveShape.hero, contentPadding = HiveSpacing.hero) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(HiveSpacing.iconXl).clip(HiveShape.tile).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(c.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(HiveSpacing.iconLg))
            }
            Spacer(Modifier.width(HiveSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(c.pitch, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val prompt = c.tryPrompt
        if (c.stat != null || (prompt != null && onTry != null)) {
            Spacer(Modifier.height(HiveSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.stat != null) StatTile(c.stat, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                if (prompt != null && onTry != null) HiveButton(HiveAction("Try it in chat", HiveIcons.ask) { onTry(prompt) }, ButtonKind.Filled)
            }
        }
    }
}
