package com.hivecore.node

import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.deck.SlideKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A deck plan end to end over the fake swarm: the owner's table, a worker's slide text, the deck and its .pptx on the commander. */
class DeckPlanTest {
    @get:Rule val tmp = TemporaryFolder()
    private var harness: SwarmHarness? = null
    private fun TestScope.mesh() = SwarmHarness(this, blobRoot = tmp.newFolder("blobs")).also { harness = it }
    @After fun tearDown() { harness?.close() }

    private val words = buildJsonObject {
        putJsonArray("items") {
            add(buildJsonObject {
                put("item", "in0")
                putJsonObject("output") {
                    put("title", "Sales on the rise"); put("subtitle", "Two years of revenue"); put("headline", "Revenue kept climbing.")
                    putJsonArray("bullets") { add(JsonPrimitive("Every region grew")); add(JsonPrimitive("Units followed revenue")) }
                    putJsonArray("closing") { add(JsonPrimitive("Keep investing in the East")) }
                }
            })
        }
    }

    private fun deckDag(h: SwarmHarness, sales: SwarmHarness.Node, withWords: Boolean): Dag {
        // the fake intent planner knows no month grouping: the owner's sub-question is canned
        sales.intentPlanner.canned["revenue by month"] = com.hivecore.core.IntentSet(listOf(com.hivecore.core.Intent("sales", "sum", "revenue", groupBy = listOf("month"))))
        return Dag("deck_growth", "2", buildList {
        add(h.agg("a", sales, "revenue by month", listOf("month")))
        if (withWords) add(TaskSpec("w", Op.LLM_MAP, buildJsonObject { put("template", "write a deck"); put("schema", buildJsonObject { put("type", "object") }); put("replicate", "none") }, inputs = listOf("a")))
        add(TaskSpec("d", Op.RENDER, buildJsonObject { put("kind", "deck"); put("measure", "revenue") }, inputs = if (withWords) listOf("a", "w") else listOf("a")))
        }, output = "d")
    }

    @Test fun theCommanderAssemblesTheDeckAndWritesThePptx() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        for (n in listOf(hr, sales, accounts)) n.runner.register(ConstExecutor(words, setOf(Op.LLM_MAP)))
        h.start()
        val asked = h.ask(hr, "Generate a pitch deck showing the increase in sales", deckDag(h, sales, withWords = true))
        (asked.outcome as? com.hivecore.core.AskOutcome.Refusal)?.let { throw AssertionError("refused: ${'$'}{it.reasons}") }
        val a = asked.answer
        val deck = Deck.fromJson(a.artifact)
        assertNotNull(a.text, deck)
        deck!!
        assertEquals("Sales on the rise", deck.title)
        assertEquals(listOf(SlideKind.TITLE, SlideKind.CHART, SlideKind.BULLETS, SlideKind.BULLETS), deck.slides.map { it.kind })
        val chart = deck.slides[1].chart!!
        assertEquals(listOf("2025", "2026"), chart.categories)       // the seed covers October 2025 to September 2026
        assertTrue(chart.values.all { it > 0 })
        assertEquals("Revenue kept climbing.", deck.slides[1].caption)
        assertEquals(listOf("Every region grew", "Units followed revenue"), deck.slides[2].bullets)
        assertTrue(deck.summary, deck.summary.startsWith("Revenue "))
        assertTrue(a.text, a.text.startsWith(deck.summary) && a.text.contains("4 slides") && a.text.contains("saved as .pptx"))
        val f = File(deck.file!!)
        assertTrue(f.path, f.isFile && f.length() == deck.bytes && f.parentFile.name == "hr-decks")
        // the owner's table was read on Sales, the words came from a worker, the deck was assembled here
        val lines = a.plan!!.lines
        assertTrue(lines.toString(), lines[0].startsWith("AGG · Sales") && lines[2].startsWith("RENDER · here"))
        assertTrue(a.provenance.toString(), a.provenance.map { it.node }.containsAll(listOf("Sales", "HR")))
    }

    @Test fun aDeckFromTheNumbersAloneWhenNoPhoneWritesText() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val asked = h.ask(hr, "Generate a pitch deck showing the increase in sales", deckDag(h, sales, withWords = false))
        (asked.outcome as? com.hivecore.core.AskOutcome.Refusal)?.let { throw AssertionError("refused: ${'$'}{it.reasons}") }
        val deck = Deck.fromJson(asked.answer.artifact)!!
        assertEquals("Revenue", deck.title)
        assertEquals(deck.summary, deck.slides[1].caption)
        assertTrue(deck.slides[2].bullets.toString(), deck.slides[2].bullets.first().startsWith("2025: Revenue ₹"))
        assertNull(deck.slides.firstOrNull { it.kind == SlideKind.IMAGE })
    }
}
