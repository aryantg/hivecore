package com.hivecore.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.HiveTheme
import com.hivecore.ui.qr.QrCode

/** Draws [text] as a QR symbol with a 4-module quiet zone; falls back to the text when it does not fit version 10. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val symbol = remember(text) { QrCode.encode(text) }
    if (symbol == null) { Text(text, style = HiveTextStyles.mono, color = MaterialTheme.colorScheme.onSurface, modifier = modifier); return }
    val dark = HiveTheme.colors.qrDark
    val light = HiveTheme.colors.qrLight
    Canvas(modifier.background(light).semantics { contentDescription = "QR code for the invite link" }) {
        val n = symbol.size + 8
        val cell = minOf(size.width, size.height) / n
        for (r in 0 until symbol.size) for (c in 0 until symbol.size) if (symbol.get(r, c)) {
            drawRect(dark, Offset((c + 4) * cell, (r + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
