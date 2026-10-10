package com.hivecore.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hivecore.ui.HiveTheme
import com.hivecore.ui.ports.Refusal

/** Small building blocks shared by every screen: badges, banners, empty / loading / error states, the message bar. */

enum class Tone { OK, WARN, CLOUD, NEUTRAL }

@Composable
fun Badge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = HiveTheme.colors
    val (bg, fg) = when (tone) {
        Tone.OK -> c.okBadge to c.onOkBadge
        Tone.WARN -> c.warnBadge to c.onWarnBadge
        Tone.CLOUD -> c.cloudBadge to c.onCloudBadge
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = fg,
        modifier = modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun Banner(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (warn) HiveTheme.colors.warnBadge else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (warn) HiveTheme.colors.onWarnBadge else MaterialTheme.colorScheme.onSecondaryContainer
    Text(text, style = MaterialTheme.typography.bodyMedium, color = fg,
        modifier = modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
fun ScreenTitle(title: String, blurb: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 12.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
fun EmptyState(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** A one-line result / error message with a Dismiss button (lighter than a Snackbar inside scrolling lists). */
@Composable
fun MessageBar(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** A labelled switch with a one-line explanation under the label. */
@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

/**
 * Spec section 17 "Refusals in plain words": one sentence a non-developer understands, plus a "details" expander with
 * the plan hash and the step. Used by the Ask screen (refused plans and answers) and the Jobs screen (failed and
 * partial jobs).
 */
@Composable
fun RefusalCard(refusal: Refusal, modifier: Modifier = Modifier, title: String = "Refused", header: (@Composable () -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    val c = HiveTheme.colors
    var expanded by rememberSaveable(refusal) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.refusalContainer, contentColor = c.onRefusalContainer)) {
        Column(Modifier.padding(14.dp)) {
            if (header != null) header() else Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(refusal.sentence, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val lines = refusal.detailLines
            if (lines.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics { contentDescription = if (expanded) "Hide details" else "Show details" }) {
                    Text(if (expanded) "Hide details" else "Details")
                }
                if (expanded) lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            if (actions != null) { Spacer(Modifier.height(4.dp)); actions() }
        }
    }
}
