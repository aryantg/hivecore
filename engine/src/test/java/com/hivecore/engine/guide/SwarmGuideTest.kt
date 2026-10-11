package com.hivecore.engine.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide: questions about the app get an answer from the facts; requests go through to the planner. */
class SwarmGuideTest {
    private val facts = SwarmFacts(
        phones = listOf(
            PhoneFacts("Accounts", listOf("financials", "receivables"), self = true),
            PhoneFacts("Sales", listOf("sales_monthly", "stockists"), listOf("kavira_company_snapshot.md")),
            PhoneFacts("HR", listOf("staff", "compensation"), listOf("onboarding_checklist.docx")),
        ),
        canDraw = true,
        patterns = mapOf("per_unit" to "a measure divided by a count", "deck_growth" to "a slide deck", "make_image" to "an illustration"),
        recipes = listOf("Letters to 48 stockists", "Read 120 invoice photos"),
        examples = listOf("Generate a pitch deck showing the increase in sales FY2024-26", "Sales per rep by branch in FY2026"),
    )

    @Test fun whatCanYouCreateAnImageOfIsAnsweredFromTheFacts() {
        val a = SwarmGuide.answer("what can you create an image off", facts)
        assertNotNull(a)
        assertTrue(a, a!!.contains("Stable Diffusion") && a.contains("512") && a.contains("Create an image of"))
        assertTrue("it says what it cannot do", a.contains("real people"))
        assertTrue("it points charts at the deck", a.contains("pitch deck"))
    }

    @Test fun withoutAnImageModelTheAnswerSaysSo() {
        val a = SwarmGuide.answer("What kind of images can you make?", facts.copy(canDraw = false))!!
        assertTrue(a, a.startsWith("Not right now"))
        assertTrue(a.contains("Models page"))
    }

    @Test fun requestsAreNotIntercepted() {
        assertNull(SwarmGuide.answer("create an image of my team", facts))
        assertNull(SwarmGuide.answer("Draw a poster for the sales kickoff", facts))
        assertNull(SwarmGuide.answer("can you draw a pharmacy counter at dawn", facts))
        assertNull(SwarmGuide.answer("Generate a pitch deck showing the increase in sales FY2024-26", facts))
        assertNull(SwarmGuide.answer("Sales per rep by branch in FY2026", facts))
        assertNull(SwarmGuide.answer("What is the highest salary in the company?", facts))
        assertNull(SwarmGuide.answer("How did sales change month on month?", facts))
        assertNull(SwarmGuide.answer("What is the weather like?", facts))
    }

    @Test fun aBareAbilityQuestionIsAnswered() {
        assertNotNull(SwarmGuide.answer("can you create images", facts))
        assertNotNull(SwarmGuide.answer("Can you make a deck?", facts))
        assertNotNull(SwarmGuide.answer("can you draw", facts))
        assertNull("a subject makes it a request", SwarmGuide.answer("can you draw a dog", facts))
    }

    @Test fun picturesOfChartsOfTheDataPointAtTheDeck() {
        val a = SwarmGuide.answer("generate images of what the charts would look like for the data", facts)!!
        assertTrue(a, a.contains("real figures") && a.contains("pitch deck"))
        assertTrue("it quotes the deck example", a.contains("Generate a pitch deck showing the increase in sales FY2024-26"))
        assertTrue("and offers the decorative picture", a.contains("Draw an abstract rising chart"))
        assertNull("the decorative picture itself is a request", SwarmGuide.answer("Draw an abstract rising chart in amber and slate", facts))
    }

    @Test fun overviewNamesThePhonesTablesAndAbilities() {
        val a = SwarmGuide.answer("what can you do", facts)!!
        assertTrue(a, a.contains("3 phones") && a.contains("Accounts (this phone)") && a.contains("Sales") && a.contains("HR"))
        assertTrue(a.contains("sales monthly"))
        assertTrue(a.contains("sales per rep"))
        assertTrue(a.contains("build a pitch deck") && a.contains("draw an illustration"))
        assertTrue(a.contains("Try: \"Generate a pitch deck"))
        assertEquals(a, SwarmGuide.answer("Help", facts))
    }

    @Test fun dataPhonesDecksAndJobsHaveTheirOwnAnswers() {
        val data = SwarmGuide.answer("what data do you have?", facts)!!
        assertTrue(data, data.contains("Sales: tables sales monthly and stockists and documents kavira company snapshot"))
        val phones = SwarmGuide.answer("which phones are in the swarm", facts)!!
        assertTrue(phones, phones.startsWith("The swarm is 3 phones"))
        val deck = SwarmGuide.answer("what kind of presentation can you make", facts)!!
        assertTrue(deck, deck.contains(".pptx") && deck.contains("never from a model"))
        val jobs = SwarmGuide.answer("what jobs can you run", facts)!!
        assertTrue(jobs, jobs.contains("Letters to 48 stockists"))
    }

    @Test fun theHintListsTheTablesAndTheExtras() {
        val h = SwarmGuide.hint(facts)
        assertTrue(h, h.startsWith("Ask about the data on your phones (financials, receivables, sales monthly"))
        assertTrue(h.contains("ask for a pitch deck or ask for an illustration"))
        assertFalse(SwarmGuide.hint(facts.copy(canDraw = false)).contains("illustration"))
    }
}
