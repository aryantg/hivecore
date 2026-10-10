package com.hivecore.runtime.llm

import com.hivecore.core.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherTest {
    private val tm = TestAssets.templates()

    @Test fun normalisation() {
        assertEquals("average commission in the west region", TemplateMatcher.normalise("  Average   commission, in the WEST region!! "))
        assertEquals("what were the q3 priorities", TemplateMatcher.normalise("What were the Q3 priorities?"))
        assertEquals("revenue per region this year and how many reps per region", TemplateMatcher.normalise("Revenue per region this year, and how many reps per region?"))
        assertEquals("", TemplateMatcher.normalise(" ?!, "))
    }

    @Test fun exactMatchAfterNormalisation() {
        val e = tm.intents("average COMMISSION in the west region.", NodeRole.HR)
        assertNotNull(e); assertEquals("Q2", e!!.id)
        assertEquals("commission", e.set.intents[0].column)
        // a near miss is not a match: templates are exact sentences only
        assertNull(tm.match("average commission in the west", NodeRole.HR))
        assertNull(tm.match("average commission in the west region please", NodeRole.HR))
    }

    @Test fun roleScoping() {
        val q1 = "Revenue per region this year, and how many reps per region?"
        assertEquals("sales", tm.intents(q1, NodeRole.SALES)!!.set.intents[0].table)
        assertEquals("employees", tm.intents(q1, NodeRole.HR)!!.set.intents[0].table)
        assertNull(tm.match(q1, NodeRole.ACCOUNTS))
        assertNull(tm.match("Average commission in the West region", NodeRole.SALES))
        assertEquals("Q5", tm.intents("Total invoiced in October?", NodeRole.ACCOUNTS)!!.id)
        assertNull(tm.match("Total invoiced in October?", NodeRole.HR))
    }

    @Test fun docEntries() {
        val q6 = tm.doc("How many leave days does a new joiner get?", NodeRole.HR)
        assertNotNull(q6); assertEquals("leave_policy.pdf", q6!!.doc); assertEquals("p.2", q6.locator)
        assertNull(tm.intents("How many leave days does a new joiner get?", NodeRole.HR))
        val q7 = tm.doc("What were the Q3 priorities?", NodeRole.SALES)
        assertNotNull(q7); assertEquals("q3_review.pptx", q7!!.doc); assertEquals("slide 3", q7.locator)
        assertNull(tm.match("What were the Q3 priorities?", NodeRole.HR))
    }

    @Test fun q3KeepsTheRefusedFilter() {
        val q3 = tm.intents("Average salary of reps named Rahul", NodeRole.HR)!!
        assertEquals("salary", q3.set.intents[0].column)
        assertEquals("name", q3.set.intents[0].filters[0].column)
    }

    @Test fun assetCoverage() {
        assertEquals(setOf("Q1", "Q2", "Q3", "Q6"), tm.sentences(NodeRole.HR).map { tm.match(it, NodeRole.HR)!!.id }.toSet())
        assertEquals(setOf("Q1", "Q7"), tm.sentences(NodeRole.SALES).map { tm.match(it, NodeRole.SALES)!!.id }.toSet())
        assertEquals(setOf("Q5"), tm.sentences(NodeRole.ACCOUNTS).map { tm.match(it, NodeRole.ACCOUNTS)!!.id }.toSet())
        assertTrue(TemplateMatcher.empty().sentences(NodeRole.HR).isEmpty())
    }
}
