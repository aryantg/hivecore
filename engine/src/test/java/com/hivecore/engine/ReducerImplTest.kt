package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.AggTable
import com.hivecore.core.Op
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerImplTest {
    private val r = ReducerImpl()

    private fun j(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> error("bad $v")
    }

    private fun table(node: String, cols: List<String>, vararg rows: List<Any?>, scanned: Long = 10, held: Long = 100) =
        Canon.encodeTable(AggTable(node, "$node table", cols, rows.map { row -> row.map(::j) }, scanned, held, "sql"))

    private fun run(op: Op, params: JsonObject, vararg ins: JsonElement): AggTable = Canon.decodeTable(r.run(op, params, ins.toList()).getOrThrow())
    private fun runRaw(op: Op, params: JsonObject, vararg ins: JsonElement): JsonElement = r.run(op, params, ins.toList()).getOrThrow()
    private fun rows(t: AggTable): List<List<String>> = t.rows.map { row -> row.map { Canon.canonical(it) } }

    private val revenue = table("sales", listOf("region", "sum_revenue"),
        listOf("West", 600), listOf("East", 500), listOf("North", 300), listOf("South", 450))
    private val reps = table("hr", listOf("region", "n"),
        listOf("East", 5), listOf("West", 4), listOf("North", 0), listOf("Central", 2))

    @Test fun joinOnSharedDimensions() {
        val t = run(Op.JOIN, JsonObject(emptyMap()), revenue, reps)
        assertEquals("swarm", t.node)
        assertEquals(listOf("region", "sales_sum_revenue", "hr_n"), t.columns)
        assertEquals(listOf(listOf("\"East\"", "500", "5"), listOf("\"North\"", "300", "0"), listOf("\"West\"", "600", "4")), rows(t))
        assertEquals(20L, t.rowsScanned)
        assertEquals(200L, t.rowsHeld)
    }

    @Test fun joinWithoutSharedKeyFails() {
        val other = table("acc", listOf("vendor", "sum_total"), listOf("A", 1))
        assertTrue(r.run(Op.JOIN, JsonObject(emptyMap()), listOf(revenue, other)).isFailure)
        assertTrue(r.run(Op.JOIN, JsonObject(emptyMap()), listOf(revenue)).isFailure)
    }

    @Test fun ratioDividesAndNotesDivisionByZeroAndMissingKeys() {
        val p = buildJsonObject { put("key", "region"); put("num", "sum_revenue"); put("den", "n") }
        val out = runRaw(Op.RATIO, p, revenue, reps)
        val t = Canon.decodeTable(out)
        assertEquals(listOf("region", "sum_revenue", "n", "ratio"), t.columns)
        assertEquals(listOf(
            listOf("\"East\"", "500", "5", "100"),
            listOf("\"North\"", "300", "0", "null"),
            listOf("\"West\"", "600", "4", "150"),
        ), rows(t))
        // exact integers stay Long
        assertEquals(JsonPrimitive(100L), t.rows[0][3])
        val notes = Canon.notesOf(out)
        assertTrue(notes.toString(), notes.any { it.contains("North") && it.contains("division by zero") })
        assertTrue(notes.any { it.contains("South") })
        assertTrue(notes.any { it.contains("Central") })
    }

    @Test fun ratioRoundsToFourDecimals() {
        val a = table("a", listOf("region", "sum_x"), listOf("X", 1), listOf("Y", 2))
        val b = table("b", listOf("region", "n"), listOf("X", 3), listOf("Y", 3))
        val t = run(Op.RATIO, buildJsonObject { put("key", "region"); put("num", "sum_x"); put("den", "n") }, a, b)
        assertEquals(JsonPrimitive(0.3333), t.rows[0][3])
        assertEquals(JsonPrimitive(0.6667), t.rows[1][3])
    }

    @Test fun diffAndShare() {
        val p = buildJsonObject { put("key", "region"); put("num", "sum_revenue"); put("den", "n") }
        val d = run(Op.DIFF, p, revenue, reps)
        assertEquals(listOf("\"East\"", "500", "5", "495"), rows(d)[0])
        assertEquals("diff", d.columns.last())
        val s = run(Op.SHARE, p, revenue, reps)
        assertEquals(listOf("\"East\"", "500", "5", "10000"), rows(s)[0])
        val total = run(Op.SHARE, buildJsonObject { put("num", "sum_revenue") }, revenue)
        assertEquals(listOf("region", "sum_revenue", "share_pct"), total.columns)
        // 500 / 1850 * 100 = 27.027027…
        assertEquals(listOf("\"East\"", "500", "27.027"), rows(total)[0])
        val zero = runRaw(Op.SHARE, buildJsonObject { put("num", "n") }, table("z", listOf("region", "n"), listOf("A", 0)))
        assertEquals(JsonNull, Canon.decodeTable(zero).rows[0][2])
        assertTrue(Canon.notesOf(zero).single().contains("division by zero"))
    }

    @Test fun ratioNeedsParams() {
        assertTrue(r.run(Op.RATIO, buildJsonObject { put("key", "region") }, listOf(revenue, reps)).isFailure)
        assertTrue(r.run(Op.RATIO, buildJsonObject { put("num", "nope"); put("den", "n"); put("key", "region") }, listOf(revenue, reps)).isFailure)
    }

    @Test fun trendComputesDeltasPerGroup() {
        val t = table("sales", listOf("region", "month", "sum_revenue"),
            listOf("West", "2026-08", 120), listOf("West", "2026-07", 100), listOf("West", "2026-09", 120),
            listOf("East", "2026-07", 0), listOf("East", "2026-08", 50))
        val out = runRaw(Op.TREND, buildJsonObject { put("measure", "sum_revenue") }, t)
        val tr = Canon.decodeTable(out)
        assertEquals(listOf("region", "month", "value", "delta", "pct", "direction"), tr.columns)
        assertEquals(listOf(
            listOf("\"East\"", "\"2026-07\"", "0", "null", "null", "\"start\""),
            listOf("\"East\"", "\"2026-08\"", "50", "50", "null", "\"up\""),
            listOf("\"West\"", "\"2026-07\"", "100", "null", "null", "\"start\""),
            listOf("\"West\"", "\"2026-08\"", "120", "20", "20", "\"up\""),
            listOf("\"West\"", "\"2026-09\"", "120", "0", "0", "\"flat\""),
        ), rows(tr))
        assertTrue(Canon.notesOf(out).any { it.contains("from 0") })
        val down = run(Op.TREND, buildJsonObject { put("measure", "v") }, table("x", listOf("month", "v"), listOf("2026-01", 3), listOf("2026-02", 2)))
        assertEquals(listOf("\"2026-02\"", "2", "-1", "-33.3333", "\"down\""), rows(down)[1])
    }

    @Test fun trendNeedsAMonthColumn() {
        assertTrue(r.run(Op.TREND, buildJsonObject { put("measure", "sum_revenue") }, listOf(revenue)).isFailure)
    }

    @Test fun rankBreaksTiesByKeyAscending() {
        val t = table("s", listOf("region", "sum_units"), listOf("West", 10), listOf("East", 10), listOf("North", 30), listOf("South", null))
        val desc = run(Op.RANK, buildJsonObject { put("measure", "sum_units"); put("k", 3) }, t)
        assertEquals(listOf("rank", "region", "sum_units"), desc.columns)
        assertEquals(listOf(listOf("1", "\"North\"", "30"), listOf("2", "\"East\"", "10"), listOf("3", "\"West\"", "10")), rows(desc))
        val asc = run(Op.RANK, buildJsonObject { put("measure", "sum_units"); put("order", "asc") }, t)
        assertEquals(listOf("\"East\"", "\"West\"", "\"North\"", "\"South\""), asc.rows.map { Canon.canonical(it[1]) })
        // shuffled input, same output
        val shuffled = table("s", listOf("region", "sum_units"), listOf("East", 10), listOf("South", null), listOf("North", 30), listOf("West", 10))
        assertEquals(rows(desc), rows(run(Op.RANK, buildJsonObject { put("measure", "sum_units"); put("k", 3) }, shuffled)))
    }

    @Test fun thresholdAgainstValueAndMean() {
        val t = table("hr", listOf("region", "avg_commission"), listOf("West", 100), listOf("East", 200), listOf("North", 300))
        val above = run(Op.THRESHOLD, buildJsonObject { put("measure", "avg_commission"); put("op", "gt"); put("reference", "mean") }, t)
        assertEquals(listOf(listOf("\"North\"", "300")), rows(above))
        assertTrue(above.title, above.title.contains("200") && above.title.contains("mean"))
        val gte = run(Op.THRESHOLD, buildJsonObject { put("measure", "avg_commission"); put("op", "gte"); put("value", 200) }, t)
        assertEquals(listOf("\"East\"", "\"North\""), gte.rows.map { Canon.canonical(it[0]) })
        val lt = run(Op.THRESHOLD, buildJsonObject { put("measure", "avg_commission"); put("op", "lt"); put("value", 200) }, t)
        assertEquals(listOf("\"West\""), lt.rows.map { Canon.canonical(it[0]) })
        val lte = run(Op.THRESHOLD, buildJsonObject { put("measure", "avg_commission"); put("op", "lte"); put("value", 200) }, t)
        assertEquals(2, lte.rows.size)
        assertTrue(r.run(Op.THRESHOLD, buildJsonObject { put("measure", "avg_commission"); put("op", "gt") }, listOf(t)).isFailure)
        assertTrue(r.run(Op.THRESHOLD, buildJsonObject { put("measure", "avg_commission"); put("op", "eq"); put("value", 1) }, listOf(t)).isFailure)
    }

    @Test fun reconcileListsMismatchesAndOneSidedKeys() {
        val sales = table("sales", listOf("region", "n"), listOf("West", 15), listOf("East", 16), listOf("North", 15))
        val hr = table("hr", listOf("region", "n"), listOf("West", 15), listOf("East", 15), listOf("South", 16))
        val t = run(Op.RECONCILE, buildJsonObject { put("key", "region") }, sales, hr)
        assertEquals(listOf("region", "a_n", "b_n", "diff"), t.columns)
        assertEquals(listOf(
            listOf("\"East\"", "16", "15", "1"),
            listOf("\"North\"", "15", "null", "null"),
            listOf("\"South\"", "null", "16", "null"),
        ), rows(t))
        val tol = run(Op.RECONCILE, buildJsonObject { put("key", "region"); put("tol", 1) }, sales, hr)
        assertEquals(2, tol.rows.size)
    }

    @Test fun emptyTablesGiveEmptyResults() {
        val empty = table("sales", listOf("region", "sum_revenue"))
        val emptyHr = table("hr", listOf("region", "n"))
        val p = buildJsonObject { put("key", "region"); put("num", "sum_revenue"); put("den", "n") }
        assertTrue(run(Op.JOIN, JsonObject(emptyMap()), empty, emptyHr).rows.isEmpty())
        assertTrue(run(Op.RATIO, p, empty, emptyHr).rows.isEmpty())
        assertTrue(run(Op.RANK, buildJsonObject { put("measure", "sum_revenue") }, empty).rows.isEmpty())
        val th = runRaw(Op.THRESHOLD, buildJsonObject { put("measure", "sum_revenue"); put("op", "gt"); put("reference", "mean") }, empty)
        assertTrue(Canon.decodeTable(th).rows.isEmpty())
        assertTrue(run(Op.TREND, buildJsonObject { put("measure", "v") }, table("x", listOf("month", "v"))).rows.isEmpty())
        assertTrue(run(Op.RECONCILE, buildJsonObject { put("key", "region") }, empty, emptyHr).rows.isEmpty())
        assertTrue(run(Op.SHARE, buildJsonObject { put("num", "sum_revenue") }, empty).rows.isEmpty())
    }

    @Test fun outputsChainAndAreDeterministic() {
        val p = buildJsonObject { put("key", "region"); put("num", "sum_revenue"); put("den", "n") }
        val ratio = runRaw(Op.RATIO, p, revenue, reps)
        val ranked = run(Op.RANK, buildJsonObject { put("measure", "ratio"); put("k", 1) }, ratio)
        assertEquals("\"West\"", Canon.canonical(ranked.rows[0][1]))
        assertEquals(Canon.hash(ratio), Canon.hash(runRaw(Op.RATIO, p, revenue, reps)))
    }

    @Test fun nonReduceOpsAndBadInputsFail() {
        assertTrue(r.run(Op.AGG, JsonObject(emptyMap()), listOf(revenue)).isFailure)
        assertTrue(r.run(Op.RANK, buildJsonObject { put("measure", "x") }, listOf(JsonArray(emptyList()))).isFailure)
    }
}
