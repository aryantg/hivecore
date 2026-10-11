package com.hivecore.engine.deck

import com.hivecore.core.AggTable
import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DeckAssemblerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun row(vararg v: Any): List<JsonElement> = v.map { if (it is String) JsonPrimitive(it) else JsonPrimitive(it as Number) }

    /** Monthly revenue from April 2023 to March 2026: FY2024 = 36 L, FY2025 = 48 L, FY2026 = 60 L. */
    private val monthly: AggTable = AggTable("Sales", "revenue by month", listOf("month", "sum_revenue"),
        (0 until 36).map { i ->
            val y = 2023 + (3 + i) / 12; val m = (3 + i) % 12 + 1
            val fy = i / 12   // 0 = FY2024, 1 = FY2025, 2 = FY2026
            row("%04d-%02d".format(y, m), 300_000 + fy * 100_000)
        }, 1204, 1204, "sql")

    private val agg = TaskSpec("a", Op.AGG, buildJsonObject { put("q", "revenue by month FY2024-26") }, owner = "sales")
    private fun render(period: String = "FY2024 26") =
        TaskSpec("d", Op.RENDER, buildJsonObject { put("kind", "deck"); put("measure", "revenue"); put("period", period) }, inputs = listOf("a", "w", "i"))

    @Test fun fiscalYearsRollUpWithGrowthAndTheWriterProducesAReadablePackage() {
        val out = tmp.newFolder("decks")
        val deck = DeckAssembler.assemble(render(), listOf(agg to Canon.encodeTable(monthly)), outDir = out)
        val chart = deck.slides.first { it.kind == SlideKind.CHART }.chart!!
        assertEquals(listOf("FY2024", "FY2025", "FY2026"), chart.categories)
        assertEquals(listOf(3_600_000.0, 4_800_000.0, 6_000_000.0), chart.values)
        assertEquals(listOf("₹36 L", "₹48 L", "₹60 L"), chart.labels)
        assertEquals(listOf(null, "+33 %", "+25 %"), chart.deltas)
        assertEquals("Revenue grew 67 % from FY2024 (₹36 L) to FY2026 (₹60 L).", deck.summary)
        assertEquals(listOf(SlideKind.TITLE, SlideKind.CHART, SlideKind.BULLETS, SlideKind.BULLETS), deck.slides.map { it.kind })
        // the file: a zip with the parts a .pptx reader opens, named by its content hash
        val f = File(deck.file!!)
        assertTrue(f.isFile); assertEquals(f.length(), deck.bytes)
        assertTrue(f.name.startsWith("deck-") && f.name.endsWith(".pptx"))
        val names = ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/slides/slide1.xml", "ppt/slides/slide4.xml", "ppt/theme/theme1.xml")))
        // every XML part is well-formed
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        ZipFile(f).use { z -> for (n in names.filter { it.endsWith(".xml") || it.endsWith(".rels") }) z.getInputStream(z.getEntry(n)).use { parser.parse(it) } }
        // the same deck writes the same bytes
        assertEquals(deck.fileSha256, com.hivecore.core.Hashing.sha256Hex(PptxWriter.bytes(deck.copy(file = null, fileSha256 = null, bytes = 0))))
        // the round trip through JSON keeps the structure
        assertEquals(deck, Deck.fromJson(deck.toJson()))
        assertTrue(Deck.isDeck(deck.toJson()))
        assertNull(Deck.fromJson(Canon.encodeTable(monthly)))
    }

    @Test fun calendarYearsWithoutFyAndTextFromTheSlideTextStep() {
        val words = TaskSpec("w", Op.LLM_MAP, inputs = listOf("a"))
        val wordsOut = buildJsonObject {
            putJsonArray("items") {
                add(buildJsonObject {
                    put("item", "in0")
                    putJsonObject("output") {
                        put("title", "Three years of growth"); put("subtitle", "Sales 2023 to 2026"); put("headline", "Revenue climbed every year.")
                        putJsonArray("bullets") { add(JsonPrimitive("Steady monthly gains")); add(JsonPrimitive("Every region contributed")) }
                        putJsonArray("closing") { add(JsonPrimitive("Keep the pace")) }
                    }
                })
            }
        }
        val deck = DeckAssembler.assemble(render(period = "2023 to 2026"), listOf(agg to Canon.encodeTable(monthly), words to wordsOut))
        assertEquals("Three years of growth", deck.title)
        assertEquals("Sales 2023 to 2026", deck.subtitle)
        val chart = deck.slides.first { it.kind == SlideKind.CHART }
        assertEquals(listOf("2023", "2024", "2025", "2026"), chart.chart!!.categories)
        assertEquals("Revenue climbed every year.", chart.caption)
        assertEquals(listOf("Steady monthly gains", "Every region contributed"), deck.slides[2].bullets)
        assertEquals(listOf("Keep the pace"), deck.slides.last().bullets)
        assertNull(deck.file)
    }

    @Test fun thePictureIsEmbeddedWhenItsBlobIsHeldAndTheSlideStaysWithoutIt() {
        val png = File(javaClass.getResource("/fixtures/invoice_01.png")?.file ?: run {
            // no fixture in this module: a tiny valid PNG
            val f = tmp.newFile("pic.png"); f.writeBytes(TINY_PNG); f.path
        })
        val sha = com.hivecore.core.Hashing.sha256Hex(png.readBytes())
        val gen = TaskSpec("i", Op.GEN_IMAGE)
        val genOut = buildJsonObject { put("op", "GEN_IMAGE"); put("blocked", false); putJsonArray("items") { add(buildJsonObject { put("seed", 7); put("out", "$sha.png"); put("sha256", sha) }) } }
        val out = tmp.newFolder("d2")
        val with = DeckAssembler.assemble(render(), listOf(agg to Canon.encodeTable(monthly), gen to genOut), imageFile = { if (it == sha) png else null }, outDir = out)
        val pic = with.slides.first { it.kind == SlideKind.IMAGE }
        assertEquals(png.absolutePath, pic.image)
        val names = ZipFile(File(with.file!!)).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.toString(), names.any { it.startsWith("ppt/media/image") && it.endsWith(".png") })
        val without = DeckAssembler.assemble(render(), listOf(agg to Canon.encodeTable(monthly), gen to genOut))
        val slide = without.slides.first { it.kind == SlideKind.IMAGE }
        assertNull(slide.image)
        assertEquals("The illustration did not reach this phone", slide.caption)
    }

    @Test fun aTableWithoutMonthsChartsItsFirstDimension() {
        val byRegion = AggTable("Sales", "revenue by region", listOf("region", "sum_revenue"), listOf(row("East", 5), row("West", 3)), 8, 8, "sql")
        val s = DeckAssembler.rollUp(byRegion, "revenue", fiscal = false)!!
        assertEquals("region", s.axis)
        assertEquals(listOf("East", "West"), s.categories)
        assertNotNull(DeckAssembler.assemble(render(""), listOf(agg to Canon.encodeTable(byRegion))).slides.first { it.kind == SlideKind.CHART }.chart)
    }

    @Test fun formatting() {
        assertEquals("₹1.2 Cr", DeckFormat.compact(12_300_000.0, true))
        assertEquals("45 K", DeckFormat.compact(45_000.0, false))
        assertEquals("-₹2 L", DeckFormat.compact(-200_000.0, true))
        assertEquals("+50 %", DeckFormat.pct(2.0, 3.0))
        assertEquals("-25 %", DeckFormat.pct(4.0, 3.0))
        assertNull(DeckFormat.pct(0.0, 3.0))
        assertEquals("2024", DeckAssembler.yearOf("2024-03", fiscal = true))
        assertEquals("2025", DeckAssembler.yearOf("2024-04", fiscal = true))
        assertEquals("2024", DeckAssembler.yearOf("2024-11", fiscal = false))
        assertEquals("Revenue", DeckFormat.measureTitle("sum_revenue"))
    }

    private companion object {
        /** A 1×1 opaque PNG. */
        val TINY_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    }
}
