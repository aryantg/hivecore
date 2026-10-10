package com.hivecore.node.docs

/**
 * One citable unit of a document: a paragraph ("¶ 12"), a slide ("slide 3") or a page ("p.2"). Extractors produce
 * these; [Chunker] never lets a chunk span two of them, so every chunk inherits exactly one locator.
 */
data class TextUnit(val locator: String, val text: String)

/** Document kinds and their unit labels, as stored in `_docs.unitLabel` and shown in the UI ("6 pages", "14 slides"). */
object DocKinds {
    const val PDF = "pdf"
    const val DOCX = "docx"
    const val PPTX = "pptx"
    const val TXT = "txt"
    const val MD = "md"

    fun unitLabel(kind: String): String = when (kind.lowercase()) {
        PDF -> "page"
        PPTX -> "slide"
        else -> "paragraph"
    }

    /** Kind from a file name's extension, or null when the extension is not a document this package extracts. */
    fun fromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        PDF -> PDF
        DOCX -> DOCX
        PPTX -> PPTX
        TXT -> TXT
        MD -> MD
        else -> null
    }
}
