package com.hivecore.app.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.File

/**
 * The picture slide of the demo deck, drawn on the device so no image is committed: three rising amber bars on
 * slate with a soft glow, what the SD 1.5 step would be asked for ("an abstract rising bar chart, warm amber and dark
 * slate"). Deterministic, so the deck file's hash is stable across runs; lands in `cache/demo/` beside the picker's
 * copies (the demo reset clears it).
 */
object DemoIllustration {
    fun draw(context: Context): File {
        val dir = File(context.cacheDir, "demo").apply { mkdirs() }
        val out = File(dir, "illustration.png")
        if (out.isFile && out.length() > 0) return out
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(SLATE)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, SIZE.toFloat(), SIZE.toFloat(), 0f, GLOW_FROM, GLOW_TO, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), glow)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val heights = floatArrayOf(0.34f, 0.52f, 0.74f)
        val slot = SIZE * 0.72f / heights.size
        val left0 = SIZE * 0.14f
        val base = SIZE * 0.84f
        heights.forEachIndexed { i, h ->
            bar.color = if (i == heights.lastIndex) AMBER else AMBER_DEEP
            val l = left0 + slot * i + slot * 0.18f
            val r = l + slot * 0.64f
            c.drawRoundRect(RectF(l, base - SIZE * h, r, base), RADIUS, RADIUS, bar)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; strokeWidth = SIZE * 0.006f }
        c.drawLine(SIN, base, SIZE - SIN, base, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER }
        heights.forEachIndexed { i, h -> c.drawCircle(left0 + slot * i + slot * 0.5f, base - SIZE * h - SIZE * 0.06f, SIZE * 0.012f, dot) }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    private const val SIZE = 768
    private const val RADIUS = 18f
    private const val SIN = SIZE * 0.08f
    private val SLATE = Color.rgb(0x1A, 0x1D, 0x22)
    private val AMBER = Color.rgb(0xFF, 0xB9, 0x56)
    private val AMBER_DEEP = Color.rgb(0x8A, 0x5A, 0x00)
    private val MUTED = Color.rgb(0xC0, 0xC7, 0xD1)
    private val GLOW_FROM = Color.argb(0x30, 0xFF, 0xB9, 0x56)
    private val GLOW_TO = Color.argb(0x00, 0xFF, 0xB9, 0x56)
}
