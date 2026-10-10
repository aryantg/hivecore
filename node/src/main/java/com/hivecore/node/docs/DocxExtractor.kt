package com.hivecore.node.docs

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File

/**
 * `word/document.xml` → one [TextUnit] per paragraph. Every `<w:t>` inside a `<w:p>` is joined in document order,
 * `<w:tab/>` becomes a tab, `<w:br/>` / `<w:cr/>` a newline. Paragraphs inside tables (`w:tc`) are plain `w:p`
 * elements and are included; a paragraph nested inside another one (text boxes) is folded into its parent with a
 * newline. Deleted text from tracked changes (`w:delText`) and field codes (`w:instrText`) are skipped.
 *
 * The locator "¶ n" counts every top-level paragraph in the body, including empty ones, so it matches the n-th
 * paragraph a reader sees; empty paragraphs are not returned.
 */
object DocxExtractor {
    const val DOCUMENT_XML = "word/document.xml"

    fun extract(file: File): List<TextUnit> {
        val xml = DocZip.entry(file, DOCUMENT_XML) ?: throw IllegalArgumentException("${file.name}: no $DOCUMENT_XML (not a .docx)")
        return parse(xml)
    }

    fun parse(documentXml: ByteArray): List<TextUnit> {
        val h = Handler()
        SaxUtil.parse(documentXml, h)
        return h.units
    }

    private class Handler : DefaultHandler() {
        val units = ArrayList<TextUnit>()
        private val buf = StringBuilder()
        private var pDepth = 0
        private var paragraphNo = 0
        private var inText = false
        private var skipDepth = 0   // inside w:delText / w:instrText

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            when (qName) {
                "w:p" -> {
                    if (pDepth == 0) { buf.setLength(0); paragraphNo++ } else buf.append('\n')
                    pDepth++
                }
                "w:t" -> if (pDepth > 0 && skipDepth == 0) inText = true
                "w:tab" -> if (pDepth > 0 && skipDepth == 0) buf.append('\t')
                "w:br", "w:cr" -> if (pDepth > 0 && skipDepth == 0) buf.append('\n')
                "w:noBreakHyphen" -> if (pDepth > 0 && skipDepth == 0) buf.append('-')
                "w:delText", "w:instrText" -> skipDepth++
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "w:p" -> {
                    pDepth--
                    if (pDepth == 0) {
                        val text = Normalize.paragraph(buf.toString())
                        if (text.isNotEmpty()) units.add(TextUnit("¶ $paragraphNo", text))
                    }
                }
                "w:t" -> inText = false
                "w:delText", "w:instrText" -> skipDepth--
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) buf.append(ch, start, length)
        }
    }
}

/** Whitespace rules shared by the extractors: keep tabs and newlines as written, trim the ends, collapse blank runs. */
internal object Normalize {
    private val BLANK_RUN = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)*")
    private val SPACES = Regex("[ \\t\\u00A0]+")

    fun paragraph(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** For multi-line units (slides, pages): trims each line, drops blank runs to a single newline. */
    fun block(s: String): String = paragraph(s)
        .split('\n')
        .joinToString("\n") { it.replace(SPACES, " ").trim() }
        .replace(BLANK_RUN, "\n")
        .trim()
}
