package com.hivecore.node.docs

import com.hivecore.core.JdbcSqlDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DocStoreTest {
    private lateinit var db: JdbcSqlDb
    private lateinit var store: DocStore
    private var clock = 1_000L

    @Before fun setUp() {
        db = JdbcSqlDb.inMemory()
        store = DocStore(db) { clock++ }
    }

    @After fun tearDown() = db.close()

    @Test fun roundTripDocxPptxAndPdfUnits() {
        val docx = Fixtures.file("onboarding_checklist.docx")
        val pptx = Fixtures.file("q3_review.pptx")
        val a = store.addDocument("onboarding_checklist.docx", "docx", Fixtures.docx(), seed = true, hash = Fixtures.sha(docx))
        val b = store.addDocument("q3_review.pptx", "pptx", Fixtures.pptx(), seed = true, hash = Fixtures.sha(pptx))
        val c = store.addDocument("leave_policy.pdf", "pdf", Fixtures.leavePolicyPages(), hash = Fixtures.sha(Fixtures.file("leave_policy.pdf")))
        assertFalse(a.duplicate); assertFalse(b.duplicate); assertFalse(c.duplicate)

        val docs = store.docs()
        assertEquals(listOf("onboarding_checklist.docx", "q3_review.pptx", "leave_policy.pdf"), docs.map { it.name })
        assertEquals(listOf(40, 14, 6), docs.map { it.units })
        assertEquals(listOf("paragraph", "slide", "page"), docs.map { it.unitLabel })
        assertEquals(listOf(40, 14, 6), docs.map { it.chunkCount })
        assertEquals(listOf("docx", "pptx", "pdf"), docs.map { it.kind })
        assertTrue(docs.all { it.id.startsWith("doc_") && it.id.length == 20 })
        assertEquals(DocStore.idFor(Fixtures.sha(docx)), a.doc.id)

        val chunks = store.chunks()
        assertEquals(60, chunks.size)
        val slide3 = chunks.single { it.docName == "q3_review.pptx" && it.locator == "slide 3" }
        assertTrue(slide3.text.startsWith("Q3 priorities"))
        assertEquals(b.doc.id, slide3.docId)
        assertEquals(Chunker.hash(b.doc.id, 2, slide3.text), slide3.hash)
        assertEquals(2, slide3.ordinal)
        assertEquals((0 until 40).toList(), store.chunksOf(a.doc.id).map { it.ordinal })

        val recs = store.records()
        assertEquals(listOf(true, true, false), recs.map { it.seed })
        assertEquals(listOf(1_000L, 1_001L, 1_002L), recs.map { it.importedAt })
        assertEquals(Fixtures.sha(pptx), recs[1].hash)
        assertEquals(c.doc, store.doc(c.doc.id)!!.def)
        assertEquals(c.doc, store.findByName("leave_policy.pdf")!!.def)
        assertNull(store.doc("doc_missing"))
    }

    @Test fun dedupesOnFileHash() {
        val units = Fixtures.leavePolicyPages()
        val first = store.addDocument("leave_policy.pdf", "pdf", units, hash = "abc123")
        val again = store.addDocument("Leave Policy (1).pdf", "pdf", units, hash = "abc123")
        assertFalse(first.duplicate)
        assertTrue(again.duplicate)
        assertEquals(first.doc, again.doc)
        assertEquals(1, store.docs().size)
        assertEquals(6, store.chunks().size)
        assertEquals(first.doc.id, store.findByHash("abc123")!!.def.id)
        // a different file with the same name is a second document
        val other = store.addDocument("leave_policy.pdf", "pdf", units.take(2), hash = "def456")
        assertFalse(other.duplicate)
        assertEquals(2, store.docs().size)
        assertEquals(8, store.chunks().size)
    }

    @Test fun deleteRemovesDocAndChunks() {
        val a = store.addDocument("a.txt", "txt", TextExtractor.parse("one\n\ntwo"), hash = "h1")
        val b = store.addDocument("b.txt", "txt", TextExtractor.parse("three"), hash = "h2")
        assertEquals(3, store.chunks().size)
        store.delete(a.doc.id)
        assertEquals(listOf(b.doc), store.docs())
        assertEquals(listOf("three"), store.chunks().map { it.text })
        assertNull(store.findByHash("h1"))
        store.delete("doc_missing")   // no-op
        assertEquals(1, store.docs().size)
    }

    @Test fun deleteNonSeedKeepsSeededDocs() {
        val seed = store.addDocument("onboarding_checklist.docx", "docx", Fixtures.docx(), seed = true, hash = "s1")
        val live1 = store.addDocument("leave_policy.pdf", "pdf", Fixtures.leavePolicyPages(), hash = "l1")
        val live2 = store.addDocument("notes.txt", "txt", TextExtractor.parse("hello"), hash = "l2")
        val removed = store.deleteNonSeed()
        assertEquals(setOf(live1.doc.id, live2.doc.id), removed.toSet())
        assertEquals(listOf(seed.doc), store.docs())
        assertEquals(40, store.chunks().size)
        assertTrue(store.records().all { it.seed })
    }

    @Test fun confidentialFlagAndEmptyUnitsAreStored() {
        val r = store.addDocument("secret.txt", "txt", TextExtractor.parse("top secret"), confidential = true, hash = "c1")
        assertTrue(r.doc.confidential)
        assertTrue(store.docs().single().confidential)
        val empty = store.addDocument("empty.txt", "txt", emptyList(), hash = "e1")
        assertEquals(0, empty.doc.units)
        assertEquals(0, empty.doc.chunkCount)
        assertEquals(2, store.docs().size)
    }

    @Test fun migrateIsIdempotentAndIndexRebuildsFromStore() {
        store.addDocument("q3_review.pptx", "pptx", Fixtures.pptx(), seed = true, hash = "p1")
        val reopened = DocStore(db)   // same connection, CREATE IF NOT EXISTS
        assertEquals(1, reopened.docs().size)
        val index = Bm25Index(reopened)
        assertTrue(index.relevant("What were the Q3 priorities?").isEmpty())   // nothing until rebuild
        index.rebuild()
        val rel = index.relevant("What were the Q3 priorities?")
        assertEquals("slide 3", rel[0].chunk.locator)
        assertNotNull(index.chunkAt("q3_review.pptx", "slide 3"))
        reopened.deleteNonSeed()
        index.rebuild()
        assertEquals(14, index.size)
        reopened.delete(reopened.docs().single().id)
        index.rebuild()
        assertEquals(0, index.size)
        assertTrue(index.relevant("What were the Q3 priorities?").isEmpty())
    }
}
