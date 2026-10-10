package com.hivecore.ui.design

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** The only text field style. */
@Composable
fun HiveTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    supporting: String? = null,
    password: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier,
        label = label?.let { { Text(it) } }, placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine, minLines = minLines, enabled = enabled, isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) keyboard.copy(keyboardType = KeyboardType.Password) else keyboard,
        trailingIcon = trailing, shape = HiveShape.field,
    )
}

@Composable
fun IntField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    HiveTextField(text, { t -> text = t; t.toIntOrNull()?.let(onChange) }, modifier, label = label, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
fun DoubleField(label: String, value: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    HiveTextField(text, { t -> text = t; t.toDoubleOrNull()?.let(onChange) }, modifier, label = label, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

/** A field that commits with an inline Save once edited. */
@Composable
fun SaveableTextField(label: String, value: String, placeholder: String, modifier: Modifier = Modifier, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    HiveTextField(text, { text = it }, modifier, label = label, placeholder = placeholder,
        trailing = { if (text != value) TextButton(onClick = { onSave(text) }) { Text("Save") } })
}
