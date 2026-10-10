package com.hivecore.node.docai

import kotlin.math.roundToLong

/** The four invoice fields, each null until found, and `by` = field -> "regex" | "llm" for the ones that are. */
data class Partial(
    val vendor: String? = null,
    val date: String? = null,      // ISO YYYY-MM-DD
    val total: Long? = null,       // whole rupees
    val status: String? = null,    // PAID | UNPAID | DUE | OVERDUE | PENDING
    val by: Map<String, String> = emptyMap(),
) {
    val missing: List<String>
        get() = buildList {
            if (vendor == null) add("vendor"); if (date == null) add("date"); if (total == null) add("total"); if (status == null) add("status")
        }
    val complete: Boolean get() = missing.isEmpty()
    /** Fields the LLM supplied (these go to the owner's review list). */
    val llmFields: List<String> get() = FIELDS.filter { by[it] == "llm" }
    val regexFields: List<String> get() = FIELDS.filter { by[it] == "regex" }

    fun value(field: String): String? = when (field) {
        "vendor" -> vendor; "date" -> date; "total" -> total?.toString(); "status" -> status; else -> null
    }

    companion object { val FIELDS = listOf("vendor", "date", "total", "status") }
}

/**
 * Deterministic extraction from the row texts of one invoice:
 *  - vendor: the first row that is not a keyword line (`invoice|tax|gst|date|total|amount|bill|no.`) and has at least two letters;
 *  - date: `d/m/y` (also `d-m-y`, two- or four-digit year), ISO `yyyy-mm-dd`, or `7 Oct 2026` / `21st October, 2026`;
 *    rows mentioning "date" are tried first;
 *  - total: the amount on the LAST row matching `grand total|total due|amount due|total`, rupee-prefix tolerant,
 *    Indian (1,28,400.00) or western (128,400.00) grouping;
 *  - status: `PAID|UNPAID|DUE|OVERDUE|PENDING` on the "Status" row (any case), else as an upper-case word anywhere.
 * Anything it cannot find stays null and goes to the LLM.
 */
object InvoiceRegex {
    private val KEYWORD_LINE = Regex("\\b(invoice|tax|gst|date|total|amount|bill|no\\.?)(?=\\W|$)", RegexOption.IGNORE_CASE)
    private val TWO_LETTERS = Regex("\\p{L}.*\\p{L}")
    private val DATE_KEYWORD = Regex("\\bdate", RegexOption.IGNORE_CASE)
    private val DMY = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})")
    private val ISO = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private val MONTH_NAME = Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?,?\\s+(\\d{4})", RegexOption.IGNORE_CASE)
    private val TOTAL_ROW = Regex("grand total|total due|amount due|total", RegexOption.IGNORE_CASE)
    val AMOUNT = Regex("(?:₹|Rs\\.?|INR)?\\s*([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)
    private val STATUS_ROW = Regex("\\bstatus\\b", RegexOption.IGNORE_CASE)
    private val STATUS_WORD_ANY_CASE = Regex("\\b(PAID|UNPAID|DUE|OVERDUE|PENDING)\\b", RegexOption.IGNORE_CASE)
    private val STATUS_WORD_UPPER = Regex("\\b(PAID|UNPAID|DUE|OVERDUE|PENDING)\\b")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun extract(rows: List<String>): Partial {
        val clean = rows.map { it.trim() }.filter { it.isNotEmpty() }
        val vendor = clean.firstOrNull { !KEYWORD_LINE.containsMatchIn(it) && TWO_LETTERS.containsMatchIn(it) }
        val date = (clean.filter { DATE_KEYWORD.containsMatchIn(it) } + clean).firstNotNullOfOrNull { parseDate(it) }
        val total = clean.lastOrNull { TOTAL_ROW.containsMatchIn(it) }?.let { parseAmount(it) }
        val status = parseStatus(clean)
        val by = LinkedHashMap<String, String>()
        if (vendor != null) by["vendor"] = "regex"
        if (date != null) by["date"] = "regex"
        if (total != null) by["total"] = "regex"
        if (status != null) by["status"] = "regex"
        return Partial(vendor, date, total, status, by)
    }

    /** The first date in [s] as ISO, or null. d/m/y order; m/d/y only when the first number cannot be a month-day. */
    fun parseDate(s: String): String? {
        ISO.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return iso(y.toInt(), mo.toInt(), d.toInt())
        }
        DMY.find(s)?.let { m ->
            var a = m.groupValues[1].toInt(); var b = m.groupValues[2].toInt()
            val yRaw = m.groupValues[3]
            val y = if (yRaw.length <= 2) 2000 + yRaw.toInt() else yRaw.toInt()
            if (a <= 12 && b > 12) { val t = a; a = b; b = t }   // m/d/y written; keep the day
            iso(y, b, a)?.let { return it }
        }
        MONTH_NAME.find(s)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = MONTHS.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            val y = m.groupValues[3].toInt()
            iso(y, mo, d)?.let { return it }
        }
        return null
    }

    /** The last amount on the row, in whole rupees (commas stripped, paise rounded), or null. */
    fun parseAmount(row: String): Long? {
        var last: String? = null
        for (m in AMOUNT.findAll(row)) { val g = m.groupValues[1]; if (g.any { it.isDigit() }) last = g }
        return last?.let { amountToLong(it) }
    }

    fun amountToLong(text: String): Long? {
        val cleaned = text.replace(",", "").replace(Regex("[^0-9.]"), "")
        if (cleaned.isEmpty() || !cleaned.any { it.isDigit() }) return null
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.roundToLong()
    }

    private fun parseStatus(rows: List<String>): String? {
        val statusRow = rows.firstOrNull { STATUS_ROW.containsMatchIn(it) }
        if (statusRow != null) return STATUS_WORD_ANY_CASE.find(statusRow)?.groupValues?.get(1)?.uppercase()
        return rows.firstNotNullOfOrNull { STATUS_WORD_UPPER.find(it)?.groupValues?.get(1) }
    }

    private fun iso(y: Int, m: Int, d: Int): String? {
        if (m !in 1..12 || d !in 1..31 || y !in 1900..2999) return null
        return "%04d-%02d-%02d".format(y, m, d)
    }
}
