package com.hivecore.node.docai

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hivecore.core.Ocr
import com.hivecore.core.OcrLine
import com.hivecore.core.SLog
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * [Ocr] over ML Kit's bundled Latin text recogniser (offline). Every `Text.textBlocks[].lines[]` with a bounding
 * box becomes one [OcrLine]; [RowGrouper] turns those into rows and cells. The recogniser is created on first use
 * (it loads a native model) and shared; call [close] when the node no longer needs it.
 */
class MlKitOcr(context: Context) : Ocr {
    private val app = context.applicationContext
    private val client: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun lines(imagePath: String): Result<List<OcrLine>> {
        val file = File(imagePath)
        if (!file.isFile) return Result.failure(java.io.FileNotFoundException(imagePath))
        return try {
            val t0 = System.currentTimeMillis()
            val image = InputImage.fromFilePath(app, Uri.fromFile(file))
            val text = client.process(image).await()
            val out = ArrayList<OcrLine>()
            for (block in text.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isBlank()) continue
                out.add(OcrLine(line.text, box.left, box.top, box.right, box.bottom))
            }
            SLog.d(TAG, "${file.name}: ${out.size} lines in ${System.currentTimeMillis() - t0} ms")
            Result.success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SLog.e(TAG, "recognise ${file.name} failed", e)
            Result.failure(e)
        }
    }

    fun close() { runCatching { client.close() } }

    private companion object { const val TAG = "MlKitOcr" }
}
