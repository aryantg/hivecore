package com.hivecore.node.docs

import android.graphics.Bitmap
import com.hivecore.core.Ocr
import com.hivecore.core.SLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

/**
 * PDF → one [TextUnit] per page ("p.n") with PdfBox-Android. A page whose text layer yields fewer than
 * [MIN_TEXT_CHARS] characters is treated as scanned: it is rendered at 2x into a PNG under [cacheDir] and sent
 * through [ocr]; the OCR lines (top-to-bottom, left-to-right) replace the page text when they are longer.
 *
 * [init] runs once before the first extraction; on a device pass `{ PDFBoxResourceLoader.init(appContext) }`
 * (PdfBox needs the asset-backed font cache). JVM tests pass a no-op, but this class is device-tested only:
 * PdfBox-Android needs `android.graphics`, so everything downstream is unit-tested on [TextUnit] fixtures.
 */
class PdfExtractor(
    private val ocr: Ocr,
    private val cacheDir: File,
    private val init: () -> Unit
) {
    companion object {
        const val MIN_TEXT_CHARS = 20
        const val RENDER_SCALE = 2f
        private const val TAG = "PdfExtractor"
    }

    @Volatile private var initialized = false

    /** Pages OCR'd by the last [extract] call (for the ingest preview's "2 pages scanned" line). */
    @Volatile var lastOcrPages: Int = 0
        private set

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) { init(); initialized = true }
        }
    }

    suspend fun extract(file: File): List<TextUnit> {
        ensureInit()
        val units = ArrayList<TextUnit>()
        var ocrPages = 0
        PDDocument.load(file).use { doc ->
            val pages = doc.numberOfPages
            var renderer: PDFRenderer? = null
            for (i in 1..pages) {
                var text = try {
                    val stripper = PDFTextStripper().apply { startPage = i; endPage = i; sortByPosition = true }
                    Normalize.block(stripper.getText(doc))
                } catch (e: Exception) {
                    SLog.w(TAG, "${file.name} p.$i: text layer failed: ${e.message}")
                    ""
                }
                if (text.length < MIN_TEXT_CHARS) {
                    val r = renderer ?: PDFRenderer(doc).also { renderer = it }
                    val ocrText = ocrPage(file, r, i)
                    if (ocrText.length > text.length) text = ocrText
                    ocrPages++
                }
                if (text.isNotEmpty()) units.add(TextUnit("p.$i", text))
            }
        }
        lastOcrPages = ocrPages
        SLog.d(TAG, "${file.name}: ${units.size} pages with text, $ocrPages via OCR")
        return units
    }

    private suspend fun ocrPage(file: File, renderer: PDFRenderer, page: Int): String {
        cacheDir.mkdirs()
        val png = File(cacheDir, "pdf_ocr_${file.nameWithoutExtension}_p$page.png")
        try {
            val bitmap = renderer.renderImage(page - 1, RENDER_SCALE)
            try {
                FileOutputStream(png).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } finally {
                bitmap.recycle()
            }
            val lines = ocr.lines(png.absolutePath).getOrElse { t ->
                SLog.w(TAG, "${file.name} p.$page: OCR failed: ${t.message}")
                return ""
            }
            return Normalize.block(lines.sortedWith(compareBy({ it.top }, { it.left })).joinToString("\n") { it.text })
        } catch (e: Exception) {
            SLog.w(TAG, "${file.name} p.$page: render failed: ${e.message}")
            return ""
        } finally {
            png.delete()
        }
    }
}
