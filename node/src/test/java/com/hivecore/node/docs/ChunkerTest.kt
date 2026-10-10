package com.hivecore.node.docs

import com.hivecore.core.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private fun words(n: Int, prefix: String = "w") = (1..n).joinToString(" ") { "$prefix$it" }
    private fun count(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    @Test fun shortUnitIsOneChunkWithItsLocator() {
        val chunks = Chunker.chunk("d1", "a.txt", listOf(TextUnit("¶ 1", "  one two three  ")))
        assertEquals(1, chunks.size)
        assertEquals("one two three", chunks[0].text)
        assertEquals("¶ 1", chunks[0].locator)
        assertEquals(0, chunks[0].ordinal)
        assertEquals("d1", chunks[0].docId)
        assertEquals("a.txt", chunks[0].docName)
    }

    @Test fun longUnitSplitsInto220WordWindowsWith40WordOverlap() {
        val chunks = Chunker.chunk("d1", "a.txt", listOf(TextUnit("p.1", words(500))))
        // 500 words: [0,220) [180,400) [360,500)
        assertEquals(3, chunks.size)
        assertEquals(listOf(220, 220, 140), chunks.map { count(it.text) })
        assertTrue(chunks[0].text.startsWith("w1 w2 "))
        assertTrue(chunks[0].text.endsWith(" w220"))
        assertTrue(chunks[1].text.startsWith("w181 w182 "))
        assertTrue(chunks[1].text.endsWith(" w400"))
        assertTrue(chunks[2].text.startsWith("w361 "))
        assertTrue(chunks[2].text.endsWith(" w500"))
        // the overlap is exactly the last 40 words of the previous chunk
        val tail = chunks[0].text.split(" ").takeLast(40)
        assertEquals(tail, chunks[1].text.split(" ").take(40))
        assertTrue(chunks.all { it.locator == "p.1" })
        assertEquals(listOf(0, 1, 2), chunks.map { it.ordinal })
    }

    @Test fun exactly220WordsIsOneChunk() {
        assertEquals(1, Chunker.chunk("d", "n", listOf(TextUnit("p.1", words(220)))).size)
        assertEquals(2, Chunker.chunk("d", "n", listOf(TextUnit("p.1", words(221)))).size)
    }

    @Test fun chunksNeverSpanUnitsAndOrdinalsRunAcrossTheDocument() {
        val chunks = Chunker.chunk("d1", "a.pdf", listOf(
            TextUnit("p.1", words(300, "a")),
            TextUnit("p.2", ""),                 // empty unit: skipped
            TextUnit("p.3", words(10, "c")),
            TextUnit("p.4", words(230, "d"))
        ))
        assertEquals(listOf("p.1", "p.1", "p.3", "p.4", "p.4"), chunks.map { it.locator })
        assertEquals(listOf(0, 1, 2, 3, 4), chunks.map { it.ordinal })
        assertTrue(chunks.none { c -> c.text.contains("a") && c.text.contains("c") })
        assertTrue(chunks[2].text == words(10, "c"))
    }

    @Test fun hashIsSha256OfDocIdOrdinalText() {
        val c = Chunker.chunk("doc_x", "x.txt", listOf(TextUnit("¶ 1", "hello"), TextUnit("¶ 2", "world")))
        assertEquals(Hashing.sha256Hex("doc_x|0|hello"), c[0].hash)
        assertEquals(Hashing.sha256Hex("doc_x|1|world"), c[1].hash)
        assertTrue(c[0].hash != c[1].hash)
    }

    @Test fun sampleDocsChunkOnePerUnit() {
        // Every fixture unit is under 220 words, so chunk counts equal unit counts and locators are preserved.
        assertEquals(40, Chunker.chunk("d", "onboarding_checklist.docx", Fixtures.docx()).size)
        assertEquals(14, Chunker.chunk("d", "q3_review.pptx", Fixtures.pptx()).size)
        val pages = Chunker.chunk("d", "leave_policy.pdf", Fixtures.leavePolicyPages())
        assertEquals(6, pages.size)
        assertEquals((1..6).map { "p.$it" }, pages.map { it.locator })
        assertTrue(pages[1].text.contains("A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly."))
    }
}
