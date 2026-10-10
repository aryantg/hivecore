package com.hivecore.node.textai

import com.hivecore.engine.text.Extractive
import com.hivecore.engine.text.SourceUnit
import com.hivecore.engine.text.Sentences
import com.hivecore.core.LlmBackend
import com.hivecore.core.fakes.FakeClosedChooser
import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCoreTest {

    // ------------------------------------------------------------------------------------------------ Sentences

    @Test fun sentenceIdsAreStable() {
        val text = "Mr. Rao opened the meeting at 10.30 am. Revenue was 4.2 crore! Did we hit Q3?\n\n- Priya will send the deck.\n- e.g. the West region"
        val a = Sentences.of("minutes.txt", "¶ 1", text)
        val b = Sentences.of("minutes.txt", "¶ 1", text)
        assertEquals(a, b)
        assertEquals(listOf("Mr. Rao opened the meeting at 10.30 am.", "Revenue was 4.2 crore!", "Did we hit Q3?",
            "Priya will send the deck.", "e.g. the West region"), a.map { it.text })
        assertEquals(listOf("minutes.txt|¶ 1|0", "minutes.txt|¶ 1|1", "minutes.txt|¶ 1|2", "minutes.txt|¶ 1|3", "minutes.txt|¶ 1|4"), a.map { it.id })
    }

    @Test fun splitterKeepsInitialsAndLowercaseContinuations() {
        assertEquals(listOf("J. Smith met Dr. Iyer at 5 p.m. today.", "They agreed."), Sentences.split("J. Smith met Dr. Iyer at 5 p.m. today. They agreed."))
        assertEquals(listOf("It costs approx. 40 rupees."), Sentences.split("It costs approx. 40 rupees."))
    }

    // ----------------------------------------------------------------------------------------------- Extractive

    private val ss = Sentences.of(listOf(
        SourceUnit("m", "¶ 1", "Good morning everyone. We reviewed the Q3 budget. The budget for marketing rises to 12 lakh."),
        SourceUnit("m", "¶ 2", "Priya will send the revised budget by Friday. Thanks all."),
    ))

    @Test fun extractivePicksIdsInDocumentOrder() = runTest {
        val llm = FakeLlmEngine(LlmBackend.NPU).apply { fallback = "4, 2" }
        val e = Extractive(llm).summarize(ss, 2)
        assertTrue(e.ok)
        assertEquals("llm", e.method)
        assertEquals(listOf("m|¶ 1|1", "m|¶ 2|0"), e.ids)
        assertEquals(listOf("m · ¶ 1", "m · ¶ 2"), e.citations)
        assertNull(llm.calls.single().second.grammar)   // never a grammar on the NPU
    }

    @Test fun grammarOnlyOffNpu() = runTest {
        val llm = FakeLlmEngine(LlmBackend.CPU).apply { fallback = "1" }
        Extractive(llm).summarize(ss, 2)
        val g = llm.calls.single().second.grammar!!
        assertTrue(g.startsWith("root ::= id (sep id)?"))
        assertTrue(g.contains("\"5\""))
        assertNull(Extractive.grammar(5, 2, false, LlmBackend.NPU))
    }

    @Test fun invalidIdsAreRejectedNotRepaired() = runTest {
        for (reply in listOf("7", "2, 2", "1, 2, 3", "The second one", "2 and 4", "0", "")) {
            val e = Extractive(FakeLlmEngine().apply { fallback = reply }).summarize(ss, 2)
            assertFalse("'$reply' must be rejected", e.ok)
            assertTrue(e.picks.isEmpty())
        }
        assertEquals(Extractive.Parsed.Ok(emptyList()), Extractive.parse("0", 5, 2, allowNone = true))
        assertEquals(Extractive.Parsed.Ok(listOf(2, 0)), Extractive.parse("[3], [1].", 5, 2, allowNone = false))
    }

    @Test fun fallbackWithoutEngineIsDeterministic() = runTest {
        val a = Extractive(null).summarize(ss, 2)
        val b = Extractive(FakeLlmEngine().apply { failNext = RuntimeException("down") }).summarize(ss, 2)
        assertEquals("centrality", a.method)
        assertEquals(a.ids, b.ids)
        assertEquals(2, a.picks.size)
        assertTrue(a.ids.any { it.startsWith("m|¶ 1|") && it != "m|¶ 1|0" })   // a budget sentence, not the greeting
        val q = Extractive(null).extract("who sends the revised budget", ss, 1)
        assertEquals("bm25", q.method)
        assertEquals(listOf("m|¶ 2|0"), q.ids)
    }

    @Test fun singlePickUsesChooserAndNoneIsValid() = runTest {
        val ch = FakeClosedChooser().apply { answers["deadline"] = 5 }   // index 5 = "none of these"
        val e = Extractive(null, ch).extract("What is the deadline for the audit?", ss, 1)
        assertTrue(e.ok); assertEquals("chooser", e.method); assertTrue(e.picks.isEmpty())
        ch.answers["deadline"] = -1
        assertFalse(Extractive(null, ch).extract("What is the deadline?", ss, 1).ok)
    }

    // ---------------------------------------------------------------------------------------------- GroundCheck

    private val source = "On 10 October 2026 the team met. Priya Sharma reported revenue of 1,250,000 rupees for Q3, " +
        "up 12% on Q2. The \"Project Falcon\" launch moves to November. Three vendors were shortlisted."

    @Test fun groundCheckPassesFaithfulParaphrase() {
        val r = GroundCheck.check("Overall, Q3 revenue was 1250000 rupees, 12% above Q2, as Priya reported on October 10. " +
            "\"Project Falcon\" launches in November; 3 vendors made the shortlist.", source)
        assertTrue(r.violations.toString(), r.ok)
    }

    @Test fun groundCheckCatchesInventedFacts() {
        fun kinds(g: String) = GroundCheck.check(g, source).violations.map { it.kind to it.text }
        assertEquals(listOf("number" to "15"), kinds("Revenue grew 15% in Q3."))
        assertEquals(listOf("name" to "Rahul"), kinds("Rahul reported revenue for Q3."))
        assertEquals(listOf("date" to "12 october 2026"), kinds("The team met on 12 October 2026."))
        assertEquals(listOf("quote" to "project eagle", "name" to "Eagle"), kinds("The \"Project Eagle\" launch moved."))
        assertEquals(listOf("name" to "Q4"), kinds("Revenue for Q4 rose."))
        assertFalse(GroundCheck.check("The launch moves to December.", source).ok)
    }

    // -------------------------------------------------------------------------------------------------- GenText

    private val schema = GenText.Schema.from(Json.parseToJsonElement("""
        {"type":"object","properties":{
          "title":{"type":"string","maxLength":40},
          "category":{"enum":["tools","garden","kitchen"]},
          "price":{"type":"number","minimum":0},
          "stock":{"type":"integer","minimum":0,"maximum":1000},
          "tags":{"type":"array","items":{"type":"string","maxLength":12},"maxItems":3}},
         "required":["title","category","price","stock"]}""") as JsonObject)

    @Test fun genTextAcceptsValidJsonInSchemaOrder() = runTest {
        val llm = FakeLlmEngine(LlmBackend.NPU).apply { fallback = """ {"stock": 4, "price": 12.50, "category": "garden", "title": "Hose reel"} """ }
        val o = GenText(llm).generate("Describe the product", schema).getOrThrow() as GenText.Outcome.Ok
        assertEquals("""{"title":"Hose reel","category":"garden","price":12.5,"stock":4,"tags":null}""", o.value.toString())
        assertNull(llm.calls.single().second.grammar)
        assertNotNull(GenText.grammarFor(schema, LlmBackend.CPU))
    }

    @Test fun genTextRejectsInvalidReplies() {
        val bad = listOf(
            "Here you go: {\"title\":\"x\",\"category\":\"garden\",\"price\":1,\"stock\":1}",
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":1}",                       // missing stock
            "{\"title\":\"x\",\"category\":\"toys\",\"price\":1,\"stock\":1}",             // enum
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":1,\"stock\":1.5}",         // integer
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":-1,\"stock\":1}",          // minimum
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":1,\"stock\":1,\"extra\":1}", // unknown key
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":\"1\",\"stock\":1}",       // string for number
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":1,\"stock\":1,\"tags\":[\"a\",\"b\",\"c\",\"d\"]}",
            "{\"title\":\"x\",\"category\":\"garden\",\"price\":1,\"stock\":1",            // truncated
        )
        for (b in bad) assertTrue(b, schema.parse(b) is GenText.Validation.Bad)
    }
}
