package com.hivecore.engine.chart

import com.hivecore.core.AggTable
import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAssemblerTest {
    private fun row(vararg v: Any): List<JsonElement> = v.map { if (it is String) JsonPrimitive(it) else JsonPrimitive(it as Number) }

    private val byBranch = AggTable("Sales", "net sales by branch FY2026", listOf("branch", "n", "sum_net_sales"),
        listOf(row("Bengaluru", 48, 6_370_000), row("Chennai", 38, 5_760_000), row("Kochi", 24, 2_940_000)), 1755, 4770, "sql")
    private val byMonth = AggTable("Sales", "net sales by month", listOf("month", "n", "sum_net_sales"),
        (1..12).map { m -> row("2025-%02d".format(m), 10, 3_000_000 + m * 100_000) }, 120, 4770, "sql")

    @Test fun groupsBecomeBarsWithIndianScaleLabelsAndASummary() {
        val spec = TaskSpec("c", Op.RENDER, buildJsonObject { put("kind", "chart") }, inputs = listOf("a"))
        assertTrue(ChartAssembler.isChartStep(spec))
        val c = ChartAssembler.assemble(spec, listOf(TaskSpec("a", Op.AGG) to Canon.encodeTable(byBranch)))
        assertEquals("bar", c.style)
        assertEquals(listOf("Bengaluru", "Chennai", "Kochi"), c.chart.categories)
        assertEquals(listOf(6_370_000.0, 5_760_000.0, 2_940_000.0), c.chart.values)
        assertEquals(listOf("₹63.7 L", "₹57.6 L", "₹29.4 L"), c.chart.labels)
        assertEquals("sum_net_sales", c.measure); assertEquals(listOf("branch"), c.dims)
        assertEquals("Net sales by branch: 3 groups; highest Bengaluru at ₹63.7 L, lowest Kochi at ₹29.4 L.", c.summary)
        val json = c.toJson()
        assertTrue(ChartArtifact.isChart(json)); assertEquals(c, ChartArtifact.fromJson(json))
    }

    @Test fun monthsBecomeALineWithDeltasAndTheChangeOverThePeriod() {
        val c = ChartAssembler.of(byMonth)
        assertEquals("line", c.style)
        assertEquals(12, c.chart.values.size)
        assertEquals(null, c.chart.deltas[0]); assertTrue(c.chart.deltas[1]!!.startsWith("+"))
        assertTrue(c.summary, c.summary.startsWith("Net sales by month: ₹31 L in 2025-01 to ₹42 L in 2025-12, +35 % over the period"))
        assertEquals("bar", ChartAssembler.of(byMonth, style = "bar").style)
    }

    @Test fun aNamedMeasureAndTooManyRowsAreHandled() {
        val wide = AggTable("Sales", "units and net sales by product", listOf("product", "sum_units", "sum_net_sales"),
            (1..50).map { row("P$it", it * 10, it * 1000) }, 50, 50, "sql")
        val c = ChartAssembler.of(wide, measure = "units")
        assertEquals("sum_units", c.measure)
        assertEquals(ChartAssembler.MAX_POINTS, c.chart.values.size)
        assertEquals(50 - ChartAssembler.MAX_POINTS, c.dropped)
        assertEquals(listOf(10.0, 20.0, 30.0), c.chart.values.take(3))
    }
}
