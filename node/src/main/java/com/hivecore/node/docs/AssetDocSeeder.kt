package com.hivecore.node.docs

import android.content.Context
import com.hivecore.core.Hashing
import com.hivecore.core.SLog
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Seeds the role's documents from `assets/seed/<name>` (plan 16.1): the file is copied to `cacheDir/seed/`, run
 * through the extractor for its kind and stored with `seed = true`. Dedupe is on the file hash, so re-seeding is a
 * no-op. Wired as `DataStore.docSeeder` by `Graph`.
 */
object AssetDocSeeder {
    private const val TAG = "DocSeeder"

    /** Returns the number of documents added (0 when every one already existed). */
    fun seed(context: Context, docStore: DocStore, pdf: PdfExtractor, names: List<String>): Int {
        var added = 0
        for (name in names) {
            try {
                val bytes = context.assets.open("seed/$name").use { it.readBytes() }
                val hash = Hashing.sha256Hex(bytes)
                if (docStore.findByHash(hash) != null) { SLog.d(TAG, "$name already seeded"); continue }
                val kind = DocKinds.fromName(name)
                if (kind == null) { SLog.w(TAG, "seed: unsupported document $name"); continue }
                val dir = File(context.cacheDir, "seed").also { it.mkdirs() }
                val file = File(dir, name).also { it.writeBytes(bytes) }
                val units = when (kind) {
                    DocKinds.DOCX -> DocxExtractor.extract(file)
                    DocKinds.PPTX -> PptxExtractor.extract(file)
                    DocKinds.PDF -> runBlocking { pdf.extract(file) }
                    else -> TextExtractor.extract(file)
                }
                val r = docStore.addDocument(name, kind, units, seed = true, hash = hash)
                if (!r.duplicate) added++
                SLog.d(TAG, "seeded $name: ${units.size} units, ${r.doc.chunkCount} chunks")
            } catch (e: Exception) {
                SLog.e(TAG, "seed $name failed", e)
            }
        }
        return added
    }
}
