package com.hivecore.node.docs

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Minimal OOXML container access over [ZipFile]. Same two functions as `data.OoxmlZip` (written concurrently by the
 * data package); the integrator dedupes the two. Entries larger than [MAX_ENTRY_BYTES] are refused (zip bombs).
 */
object DocZip {
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** The uncompressed bytes of [path] (exact name, forward slashes) or null when the entry does not exist. */
    fun entry(file: File, path: String): ByteArray? = ZipFile(file).use { zip ->
        val e = zip.getEntry(path) ?: return null
        if (e.size > MAX_ENTRY_BYTES) throw IllegalStateException("zip entry $path is too large (${e.size} bytes)")
        zip.getInputStream(e).use { s ->
            val out = ByteArrayOutputStream(if (e.size > 0) e.size.toInt() else 8192)
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_ENTRY_BYTES) throw IllegalStateException("zip entry $path is too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    /** Entry names in archive order. */
    fun entries(file: File): List<String> = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
}
