package com.hivecore.node.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorsTest {

    // ---- docx ----

    @Test fun docxFixtureGives40ParagraphsWithLocators() {
        val units = Fixtures.docx()
        assertEquals(40, units.size)
        assertEquals("¶ 1", units[0].locator)
        assertEquals("Onboarding Checklist for New Hires", units[0].text)
        assertEquals("¶ 2", units[1].locator)
        assertTrue(units[1].text.startsWith("This checklist guides managers and the HR coordinator"))
        assertEquals("¶ 40", units[39].locator)
        assertEquals("This checklist is reviewed by HR every January; send improvement ideas to the HR helpdesk.", units[39].text)
        assertEquals("Contacts", units[37].text)
        assertEquals("HR helpdesk: extension 210. IT helpdesk: extension 330. Accounts: extension 450.", units[38].text)
        assertTrue(units.none { it.text.isBlank() })
    }

    @Test fun docxRunsTabsBreaksTablesAndEmptyParagraphs() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
            <w:p/>
            <w:p><w:r><w:t>Name</w:t><w:tab/><w:t>Value</w:t><w:br/><w:t>Next line</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Cell B</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:r><w:t>Kept</w:t></w:r><w:del><w:r><w:delText>deleted</w:delText></w:r></w:del><w:r><w:instrText>PAGE</w:instrText></w:r></w:p>
            <w:sectPr/></w:body></w:document>""".trimIndent().toByteArray()
        val units = DocxExtractor.parse(xml)
        assertEquals(listOf("¶ 1", "¶ 3", "¶ 4", "¶ 5", "¶ 6"), units.map { it.locator })   // ¶ 2 is empty and skipped
        assertEquals("Hello world", units[0].text)
        assertEquals("Name\tValue\nNext line", units[1].text)
        assertEquals("Cell A", units[2].text)
        assertEquals("Cell B", units[3].text)
        assertEquals("Kept", units[4].text)
    }

    @Test fun docxEntityInTextIsDataNotMarkup() {
        val xml = """<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>a &lt;b&gt; &amp; c</w:t></w:r></w:p></w:body></w:document>""".toByteArray()
        assertEquals("a <b> & c", DocxExtractor.parse(xml).single().text)
    }

    // ---- pptx ----

    @Test fun pptxFixtureGives14SlidesInOrderAndSlide3IsQ3Priorities() {
        val units = Fixtures.pptx()
        assertEquals(14, units.size)
        assertEquals((1..14).map { "slide $it" }, units.map { it.locator })
        assertEquals("Q3 review\nMeridian Traders sales team\nJuly to September 2026\nPrepared by the sales operations desk", units[0].text)
        assertEquals(
            "Q3 priorities\n" +
                "Win back the top ten dormant accounts in the West\n" +
                "Launch the premium catalogue in the South\n" +
                "Cut the order-to-dispatch cycle to 48 hours",
            units[2].text
        )
        assertEquals("slide 3", units[2].locator)
        assertTrue(units[13].text.startsWith("Thank you"))
    }

    @Test fun pptxFollowsSldIdOrderNotFileNames() {
        val pres = """<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst>
            <p:sldId id="256" r:id="rId9"/><p:sldId id="257" r:id="rId7"/><p:sldId id="258" r:id="rId8"/></p:sldIdLst></p:presentation>"""
        val rels = """<Relationships xmlns="x">
            <Relationship Id="rId7" Type="t" Target="slides/slide1.xml"/>
            <Relationship Id="rId8" Type="t" Target="/ppt/slides/slide2.xml"/>
            <Relationship Id="rId9" Type="t" Target="slides/slide3.xml"/></Relationships>"""
        fun slide(vararg paras: String) = """<p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree><p:sp><p:txBody>""" +
            paras.joinToString("") { "<a:p><a:r><a:t>$it</a:t></a:r></a:p>" } + "</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"
        val file = Fixtures.zip(mapOf(
            "ppt/presentation.xml" to pres,
            "ppt/_rels/presentation.xml.rels" to rels,
            "ppt/slides/slide1.xml" to slide("First file", "bullet"),
            "ppt/slides/slide2.xml" to slide(),               // empty slide keeps its number but is not returned
            "ppt/slides/slide3.xml" to slide("Third file")
        ))
        val units = PptxExtractor.extract(file)
        assertEquals(listOf("slide 1", "slide 2"), units.map { it.locator })
        assertEquals("Third file", units[0].text)
        assertEquals("First file\nbullet", units[1].text)
    }

    @Test fun pptxSlideTextJoinsRunsAndBreaks() {
        val xml = """<p:sld xmlns:p="p" xmlns:a="a"><p:txBody>
            <a:p><a:r><a:t>Q3 </a:t></a:r><a:r><a:t>review</a:t></a:r></a:p>
            <a:p><a:r><a:t>line one</a:t></a:r><a:br/><a:r><a:t>line two</a:t></a:r></a:p>
            <a:p><a:endParaRPr/></a:p>
            <a:p><a:r><a:t>last</a:t></a:r></a:p></p:txBody></p:sld>""".toByteArray()
        assertEquals("Q3 review\nline one\nline two\nlast", PptxExtractor.slideText(xml))
    }

    @Test fun pptxRelationshipTargetsResolveAgainstPpt() {
        assertEquals("ppt/slides/slide1.xml", PptxExtractor.resolve("slides/slide1.xml"))
        assertEquals("ppt/slides/slide1.xml", PptxExtractor.resolve("/ppt/slides/slide1.xml"))
        assertEquals("ppt/slides/slide1.xml", PptxExtractor.resolve("./slides/slide1.xml"))
    }

    // ---- txt / md ----

    @Test fun textSplitsOnBlankLines() {
        val units = TextExtractor.parse("﻿# Title\r\n\r\nFirst para line one\r\nline two\n\n\n   \nSecond para  \n")
        assertEquals(listOf("¶ 1", "¶ 2", "¶ 3"), units.map { it.locator })
        assertEquals("# Title", units[0].text)
        assertEquals("First para line one\nline two", units[1].text)
        assertEquals("Second para", units[2].text)
        assertTrue(TextExtractor.parse("   \n\n ").isEmpty())
    }

    // ---- DocZip / DocKinds ----

    @Test fun docZipReadsEntriesAndReturnsNullForMissing() {
        val f = Fixtures.zip(mapOf("a/b.xml" to "<x/>", "c.txt" to "hi"))
        assertEquals("<x/>", String(DocZip.entry(f, "a/b.xml")!!))
        assertNull(DocZip.entry(f, "nope.xml"))
        assertEquals(listOf("a/b.xml", "c.txt"), DocZip.entries(f))
        assertTrue(DocZip.entries(Fixtures.file("q3_review.pptx")).contains("ppt/slides/slide3.xml"))
    }

    @Test fun docKinds() {
        assertEquals("page", DocKinds.unitLabel("pdf"))
        assertEquals("slide", DocKinds.unitLabel("pptx"))
        assertEquals("paragraph", DocKinds.unitLabel("docx"))
        assertEquals("paragraph", DocKinds.unitLabel("txt"))
        assertEquals("pptx", DocKinds.fromName("Q3_Review.PPTX"))
        assertNull(DocKinds.fromName("legacy.xls"))
    }
}
