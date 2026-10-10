package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.Compiled
import com.hivecore.core.Filter
import com.hivecore.core.Hashing
import com.hivecore.core.Intent
import com.hivecore.core.NodeRole
import com.hivecore.core.Schema
import com.hivecore.core.TableDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class IntentCompilerTest {
    companion object {
        lateinit var hr: Schema
        lateinit var sales: Schema
        lateinit var accounts: Schema
        @BeforeClass @JvmStatic fun seed() {
            hr = Fixtures.store(NodeRole.HR).schema()
            sales = Fixtures.store(NodeRole.SALES).schema()
            accounts = Fixtures.store(NodeRole.ACCOUNTS).schema()
        }
    }

    private val c = RuleIntentCompiler()
    private fun eq(col: String, v: String) = Filter(col, "eq", JsonPrimitive(v))
    private fun refused(i: Intent, s: Schema): String = (c.compile(i, s) as Compiled.Refused).reason
    private fun query(i: Intent, s: Schema): Compiled.Query = c.compile(i, s) as Compiled.Query

    @Test fun rule1UnknownNamesAreNotRelevant() {
        assertTrue(c.compile(Intent("weather", "count"), hr) is Compiled.NotRelevant)
        assertTrue(c.compile(Intent("employees", "avg", "bonus_pct"), hr) is Compiled.NotRelevant)
        assertTrue(c.compile(Intent("employees", "count", groupBy = listOf("city")), hr) is Compiled.NotRelevant)
        assertTrue(c.compile(Intent("employees", "count", filters = listOf(eq("city", "Pune"))), hr) is Compiled.NotRelevant)
        assertTrue(c.compile(Intent("employees", "count", filters = listOf(eq("_src", "1"))), hr) is Compiled.NotRelevant)
        assertEquals("unknown table weather", (c.compile(Intent("weather", "count"), hr) as Compiled.NotRelevant).reason)
    }

    @Test fun rule2IdentifierFilterOnSensitiveTableBeatsTheMetricRule() {
        // "Rahul's maximum salary": the identifier filter is the story reason, not the min/max rule.
        assertEquals("filters on name are not allowed on confidential data",
            refused(Intent("employees", "max", "salary", filters = listOf(eq("name", "Rahul Sharma"))), hr))
        assertEquals("filters on rep_id are not allowed on confidential data",
            refused(Intent("employees", "avg", "salary", filters = listOf(eq("region", "West"), eq("rep_id", "R001"))), hr))
        // Sales is not sensitive, so a rep_id filter is fine there.
        assertTrue(c.compile(Intent("sales", "sum", "revenue", filters = listOf(eq("rep_id", "R001"))), sales) is Compiled.Query)
    }

    @Test fun rule3Metrics() {
        assertEquals("unknown metric median", refused(Intent("sales", "median", "revenue"), sales))
        assertEquals("sum needs a column", refused(Intent("sales", "sum"), sales))
        assertEquals("avg needs a numeric column; region is text", refused(Intent("sales", "avg", "region"), sales))
        // count ignores the column, even an unknown one.
        val q = query(Intent("sales", "count", "whatever"), sales)
        assertEquals(null, q.intent.column)
        assertEquals("SELECT COUNT(*) AS n FROM \"t_sales\" HAVING COUNT(*) >= 5 LIMIT 50", q.sql)
    }

    @Test fun rule4ConfidentialColumnsAllowOnlyAverageAndCount() {
        assertEquals("only average and count are allowed on confidential column salary", refused(Intent("employees", "max", "salary"), hr))
        assertEquals("only average and count are allowed on confidential column salary", refused(Intent("employees", "sum", "salary"), hr))
        assertEquals("only average and count are allowed on confidential column commission", refused(Intent("employees", "min", "commission"), hr))
        assertTrue(c.compile(Intent("employees", "avg", "salary"), hr) is Compiled.Query)
        assertTrue(c.compile(Intent("employees", "count", "salary"), hr) is Compiled.Query)
    }

    @Test fun rule5GroupByMustBeGroupable() {
        assertEquals("cannot group by name; this table allows: region", refused(Intent("employees", "count", groupBy = listOf("name")), hr))
        assertEquals("cannot group by rep_id; this table allows: region, month", refused(Intent("sales", "sum", "revenue", listOf("region", "rep_id")), sales))
        assertEquals("cannot group by date; this table allows: vendor, month, status", refused(Intent("invoices", "sum", "total", listOf("date")), accounts))
        assertTrue(c.compile(Intent("invoices", "sum", "total", listOf("month")), accounts) is Compiled.Query)
    }

    @Test fun rule6OperatorsAndBindParameters() {
        assertEquals("unknown operator like", refused(Intent("sales", "count", filters = listOf(Filter("region", "like", JsonPrimitive("W%")))), sales))
        assertEquals("unknown operator between", refused(Intent("sales", "count", filters = listOf(Filter("month", "between", JsonPrimitive("2026-01")))), sales))
        val q = query(Intent("sales", "sum", "revenue", filters = listOf(
            Filter("region", "in", JsonArray(listOf(JsonPrimitive("West"), JsonPrimitive("South")))),
            Filter("revenue", "gte", JsonPrimitive("100000")),
            Filter("units", "lt", JsonPrimitive(10)),
            Filter("month", "neq", JsonPrimitive("2026-01")))), sales)
        assertEquals("SELECT COUNT(*) AS n, SUM(\"revenue\") AS \"sum_revenue\" FROM \"t_sales\" WHERE \"region\" IN (?, ?) AND \"revenue\" >= ? AND \"units\" < ? AND \"month\" <> ? HAVING COUNT(*) >= 5 LIMIT 50", q.sql)
        assertEquals(listOf("West", "South", 100000L, 10L, "2026-01"), q.args)
        assertEquals("SELECT COUNT(*) FROM \"t_sales\" WHERE \"region\" IN (?, ?) AND \"revenue\" >= ? AND \"units\" < ? AND \"month\" <> ?", q.countSql)
        // A value never lands in the SQL text.
        val inj = query(Intent("sales", "count", filters = listOf(eq("region", "West'; DROP TABLE t_sales; --"))), sales)
        assertFalse(inj.sql.contains("DROP"))
        assertEquals(listOf("West'; DROP TABLE t_sales; --"), inj.args)
    }

    @Test fun rule7TouchesConfidential() {
        assertTrue(query(Intent("employees", "avg", "commission"), hr).touchesConfidential)
        assertTrue(query(Intent("employees", "avg", "salary", filters = listOf(eq("region", "South"))), hr).touchesConfidential)
        assertFalse(query(Intent("employees", "count", groupBy = listOf("region")), hr).touchesConfidential)   // Q1 headcount needs no approval
        assertFalse(query(Intent("sales", "sum", "revenue", listOf("region")), sales).touchesConfidential)
    }

    @Test fun rule8SqlShapeForTheTourQuestions() {
        val q1 = query(Intent("sales", "sum", "revenue", listOf("region"), listOf(Filter("month", "gte", JsonPrimitive("2026-01")), Filter("month", "lte", JsonPrimitive("2026-12")))), sales)
        assertEquals("SELECT \"region\", COUNT(*) AS n, SUM(\"revenue\") AS \"sum_revenue\" FROM \"t_sales\" WHERE \"month\" >= ? AND \"month\" <= ? GROUP BY \"region\" HAVING COUNT(*) >= 5 ORDER BY \"region\" LIMIT 50", q1.sql)
        assertEquals("SELECT COUNT(*) FROM \"t_sales\" WHERE \"month\" >= ? AND \"month\" <= ?", q1.countSql)
        assertEquals(listOf("2026-01", "2026-12"), q1.args)
        val q2 = query(Intent("employees", "avg", "commission", filters = listOf(eq("region", "West"))), hr)
        // employees is sensitive: the floor counts distinct people under every identifier column.
        assertEquals("SELECT COUNT(*) AS n, AVG(\"commission\") AS \"avg_commission\" FROM \"t_employees\" WHERE \"region\" = ? HAVING COUNT(DISTINCT \"rep_id\") >= 5 AND COUNT(DISTINCT \"name\") >= 5 LIMIT 50", q2.sql)
        val hr1 = query(Intent("employees", "count", groupBy = listOf("region")), hr)
        assertEquals("SELECT \"region\", COUNT(*) AS n FROM \"t_employees\" GROUP BY \"region\" HAVING COUNT(DISTINCT \"rep_id\") >= 5 AND COUNT(DISTINCT \"name\") >= 5 ORDER BY \"region\" LIMIT 50", hr1.sql)
        val q5 = query(Intent("invoices", "sum", "total", filters = listOf(eq("month", "2026-10"))), accounts)
        assertEquals("SELECT COUNT(*) AS n, SUM(\"total\") AS \"sum_total\" FROM \"t_invoices\" WHERE \"month\" = ? HAVING COUNT(*) >= 5 LIMIT 50", q5.sql)
        val two = query(Intent("sales", "sum", "units", listOf("region", "month")), sales)
        assertTrue(two.sql.contains("GROUP BY \"region\", \"month\" HAVING COUNT(*) >= 5 ORDER BY \"region\", \"month\""))
    }

    @Test fun rule9DescriptionAndSignature() {
        val q = query(Intent("employees", "avg", "commission", listOf("region"), listOf(eq("region", "West"))), hr)
        assertEquals("Average of commission by region, where region = West, over employees (confidential: commission). Each group shows its count; groups under 5 people are hidden.", q.description)
        assertEquals("{\"column\":\"commission\",\"filters\":[{\"column\":\"region\",\"op\":\"eq\",\"value\":\"West\"}],\"group_by\":[\"region\"],\"metric\":\"avg\",\"table\":\"employees\"}", q.signature)
        val plain = query(Intent("sales", "count", groupBy = listOf("region")), sales)
        assertEquals("Count by region, over sales. Each group shows its count; groups under 5 rows are hidden.", plain.description)
        assertEquals("{\"column\":null,\"filters\":[],\"group_by\":[\"region\"],\"metric\":\"count\",\"table\":\"sales\"}", plain.signature)
        // Aliases resolve to registry names, so the hash is the same whichever the LLM used.
        val byAlias = query(Intent("reps", "avg", "commission", listOf("region"), listOf(eq("Region", "West"))), hr)
        assertEquals(q.signature, byAlias.signature)
        assertEquals(Hashing.sha256Hex(q.signature), Hashing.sha256Hex(byAlias.signature))
        assertEquals("employees", byAlias.intent.table)
        // Numbers and lists in filters are rendered plainly.
        val d = query(Intent("sales", "sum", "revenue", filters = listOf(Filter("units", "gte", JsonPrimitive(10)), Filter("region", "in", JsonArray(listOf(JsonPrimitive("West"), JsonPrimitive("East")))))), sales).description
        assertEquals("Sum of revenue, where units ≥ 10 and region in (West, East), over sales. Each group shows its count; groups under 5 rows are hidden.", d)
    }

    // ------------------------------------------------------------------ security review #1, #2

    /** A sensitive table with several rows per person, a non-confidential measure and the derived month. */
    private val payroll = Schema(NodeRole.HR, listOf(TableDef("payroll", listOf(
        ColumnDef("person", ColType.TEXT, identifier = true),
        ColumnDef("region", ColType.TEXT, groupable = true),
        ColumnDef("month", ColType.TEXT, groupable = true),
        ColumnDef("team", ColType.TEXT),
        ColumnDef("hours", ColType.INTEGER),
        ColumnDef("bonus", ColType.INTEGER, confidential = true)), 100)), emptyList())

    @Test fun sensitiveTablesRefuseFiltersOnConfidentialOrNonGroupableColumns() {
        // Binary search on a confidential value: "how many earn more than 12 lakh?" then 11, 11.5, ...
        assertEquals("filters on salary are not allowed on confidential data",
            refused(Intent("employees", "count", filters = listOf(Filter("salary", "gt", JsonPrimitive(1200000)))), hr))
        assertEquals("filters on commission are not allowed on confidential data",
            refused(Intent("employees", "avg", "commission", filters = listOf(Filter("commission", "lte", JsonPrimitive(50000)))), hr))
        // A free-text column (one value per person in practice) is not a filter either.
        assertEquals("filters on joined are not allowed on confidential data",
            refused(Intent("employees", "count", filters = listOf(eq("joined", "2024-03-01"))), hr))
        assertEquals("filters on team are not allowed on confidential data",
            refused(Intent("payroll", "sum", "hours", filters = listOf(eq("team", "A"))), payroll))
        // Non-sensitive tables keep every filter.
        assertTrue(c.compile(Intent("sales", "count", filters = listOf(Filter("revenue", "gt", JsonPrimitive(100000)))), sales) is Compiled.Query)
    }

    @Test fun sensitiveTablesAllowOnlyEqAndInExceptMonthRanges() {
        // Differencing: West minus "West except one value" isolates the rest of the group.
        assertEquals("region ≠ is not allowed on confidential data; only = and in filters (and month ranges) are allowed",
            refused(Intent("employees", "avg", "commission", filters = listOf(Filter("region", "neq", JsonPrimitive("West")))), hr))
        assertEquals("region > is not allowed on confidential data; only = and in filters (and month ranges) are allowed",
            refused(Intent("employees", "count", filters = listOf(Filter("region", "gt", JsonPrimitive("N")))), hr))
        assertEquals("month ≠ is not allowed on confidential data; only = and in filters (and month ranges) are allowed",
            refused(Intent("payroll", "sum", "hours", filters = listOf(Filter("month", "neq", JsonPrimitive("2026-01")))), payroll))
        assertTrue(c.compile(Intent("employees", "count", filters = listOf(Filter("region", "in", JsonArray(listOf(JsonPrimitive("West"), JsonPrimitive("East")))))), hr) is Compiled.Query)
        val range = query(Intent("payroll", "sum", "hours", listOf("region"), listOf(Filter("month", "gte", JsonPrimitive("2026-01")), Filter("month", "lte", JsonPrimitive("2026-06")))), payroll)
        assertTrue(range.sql, range.sql.contains("WHERE \"month\" >= ? AND \"month\" <= ?"))
    }

    @Test fun sensitiveTablesRefuseMinAndMaxOnEveryColumn() {
        // hours is not confidential, but the maximum of a 5-person group is still one person's value.
        assertEquals("minimum and maximum are not allowed on confidential data", refused(Intent("payroll", "max", "hours"), payroll))
        assertEquals("minimum and maximum are not allowed on confidential data", refused(Intent("payroll", "min", "hours", listOf("region")), payroll))
        // The confidential column keeps its own (story) reason.
        assertEquals("only average and count are allowed on confidential column bonus", refused(Intent("payroll", "max", "bonus"), payroll))
        assertTrue(c.compile(Intent("payroll", "sum", "hours", listOf("region")), payroll) is Compiled.Query)
        // Non-sensitive tables keep min/max.
        assertTrue(c.compile(Intent("sales", "max", "revenue", listOf("region")), sales) is Compiled.Query)
    }

    @Test fun theFloorCountsPeopleOnSensitiveTables() {
        val q = query(Intent("payroll", "avg", "bonus", listOf("region")), payroll)
        assertTrue(q.sql, q.sql.contains("HAVING COUNT(DISTINCT \"person\") >= 5 ORDER BY"))
        assertTrue(q.description, q.description.endsWith("groups under 5 people are hidden."))
        // Sensitive by table flag only, no identifier column: the floor stays on rows.
        val flagged = Schema(NodeRole.HR, listOf(TableDef("notes", listOf(ColumnDef("region", ColType.TEXT, groupable = true)), 10, confidential = true)), emptyList())
        assertTrue(query(Intent("notes", "count", groupBy = listOf("region")), flagged).sql.contains("HAVING COUNT(*) >= 5"))
    }

    @Test fun titles() {
        assertEquals("Revenue by region, 2026", IntentText.title(Intent("sales", "sum", "revenue", listOf("region"), listOf(Filter("month", "gte", JsonPrimitive("2026-01")), Filter("month", "lte", JsonPrimitive("2026-12"))))))
        assertEquals("Average commission in West", IntentText.title(Intent("employees", "avg", "commission", filters = listOf(eq("region", "West")))))
        assertEquals("Employees by region", IntentText.title(Intent("employees", "count", groupBy = listOf("region"))))
        assertEquals("Total, October 2026", IntentText.title(Intent("invoices", "sum", "total", filters = listOf(eq("month", "2026-10")))))
        assertEquals("Invoices with status DUE", IntentText.title(Intent("invoices", "count", filters = listOf(eq("status", "DUE")))))
        assertEquals("Revenue by region, Jul–Sep 2026", IntentText.title(Intent("sales", "sum", "revenue", listOf("region"), listOf(Filter("month", "gte", JsonPrimitive("2026-07")), Filter("month", "lte", JsonPrimitive("2026-09"))))))
        assertEquals("Units by region and month, from January 2026", IntentText.title(Intent("sales", "sum", "units", listOf("region", "month"), listOf(Filter("month", "gte", JsonPrimitive("2026-01"))))))
        assertEquals("Maximum salary with salary > 100000", IntentText.title(Intent("employees", "max", "salary", filters = listOf(Filter("salary", "gt", JsonPrimitive(100000))))))
    }
}
