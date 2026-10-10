package com.hivecore.node.docs

import com.hivecore.engine.text.Tokenizer
import com.hivecore.core.DocAnswer
import com.hivecore.core.DocAnswerer
import com.hivecore.core.GenParams
import com.hivecore.core.LlmEngine
import com.hivecore.core.SLog
import com.hivecore.core.ScoredChunk

/**
 * RAG over the top [maxChunks] chunks (plan 9, 11.3): [RagPrompt] → `llm.generate(maxTokens = 96)` → the trailing
 * `[n]` names the cited excerpt (default the top one) → [DocAnswer]. `NOT_FOUND` (or an empty reply) is a null
 * answer, which the node reports as not relevant; an engine failure is returned as a failure so the caller can
 * fall back (plan 8: the template path then quotes the chunk's best sentence).
 */
class LlmDocAnswerer(
    private val llm: LlmEngine,
    private val maxChunks: Int = 3,
    private val maxTokens: Int = RagPrompt.MAX_TOKENS
) : DocAnswerer {

    override suspend fun answer(question: String, chunks: List<ScoredChunk>): Result<DocAnswer?> {
        val top = chunks.sortedByDescending { it.score }.take(maxChunks)
        if (top.isEmpty()) return Result.success(null)
        val messages = RagPrompt.build(question, top)
        val result = llm.generate(messages, GenParams(maxTokens = maxTokens)).getOrElse { t ->
            SLog.w(TAG, "generate failed: ${t.message}")
            return Result.failure(t)
        }
        val parsed = RagPrompt.parse(result.text, top.size)
        if (parsed.notFound) {
            SLog.d(TAG, "NOT_FOUND for '${question.take(60)}' over ${top.size} chunks")
            return Result.success(null)
        }
        val cited = top[parsed.citation - 1].chunk
        SLog.d(TAG, "answer from ${cited.docName} ${cited.locator} (${result.stats.genTokens} tokens)")
        return Result.success(DocAnswer(parsed.text, cited.docName, cited.locator, top.size))
    }

    companion object {
        private const val TAG = "LlmDocAnswerer"

        /** The sentence of [chunkText] sharing the most content terms with [question]; the fallback when the LLM fails. */
        fun bestSentence(question: String, chunkText: String): String {
            val q = Tokenizer.tokens(question).toSet()
            val sentences = chunkText.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
            return sentences.maxByOrNull { s -> Tokenizer.tokens(s).count { it in q } } ?: chunkText.trim()
        }
    }
}
