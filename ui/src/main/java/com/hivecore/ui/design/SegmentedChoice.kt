package com.hivecore.ui.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** One choice out of a few: cloud policy, the Data kinds, the phone's role, the demo speed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.semantics { contentDescription = label(o) },
                icon = { if (icon != null && o != selected) Icon(icon(o), contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) else SegmentedButtonDefaults.Icon(o == selected) },
                label = { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
