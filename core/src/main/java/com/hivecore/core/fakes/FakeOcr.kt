package com.hivecore.core.fakes

import com.hivecore.core.Ocr
import com.hivecore.core.OcrLine
import java.io.File

/**
 * Reads the expected OCR text from a `.txt` next to the image (`invoice_3.png` → `invoice_3.txt`, or `<path>.txt`),
 * one printed line per text line, laid out top-to-bottom with synthetic boxes (line height 20 px, 10 px per char).
 * [canned] overrides by exact path.
 */
class FakeOcr : Ocr {
    val canned = HashMap<String, List<OcrLine>>()
    val calls = mutableListOf<String>()

    override suspend fun lines(imagePath: String): Result<List<OcrLine>> {
        calls.add(imagePath)
        canned[imagePath]?.let { return Result.success(it) }
        val txt = listOf(File(imagePath.substringBeforeLast('.') + ".txt"), File("$imagePath.txt")).firstOrNull { it.isFile }
            ?: return Result.failure(java.io.FileNotFoundException("no OCR text next to $imagePath"))
        return Result.success(fromText(txt.readText()))
    }

    companion object {
        fun fromText(text: String): List<OcrLine> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, line ->
            OcrLine(line, left = 40, top = 40 + i * 20, right = 40 + line.length * 10, bottom = 40 + i * 20 + 16)
        }
    }
}
