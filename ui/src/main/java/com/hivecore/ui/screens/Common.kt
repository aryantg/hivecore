package com.hivecore.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.ports.Refusal

/**
 * Spec section 17 "Refusals in plain words": one sentence a non-developer understands, plus a "details" expander with
 * the plan hash and the step. Used by the chat (refused plans and answers) and the Jobs screen (failed and partial jobs).
 */
@Composable
fun RefusalCard(refusal: Refusal, modifier: Modifier = Modifier, title: String = "Refused", header: (@Composable () -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    var expanded by rememberSaveable(refusal) { mutableStateOf(false) }
    HiveCard(modifier, tone = CardTone.Refusal) {
        if (header != null) header() else Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HiveIcons.warning, contentDescription = null, modifier = Modifier.size(HiveSpacing.iconSm))
            Spacer(Modifier.width(HiveSpacing.sm))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        Text(refusal.sentence, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        val lines = refusal.detailLines
        if (lines.isNotEmpty()) {
            HiveButton(HiveAction(if (expanded) "Hide details" else "Details", if (expanded) HiveIcons.collapse else HiveIcons.expand) { expanded = !expanded }, ButtonKind.Text)
            if (expanded) Column { lines.forEach { Text(it, style = HiveTextStyles.mono) } }
        }
        if (actions != null) { Spacer(Modifier.height(HiveSpacing.xs)); actions() }
    }
}
