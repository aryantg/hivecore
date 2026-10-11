package com.hivecore.node.data

import com.hivecore.core.AnswerChip
import com.hivecore.core.Compiled
import com.hivecore.core.Filter
import com.hivecore.core.Intent
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeOutcome
import com.hivecore.core.NodeRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The intent model's filter values snap to the stored ones before the query runs (the Sales phone's "2026" for "FY2026"). */
class FilterValuesTest {
    private val assetRoot = listOf(java.io.File("src/main/assets"), java.io.File("node/src/main/assets")).first { java.io.File(it, "seed/seed_manifest.json").isFile }
    private val assets = AssetReader { path -> java.io.File(assetRoot, path).inputStream() }
    private val store = DataStore(JdbcSqlDb.inMemory(), assets) { NodeRole.SALES }.also { it.migrate(); it.seedNow(NodeRole.SALES) }
    private val values = FilterValues(store.db)

    @Test fun yearsCodesAndCasesSnapToTheStoredValue() {
        assertEquals("FY2026", values.snap("sales_monthly", "fiscal_year", "2026"))
        assertEquals("FY2026", values.snap("sales_monthly", "fiscal_year", "fy 2026"))
        assertEquals("FY2026", values.snap("sales_monthly", "fiscal_year", "FY2026"))
        assertEquals("Bengaluru", values.snap("sales_monthly", "branch_city", "bengaluru"))
        assertEquals("BLR", values.snap("sales_monthly", "branch", "blr"))
        assertNull("no such year", values.snap("sales_monthly", "fiscal_year", "2031"))
        assertNull("ambiguous", values.snap("sales_monthly", "fiscal_quarter", "q"))
    }

    @Test fun theQueryRunsWithTheSnappedValue() {
        val schema = store.schema()
        val intent = Intent("sales_monthly", "sum", "net_sales", listOf("branch"), listOf(Filter("fiscal_year", "eq", JsonPrimitive("2026"))))
        val q = RuleIntentCompiler().compile(intent, schema) as Compiled.Query
        val table = schema.tables.first { it.name == "sales_monthly" }
        assertEquals(listOf("FY2026"), values.resolve(q, table).args)
        val out = SqlQueryExecutor(store.db) { AnswerChip.LLM }.run(q, table, "Sales")
        assertTrue(out.toString(), out is NodeOutcome.Tables)
        val t = (out as NodeOutcome.Tables).tables.single()
        assertEquals(3, t.rows.size)
        assertTrue(t.rows.all { it[2].jsonPrimitive.content.toDouble() > 0 })
    }
}
