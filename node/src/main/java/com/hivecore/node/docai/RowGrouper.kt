package com.hivecore.node.docai

import com.hivecore.core.OcrLine

/**
 * Turns OCR lines (text + box) into rows of cells: sort by centre-y; a new row starts when the gap to the current
 * row's centre is more than 0.6 x the median line height; within a row the lines are sorted by x and split into
 * cells wherever the horizontal gap between two boxes is more than 1.5 x the median character width. Text inside
 * one box is also split at runs of two or more spaces (the same gap, seen by an engine that merged the boxes).
 * Blank lines are dropped.
 */
object RowGrouper {
    const val ROW_GAP = 0.6
    const val CELL_GAP = 1.5
    private val TEXT_GAP = Regex("\\s{2,}")

    fun rows(lines: List<OcrLine>): List<List<String>> {
        val valid = lines.filter { it.text.isNotBlank() }
        if (valid.isEmpty()) return emptyList()
        val lineHeight = median(valid.map { (it.bottom - it.top).coerceAtLeast(1).toDouble() })
        val charWidth = median(valid.map { (it.right - it.left).coerceAtLeast(1).toDouble() / it.text.trim().length.coerceAtLeast(1) })
        val rowGap = ROW_GAP * lineHeight
        val cellGap = CELL_GAP * charWidth

        val groups = ArrayList<MutableList<OcrLine>>()
        var sumY = 0.0
        for (line in valid.sortedBy { centreY(it) }) {
            val cy = centreY(line)
            val current = groups.lastOrNull()
            if (current == null || cy - sumY / current.size > rowGap) {
                groups.add(mutableListOf(line)); sumY = cy
            } else {
                current.add(line); sumY += cy
            }
        }

        val out = ArrayList<List<String>>(groups.size)
        for (g in groups) {
            val cells = ArrayList<String>()
            var text: StringBuilder? = null
            var right = Int.MIN_VALUE
            for (line in g.sortedBy { it.left }) {
                val t = line.text.trim()
                if (text == null || line.left - right > cellGap) {
                    text?.let { cells.addAll(splitText(it.toString())) }
                    text = StringBuilder(t)
                } else {
                    text.append(' ').append(t)
                }
                right = maxOf(right, line.right)
            }
            text?.let { cells.addAll(splitText(it.toString())) }
            if (cells.isNotEmpty()) out.add(cells)
        }
        return out
    }

    /** [rows] with the cells of each row joined by two spaces: the text form the regex and the LLM read. */
    fun rowsText(lines: List<OcrLine>): List<String> = rows(lines).map { it.joinToString("  ") }

    private fun splitText(s: String): List<String> = s.split(TEXT_GAP).map { it.trim() }.filter { it.isNotEmpty() }

    private fun centreY(l: OcrLine): Double = (l.top + l.bottom) / 2.0

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 1.0
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
