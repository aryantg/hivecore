package com.hivecore.node.data

import com.hivecore.core.AggTable
import com.hivecore.core.AnswerChip
import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.Compiled
import com.hivecore.core.Filter
import com.hivecore.core.Hashing
import com.hivecore.core.Intent
import com.hivecore.core.NodeOutcome
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import com.hivecore.core.Schema
import com.hivecore.core.TableDef
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryExecutorTest {
    private val compiler = RuleIntentCompiler()

    private fun year2026() = listOf(Filter("month", "gte", JsonPrimitive("2026-01")), Filter("month", "lte", JsonPrimitive("2026-12")))
    private val q1Sales = Intent("sales", "sum", "revenue", listOf("region"), year2026())
    private val q1Hr = Intent("employees", "count", groupBy = listOf("region"))

    private fun run(store: DataStore, intent: Intent, node: String, chip: AnswerChip = AnswerChip.LLM): NodeOutcome {
        val schema = store.schema()
        val q = compiler.compile(intent, schema) as Compiled.Query
        val table = schema.tables.first { it.name == q.intent.table }
        return SqlQueryExecutor(store.db) { chip }.run(q, table, node)
    }

    private fun table(o: NodeOutcome): AggTable = (o as NodeOutcome.Tables).tables.single()

    private fun groups(t: AggTable, measure: String?): Map<String, Pair<Long, Long>> {
        val g = t.columns.indexOf(t.columns.first { !SqlJoiner.isMeasure(it) })
        val n = t.columns.indexOf("n")
        val m = measure?.let { t.columns.indexOf(it) } ?: -1
        return t.rows.associate { r -> r[g].jsonPrimitive.content to ((if (m >= 0) r[m].jsonPrimitive.long else r[n].jsonPrimitive.long) to r[n].jsonPrimitive.long) }
    }

    @Test fun q1RevenueByRegion2026BeforeAndAfterQ4() {
        val store = Fixtures.store(NodeRole.SALES)
        val before = table(run(store, q1Sales, "Sales"))
        assertEquals("Revenue by region, 2026", before.title)
        assertEquals(listOf("region", "n", "sum_revenue"), before.columns)
        assertEquals("Sales", before.node)
        assertEquals("sql", before.source)
        assertEquals(Fixtures.expectedGroups("q1_revenue_by_region_2026_before_q4", "revenue"), groups(before, "sum_revenue"))
        assertEquals(1000L, before.rowsScanned)
        assertEquals(1204L, before.rowsHeld)
        assertEquals(Fixtures.expectedLong("q1_total_revenue_2026_before_q4"), before.rows.sumOf { it[2].jsonPrimitive.long })

        val q4 = XlsxReader.read(Fixtures.file("q4_sales.xlsx"))
        store.importTable("q4_sales", q4.header, q4.rows, "q4_sales.xlsx", seed = false)
        val after = table(run(store, q1Sales, "Sales"))
        assertEquals(Fixtures.expectedGroups("q1_revenue_by_region_2026_after_q4", "revenue"), groups(after, "sum_revenue"))
        assertEquals(2380L, after.rowsScanned)
        assertEquals(2584L, after.rowsHeld)
        assertEquals(Fixtures.expectedLong("q1_total_revenue_2026_after_q4"), after.rows.sumOf { it[2].jsonPrimitive.long })

        // Other expected sections.
        val units = table(run(store, Intent("sales", "sum", "units", listOf("region"), year2026()), "Sales"))
        store.resetDemo()
        val unitsBefore = table(run(store, Intent("sales", "sum", "units", listOf("region"), year2026()), "Sales"))
        assertEquals(Fixtures.expectedGroups("units_by_region_2026_before_q4", "units"), groups(unitsBefore, "sum_units"))
        assertTrue(units.rows.sumOf { it[2].jsonPrimitive.long } > unitsBefore.rows.sumOf { it[2].jsonPrimitive.long })
        val byMonth = table(run(store, Intent("sales", "sum", "revenue", listOf("month")), "Sales"))
        assertEquals(Fixtures.expectedGroups("revenue_by_month_all", "revenue"), groups(byMonth, "sum_revenue"))
        val lastQ = table(run(store, Intent("sales", "sum", "revenue", listOf("region"), listOf(Filter("month", "gte", JsonPrimitive("2026-07")), Filter("month", "lte", JsonPrimitive("2026-09")))), "Sales"))
        assertEquals(Fixtures.expectedGroups("revenue_by_region_last_quarter_2026_07_09", "revenue"), groups(lastQ, "sum_revenue"))
    }

    @Test fun hrCountByRegionAndQ2AverageCommissionWest() {
        val store = Fixtures.store(NodeRole.HR)
        val count = table(run(store, q1Hr, "HR"))
        assertEquals("Employees by region", count.title)
        assertEquals(listOf("region", "n"), count.columns)
        val expectedCounts = Fixtures.expected["employees_by_region"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }
        assertEquals(expectedCounts, groups(count, null).mapValues { it.value.second })
        assertEquals(61L, count.rowsScanned)
        assertEquals(61L, count.rowsHeld)

        val q2 = run(store, Intent("employees", "avg", "commission", filters = listOf(Filter("region", "eq", JsonPrimitive("West")))), "HR", AnswerChip.TEMPLATE)
        val t = table(q2)
        assertEquals(AnswerChip.TEMPLATE, (q2 as NodeOutcome.Tables).chip)
        assertEquals("Average commission in West", t.title)
        assertEquals(listOf("n", "avg_commission"), t.columns)
        assertEquals(1, t.rows.size)
        assertEquals(15L, t.rows[0][0].jsonPrimitive.long)
        assertEquals(85760.0, t.rows[0][1].jsonPrimitive.double, 0.0)
        assertEquals(15L, t.rowsScanned)
        val sig = (compiler.compile(Intent("employees", "avg", "commission", filters = listOf(Filter("region", "eq", JsonPrimitive("West")))), store.schema()) as Compiled.Query).signature
        assertEquals(Hashing.sha256Hex(sig), q2.intentHash)

        val avgByRegion = table(run(store, Intent("employees", "avg", "salary", listOf("region")), "HR"))
        val west = avgByRegion.rows.first { it[0].jsonPrimitive.content == "West" }
        assertEquals(1179933.3333333333, west[2].jsonPrimitive.double, 1e-6)
    }

    @Test fun q5OctoberInvoicesAndTheFloorRefusal() {
        val store = Fixtures.store(NodeRole.ACCOUNTS)
        val q5 = table(run(store, Intent("invoices", "sum", "total", filters = listOf(Filter("month", "eq", JsonPrimitive("2026-10")))), "Accounts"))
        assertEquals("Total, October 2026", q5.title)
        assertEquals(10L, q5.rows[0][0].jsonPrimitive.long)
        assertEquals(Fixtures.expectedLong("q5_october_invoices_typed", "sum"), q5.rows[0][1].jsonPrimitive.long)
        assertEquals(10L, q5.rowsScanned)
        assertEquals(40L, q5.rowsHeld)

        // "How many invoices are due": DUE has 4 rows -> under the floor.
        val due = run(store, Intent("invoices", "count", filters = listOf(Filter("status", "eq", JsonPrimitive("DUE")))), "Accounts")
        assertEquals(NodeOutcome.Refused("every group has fewer than 5 rows"), due)
        // Grouped: DUE is hidden, PAID and UNPAID show.
        val byStatus = table(run(store, Intent("invoices", "sum", "total", listOf("status")), "Accounts"))
        assertEquals(listOf("PAID", "UNPAID"), byStatus.rows.map { it[0].jsonPrimitive.content })
        val shown = Fixtures.expectedGroups("invoices_by_status", "total").filterKeys { it != "DUE" }
        assertEquals(shown, groups(byStatus, "sum_total"))
        // rowsScanned is the sum of the groups returned: the 4 hidden DUE rows are not revealed by a 40.
        assertEquals(shown.values.sumOf { it.second }, byStatus.rowsScanned)
        assertEquals(36L, byStatus.rowsScanned)
    }

    @Test fun zeroRowsAndUnderTheFloorAreIndistinguishable() {
        // Membership test: "invoices from vendor X" answering "no rows" vs "fewer than 5" reveals that X exists.
        val store = Fixtures.store(NodeRole.ACCOUNTS)
        val none = run(store, Intent("invoices", "count", filters = listOf(Filter("status", "eq", JsonPrimitive("VOID")))), "Accounts")
        val few = run(store, Intent("invoices", "count", filters = listOf(Filter("status", "eq", JsonPrimitive("DUE")))), "Accounts")
        assertEquals(NodeOutcome.Refused("every group has fewer than 5 rows"), none)
        assertEquals(few, none)
        val sales = run(Fixtures.store(NodeRole.SALES), Intent("sales", "sum", "revenue", listOf("region"), listOf(Filter("region", "eq", JsonPrimitive("Mars")))), "Sales")
        assertEquals(NodeOutcome.Refused("every group has fewer than 5 rows"), sales)
        // On a sensitive table the reason says people.
        val hr = run(Fixtures.store(NodeRole.HR), Intent("employees", "count", filters = listOf(Filter("region", "eq", JsonPrimitive("Mars")))), "HR")
        assertEquals(NodeOutcome.Refused("every group has fewer than 5 people"), hr)
    }

    @Test fun perPersonFloorHidesAGroupOfTwoPeopleWithTenRows() {
        // Two people with five rows each: a row floor would publish their average bonus; the person floor does not.
        val db = JdbcSqlDb.inMemory()
        db.exec("CREATE TABLE t_payroll (person TEXT, region TEXT, bonus INTEGER, _src INTEGER)")
        for (p in listOf("Asha", "Ravi")) repeat(5) { i -> db.exec("INSERT INTO t_payroll VALUES (?, 'West', ?, 1)", listOf(p, 1000L * (i + 1))) }
        for (p in listOf("A", "B", "C", "D", "E")) db.exec("INSERT INTO t_payroll VALUES (?, 'South', 500, 1)", listOf(p))
        val table = TableDef("payroll", listOf(
            ColumnDef("person", ColType.TEXT, identifier = true),
            ColumnDef("region", ColType.TEXT, groupable = true),
            ColumnDef("bonus", ColType.INTEGER, confidential = true)), 15)
        val schema = Schema(NodeRole.HR, listOf(table), emptyList())
        fun exec(i: Intent) = SqlQueryExecutor(db).run(compiler.compile(i, schema) as Compiled.Query, table, "HR")
        assertEquals(NodeOutcome.Refused("every group has fewer than 5 people"),
            exec(Intent("payroll", "avg", "bonus", filters = listOf(Filter("region", "eq", JsonPrimitive("West"))))))
        val byRegion = table(exec(Intent("payroll", "avg", "bonus", listOf("region"))))
        assertEquals(listOf("South"), byRegion.rows.map { it[0].jsonPrimitive.content })
        assertEquals(5L, byRegion.rowsScanned)
        db.close()
    }

    @Test fun rollupShortCircuitReproducesTheSqlNumbers() {
        val store = Fixtures.store(NodeRole.SALES)
        val db = store.db
        db.exec("CREATE TABLE _r_sales_region_month AS SELECT region, month, SUM(revenue) AS revenue, SUM(units) AS units, COUNT(*) AS n FROM t_sales GROUP BY region, month")
        db.exec("INSERT INTO _rollups (name, tbl, signature, builtAt) VALUES (?, ?, ?, ?)",
            listOf("_r_sales_region_month", "sales", SqlQueryExecutor.rollupSignature("sales", listOf("region", "month")), 1L))
        assertEquals(listOf("month", "region"), SqlQueryExecutor.parseSignatureGroups(SqlQueryExecutor.rollupSignature("sales", listOf("region", "month")), "sales"))

        val q1 = table(run(store, q1Sales, "Sales"))
        assertEquals("rollup", q1.source)
        assertEquals(Fixtures.expectedGroups("q1_revenue_by_region_2026_before_q4", "revenue"), groups(q1, "sum_revenue"))
        assertEquals(1000L, q1.rowsScanned)

        val byMonth = table(run(store, Intent("sales", "sum", "revenue", listOf("month")), "Sales"))
        assertEquals("rollup", byMonth.source)
        assertEquals(Fixtures.expectedGroups("revenue_by_month_all", "revenue"), groups(byMonth, "sum_revenue"))

        val avg = table(run(store, Intent("sales", "avg", "revenue", filters = listOf(Filter("region", "eq", JsonPrimitive("East")))), "Sales"))
        assertEquals("sql", avg.source)   // a region filter is not a month range
        assertEquals(205955.12820512822, avg.rows[0][1].jsonPrimitive.double, 1e-6)
        val avgAll = table(run(store, Intent("sales", "avg", "units", listOf("region"), year2026()), "Sales"))
        assertEquals("rollup", avgAll.source)
        // Force the SQL path by dropping the rollup registration and compare the numbers.
        db.exec("DELETE FROM _rollups")
        val sqlAvg = table(run(store, Intent("sales", "avg", "units", listOf("region"), year2026()), "Sales"))
        assertEquals("sql", sqlAvg.source)
        avgAll.rows.zip(sqlAvg.rows).forEach { (a, b) -> assertEquals(b[2].jsonPrimitive.double, a[2].jsonPrimitive.double, 1e-9); assertEquals(b[1], a[1]) }

        db.exec("INSERT INTO _rollups (name, tbl, signature, builtAt) VALUES (?, ?, ?, ?)",
            listOf("_r_sales_region_month", "sales", SqlQueryExecutor.rollupSignature("sales", listOf("region", "month")), 1L))
        val max = table(run(store, Intent("sales", "max", "revenue", listOf("region")), "Sales"))
        assertEquals("sql", max.source)   // min/max cannot come from sums
    }
}
