package com.hivecore.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.hivecore.engine.picture.Picture
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone
import java.io.File

// The picture as the chat shows it (spec amendment "pictures from a description"): the PNG a phone of the swarm drew,
// its subject as the user said it, and Open / Share through the file provider, drawn with the kit like the deck card.

/** Under a picture answer: the illustration, the subject line and Open / Share. */
@Composable
fun PictureCard(picture: Picture, onOpenFile: ((String) -> Unit)?, onShareFile: ((String) -> Unit)?) {
    HiveCard(tone = CardTone.Tonal, contentPadding = HiveSpacing.cardTight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HiveIcons.photo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.icon))
            Spacer(Modifier.width(HiveSpacing.sm))
            Text(picture.subject.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (picture.drawn) StatusPill("${picture.width}×${picture.height}", Tone.Accent) else StatusPill("not drawn", Tone.Warn, icon = HiveIcons.warning)
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        GeneratedImage(picture.file, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(HiveSpacing.xs))
        val line = picture.file?.let { f -> File(f).name + " · generated on a phone of the swarm" + (picture.seed?.let { " · seed $it" } ?: "") }
            ?: picture.blocked ?: "no picture"
        Text(line, style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        picture.file?.let { f ->
            Spacer(Modifier.height(HiveSpacing.xs))
            ActionRow(
                primary = onOpenFile?.let { HiveAction("Open", HiveIcons.openExternal) { it(f) } },
                secondaries = listOfNotNull(onShareFile?.let { HiveAction("Share", HiveIcons.share) { it(f) } }),
            )
        }
    }
}

@HivePreviews
@Composable
private fun PictureCardPreview() = HiveCoreTheme {
    PictureCard(Picture("my team", "my team, clean modern flat illustration", file = "/data/decks/picture-3f9a1c0b2d4e.png", seed = 7, width = 512, height = 512,
        summary = "An illustration of my team, drawn from your words on a phone of the swarm, 512×512, seed 7."), onOpenFile = {}, onShareFile = {})
}

@HivePreviews
@Composable
private fun PictureCardBlockedPreview() = HiveCoreTheme {
    PictureCard(Picture("my team", "my team, clean modern flat illustration", blocked = "the picture did not reach this phone",
        summary = "No picture of my team: the picture did not reach this phone."), onOpenFile = {}, onShareFile = {})
}
