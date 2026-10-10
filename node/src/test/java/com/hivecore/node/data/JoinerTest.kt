package com.hivecore.node.data

import com.hivecore.core.AggTable
import com.hivecore.core.Compiled
import com.hivecore.core.Filter
import com.hivecore.core.Intent
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeOutcome
import com.hivecore.core.NodeRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinerTest {
    private val compiler = RuleIntentCompiler()

    private fun answer(store: DataStore, intent: Intent, node: String): AggTable {
        val schema = store.schema()
        val q = compiler.compile(intent, schema) as Compiled.Query
        return (SqlQueryExecutor(store.db).run(q, schema.tables.first { it.name == q.intent.table }, node) as NodeOutcome.Tables).tables.single()
    }

    @Test fun q1JoinsSalesRevenueWithHrHeadcountOnRegion() {
        val salesStore = Fixtures.store(NodeRole.SALES)
        val hrStore = Fixtures.store(NodeRole.HR)
        val sales = answer(salesStore, Intent("sales", "sum", "revenue", listOf("region"), listOf(Filter("month", "gte", JsonPrimitive("2026-01")), Filter("month", "lte", JsonPrimitive("2026-12")))), "Sales")
        val hr = answer(hrStore, Intent("employees", "count", groupBy = listOf("region")), "HR")

        val askerDb = JdbcSqlDb.inMemory()   // the asker joins on its own db; no user tables needed
        val joined = SqlJoiner(askerDb).join(listOf(sales, hr))!!
        assertEquals("joined", joined.node)
        assertEquals("Sales + HR", joined.title)
        assertEquals(listOf("region", "sales_n", "sales_sum_revenue", "hr_n"), joined.columns)
        assertEquals(listOf("East", "North", "South", "West"), joined.rows.map { it[0].jsonPrimitive.content })
        val west = joined.rows.first { it[0].jsonPrimitive.content == "West" }
        assertEquals(238L, west[1].jsonPrimitive.long)
        assertEquals(Fixtures.expectedLong("q1_revenue_by_region_2026_before_q4", "West", "revenue"), west[2].jsonPrimitive.long)
        assertEquals(15L, west[3].jsonPrimitive.long)
        assertEquals(1000L + 61L, joined.rowsScanned)
        assertEquals(1265L, joined.rowsHeld)   // the "0 of 1,265" counter
        assertEquals("sql", joined.source)
        // Temp tables are gone.
        assertTrue(askerDb.query("SELECT name FROM sqlite_temp_master WHERE type = 'table'").isEmpty())
        // The join can run again on the same db.
        assertEquals(4, SqlJoiner(askerDb).join(listOf(hr, sales))!!.rows.size)
        assertEquals(listOf("region", "hr_n", "sales_n", "sales_sum_revenue"), SqlJoiner(askerDb).join(listOf(hr, sales))!!.columns)
    }

    @Test fun firstTablePerNodeAndNodesWithoutSharedColumnsAreLeftOut() {
        val db = JdbcSqlDb.inMemory()
        val a = AggTable("Sales", "Revenue by region", listOf("region", "n", "sum_revenue"), listOf(row("West", 10, 500), row("East", 20, 700)), 30, 100, "sql")
        val a2 = AggTable("Sales", "Units by region", listOf("region", "n", "sum_units"), listOf(row("West", 10, 5)), 10, 100, "sql")
        val b = AggTable("HR", "Employees by region", listOf("region", "n"), listOf(row("West", 3), row("East", 4)), 7, 7, "sql")
        val c = AggTable("Accounts", "Total by status", listOf("status", "n", "sum_total"), listOf(row("PAID", 5, 9)), 5, 40, "rollup")
        val j = SqlJoiner(db).join(listOf(a, a2, b, c))!!
        assertEquals(listOf("region", "sales_n", "sales_sum_revenue", "hr_n"), j.columns)   // a2 ignored (second Sales table), c has no shared column
        assertEquals(2, j.rows.size)
        assertEquals("Sales + HR", j.title)
        assertNull(SqlJoiner(db).join(listOf(a)))
        assertNull(SqlJoiner(db).join(listOf(a, c)))
        assertNull(SqlJoiner(db).join(emptyList()))
        // Two-column keys join on both.
        val m1 = AggTable("Sales", "t", listOf("region", "month", "n", "sum_revenue"), listOf(row("West", "2026-01", 6, 100), row("West", "2026-02", 7, 200)), 13, 13, "sql")
        val m2 = AggTable("Accounts", "t", listOf("month", "region", "n"), listOf(row("2026-02", "West", 9)), 9, 9, "sql")
        val mj = SqlJoiner(db).join(listOf(m1, m2))!!
        assertEquals(1, mj.rows.size)
        assertEquals(setOf("region", "month"), mj.columns.filterNot { it.contains("_") }.toSet())
        assertEquals("rollup", SqlJoiner(db).join(listOf(a, b.copy(source = "rollup")))!!.source)
    }

    /** The phone's pooled connections: a statement outside a transaction may run on another connection. */
    private class TxOnlyDb(private val inner: com.hivecore.core.SqlDb) : com.hivecore.core.SqlDb {
        private var depth = 0
        override fun exec(sql: String, args: List<Any?>) { check(depth > 0) { "outside a transaction: $sql" }; inner.exec(sql, args) }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> { check(depth > 0) { "outside a transaction: $sql" }; return inner.query(sql, args) }
        override fun <T> tx(block: () -> T): T { depth++; try { return inner.tx(block) } finally { depth-- } }
    }

    @Test fun theWholeJoinRunsInOneTransactionSoItsTempTablesStayOnOneConnection() {
        val a = AggTable("Sales", "Revenue by region", listOf("region", "n", "sum_revenue"), listOf(row("West", 10, 500), row("East", 20, 700)), 30, 100, "sql")
        val b = AggTable("HR", "Employees by region", listOf("region", "n"), listOf(row("West", 3), row("East", 4)), 7, 7, "sql")
        val j = SqlJoiner(TxOnlyDb(JdbcSqlDb.inMemory())).join(listOf(a, b))
        assertEquals(2, j!!.rows.size)
    }

    private fun row(vararg v: Any): List<kotlinx.serialization.json.JsonElement> = v.map { when (it) { is Int -> JsonPrimitive(it.toLong()); is Long -> JsonPrimitive(it); else -> JsonPrimitive(it.toString()) } }
}
