package com.hivecore.node.data

import java.io.InputStream

/** A parsed sheet: the header row and the data rows, every row padded or cut to the header's width. */
data class TableData(val header: List<String>, val rows: List<List<String>>)

/**
 * RFC 4180 reader (plan 7): quoted fields, doubled quotes inside quotes, newlines inside quotes, CRLF or LF line
 * ends, an optional UTF-8 BOM. The delimiter is detected from the header line (comma, tab or semicolon; comma wins
 * ties) unless given. Fully blank lines are skipped; rows are padded / cut to the header width.
 */
object CsvReader {
    fun read(input: InputStream, delimiter: Char? = null): TableData =
        read(input.readBytes().toString(Charsets.UTF_8), delimiter)

    fun read(text: String, delimiter: Char? = null): TableData {
        val s = if (text.startsWith("﻿")) text.substring(1) else text
        val delim = delimiter ?: detectDelimiter(s)
        val records = parse(s, delim)
        if (records.isEmpty()) return TableData(emptyList(), emptyList())
        val header = records[0].map { it.trim() }
        val width = header.size
        val rows = ArrayList<List<String>>(records.size - 1)
        for (i in 1 until records.size) {
            val r = records[i]
            if (r.all { it.isBlank() }) continue
            rows.add(fit(r, width))
        }
        return TableData(header, rows)
    }

    /** Looks like CSV: at least two lines with the same, non-zero count of the detected delimiter. */
    fun looksTabular(text: String): Boolean {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(3).toList()
        if (lines.size < 2) return false
        val d = detectDelimiter(text)
        val c0 = lines[0].count { it == d }
        return c0 > 0 && lines.drop(1).all { it.count { ch -> ch == d } == c0 }
    }

    private fun detectDelimiter(s: String): Char {
        val first = s.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val counts = listOf(',', '\t', ';').map { d -> d to first.count { it == d } }
        return counts.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first ?: ','
    }

    private fun fit(r: List<String>, width: Int): List<String> = when {
        r.size == width -> r
        r.size > width -> r.subList(0, width)
        else -> r + List(width - r.size) { "" }
    }

    private fun parse(s: String, delim: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else {
                when (c) {
                    '"' -> { inQuotes = true; fieldStarted = true }
                    delim -> { row.add(field.toString()); field.setLength(0); fieldStarted = true }
                    '\r' -> {
                        if (i + 1 < n && s[i + 1] == '\n') i++
                        row.add(field.toString()); field.setLength(0); out.add(row); row = ArrayList(); fieldStarted = false
                    }
                    '\n' -> { row.add(field.toString()); field.setLength(0); out.add(row); row = ArrayList(); fieldStarted = false }
                    else -> { field.append(c); fieldStarted = true }
                }
            }
            i++
        }
        if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); out.add(row) }
        return out
    }
}
