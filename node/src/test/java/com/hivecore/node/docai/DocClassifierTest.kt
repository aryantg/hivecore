package com.hivecore.node.docai

import com.hivecore.core.fakes.FakeClosedChooser
import com.hivecore.node.data.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocClassifierTest {

    @Test fun twelvePrintedInvoicesAreInvoicesByRules() = runTest {
        val chooser = FakeClosedChooser()
        val c = DocClassifier(chooser, DocTestData.schemas)
        for (e in DocTestData.invoices) {
            val txt = e["txt"]!!.jsonPrimitive.content
            val r = c.classify(DocTestData.rowsText(Fixtures.text(txt)), e["png"]!!.jsonPrimitive.content)
            assertEquals(txt, "invoice", r.decision.value)
            assertEquals(txt, "rule", r.by)
            assertEquals(1.0, r.decision.confidence, 0.0)
            assertEquals(DocSchemas.OPTIONS, r.decision.options)
        }
        assertTrue("rules decided every invoice", chooser.calls.isEmpty())
    }

    @Test fun syntheticDocumentsByRules() = runTest {
        val c = DocClassifier(FakeClosedChooser(), DocTestData.schemas)
        val cases = listOf(
            DocTestData.RECEIPT to "receipt", DocTestData.CONTRACT to "contract", DocTestData.RESUME to "resume",
            DocTestData.LETTER to "letter", DocTestData.REPORT to "report", DocTestData.PURCHASE_ORDER to "purchase_order",
            DocTestData.ID_CARD to "id_document",
        )
        for ((text, expected) in cases) {
            val r = c.classify(DocTestData.rowsText(text))
            assertEquals(text.lineSequence().first(), expected, r.decision.value)
            assertEquals("rule", r.by)
        }
    }

    @Test fun filenameCuesAddWeight() {
        val text = "Kavya Textiles\nDate: 7 Oct 2026\nTotal: 5,000"
        assertEquals(0, DocClassifier.score(text).getValue("invoice"))
        assertEquals(DocClassifier.FILENAME_WEIGHT, DocClassifier.score(text, "/sdcard/Download/inv_2026_10.jpg").getValue("invoice"))
        assertNull(DocClassifier.rules(DocClassifier.score(text, "inv_2026_10.jpg")))      // 3 < MIN_SCORE: not decisive alone
        assertEquals("invoice", DocClassifier.rules(DocClassifier.score("Invoice No: 7", "invoice.png"))!!.value)
    }

    @Test fun undecidedRulesAskTheClosedChooserOverTheClassEnum() = runTest {
        val chooser = FakeClosedChooser().apply { answers["what kind of document"] = DocSchemas.OPTIONS.indexOf("receipt"); confidence = 1.0 }
        val c = DocClassifier(chooser, DocTestData.schemas)
        val text = "Kavya Textiles\nDate: 7 Oct 2026\nTotal: 5,000"
        val r = c.classify(text, "scan_0012.jpg")
        assertEquals("receipt", r.decision.value)
        assertEquals("llm", r.by)
        val (question, options) = chooser.calls.single()
        assertTrue(question.contains("Filename: scan_0012.jpg"))
        assertTrue(question.contains("<<<\nKavya Textiles\nDate: 7 Oct 2026\nTotal: 5,000\n>>>"))
        assertEquals(DocSchemas.OPTIONS.map { DocTestData.schemas.label(it) }, options)
        assertEquals("other: none of the above", options.last())
    }

    @Test fun ambiguousScoresAlsoGoToTheChooserAndNoConfidentChoiceIsNull() = runTest {
        // invoice cues and receipt cues tie closely: not decisive
        val text = "INVOICE\nRECEIPT\nTotal: 500"
        val s = DocClassifier.score(text)
        assertEquals(3, s.getValue("invoice")); assertEquals(4, s.getValue("receipt"))
        assertNull(DocClassifier.rules(s))
        val chooser = FakeClosedChooser().apply { answers["what kind"] = -1 }
        val r = DocClassifier(chooser, DocTestData.schemas).classify(text)
        assertNull(r.decision.value)
        assertEquals("llm", r.by)
    }

    @Test fun scoringIsDeterministic() {
        val a = DocClassifier.score(DocTestData.CONTRACT, "nda.pdf")
        val b = DocClassifier.score(DocTestData.CONTRACT, "nda.pdf")
        assertEquals(a, b)
        assertEquals(DocSchemas.CLASSES, a.keys.toList())
        val d = DocClassifier.rules(a)!!
        assertEquals(DocSchemas.OPTIONS.map { a[it] ?: 0 }, d.votes)
        assertEquals(0, d.runs)
    }
}
