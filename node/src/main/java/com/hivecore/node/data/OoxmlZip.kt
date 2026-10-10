package com.hivecore.node.data

import java.io.File
import java.util.zip.ZipFile

/**
 * Minimal access to an OOXML package (xlsx / docx / pptx are zip files). Shared with the `docs` package, which
 * reads `word/document.xml`, `ppt/presentation.xml` and the rels files through exactly these two functions.
 */
object OoxmlZip {
    /** The bytes of one package part, or null when the part does not exist (a leading "/" is tolerated). */
    fun entry(file: File, path: String): ByteArray? {
        val name = path.removePrefix("/")
        return try {
            ZipFile(file).use { zip ->
                val e = zip.getEntry(name) ?: zip.getEntry("/$name") ?: return null
                zip.getInputStream(e).use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Every part name in the package, in archive order; empty when the file is not a zip. */
    fun entries(file: File): List<String> = try {
        ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
    } catch (_: Exception) {
        emptyList()
    }
}
