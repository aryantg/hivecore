package com.hivecore.node.docai

import com.hivecore.core.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocSchemasLayoutTest {

    @Test fun everyClassHasASchemaWithTheAgreedFields() {
        val s = DocTestData.schemas
        assertEquals(DocSchemas.CLASSES, s.byClass.keys.toList())
        fun names(c: String) = s[c]!!.fields.map { it.name }
        assertEquals(listOf("vendor", "invoice_no", "date", "total", "status", "gstin"), names("invoice"))
        assertEquals(listOf("merchant", "date", "total"), names("receipt"))
        assertEquals(listOf("po_no", "vendor", "date", "total"), names("purchase_order"))
        assertEquals(listOf("parties", "effective_date", "term_months", "value"), names("contract"))
        assertEquals(listOf("name", "skills", "years_experience"), names("resume"))
        assertEquals(listOf("kind", "name"), names("id_document"))
        assertEquals(listOf("sender", "date", "subject"), names("letter"))
        assertEquals(listOf("title", "date"), names("report"))
        assertEquals("date", s["invoice"]!!.field("date")!!.type)
        assertEquals("number", s["invoice"]!!.field("total")!!.type)
        assertEquals("string_list", s["contract"]!!.field("parties")!!.type)
        // identity documents: identifiers are only ever redacted, never extracted
        val id = s["id_document"]!!
        assertTrue(id.redactOnly)
        assertEquals(listOf("id_number"), id.neverExtract)
        assertFalse(s["invoice"]!!.redactOnly)
        assertEquals("invoice: a bill from a vendor asking for payment", s.label("invoice"))
        assertEquals("other: none of the above", s.label("other"))
        assertEquals(DocSchemas.CLASSES.joinToString(",") { "$it@1" }, s.versions)
    }

    @Test fun schemaParserRejectsBadSchemas() {
        fun fails(text: String) = assertTrue(runCatching { DocSchemas.parse(text) }.isFailure)
        fails("""{"class":"poem","version":"1","description":"x","fields":[{"name":"a","type":"string"}]}""")
        fails("""{"class":"invoice","version":"1","description":"x","fields":[{"name":"a","type":"blob"}]}""")
        fails("""{"class":"invoice","version":"1","description":"x","fields":[{"name":"a","type":"enum"}]}""")
        fails("""{"class":"id_document","version":"1","description":"x","never_extract":["id_number"],"fields":[{"name":"id_number","type":"string"}]}""")
    }

    @Test fun inlineAndBelowPairs() {
        val r = Layout.ofRows(listOf(
            listOf("Mehta Stationers"),
            listOf("Invoice No: 1007", "Date: 12/10/2026"),
            listOf("Bill to:", "Meridian Traders Pvt Ltd"),
            listOf("Invoice Date"),
            listOf("14/10/2026"),
            listOf("Vendor:"),
            listOf("Omkar Packaging"),
            listOf("Meeting at 10:30 sharp"),
            listOf("Total: ₹ 45,600.00"),
        ))
        assertEquals(listOf(
            KeyValue("invoice_no", "Invoice No", "1007", 1, "inline"),
            KeyValue("date", "Date", "12/10/2026", 1, "inline"),
            KeyValue("bill_to", "Bill to", "Meridian Traders Pvt Ltd", 2, "inline"),
            KeyValue("invoice_date", "Invoice Date", "14/10/2026", 4, "below"),
            KeyValue("vendor", "Vendor", "Omkar Packaging", 6, "below"),
            KeyValue("total", "Total", "₹ 45,600.00", 8, "inline"),
        ), r.pairs)
        assertEquals("Omkar Packaging", r.value("vendor"))
        assertNull(r.value("gstin"))
        assertEquals("Invoice No: 1007  Date: 12/10/2026", r.text[1])
    }

    @Test fun timesAndNumbersAreNotLabels() {
        assertFalse(Layout.isLabel("10"))
        assertFalse(Layout.isLabel("2026 10 12"))
        assertFalse(Layout.isLabel("one two three four five six"))
        assertTrue(Layout.isLabel("GST 18%"))
        assertEquals(emptyList<KeyValue>(), Layout.ofRows(listOf(listOf("10:30"))).pairs)
    }

    @Test fun layoutFromOcrLinesIsDeterministicAndOrderIndependent() {
        val lines = listOf(
            OcrLine("Date: 12/10/2026", 300, 40, 460, 56),
            OcrLine("Invoice No: 1007", 40, 41, 200, 57),
            OcrLine("Status:", 40, 80, 110, 96),
            OcrLine("PAID", 200, 81, 240, 97),
        )
        val a = Layout.of(lines)
        val b = Layout.of(lines.reversed())
        assertEquals(a, b)
        assertEquals(listOf(listOf("Invoice No: 1007", "Date: 12/10/2026"), listOf("Status:", "PAID")), a.rows)
        assertEquals("PAID", a.value("status"))
    }
}
