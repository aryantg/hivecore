package com.hivecore.node.docs

import com.hivecore.engine.text.Tokenizer
import com.hivecore.engine.text.Thresholds
import com.hivecore.core.Chunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25IndexTest {
    private val index = Bm25Index { Fixtures.allChunks() }.also { it.rebuild() }

    @Test fun tokenizerStoplistHas120WordsAndStemsLightly() {
        assertEquals(120, Tokenizer.STOP.size)
        assertEquals(listOf("leave", "days", "new", "joiner"), Tokenizer.tokens("How many leave days does a new joiner get?"))
        assertEquals(listOf("q3", "priority"), Tokenizer.tokens("What were the Q3 priorities?"))
        assertEquals("priority", Tokenizer.stem("priorities"))
        assertEquals("account", Tokenizer.stem("accounts"))
        assertEquals("join", Tokenizer.stem("joining"))
        assertEquals("credit", Tokenizer.stem("credited"))
        assertEquals("days", Tokenizer.stem("days"))
        assertEquals("class", Tokenizer.stem("class"))
        assertEquals("sing", Tokenizer.stem("sing"))
        assertTrue(Tokenizer.tokens("the of and").isEmpty())
    }

    @Test fun q6HitsLeavePolicyPage2() {
        val hits = index.search("How many leave days does a new joiner get?")
        assertEquals(3, hits.size)
        assertEquals("leave_policy.pdf", hits[0].chunk.docName)
        assertEquals("p.2", hits[0].chunk.locator)
        assertEquals(4, hits[0].matchedTerms)
        assertTrue(hits[0].score >= Thresholds.MIN_SCORE)
        val rel = index.relevant("How many leave days does a new joiner get?")
        assertFalse(rel.isEmpty())
        assertEquals("p.2", rel[0].chunk.locator)
        assertTrue(rel.all { it.chunk.docName == "leave_policy.pdf" })
    }

    @Test fun q7HitsSlide3() {
        val hits = index.search("What were the Q3 priorities?")
        assertEquals("q3_review.pptx", hits[0].chunk.docName)
        assertEquals("slide 3", hits[0].chunk.locator)
        assertEquals(2, hits[0].matchedTerms)
        val rel = index.relevant("What were the Q3 priorities?")
        assertEquals(1, rel.size)   // slides 1 and 14 match "q3" only
        assertEquals("slide 3", rel[0].chunk.locator)
    }

    @Test fun offTopicQuestionsAreNotRelevant() {
        assertTrue(index.relevant("What is the capital of France?").isEmpty())
        assertTrue(index.relevant("Revenue per region this year").isEmpty())
        assertTrue(index.relevant("What is the weather like today?").isEmpty())
        assertTrue(index.relevant("").isEmpty())
        assertTrue(index.relevant("the of and").isEmpty())
        // "year" alone matches leave policy pages, but one matched term out of three is below the gate
        val raw = index.search("Revenue per region this year")
        assertTrue(raw.isNotEmpty())
        assertTrue(raw.all { it.matchedTerms == 1 })
    }

    @Test fun sqlOnlyQuestionsDoNotHitDocs() {
        assertTrue(index.relevant("Average commission in the West").isEmpty())
        assertTrue(index.relevant("Total invoiced in October").isEmpty())
        assertTrue(index.relevant("How many unpaid invoices?").isEmpty())
    }

    @Test fun thresholdsObject() {
        assertEquals(2.0, Thresholds.MIN_SCORE, 0.0)
        assertEquals(2, Thresholds.MIN_MATCHED_TERMS)
        assertEquals(1.5, Thresholds.K1, 0.0)
        assertEquals(0.75, Thresholds.B, 0.0)
        assertEquals(1, Thresholds.requiredMatches(1))
        assertEquals(2, Thresholds.requiredMatches(5))
        assertTrue(Thresholds.passes(2.0, 2, 4))
        assertFalse(Thresholds.passes(1.99, 2, 4))
        assertFalse(Thresholds.passes(9.0, 1, 4))
        assertTrue(Thresholds.passes(9.0, 1, 1))
        assertFalse(Thresholds.passes(9.0, 0, 0))
    }

    @Test fun singleContentTermNeedsOnlyOneMatch() {
        assertEquals("p.2", index.relevant("joiner")[0].chunk.locator)
    }

    @Test fun kLimitsResults() {
        assertEquals(1, index.search("leave", 1).size)
        assertEquals(6, index.search("leave", 10).size)   // all six policy pages mention leave; no other doc does
        assertTrue(index.search("leave", 0).isEmpty())
    }

    @Test fun chunkAtFindsByDocNameAndLocator() {
        val c = index.chunkAt("q3_review.pptx", "slide 3")
        assertNotNull(c)
        assertTrue(c!!.text.startsWith("Q3 priorities"))
        assertNotNull(index.chunkAt("leave_policy.pdf", "p.2"))
        assertNull(index.chunkAt("leave_policy.pdf", "p.9"))
        assertNull(index.chunkAt("other.pdf", "p.2"))
    }

    @Test fun rebuildReloadsFromSource() {
        val chunks = ArrayList<Chunk>()
        val idx = Bm25Index { chunks }
        idx.rebuild()
        assertEquals(0, idx.size)
        assertTrue(idx.search("zebra").isEmpty())
        chunks += Chunker.chunk("d", "zoo.txt", listOf(TextUnit("¶ 1", "A zebra and a giraffe live in the zoo.")))
        assertTrue(idx.search("zebra").isEmpty())   // snapshot until rebuild
        idx.rebuild()
        assertEquals(1, idx.search("zebra").size)
        assertEquals(1, idx.search("zebra")[0].matchedTerms)
    }

    @Test fun scoresAreDeterministicAndOrdered() {
        val a = index.search("paid leave days carry over next year", 5)
        val b = index.search("paid leave days carry over next year", 5)
        assertEquals(a.map { it.chunk.hash to it.score }, b.map { it.chunk.hash to it.score })
        assertTrue(a.zipWithNext().all { (x, y) -> x.score >= y.score })
    }
}
