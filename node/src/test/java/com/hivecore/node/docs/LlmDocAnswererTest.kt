package com.hivecore.node.docs

import com.hivecore.core.Chunk
import com.hivecore.core.DocAnswer
import com.hivecore.core.ScoredChunk
import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmDocAnswererTest {
    private val index = Bm25Index { Fixtures.allChunks() }.also { it.rebuild() }
    private val q6 = "How many leave days does a new joiner get?"
    private val q7 = "What were the Q3 priorities?"

    @Test fun answersFromTheCitedChunkWithKeywordFake() = runTest {
        // Same replies as FakeLlmEngine.tour() for the two doc questions; the tour's SQL keys are left out because
        // the fake matches the whole user message (excerpts included) and page 3 of the policy mentions "salary".
        val llm = FakeLlmEngine().apply {
            replies["leave"] = "A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly. [1]"
            replies["priorities"] = "The Q3 priorities are the three bullets on slide 3. [1]"
        }
        val answerer = LlmDocAnswerer(llm)
        val chunks = index.relevant(q6)
        val a = answerer.answer(q6, chunks).getOrThrow()
        assertEquals(
            DocAnswer("A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly.", "leave_policy.pdf", "p.2", chunks.size),
            a
        )
        val (messages, params) = llm.calls.single()
        assertEquals(96, params.maxTokens)
        assertEquals(RagPrompt.SYSTEM, messages[0].content)
        assertTrue(messages[1].content.startsWith("[1] (leave_policy.pdf p.2) "))
        assertTrue(messages[1].content.endsWith("Question: $q6"))

        val b = answerer.answer(q7, index.relevant(q7)).getOrThrow()!!
        assertEquals("q3_review.pptx", b.doc)
        assertEquals("slide 3", b.locator)
        assertEquals(1, b.chunksRead)
        assertEquals("The Q3 priorities are the three bullets on slide 3.", b.text)
    }

    @Test fun citationPicksTheSecondExcerpt() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "Unused paid leave of up to 10 days carries over. [2]" }
        val chunks = listOf(
            sc("leave_policy.pdf", "p.1", "The leave year runs from 1 January to 31 December.", 9.0),
            sc("leave_policy.pdf", "p.2", "Unused paid leave of up to 10 days carries over into the next year.", 8.0),
            sc("leave_policy.pdf", "p.3", "Casual leave is 6 days per year.", 7.0)
        )
        val a = LlmDocAnswerer(llm).answer("How many days carry over?", chunks).getOrThrow()!!
        assertEquals("p.2", a.locator)
        assertEquals("Unused paid leave of up to 10 days carries over.", a.text)
        assertEquals(3, a.chunksRead)
    }

    @Test fun noCitationDefaultsToTopScoringChunkEvenWhenListIsUnordered() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "Six days." }
        val chunks = listOf(
            sc("leave_policy.pdf", "p.1", "The leave year runs from 1 January to 31 December.", 1.0),
            sc("leave_policy.pdf", "p.3", "Casual leave is 6 days per year.", 7.0)
        )
        val a = LlmDocAnswerer(llm).answer("Casual leave?", chunks).getOrThrow()!!
        assertEquals("p.3", a.locator)
        assertEquals("[1] (leave_policy.pdf p.3) Casual leave is 6 days per year.", llm.calls.single().first[1].content.lineSequence().first())
    }

    @Test fun onlyTopThreeChunksArePrompted() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "x [4]" }
        val chunks = (1..5).map { sc("d.txt", "¶ $it", "text $it", 10.0 - it) }
        val a = LlmDocAnswerer(llm).answer("q", chunks).getOrThrow()!!
        assertEquals(3, a.chunksRead)
        assertEquals("¶ 1", a.locator)   // [4] is out of range → top-1
        val user = llm.calls.single().first[1].content
        assertTrue(user.contains("[3] (d.txt ¶ 3)"))
        assertTrue(!user.contains("[4]"))
    }

    @Test fun notFoundIsNull() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "NOT_FOUND" }
        assertNull(LlmDocAnswerer(llm).answer(q6, index.relevant(q6)).getOrThrow())
        llm.fallback = "NOT_FOUND [1]"
        assertNull(LlmDocAnswerer(llm).answer(q6, index.relevant(q6)).getOrThrow())
        llm.fallback = ""
        assertNull(LlmDocAnswerer(llm).answer(q6, index.relevant(q6)).getOrThrow())
    }

    @Test fun noChunksIsNullWithoutCallingTheLlm() = runTest {
        val llm = FakeLlmEngine()
        assertNull(LlmDocAnswerer(llm).answer(q6, emptyList()).getOrThrow())
        assertTrue(llm.calls.isEmpty())
    }

    @Test fun engineFailurePropagates() = runTest {
        val llm = FakeLlmEngine().apply { failNext = IllegalStateException("npu down") }
        val r = LlmDocAnswerer(llm).answer(q6, index.relevant(q6))
        assertTrue(r.isFailure)
        assertEquals("npu down", r.exceptionOrNull()!!.message)
    }

    @Test fun bestSentenceFallbackQuotesTheChunk() {
        val chunk = index.chunkAt("leave_policy.pdf", "p.2")!!
        assertEquals("A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly.", LlmDocAnswerer.bestSentence(q6, chunk.text))
        val slide = index.chunkAt("q3_review.pptx", "slide 3")!!
        assertEquals("Q3 priorities", LlmDocAnswerer.bestSentence(q7, slide.text))
    }

    private fun sc(doc: String, locator: String, text: String, score: Double) =
        ScoredChunk(Chunk("id_$doc", doc, 0, locator, text, "h"), score, 2)
}
