package com.hivecore.app.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.hivecore.ui.demo.InvoiceText
import com.hivecore.ui.demo.InvoiceTexts
import java.io.File
import java.util.Locale

/**
 * Draws invoice-like photos on the device for the OCR demo, so no photo is ever committed to the repository. The
 * text is deterministic per index and seed; the files land in `cache/picked/` beside the system picker's copies
 * (the demo reset clears both).
 */
object SampleInvoices {
    fun text(i: Int, seed: Long = 7): InvoiceText = InvoiceTexts.text(i, seed)
    fun rupees(n: Int): String = InvoiceTexts.rupees(n)

    /** Draws [n] invoices and returns their paths, in order. Runs on the caller's thread: call it off the main thread. */
    fun generate(context: Context, n: Int, seed: Long = 7): List<String> {
        val dir = File(context.cacheDir, "picked").apply { mkdirs() }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; typeface = Typeface.SANS_SERIF }
        val rule = Paint().apply { color = Color.DKGRAY; strokeWidth = 3f }
        return (0 until n).map { i ->
            val t = text(i, seed)
            val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            var y = 140f
            c.drawText(t.lines[0], MARGIN, y, title); y += 110f
            for (line in t.lines.drop(1)) {
                if (line.startsWith("TOTAL")) { c.drawLine(MARGIN, y - 40f, WIDTH - MARGIN, y - 40f, rule); y += 20f }
                c.drawText(line, MARGIN, y, if (line.startsWith("TOTAL")) title.apply { textSize = 56f } else body)
                y += 86f
            }
            title.textSize = 72f
            val out = File(dir, InvoiceTexts.fileName(i))
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            out.path
        }
    }

    private const val WIDTH = 1080
    private const val HEIGHT = 1400
    private const val MARGIN = 80f
}
