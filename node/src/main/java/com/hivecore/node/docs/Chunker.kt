package com.hivecore.node.docs

import com.hivecore.core.Chunk
import com.hivecore.core.Hashing

/**
 * Splits a document's [TextUnit]s into [Chunk]s of about [CHUNK_WORDS] whitespace-separated words with a
 * [OVERLAP_WORDS] overlap between consecutive windows of the same unit. A chunk never spans two units, so its
 * locator is always its unit's. Ordinals are 0-based and run across the whole document; the hash is
 * `sha256Hex("docId|ordinal|text")` and is what the ledger and the mesh cite.
 */
object Chunker {
    const val CHUNK_WORDS = 220
    const val OVERLAP_WORDS = 40
    private val WS = Regex("\\s+")

    fun chunk(docId: String, docName: String, units: List<TextUnit>): List<Chunk> {
        val out = ArrayList<Chunk>()
        for (unit in units) {
            val words = unit.text.trim().split(WS).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            if (words.size <= CHUNK_WORDS) {
                out.add(make(docId, docName, out.size, unit.locator, unit.text.trim()))
                continue
            }
            var start = 0
            while (true) {
                val end = minOf(start + CHUNK_WORDS, words.size)
                out.add(make(docId, docName, out.size, unit.locator, words.subList(start, end).joinToString(" ")))
                if (end == words.size) break
                start = end - OVERLAP_WORDS
            }
        }
        return out
    }

    fun hash(docId: String, ordinal: Int, text: String): String = Hashing.sha256Hex("$docId|$ordinal|$text")

    private fun make(docId: String, docName: String, ordinal: Int, locator: String, text: String) =
        Chunk(docId, docName, ordinal, locator, text, hash(docId, ordinal, text))
}
