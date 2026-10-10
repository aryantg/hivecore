package com.hivecore.node.docs

import com.hivecore.engine.text.Thresholds
import com.hivecore.engine.text.Tokenizer
import com.hivecore.core.Chunk
import com.hivecore.core.DocIndex
import com.hivecore.core.SLog
import com.hivecore.core.ScoredChunk
import kotlin.math.ln

/**
 * The relevance gate (plan 9), kept in one place so the ask pipeline, the tests and the UI agree. A chunk is
 * relevant when its BM25 score reaches [MIN_SCORE] and it matches at least `min(2, queryContentTerms)` distinct
 * query terms; a query with no content terms is never relevant.
 */

/**
 * Pure-Kotlin BM25 over the chunks returned by [source] (normally `DocStore::chunks`). [rebuild] reloads them; the
 * index is an immutable snapshot swapped atomically, so searches never see a half-built state. Scores are
 * deterministic: ties break by document name then ordinal.
 */
class Bm25Index(private val source: () -> List<Chunk>) : DocIndex {

    constructor(store: DocStore) : this({ store.chunks() })

    private class Entry(val chunk: Chunk, val tf: Map<String, Int>, val length: Int)
    private class Snapshot(val entries: List<Entry>, val df: Map<String, Int>, val avgLength: Double)

    @Volatile private var snapshot = Snapshot(emptyList(), emptyMap(), 0.0)

    val size: Int get() = snapshot.entries.size

    override fun rebuild() {
        val chunks = source()
        val entries = ArrayList<Entry>(chunks.size)
        val df = HashMap<String, Int>()
        for (c in chunks.sortedWith(compareBy({ it.docName }, { it.ordinal }))) {
            val toks = Tokenizer.tokens(c.text)
            val tf = HashMap<String, Int>()
            for (t in toks) tf[t] = (tf[t] ?: 0) + 1
            for (t in tf.keys) df[t] = (df[t] ?: 0) + 1
            entries.add(Entry(c, tf, toks.size))
        }
        val avg = if (entries.isEmpty()) 0.0 else entries.sumOf { it.length }.toDouble() / entries.size
        snapshot = Snapshot(entries, df, avg)
        SLog.d("Bm25Index", "rebuilt: ${entries.size} chunks, ${df.size} terms")
    }

    override fun search(query: String, k: Int): List<ScoredChunk> {
        val terms = queryTerms(query)
        if (terms.isEmpty() || k <= 0) return emptyList()
        val s = snapshot
        if (s.entries.isEmpty()) return emptyList()
        val n = s.entries.size.toDouble()
        val scored = ArrayList<ScoredChunk>()
        for (e in s.entries) {
            var score = 0.0
            var matched = 0
            for (t in terms) {
                val tf = e.tf[t] ?: continue
                matched++
                val df = s.df[t] ?: 0
                val idf = ln((n - df + 0.5) / (df + 0.5) + 1.0)
                val norm = 1.0 - Thresholds.B + Thresholds.B * (e.length / s.avgLength)
                score += idf * (tf * (Thresholds.K1 + 1.0)) / (tf + Thresholds.K1 * norm)
            }
            if (matched > 0) scored.add(ScoredChunk(e.chunk, score, matched))
        }
        return scored.sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.docName }.thenBy { it.chunk.ordinal }).take(k)
    }

    override fun relevant(query: String, k: Int): List<ScoredChunk> {
        val qn = queryTerms(query).size
        return search(query, k).filter { Thresholds.passes(it.score, it.matchedTerms, qn) }
    }

    override fun chunkAt(docName: String, locator: String): Chunk? =
        snapshot.entries.firstOrNull { it.chunk.docName == docName && it.chunk.locator == locator }?.chunk

    /** Distinct content terms of a query, in order. */
    fun queryTerms(query: String): List<String> = Tokenizer.tokens(query).distinct()
}
