package com.hivecore.node.docai

import com.hivecore.core.OcrLine

/**
 * A key-value pair found on the page. [key] is the normalised label (`Invoice No.` → `invoice_no`), [label] the label
 * as printed, [row] the 0-based row of the value, [how] = "inline" (`Label: value` in one cell, or a `Label:` cell
 * followed by a value cell on the same row) or "below" (a row that is only a label, value on the next row).
 */
data class KeyValue(val key: String, val label: String, val value: String, val row: Int, val how: String)

/** LAYOUT output: [RowGrouper] rows, their text form, and the key-value pairs in reading order. */
data class LayoutResult(val rows: List<List<String>>, val text: List<String>, val pairs: List<KeyValue>) {
    /** The first value for any of [keys] (normalised keys), or null. */
    fun value(vararg keys: String): String? = pairs.firstOrNull { it.key in keys }?.value
}

/**
 * Swarm plan §4A.3 LAYOUT: rows from [RowGrouper], then key-value pairing rules. Deterministic, no model.
 *
 * Rules, applied per row in order:
 *  1. each cell of the form `Label: value` (label = text before the first colon) is a pair;
 *  2. a cell that is only `Label:` takes the next cell of the same row as its value, if there is one;
 *  3. otherwise, when the `Label:` cell (or a row that is exactly a known label such as `Invoice Date`) is the whole
 *     row, the next row's text is its value ("below"), and that row is consumed.
 * A label has 1-[MAX_LABEL_WORDS] words, at most [MAX_LABEL_CHARS] characters, a letter, and is not mostly digits
 * (so `10` is never a label); a colon between two digits (`10:30`) is never a label colon.
 */
object Layout {
    const val MAX_LABEL_WORDS = 5
    const val MAX_LABEL_CHARS = 40

    /** Labels that may stand alone on a row without a colon. */
    val KNOWN_LABELS = setOf(
        "invoice no", "invoice number", "invoice date", "date", "due date", "bill to", "ship to", "total", "grand total",
        "amount due", "vendor", "supplier", "customer", "po number", "po no", "subject", "name", "skills", "status",
        "gstin", "effective date", "term", "from", "to",
    )

    fun of(lines: List<OcrLine>): LayoutResult = ofRows(RowGrouper.rows(lines))

    fun ofRows(rows: List<List<String>>): LayoutResult {
        val clean = rows.map { r -> r.map { it.trim() }.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        val pairs = ArrayList<KeyValue>()
        var i = 0
        while (i < clean.size) {
            val cells = clean[i]
            var consumedNext = false
            var c = 0
            while (c < cells.size) {
                val cell = cells[c]
                val colon = labelColon(cell)
                if (colon > 0) {
                    val label = cell.substring(0, colon).trim()
                    val rest = cell.substring(colon + 1).trim()
                    if (isLabel(label)) {
                        when {
                            rest.isNotEmpty() -> pairs += KeyValue(key(label), label, rest, i, "inline")
                            c + 1 < cells.size -> { pairs += KeyValue(key(label), label, cells[c + 1], i, "inline"); c++ }
                            cells.size == 1 && i + 1 < clean.size -> {
                                pairs += KeyValue(key(label), label, clean[i + 1].joinToString("  "), i + 1, "below"); consumedNext = true
                            }
                        }
                    }
                } else if (cells.size == 1 && key(cell).replace('_', ' ') in KNOWN_LABELS && i + 1 < clean.size && !isLabelRow(clean[i + 1])) {
                    pairs += KeyValue(key(cell), cell, clean[i + 1].joinToString("  "), i + 1, "below"); consumedNext = true
                }
                c++
            }
            i += if (consumedNext) 2 else 1
        }
        return LayoutResult(clean, clean.map { it.joinToString("  ") }, pairs)
    }

    /** The first colon that is not between two digits (`10:30` is a time, not a label), or -1. */
    private fun labelColon(cell: String): Int {
        var i = cell.indexOf(':')
        while (i >= 0) {
            val timeLike = i > 0 && cell[i - 1].isDigit() && i + 1 < cell.length && cell[i + 1].isDigit()
            if (!timeLike) return i
            i = cell.indexOf(':', i + 1)
        }
        return -1
    }

    private val DIGIT = Regex("\\d")
    private val LETTER = Regex("\\p{L}")

    fun isLabel(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > MAX_LABEL_CHARS || !LETTER.containsMatchIn(t)) return false
        if (t.split(Regex("\\s+")).size > MAX_LABEL_WORDS) return false
        val digits = DIGIT.findAll(t).count()
        val letters = LETTER.findAll(t).count()
        return digits <= letters
    }

    private fun isLabelRow(cells: List<String>): Boolean =
        cells.size == 1 && (cells[0].endsWith(":") || key(cells[0]).replace('_', ' ') in KNOWN_LABELS)

    /** `Invoice No.` → `invoice_no`; `A/c No` → `a_c_no`. */
    fun key(label: String): String = label.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
}
