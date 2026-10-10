package com.hivecore.node.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    @Test fun q4SalesIs1380RowsBy5WithMonthStrings() {
        val t = XlsxReader.read(Fixtures.file("q4_sales.xlsx"))
        val expectedHeader = Fixtures.expected["headers"]!!.jsonObject["q4_sales.xlsx"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expectedHeader, t.header)
        assertEquals(1380, t.rows.size)
        assertTrue(t.rows.all { it.size == 5 })
        assertEquals(setOf("2026-10", "2026-11", "2026-12"), t.rows.map { it[2] }.toSet())
        assertEquals("303000", t.rows[0][3])
        assertEquals("14", t.rows[0][4])
        assertEquals("West", t.rows[0][0])
    }

    @Test fun datesXlsxConvertsSerialDatesToIso() {
        val t = XlsxReader.read(Fixtures.file("dates.xlsx"))
        val spec = Fixtures.expected["dates.xlsx"]!!.jsonObject
        assertEquals(spec["columns"]!!.jsonArray.map { it.jsonPrimitive.content }, t.header)
        val rows = spec["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(rows.size, t.rows.size)
        rows.forEachIndexed { i, r ->
            assertEquals(r["date"]!!.jsonPrimitive.content, t.rows[i][0])
            assertEquals(r["label"]!!.jsonPrimitive.content, t.rows[i][1])
            assertEquals(r["due"]!!.jsonPrimitive.content, t.rows[i][3])
            val amount = r["amount"]!!.jsonPrimitive.content
            assertEquals(amount.toDouble(), t.rows[i][2].toDouble(), 0.0)
        }
        assertEquals("1200", t.rows[0][2])
        assertEquals("2350.5", t.rows[1][2])
        assertEquals("2024-02-29", t.rows[7][0])
    }

    @Test fun serialAndFormatRules() {
        assertEquals("1899-12-31", XlsxReader.serialToIso(1.0))
        assertEquals("2026-01-05", XlsxReader.serialToIso(46027.0))
        assertEquals("2026-01-05", XlsxReader.serialToIso(46027.75))
        assertNull(XlsxReader.serialToIso(0.0))
        assertTrue(XlsxReader.isDateFormat(14, null))
        assertTrue(XlsxReader.isDateFormat(22, null))
        assertTrue(XlsxReader.isDateFormat(47, null))
        assertFalse(XlsxReader.isDateFormat(0, null))
        assertFalse(XlsxReader.isDateFormat(4, null))
        assertTrue(XlsxReader.isDateFormat(164, "yyyy-mm-dd"))
        assertTrue(XlsxReader.isDateFormat(165, "[\$-409]d-mmm-yy;@"))
        assertFalse(XlsxReader.isDateFormat(166, "0.00"))
        assertFalse(XlsxReader.isDateFormat(167, "h:mm:ss"))
        assertFalse(XlsxReader.isDateFormat(168, "\"Days\" 0"))   // quoted literal, no real y/d codes
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(25, XlsxReader.columnIndex("Z9"))
        assertEquals(26, XlsxReader.columnIndex("AA10"))
        assertEquals("12000000", XlsxReader.plainNumber("1.2E7"))
        assertEquals("1200", XlsxReader.plainNumber("1200.0"))
        assertEquals("2350.5", XlsxReader.plainNumber("2350.5"))
    }

    @Test fun relsTargetsResolve() {
        assertEquals("xl/worksheets/sheet1.xml", XlsxReader.resolve("xl/", "/xl/worksheets/sheet1.xml"))
        assertEquals("xl/worksheets/sheet1.xml", XlsxReader.resolve("xl/", "worksheets/sheet1.xml"))
        assertEquals("xl/worksheets/sheet1.xml", XlsxReader.resolve("xl/", "./worksheets/sheet1.xml"))
        assertEquals("other/x.xml", XlsxReader.resolve("xl/", "../other/x.xml"))
    }

    @Test fun ooxmlZipEntryAndEntries() {
        val f = Fixtures.file("q4_sales.xlsx")
        assertTrue("xl/workbook.xml" in OoxmlZip.entries(f))
        assertTrue(OoxmlZip.entry(f, "xl/workbook.xml")!!.isNotEmpty())
        assertTrue(OoxmlZip.entry(f, "/xl/workbook.xml")!!.isNotEmpty())
        assertNull(OoxmlZip.entry(f, "xl/sharedStrings.xml"))
        val ole = Fixtures.file("legacy.xls")
        assertNull(OoxmlZip.entry(ole, "xl/workbook.xml"))
        assertTrue(OoxmlZip.entries(ole).isEmpty())
    }

    @Test fun syntheticWorkbookSharedStringsBooleansFormulasBlankRowsAndSheetOrder() {
        val f = synthetic()
        val t = XlsxReader.read(f)
        assertEquals(listOf("name", "ok", "total", "when", "note"), t.header)
        assertEquals(3, t.rows.size)
        assertEquals(listOf("Rahul Sharma", "true", "300", "2026-10-09", "plain"), t.rows[0])      // rich-text si, bool, cached formula, custom dd/mm/yyyy
        assertEquals(listOf("", "false", "1.5", "46334", "x"), t.rows[1])                        // sparse row: A missing; D unstyled number stays a number
        assertEquals(listOf("Z", "", "", "", ""), t.rows[2])                                     // after a blank row that is dropped
    }

    /** First sheet in workbook order is sheet2.xml (relative rels target); sheet1.xml is a decoy. */
    private fun synthetic(): File {
        val f = File.createTempFile("synthetic_", ".xlsx")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
            put("xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Data\" sheetId=\"2\" r:id=\"rId7\"/><sheet name=\"Decoy\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId7\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>")
            put("xl/sharedStrings.xml", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"4\" uniqueCount=\"4\"><si><t>name</t></si><si><r><rPr><b/></rPr><t>Rahul</t></r><r><t xml:space=\"preserve\"> Sharma</t></r><rPh sb=\"0\" eb=\"1\"><t>ignored</t></rPh></si><si><t>plain</t></si><si><t>Z</t></si></sst>")
            put("xl/styles.xml", "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\"/><numFmt numFmtId=\"165\" formatCode=\"0.00\"/></numFmts><cellXfs count=\"3\"><xf numFmtId=\"0\"/><xf numFmtId=\"164\"/><xf numFmtId=\"165\"/></cellXfs></styleSheet>")
            put("xl/worksheets/sheet1.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>decoy</t></is></c></row></sheetData></worksheet>")
            put("xl/worksheets/sheet2.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"inlineStr\"><is><t>ok</t></is></c><c r=\"C1\" t=\"str\"><f>\"to\"&amp;\"tal\"</f><v>total</v></c><c r=\"D1\" t=\"inlineStr\"><is><t>when</t></is></c><c r=\"E1\" t=\"inlineStr\"><is><t>note</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\" t=\"b\"><v>1</v></c><c r=\"C2\"><f>SUM(1,2)*100</f><v>300</v></c><c r=\"D2\" s=\"1\"><v>46304</v></c><c r=\"E2\" t=\"s\"><v>2</v></c></row>" +
                "<row r=\"3\"><c r=\"B3\" t=\"b\"><v>0</v></c><c r=\"C3\" s=\"2\"><v>1.5</v></c><c r=\"D3\"><v>46334</v></c><c r=\"E3\" t=\"inlineStr\"><is><t>x</t></is></c></row>" +
                "<row r=\"4\"></row>" +
                "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>3</v></c><c r=\"B5\" t=\"e\"><v>#N/A</v></c></row>" +
                "<row r=\"6\"><c r=\"A6\"/><c r=\"B6\"><v></v></c></row>" +
                "</sheetData></worksheet>")
        }
        return f
    }
}
