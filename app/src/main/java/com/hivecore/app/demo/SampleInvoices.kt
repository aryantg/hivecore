package com.hivecore.app.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.util.Locale

/** The words on one sample invoice (pure, so the content is JVM-tested). */
data class InvoiceText(val number: String, val vendor: String, val date: String, val items: List<String>, val total: String) {
    val lines: List<String> get() = listOf("INVOICE", "Invoice No: $number", "Vendor: $vendor", "Date: $date") + items + listOf("TOTAL  $total")
}

/**
 * Draws invoice-like photos on the device for the OCR demo, so no photo is ever committed to the repository. The
 * text is deterministic per index and seed; the files land in `cache/picked/` beside the system picker's copies
 * (the demo reset clears both).
 */
object SampleInvoices {
    private val VENDORS = listOf("Kaveri Traders", "Lotus Office Supply", "Sahyadri Logistics", "Neel Print House",
        "Ganga Electricals", "Vindhya Steel", "Coral Catering", "Banyan IT Services")
    private val ITEMS = listOf("A4 paper ream" to 290, "Toner cartridge" to 3_450, "Courier, Pune-Mumbai" to 640, "LED panel 36W" to 1_180,
        "Steel rack" to 7_800, "Lunch boxes" to 150, "Laptop service" to 2_200, "Visiting cards (500)" to 900, "Ethernet cable 5m" to 340)

    fun text(i: Int, seed: Long = 7): InvoiceText {
        var x = (seed * 6364136223846793005L + i * 1442695040888963407L) xor 0x5DEECE66DL
        fun next(n: Int): Int { x = x * 6364136223846793005L + 1442695040888963407L; return ((x ushr 33) % n).toInt().let { if (it < 0) it + n else it } }
        val items = List(3) {
            val (name, price) = ITEMS[next(ITEMS.size)]
            val qty = 1 + next(6)
            Triple(name, qty, price)
        }
        val total = items.sumOf { it.second * it.third }
        return InvoiceText(
            number = String.format(Locale.US, "INV-2026-%04d", 1000 + i),
            vendor = VENDORS[next(VENDORS.size)],
            date = String.format(Locale.US, "2026-%02d-%02d", 7 + next(3), 1 + next(28)),
            items = items.map { (name, qty, price) -> "$qty x $name  ₹ ${rupees(qty * price)}" },
            total = "₹ ${rupees(total)}",
        )
    }

    /** "12,345" (Indian grouping is not needed for a demo; plain thousands separators read well). */
    fun rupees(n: Int): String = String.format(Locale.US, "%,d", n)

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
            val out = File(dir, String.format(Locale.US, "sample_%04d.png", i + 1))
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            out.path
        }
    }

    private const val WIDTH = 1080
    private const val HEIGHT = 1400
    private const val MARGIN = 80f
}
