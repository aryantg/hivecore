package com.hivecore.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-trip with Long / Double / String / null arguments. The same statements and expectations are what the
 * instrumented AndroidSqlDb test must reproduce (plan 5), so keep them in sync.
 */
class JdbcSqlDbTest {
    private lateinit var db: JdbcSqlDb

    @Before fun setUp() {
        db = JdbcSqlDb.inMemory()
        db.exec("CREATE TABLE t (i INTEGER, r REAL, s TEXT, n TEXT)")
        db.exec("INSERT INTO t (i, r, s, n) VALUES (?, ?, ?, ?)", listOf(1L, 2.5, "x", null))
        db.exec("INSERT INTO t (i, r, s, n) VALUES (?, ?, ?, ?)", listOf(10L, 0.25, "y", "set"))
        db.exec("INSERT INTO t (i, r, s, n) VALUES (?, ?, ?, ?)", listOf(100L, -1.0, "10", null))
    }

    @After fun tearDown() = db.close()

    @Test fun typesComeBackAsLongDoubleStringNull() {
        val rows = db.query("SELECT i, r, s, n FROM t WHERE i = ? AND r > ? AND s = ? AND n IS ?", listOf(1L, 2.0, "x", null))
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(listOf("i", "r", "s", "n"), row.keys.toList())
        assertEquals(1L, row["i"])
        assertTrue(row["i"] is Long)
        assertEquals(2.5, row["r"])
        assertTrue(row["r"] is Double)
        assertEquals("x", row["s"])
        assertNull(row["n"])
    }

    @Test fun integerBindsCompareNumericallyNotLexically() {
        // "10" < "9" as text; 10 > 9 as a number. A String[] bind would get this wrong.
        assertEquals(2, db.query("SELECT i FROM t WHERE i > ?", listOf(9L)).size)
        assertEquals(listOf(10L, 100L), db.query("SELECT i FROM t WHERE i > ? ORDER BY i", listOf(9L)).map { it["i"] })
        // The TEXT column holding "10" matches a string bind; an integer bind also matches because SQLite applies
        // the column's TEXT affinity to the bound value (so typed binds never make a TEXT comparison stricter).
        assertEquals(1, db.query("SELECT s FROM t WHERE s = ?", listOf("10")).size)
        assertEquals(1, db.query("SELECT s FROM t WHERE s = ?", listOf(10L)).size)
        // But an INTEGER column never matches a lexically-equal-but-numerically-different text bind.
        assertEquals(0, db.query("SELECT i FROM t WHERE i = ?", listOf("010x")).size)
    }

    @Test fun doubleBindAndAggregates() {
        val rows = db.query("SELECT COUNT(*) AS n, SUM(r) AS sum_r, AVG(i) AS avg_i FROM t WHERE r >= ?", listOf(0.0))
        assertEquals(2L, rows[0]["n"])
        assertEquals(2.75, rows[0]["sum_r"])
        assertEquals(5.5, rows[0]["avg_i"])
    }

    @Test fun nullBindAndIsNull() {
        assertEquals(2, db.query("SELECT i FROM t WHERE n IS NULL").size)
        assertEquals(1, db.query("SELECT i FROM t WHERE n = ?", listOf("set")).size)
        db.exec("UPDATE t SET n = ? WHERE i = ?", listOf(null, 10L))
        assertEquals(3, db.query("SELECT i FROM t WHERE n IS NULL").size)
    }

    @Test fun txCommitsAndRollsBack() {
        val n = db.tx {
            db.exec("INSERT INTO t (i, r, s, n) VALUES (?, ?, ?, ?)", listOf(7L, 7.0, "seven", null))
            db.query("SELECT COUNT(*) AS n FROM t")[0]["n"]
        }
        assertEquals(4L, n)
        try {
            db.tx {
                db.exec("INSERT INTO t (i, r, s, n) VALUES (?, ?, ?, ?)", listOf(8L, 8.0, "eight", null))
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(4L, db.query("SELECT COUNT(*) AS n FROM t")[0]["n"])
    }

    @Test fun groupByWithHavingFloorShape() {
        db.exec("CREATE TABLE t_employees (region TEXT, salary INTEGER)")
        for (i in 0 until 6) db.exec("INSERT INTO t_employees VALUES (?, ?)", listOf("West", 100L + i))
        for (i in 0 until 3) db.exec("INSERT INTO t_employees VALUES (?, ?)", listOf("East", 50L + i))
        val rows = db.query("SELECT region, COUNT(*) AS n, AVG(salary) AS avg_salary FROM t_employees GROUP BY region HAVING COUNT(*) >= 5 ORDER BY region LIMIT 50")
        assertEquals(1, rows.size)
        assertEquals("West", rows[0]["region"])
        assertEquals(6L, rows[0]["n"])
        assertEquals(102.5, rows[0]["avg_salary"])
    }
}
