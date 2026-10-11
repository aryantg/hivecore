package com.hivecore.node.docs

import com.hivecore.engine.deck.Chart
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.deck.PptxWriter
import com.hivecore.engine.deck.Slide
import com.hivecore.engine.deck.SlideKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The writer's output read back by the app's own .pptx reader: slide order, titles, bullets and chart labels. */
class DeckRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun whatIsWrittenIsWhatIsRead() {
        val deck = Deck("Pharma sales FY2024-26", "Prepared on three phones", listOf(
            Slide(SlideKind.TITLE, "Pharma sales FY2024-26", bullets = listOf("Prepared on three phones")),
            Slide(SlideKind.CHART, "Revenue by fiscal year", chart = Chart("Revenue by fiscal year", listOf("FY2024", "FY2025", "FY2026"),
                listOf(3.6e6, 4.8e6, 6.0e6), listOf("₹36 L", "₹48 L", "₹60 L"), listOf(null, "+33 %", "+25 %")), caption = "Revenue grew 67 %."),
            Slide(SlideKind.BULLETS, "Highlights", bullets = listOf("East led & West followed", "Units up <10 %>")),
            Slide(SlideKind.BULLETS, "Next steps", bullets = listOf("Keep the pace")),
        ), summary = "Revenue grew 67 %.")
        val f = tmp.newFile("deck.pptx")
        PptxWriter.write(deck, f)
        val units = PptxExtractor.extract(f)
        assertEquals(listOf("slide 1", "slide 2", "slide 3", "slide 4"), units.map { it.locator })
        assertTrue(units[0].text, units[0].text.startsWith("Pharma sales FY2024-26\nPrepared on three phones"))
        val chart = units[1].text
        for (s in listOf("Revenue by fiscal year", "FY2024", "FY2026", "₹60 L", "+33 %", "Revenue grew 67 %.")) assertTrue("$s in\n$chart", s in chart)
        assertTrue(units[2].text, units[2].text.contains("East led & West followed") && units[2].text.contains("Units up <10 %>"))
        assertTrue(units[3].text.endsWith("4 / 4"))
    }
}
