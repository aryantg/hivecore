package com.hivecore.node

import com.hivecore.engine.ReducerImpl
import com.hivecore.planner.PlanCompilerImpl
import com.hivecore.planner.PlannerImpl
import com.hivecore.planner.SwarmRouterImpl
import com.hivecore.core.Canon
import com.hivecore.core.AggTable
import com.hivecore.core.Intent
import com.hivecore.core.IntentSet
import com.hivecore.core.Op
import com.hivecore.core.fakes.FakeClosedChooser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset

/**
 * The ungrouped per_unit question (questions_50: "Revenue per rep this year"): a keyless RATIO of one figure per owner,
 * planned by the real PlannerImpl over the real patterns, validated by PlanCompilerImpl and run on the 3-phone mesh.
 * Seed: Sales revenue 2026 = 21,38,98,000; HR headcount = 61 → 35,06,524.59 per rep.
 */
class KeylessRatioTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    @Test fun reducerDividesOneFigureByAnother() {
        val a = AggTable("Sales", "revenue", listOf("n", "sum_revenue"), listOf(listOf(JsonPrimitive(10), JsonPrimitive(213898000))), 10, 10, "sql")
        val b = AggTable("HR", "reps", listOf("n"), listOf(listOf(JsonPrimitive(61))), 61, 61, "sql")
        val out = ReducerImpl().run(Op.RATIO, buildJsonObject { put("num", "sum_revenue"); put("den", "n") },
            listOf(Canon.encodeTable(a), Canon.encodeTable(b))).getOrThrow()
        val t = Canon.decodeTable(out)
        assertEquals(listOf("sum_revenue", "n", "ratio"), t.columns)
        assertEquals(1, t.rows.size)
        assertEquals(BigDecimal("3506524.59"), BigDecimal(t.rows[0][2].jsonPrimitive.content).setScale(2, RoundingMode.HALF_UP))
        assertEquals("sum_revenue per n", t.title)
        // a grouped table on one side is still an error (no key column in common)
        val grouped = AggTable("HR", "reps", listOf("region", "n"), listOf(listOf(JsonPrimitive("East"), JsonPrimitive(20))), 61, 61, "sql")
        assertTrue(ReducerImpl().run(Op.RATIO, buildJsonObject { put("num", "sum_revenue"); put("den", "n") },
            listOf(Canon.encodeTable(a), Canon.encodeTable(grouped))).isFailure)
    }

    @Test fun revenuePerRepThisYearOnTheSeed() = runTest {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val q = "Revenue per rep this year"
        val catalogs = h.catalogs()
        val route = SwarmRouterImpl().route(q, sales.nodeId, catalogs)
        assertTrue(route.reason, route.composite)
        val planned = PlannerImpl(FakeClosedChooser(), PlannerTestCatalogs.patterns(), PlannerTestCatalogs.CLOCK, ZoneOffset.UTC)
            .plan(q, route, catalogs).getOrThrow()
        val dag = planned.dag
        assertEquals("per_unit", dag.patternId)
        val (a, b, r) = dag.tasks
        assertEquals(sales.nodeId, a.owner); assertEquals(hr.nodeId, b.owner)
        assertEquals(emptyList<String>(), (a.params["expect"] as JsonObject)["dims"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("sum_revenue"), r.params["num"]); assertEquals(JsonPrimitive("n"), r.params["den"])
        assertTrue("key" !in r.params)
        val check = PlanCompilerImpl().validate(dag, catalogs, "m1")
        assertTrue(check.errors.toString(), check.ok)

        // the HR sub-question in the fake planner's words
        hr.intentPlanner.canned[b.params["q"]!!.jsonPrimitive.content] = IntentSet(listOf(Intent("employees", "count")))
        val answer = h.ask(sales, q, dag).answer
        val t = answer.joined!!
        assertEquals(listOf("sum_revenue", "n", "ratio"), t.columns)
        assertEquals(1, t.rows.size)
        assertEquals(BigDecimal("213898000"), BigDecimal(t.rows[0][0].jsonPrimitive.content).stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
        assertEquals(61L, t.rows[0][1].jsonPrimitive.content.toBigDecimal().toLong())
        assertEquals(BigDecimal("3506524.59"), BigDecimal(t.rows[0][2].jsonPrimitive.content).setScale(2, RoundingMode.HALF_UP))
        assertTrue(answer.text, answer.text.contains("35,06,524.59"))
    }

}
