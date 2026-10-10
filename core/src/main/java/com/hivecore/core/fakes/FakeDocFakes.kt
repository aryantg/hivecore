package com.hivecore.core.fakes

import com.hivecore.core.Chunk
import com.hivecore.core.DocAnswer
import com.hivecore.core.DocAnswerer
import com.hivecore.core.DocIndex
import com.hivecore.core.Hashing
import com.hivecore.core.ScoredChunk

private val WORD = Regex("[a-z0-9]+")
private val STOP = setOf("the", "and", "for", "are", "with", "what", "how", "many", "does", "this", "that", "our", "per", "from", "was", "were", "you", "have", "has", "who", "which")

private fun terms(s: String): List<String> = WORD.findAll(s.lowercase()).map { it.value }.filter { it.length >= 3 && it !in STOP }.distinct().toList()

/** Term-overlap search over an in-memory chunk list. `relevant` needs >= min(2, query terms) matches. */
class FakeDocIndex(initial: List<Chunk> = emptyList()) : DocIndex {
    val chunks: MutableList<Chunk> = initial.toMutableList()
    var rebuilds = 0

    override fun search(query: String, k: Int): List<ScoredChunk> {
        val q = terms(query)
        if (q.isEmpty()) return emptyList()
        return chunks.map { c ->
            val t = terms(c.text).toSet()
            val matched = q.count { it in t }
            ScoredChunk(c, matched * 2.0, matched)
        }.filter { it.matchedTerms > 0 }.sortedByDescending { it.score }.take(k)
    }

    override fun relevant(query: String, k: Int): List<ScoredChunk> {
        val need = minOf(2, terms(query).size)
        return search(query, k).filter { it.matchedTerms >= need && it.score >= 2.0 }
    }

    override fun chunkAt(docName: String, locator: String): Chunk? = chunks.firstOrNull { it.docName == docName && it.locator == locator }
    override fun rebuild() { rebuilds++ }

    companion object {
        fun chunk(docId: String, docName: String, ordinal: Int, locator: String, text: String) =
            Chunk(docId, docName, ordinal, locator, text, Hashing.sha256Hex("$docId|$ordinal|$text"))

        /** The two chunks the tour's doc questions (Q6, Q7) must hit. */
        fun tour(): FakeDocIndex = FakeDocIndex(listOf(
            chunk("doc_leave", "leave_policy.pdf", 1, "p.2", "Leave entitlement. A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly. Unused days lapse at year end."),
            chunk("doc_q3", "q3_review.pptx", 2, "slide 3", "Q3 priorities. Win two enterprise accounts in the West. Cut invoice-to-cash from 45 to 30 days. Launch the partner portal.")
        ))
    }
}

/** Returns the sentence of the top chunk that shares the most terms with the question; null when no chunks. */
class FakeDocAnswerer : DocAnswerer {
    var canned: DocAnswer? = null
    var failNext: Throwable? = null
    val calls = mutableListOf<String>()

    override suspend fun answer(question: String, chunks: List<ScoredChunk>): Result<DocAnswer?> {
        calls.add(question)
        failNext?.let { failNext = null; return Result.failure(it) }
        canned?.let { return Result.success(it) }
        val top = chunks.maxByOrNull { it.score } ?: return Result.success(null)
        val q = terms(question)
        val sentence = top.chunk.text.split(Regex("(?<=[.!?])\\s+")).maxByOrNull { s -> terms(s).count { it in q } } ?: top.chunk.text
        return Result.success(DocAnswer(sentence.trim(), top.chunk.docName, top.chunk.locator, chunks.size))
    }
}
