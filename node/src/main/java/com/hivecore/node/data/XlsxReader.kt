package com.hivecore.node.data

import com.hivecore.core.SLog
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import javax.xml.parsers.SAXParserFactory

/**
 * Reads the first worksheet (workbook order, resolved through the rels) of an .xlsx with SAX (plan 7 / 9):
 * shared strings (all `<t>` of an `<si>` concatenated), inline strings, cached formula values, booleans, and
 * serial dates converted to ISO `YYYY-MM-DD` when the cell style's number format is a date format (built-in ids
 * 14-22 and 45-47, or a custom format containing both `y` and `d`). Row 1 is the header; blank rows are dropped.
 */
object XlsxReader {
    private const val TAG = "XlsxReader"

    fun read(file: File): TableData {
        val sheetPath = firstSheetPath(file)
        val sheetXml = OoxmlZip.entry(file, sheetPath)
            ?: throw IllegalArgumentException("no worksheet at $sheetPath")
        val shared = OoxmlZip.entry(file, "xl/sharedStrings.xml")?.let { sharedStrings(it) } ?: emptyList()
        val dateStyles = OoxmlZip.entry(file, "xl/styles.xml")?.let { dateStyles(it) } ?: emptySet()
        val handler = SheetHandler(shared, dateStyles)
        parse(sheetXml, handler)
        val cells = handler.rows
        if (cells.isEmpty()) return TableData(emptyList(), emptyList())
        val headerCells = cells[0]
        val width = headerCells.size
        val header = headerCells.map { it.trim() }
        val rows = ArrayList<List<String>>()
        for (i in 1 until cells.size) {
            val r = cells[i]
            if (r.all { it.isEmpty() }) continue
            rows.add(when {
                r.size == width -> r
                r.size > width -> r.subList(0, width)
                else -> r + List(width - r.size) { "" }
            })
        }
        SLog.d(TAG, "${file.name}: sheet=$sheetPath ${rows.size} rows x $width cols")
        return TableData(header, rows)
    }

    /** Path of the first sheet in workbook order, via `xl/_rels/workbook.xml.rels`; falls back to sheet1. */
    fun firstSheetPath(file: File): String {
        val wb = OoxmlZip.entry(file, "xl/workbook.xml") ?: return "xl/worksheets/sheet1.xml"
        var rid: String? = null
        parse(wb, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (rid == null && local(qName) == "sheet" && attributes != null) {
                    for (i in 0 until attributes.length) if (local(attributes.getQName(i)) == "id") { rid = attributes.getValue(i); break }
                }
            }
        })
        val id = rid ?: return "xl/worksheets/sheet1.xml"
        val rels = OoxmlZip.entry(file, "xl/_rels/workbook.xml.rels") ?: return "xl/worksheets/sheet1.xml"
        var target: String? = null
        parse(rels, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (local(qName) == "Relationship" && attributes?.getValue("Id") == id) target = attributes.getValue("Target")
            }
        })
        val t = target ?: return "xl/worksheets/sheet1.xml"
        return resolve("xl/", t)
    }

    /** Resolves a rels Target against a base folder: absolute targets start with "/", relative ones may use "../". */
    fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val parts = ArrayList(base.trimEnd('/').split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(seg)
        }
        return parts.joinToString("/")
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        parse(xml, object : DefaultHandler() {
            private var sb: StringBuilder? = null
            private var inT = false
            private var phonetic = 0
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (local(qName)) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inT = phonetic == 0 && sb != null
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) sb?.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (local(qName)) {
                    "t" -> inT = false
                    "rPh" -> phonetic--
                    "si" -> { out.add(sb.toString()); sb = null }
                }
            }
        })
        return out
    }

    /** Indices into cellXfs whose numFmtId is a date format. */
    private fun dateStyles(xml: ByteArray): Set<Int> {
        val custom = HashMap<Int, String>()
        val xfFormats = ArrayList<Int>()
        parse(xml, object : DefaultHandler() {
            private var inCellXfs = false
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (local(qName)) {
                    "numFmt" -> {
                        val id = attributes?.getValue("numFmtId")?.toIntOrNull()
                        val code = attributes?.getValue("formatCode")
                        if (id != null && code != null) custom[id] = code
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) xfFormats.add(attributes?.getValue("numFmtId")?.toIntOrNull() ?: 0)
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { if (local(qName) == "cellXfs") inCellXfs = false }
        })
        val out = HashSet<Int>()
        xfFormats.forEachIndexed { i, id -> if (isDateFormat(id, custom[id])) out.add(i) }
        return out
    }

    fun isDateFormat(numFmtId: Int, customCode: String?): Boolean {
        if (numFmtId in 14..22 || numFmtId in 45..47) return true
        val code = customCode ?: return false
        // Strip quoted literals, [colour]/[locale] blocks and escaped characters before looking for y and d.
        val cleaned = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").replace(Regex("\\\\."), "").lowercase()
        return cleaned.contains('y') && cleaned.contains('d')
    }

    /** Excel serial day (1900 system, epoch 1899-12-30) to ISO date; null when the serial is not a plausible date. */
    fun serialToIso(serial: Double): String? {
        if (serial.isNaN() || serial < 1 || serial > 2_958_465.0) return null
        return LocalDate.of(1899, 12, 30).plusDays(Math.floor(serial).toLong()).toString()
    }

    /** Numbers as Excel stores them ("1.2E7", "1200.0") to plain text without a spurious fraction or exponent. */
    fun plainNumber(v: String): String {
        val t = v.trim()
        if (t.isEmpty()) return t
        if (!t.contains('E') && !t.contains('e') && !t.endsWith(".0")) return t
        return try {
            BigDecimal(t).stripTrailingZeros().toPlainString()
        } catch (_: NumberFormatException) {
            t
        }
    }

    /** "A" -> 0, "Z" -> 25, "AA" -> 26; null when the reference has no letters. */
    fun columnIndex(ref: String): Int? {
        var n = 0
        var any = false
        for (c in ref) {
            if (c in 'A'..'Z') { n = n * 26 + (c - 'A' + 1); any = true } else if (c in 'a'..'z') { n = n * 26 + (c - 'a' + 1); any = true } else break
        }
        return if (any) n - 1 else null
    }

    private class SheetHandler(val shared: List<String>, val dateStyles: Set<Int>) : DefaultHandler() {
        val rows = ArrayList<List<String>>()
        private var row: ArrayList<String>? = null
        private var cellType = ""
        private var cellStyle = -1
        private var cellCol = -1
        private var nextCol = 0
        private val v = StringBuilder()
        private val inline = StringBuilder()
        private var inV = false
        private var inIs = false
        private var inT = false
        private var inCell = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (local(qName)) {
                "row" -> { row = ArrayList(); nextCol = 0 }
                "c" -> {
                    inCell = true
                    cellType = attributes?.getValue("t") ?: ""
                    cellStyle = attributes?.getValue("s")?.toIntOrNull() ?: -1
                    cellCol = attributes?.getValue("r")?.let { columnIndex(it) } ?: nextCol
                    v.setLength(0); inline.setLength(0)
                }
                "v" -> if (inCell) { inV = true; v.setLength(0) }
                "is" -> if (inCell) inIs = true
                "t" -> if (inIs) inT = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inV) v.append(ch, start, length) else if (inT) inline.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(qName)) {
                "v" -> inV = false
                "t" -> inT = false
                "is" -> inIs = false
                "c" -> {
                    inCell = false
                    val r = row ?: return
                    while (r.size < cellCol) r.add("")
                    val value = cellValue()
                    if (cellCol < r.size) r[cellCol] = value else r.add(value)
                    nextCol = cellCol + 1
                }
                "row" -> { row?.let { rows.add(it) }; row = null }
            }
        }

        private fun cellValue(): String {
            val raw = v.toString()
            return when (cellType) {
                "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                "inlineStr" -> inline.toString()
                "b" -> if (raw.trim() == "1") "true" else "false"
                "str" -> raw
                "e" -> ""
                "d" -> raw.trim().let { if (it.length >= 10) it.substring(0, 10) else it }
                else -> {
                    val t = raw.trim()
                    if (t.isEmpty()) return ""
                    if (cellStyle in dateStyles) t.toDoubleOrNull()?.let { serialToIso(it) }?.let { return it }
                    plainNumber(t)
                }
            }
        }
    }

    private fun local(qName: String?): String = qName?.substringAfter(':') ?: ""

    private fun parse(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        for (f in listOf("http://apache.org/xml/features/disallow-doctype-decl", "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities")) {
            try { factory.setFeature(f, f == "http://apache.org/xml/features/disallow-doctype-decl") } catch (_: Exception) { }
        }
        val parser = factory.newSAXParser()
        parser.parse(InputSource(ByteArrayInputStream(xml)), handler)
    }
}
