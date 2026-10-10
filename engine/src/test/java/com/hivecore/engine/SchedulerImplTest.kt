package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.Profile
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SchedulerImplTest {
    private val s = SchedulerImpl()

    private fun p(id: String, eligible: Boolean = true, big: String = "llm", heavyOk: Boolean = false, tps: Double = 15.0,
                  queue: Int = 0, link: Double? = 1200.0) =
        Profile(id, ts = 1, tokPerSec = tps, eligible = eligible, big = big, heavyOk = heavyOk, queueDepth = queue, linkKbps = link)

    private val profiles = listOf(
        p("hr"), p("sales", tps = 20.0), p("accounts", big = "vlm"), p("spare", heavyOk = true, big = "none"), p("busy", eligible = false),
    )

    private fun extract(id: String) = TaskSpec(id, Op.EXTRACT, buildJsonObject { put("maxTokens", 64) })

    private val dag = Dag("mixed", "p", listOf(
        TaskSpec("a", Op.AGG, buildJsonObject { put("q", "revenue by region") }, owner = "sales"),
        TaskSpec("b", Op.DOC, buildJsonObject { put("q", "leave days") }, owner = "hr"),
        extract("e1"), extract("e2"), extract("e3"), extract("e4"),
        TaskSpec("v", Op.VLM_ASK, buildJsonObject { put("items", 2) }),
        TaskSpec("g", Op.GEN_IMAGE, buildJsonObject { put("items", 3) }),
        TaskSpec("o", Op.OCR, buildJsonObject { put("items", 5) }),
        TaskSpec("j", Op.JOIN, inputs = listOf("a", "b")),
        TaskSpec("k", Op.RANK, buildJsonObject { put("measure", "x") }, listOf("j")),
    ), "k")

    @Test fun ownersArePinnedAndReducesRunOnSelf() {
        val a = s.assign(dag, profiles, "accounts")
        assertEquals("sales", a["a"])
        assertEquals("hr", a["b"])
        assertEquals("accounts", a["j"])
        assertEquals("accounts", a["k"])
        assertEquals(dag.tasks.map { it.id }, a.keys.toList())
    }

    @Test fun classGating() {
        val a = s.assign(dag, profiles, "hr")
        // LLM tasks only on big == llm phones (hr, sales), never on the ineligible one
        for (e in listOf("e1", "e2", "e3", "e4")) assertTrue("$e -> ${a[e]}", a[e] in setOf("hr", "sales"))
        assertEquals("accounts", a["v"])          // the only VLM phone
        assertEquals("spare", a["g"])             // the only heavyOk phone
        assertTrue(a["o"] != "busy")
        assertTrue(a.values.none { it == "busy" })
    }

    @Test fun listSchedulingSpreadsLlmWork() {
        val a = s.assign(dag, profiles, "accounts")
        val used = listOf("e1", "e2", "e3", "e4").map { a.getValue(it) }.toSet()
        assertEquals(setOf("hr", "sales"), used)
    }

    @Test fun noEligiblePhoneFallsBackToSelf() {
        val a = s.assign(dag, listOf(p("hr", eligible = false), p("sales", big = "none")), "hr")
        assertEquals("hr", a["e1"])
        assertEquals("hr", a["v"])
        assertEquals("hr", a["g"])
        assertEquals("sales", a["o"])          // LIGHT: any eligible phone
    }

    @Test fun reproducibleUnderShuffledProfiles() {
        val base = s.assign(dag, profiles, "accounts")
        val rnd = Random(7)
        repeat(20) {
            assertEquals(base, s.assign(dag, profiles.shuffled(rnd), "accounts"))
        }
        // duplicates of a node: the latest ts wins whatever the order
        val stale = p("sales", eligible = false).copy(ts = 0)
        assertEquals(base, s.assign(dag, profiles + stale, "accounts"))
        assertEquals(base, s.assign(dag, listOf(stale) + profiles.reversed(), "accounts"))
    }

    @Test fun tiesGoToTheLowerHash() {
        val twins = listOf(p("x"), p("y"))
        val t = TaskSpec("t1", Op.EXTRACT)
        val picked = s.assign(Dag("d", "p", listOf(t), "t1"), twins, "self").getValue("t1")
        val expected = listOf("x", "y").minByOrNull { Canon.sha256Hex(it + "t1") }
        assertEquals(expected, picked)
    }

    @Test fun fasterAndLessLoadedPhonesWin() {
        val t = Dag("d", "p", listOf(TaskSpec("t", Op.SUMMARIZE)), "t")
        assertEquals("fast", s.assign(t, listOf(p("slow", tps = 5.0), p("fast", tps = 25.0)), "self")["t"])
        assertEquals("idle", s.assign(t, listOf(p("loaded", queue = 3), p("idle")), "self")["t"])
    }

    @Test fun reassignPicksTheNextBest() {
        val t = TaskSpec("e", Op.EXTRACT)
        val first = s.reassign(t, profiles, "accounts", emptySet())
        assertTrue(first in setOf("hr", "sales"))
        val second = s.reassign(t, profiles, "accounts", setOf(first!!))
        assertTrue(second in setOf("hr", "sales") && second != first)
        // every LLM phone failed: the commander takes it; then nobody
        assertEquals("accounts", s.reassign(t, profiles, "accounts", setOf("hr", "sales")))
        assertNull(s.reassign(t, profiles, "accounts", setOf("hr", "sales", "accounts")))
        // owner tasks cannot move; reduces stay on self
        assertNull(s.reassign(dag.tasks[0], profiles, "accounts", setOf("sales")))
        assertEquals("accounts", s.reassign(dag.tasks[9], profiles, "accounts", emptySet()))
    }
}
