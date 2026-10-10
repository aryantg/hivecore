package com.hivecore.node.docai

import com.hivecore.core.fakes.FakeOcr
import com.hivecore.node.data.Fixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceRegexTest {

    @Test fun twelvePrintedInvoicesNineFullThreeWithTheExpectedMiss() {
        var full = 0
        for (e in Fixtures.expected["printed_invoices"]!!.jsonArray.map { it.jsonObject }) {
            val txt = e["txt"]!!.jsonPrimitive.content
            val rows = RowGrouper.rowsText(FakeOcr.fromText(Fixtures.text(txt)))
            val p = InvoiceRegex.extract(rows)
            val misses = e["regex_misses"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$txt missing", misses, p.missing)
            if ("vendor" !in misses) assertEquals(txt, e["vendor"]!!.jsonPrimitive.content, p.vendor)
            if ("date" !in misses) assertEquals(txt, e["date"]!!.jsonPrimitive.content, p.date)
            if ("total" !in misses) assertEquals(txt, e["total"]!!.jsonPrimitive.long, p.total)
            if ("status" !in misses) assertEquals(txt, e["status"]!!.jsonPrimitive.content, p.status)
            assertEquals(txt, Partial.FIELDS.filter { it !in misses }, p.regexFields)
            if (p.complete) full++
        }
        assertEquals(9, full)
    }

    @Test fun datePatternsToIso() {
        assertEquals("2026-10-12", InvoiceRegex.parseDate("Date: 12/10/2026"))
        assertEquals("2026-10-03", InvoiceRegex.parseDate("Date: 03-10-2026"))
        assertEquals("2026-10-09", InvoiceRegex.parseDate("09/10/26"))
        assertEquals("2026-10-25", InvoiceRegex.parseDate("10/25/2026"))         // m/d/y when the day cannot be a month
        assertEquals("2026-10-05", InvoiceRegex.parseDate("Date: 2026-10-05"))
        assertEquals("2026-10-07", InvoiceRegex.parseDate("Date: 7 Oct 2026"))
        assertEquals("2026-10-21", InvoiceRegex.parseDate("Date: 21 October 2026"))
        assertEquals("2026-10-03", InvoiceRegex.parseDate("3rd October, 2026"))
        assertEquals("2026-10-01", InvoiceRegex.parseDate("1st Oct. 2026"))
        assertNull(InvoiceRegex.parseDate("Dated the third of October, 2026"))
        assertNull(InvoiceRegex.parseDate("Invoice No: 1007"))
        assertNull(InvoiceRegex.parseDate("31/13/2026"))
    }

    @Test fun totalIsTheLastTotalRowWithAnyRupeePrefix() {
        val rows = listOf("Subtotal: ₹ 38,644.00", "GST 18%: ₹ 6,956.00", "Total: ₹ 45,600.00", "Status: PAID")
        assertEquals(45600L, InvoiceRegex.extract(rows).total)
        assertEquals(128400L, InvoiceRegex.parseAmount("Grand Total: Rs. 1,28,400.00"))
        assertEquals(128400L, InvoiceRegex.parseAmount("Grand Total: Rs. 128,400.00"))
        assertEquals(62150L, InvoiceRegex.parseAmount("Total: INR 62,150"))
        assertEquals(73500L, InvoiceRegex.parseAmount("Total: Rs 73,500"))
        assertEquals(9800L, InvoiceRegex.parseAmount("Total due  9800.40"))
        assertNull(InvoiceRegex.parseAmount("Total:"))
        assertNull(InvoiceRegex.extract(listOf("Amount payable: ₹ 1,12,000.00")).total)
        assertEquals(1200L, InvoiceRegex.extract(listOf("Total due: ₹ 1,200")).total)
    }

    @Test fun vendorSkipsKeywordLinesAndStatusNeedsAKnownWord() {
        val p = InvoiceRegex.extract(listOf("TAX INVOICE", "Invoice No: 44", "Bill to: Someone", "Nilgiri Tea Co", "Status: Settled"))
        assertEquals("Nilgiri Tea Co", p.vendor)
        assertNull(p.status)
        assertEquals("UNPAID", InvoiceRegex.extract(listOf("Status: Unpaid")).status)
        assertEquals("DUE", InvoiceRegex.extract(listOf("Payment: DUE")).status)       // no status row: upper-case word anywhere
        assertNull(InvoiceRegex.extract(listOf("Amount due: 10")).status)               // lower-case "due" is not a status
        assertNull(InvoiceRegex.extract(listOf("123", "--")).vendor)
    }
}
