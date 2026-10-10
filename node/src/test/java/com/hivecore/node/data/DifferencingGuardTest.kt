package com.hivecore.node.data

import com.hivecore.core.Filter
import com.hivecore.core.Intent
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import com.hivecore.core.Schema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Against the real seed. West has 15 reps; by `joined`: 5 joined in 2019, 6 in 2020-2023 (11 before 2024-01) and
 * 4 from 2024-01 on (R030, R053, R012, R013).
 */
class DifferencingGuardTest {
    private lateinit var db: JdbcSqlDb
    private lateinit var hr: Schema
    private lateinit var sales: Schema
    private lateinit var guard: SqlDifferencingGuard
    private val hour = 60L * 60 * 1000
    private val t0 = 1_800_000_000_000L

    @Before fun setUp() {
        db = JdbcSqlDb.inMemory()
        hr = Fixtures.store(NodeRole.HR, db).schema()
        guard = SqlDifferencingGuard(db, RuleIntentCompiler()) { t0 }
        sales = Fixtures.store(NodeRole.SALES).schema()
    }

    private fun eq(c: String, v: String) = Filter(c, "eq", JsonPrimitive(v))
    private fun lt(c: String, v: String) = Filter(c, "lt", JsonPrimitive(v))
    private fun gte(c: String, v: String) = Filter(c, "gte", JsonPrimitive(v))
    private fun avgCommission(vararg f: Filter, groupBy: List<String> = emptyList()) = Intent("employees", "avg", "commission", groupBy, f.toList())

    private val west = avgCommission(eq("region", "West"))
    /** "excluding reps who joined in or after 2024-01": 11 people, so the pair isolates 4. */
    private val westBefore2024 = avgCommission(eq("region", "West"), lt("joined", "2024-01"))

    @Test fun seedFactsHold() {
        val n = { sql: String -> (db.query(sql).single().values.single() as Number).toLong() }
        assertEquals(15L, n("SELECT COUNT(*) FROM t_employees WHERE region = 'West'"))
        assertEquals(4L, n("SELECT COUNT(*) FROM t_employees WHERE region = 'West' AND joined >= '2024-01'"))
        assertEquals(5L, n("SELECT COUNT(*) FROM t_employees WHERE region = 'West' AND joined < '2020-01'"))
    }

    @Test fun pairIsolatingFourPeopleIsRefused() {
        assertEquals("these figures together would reveal fewer than 5 people", guard.check("sales", listOf(west, westBefore2024), hr, t0))
        // order does not matter, aliases resolve
        val aliased = Intent("reps", "avg", "commission", filters = listOf(eq("area", "West")))
        assertEquals(SqlDifferencingGuard.REFUSAL, guard.check("sales", listOf(westBefore2024, aliased), hr, t0))
    }

    @Test fun pairDifferingByFiveOrMoreIsAllowed() {
        // excluding the 5 reps who joined before 2020: difference is exactly the floor
        assertNull(guard.check("sales", listOf(west, avgCommission(eq("region", "West"), gte("joined", "2020-01"))), hr, t0))
        // excluding reps who joined before 2024-01: the subset is 4 people and hidden by the floor anyway; difference 11
        assertNull(guard.check("sales", listOf(west, avgCommission(eq("region", "West"), gte("joined", "2024-01"))), hr, t0))
        // West vs West + North: 15 people apart
        val westNorth = avgCommission(Filter("region", "in", JsonArray(listOf(JsonPrimitive("West"), JsonPrimitive("North")))))
        assertNull(guard.check("sales", listOf(west, westNorth), hr, t0))
        // the same figure twice, and a single figure
        assertNull(guard.check("sales", listOf(west, west), hr, t0))
        assertNull(guard.check("sales", listOf(west), hr, t0))
        // a different metric over the same rows
        assertNull(guard.check("sales", listOf(west, Intent("employees", "count", filters = listOf(eq("region", "West")))), hr, t0))
    }

    @Test fun groupValuesActAsFilters() {
        val byRegion = avgCommission(groupBy = listOf("region"))
        val byRegionBefore2024 = avgCommission(lt("joined", "2024-01"), groupBy = listOf("region"))
        // inside the West group the second figure drops 4 people
        assertEquals(SqlDifferencingGuard.REFUSAL, guard.check("sales", listOf(byRegion, byRegionBefore2024), hr, t0))
        // a grouped figure against one ungrouped filter inside one group
        assertEquals(SqlDifferencingGuard.REFUSAL, guard.check("sales", listOf(byRegion, westBefore2024), hr, t0))
        // the grouped figure's West cell is the same rows as the West figure: nothing revealed
        assertNull(guard.check("sales", listOf(byRegion, west), hr, t0))
        // whole table vs by region: every region has 15+ people
        assertNull(guard.check("sales", listOf(byRegion, avgCommission()), hr, t0))
    }

    @Test fun nonSensitiveTableIsNeverRefused() {
        val s = SqlDifferencingGuard(Fixtures.store(NodeRole.SALES).db, RuleIntentCompiler()) { t0 }
        val all = Intent("sales", "sum", "revenue", filters = listOf(eq("region", "West")))
        val oneRep = Intent("sales", "sum", "revenue", filters = listOf(eq("region", "West"), Filter("rep_id", "neq", JsonPrimitive("R009"))))
        assertNull(s.check("hr", listOf(all, oneRep), sales, t0))
    }

    @Test fun differentTablesAreNotCompared() {
        assertNull(guard.check("sales", listOf(west, Intent("nope", "count")), hr, t0))
    }

    @Test fun crossPlanMemoryRefusesWithin24hAndAllowsAfter() {
        assertNull(guard.check("sales", listOf(west), hr, t0))
        guard.remember("sales", listOf(west), t0)
        assertEquals(SqlDifferencingGuard.REFUSAL, guard.check("sales", listOf(westBefore2024), hr, t0 + 1 * hour))
        assertEquals(SqlDifferencingGuard.REFUSAL, guard.check("sales", listOf(westBefore2024), hr, t0 + 23 * hour))
        // another requester has no history
        assertNull(guard.check("accounts", listOf(westBefore2024), hr, t0 + 1 * hour))
        // after 24 h the earlier answer is forgotten
        assertNull(guard.check("sales", listOf(westBefore2024), hr, t0 + 24 * hour + 1))
        guard.remember("sales", listOf(westBefore2024), t0 + 25 * hour)
        assertEquals(1, guard.answered("sales", t0 + 25 * hour).size)
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM _answered").single()["n"])
    }

    @Test fun guardCreatesItsOwnTable() {
        val fresh = JdbcSqlDb.inMemory()
        val s = Fixtures.store(NodeRole.HR, fresh).schema()
        val g = SqlDifferencingGuard(fresh)
        g.remember("x", listOf(west), t0)
        assertEquals(listOf(west), g.answered("x", t0))
        assertEquals(SqlDifferencingGuard.REFUSAL, g.check("x", listOf(westBefore2024), s, t0))
    }
}
