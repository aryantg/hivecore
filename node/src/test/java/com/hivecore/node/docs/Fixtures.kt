package com.hivecore.node.docs

import com.hivecore.core.Chunk
import com.hivecore.core.Hashing
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test fixtures from `src/test/resources/fixtures` and the three sample documents as [TextUnit]s. */
object Fixtures {
    fun file(name: String): File {
        val url = Fixtures::class.java.classLoader!!.getResource("fixtures/$name") ?: error("fixture $name missing")
        return File(url.toURI())
    }

    fun docx(): List<TextUnit> = DocxExtractor.extract(file("onboarding_checklist.docx"))
    fun pptx(): List<TextUnit> = PptxExtractor.extract(file("q3_review.pptx"))

    /**
     * The leave policy's six pages as PdfBox would yield them on a device (header line, title, paragraphs),
     * written from the generator's page content because PdfBox-Android cannot run on the JVM.
     */
    fun leavePolicyPages(): List<TextUnit> {
        val text = file("leave_policy_pages.txt").readText(Charsets.UTF_8).replace("\r\n", "\n")
        val units = ArrayList<TextUnit>()
        var locator: String? = null
        val buf = StringBuilder()
        fun flush() { locator?.let { units.add(TextUnit(it, buf.toString().trim())) }; buf.setLength(0) }
        for (line in text.split('\n')) {
            val m = Regex("^=== (p\\.\\d+) ===$").find(line.trim())
            if (m != null) { flush(); locator = m.groupValues[1] } else buf.append(line).append('\n')
        }
        flush()
        return units
    }

    fun sha(file: File): String = Hashing.sha256Hex(file.readBytes())

    /** All three sample documents chunked, as the HR + Sales nodes would hold them together. */
    fun allChunks(): List<Chunk> =
        Chunker.chunk("doc_leave", "leave_policy.pdf", leavePolicyPages()) +
            Chunker.chunk("doc_onb", "onboarding_checklist.docx", docx()) +
            Chunker.chunk("doc_q3", "q3_review.pptx", pptx())

    /** Writes a zip with the given entries to a temp file. */
    fun zip(entries: Map<String, String>): File {
        val f = File.createTempFile("docs_test_", ".zip")
        f.deleteOnExit()
        ZipOutputStream(FileOutputStream(f)).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return f
    }
}
