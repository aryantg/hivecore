package com.hivecore.ui.demo

import java.util.Locale

/** The words on one sample invoice. */
data class InvoiceText(val number: String, val vendor: String, val date: String, val items: List<String>, val total: String) {
    val lines: List<String> get() = listOf("INVOICE", "Invoice No: $number", "Vendor: $vendor", "Date: $date") + items + listOf("TOTAL  $total")
}

/**
 * Deterministic sample invoices (per index and seed). The app draws them as photos for the real OCR demo; the scripted
 * demo returns the same text as its OCR result, so both read alike.
 */
object InvoiceTexts {
    private val VENDORS = listOf("Kaveri Traders", "Lotus Office Supply", "Sahyadri Logistics", "Neel Print House",
        "Ganga Electricals", "Vindhya Steel", "Coral Catering", "Banyan IT Services")
    private val ITEMS = listOf("A4 paper ream" to 290, "Toner cartridge" to 3_450, "Courier, Pune-Mumbai" to 640, "LED panel 36W" to 1_180,
        "Steel rack" to 7_800, "Lunch boxes" to 150, "Laptop service" to 2_200, "Visiting cards (500)" to 900, "Ethernet cable 5m" to 340)

    fun text(i: Int, seed: Long = 7): InvoiceText {
        var x = (seed * 6364136223846793005L + i * 1442695040888963407L) xor 0x5DEECE66DL
        fun next(n: Int): Int { x = x * 6364136223846793005L + 1442695040888963407L; return ((x ushr 33) % n).toInt().let { if (it < 0) it + n else it } }
        val items = List(3) {
            val (name, price) = ITEMS[next(ITEMS.size)]
            Triple(name, 1 + next(6), price)
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

    fun rupees(n: Int): String = String.format(Locale.US, "%,d", n)

    /** The file name the app gives sample photo [i] (0-based). */
    fun fileName(i: Int): String = String.format(Locale.US, "sample_%04d.png", i + 1)
}
