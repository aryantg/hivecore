package com.hivecore.node.data

import com.hivecore.core.AggTable
import com.hivecore.core.Joiner
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Plan 7 join: the first [AggTable] per node; when two or more nodes share at least one non-measure column the
 * tables go into temp tables `r_<node>` (measures renamed `<node>_n`, `<node>_<measure>`) and are joined with a
 * chained `USING (<shared>)`. Nodes that share nothing with the running key set are left out; fewer than two
 * joined nodes -> null. The temp tables are dropped afterwards.
 */
class SqlJoiner(private val db: SqlDb) : Joiner {

    override fun join(tables: List<AggTable>): AggTable? {
        val perNode = LinkedHashMap<String, AggTable>()
        for (t in tables) perNode.putIfAbsent(t.node, t)
        if (perNode.size < 2) return null
        val list = perNode.values.toList()
        // Pick the nodes that chain: the key set is the intersection of the joined nodes' dimension columns.
        val joined = ArrayList<AggTable>()
        var keys: Set<String> = dims(list[0])
        if (keys.isEmpty()) return null
        joined.add(list[0])
        for (t in list.drop(1)) {
            val shared = keys intersect dims(t)
            if (shared.isEmpty()) continue
            keys = shared
            joined.add(t)
        }
        if (joined.size < 2) return null
        val shared = keys.toList()
        val temps = ArrayList<String>()
        // One transaction: on the phone the framework pools connections (WAL), and a TEMP table lives on the one
        // connection that created it. Outside a transaction the SELECT ran on another connection: "no such table:
        // r_sales" (Wave 3, Q1 across HR + Sales). Inside one, every statement uses the same connection.
        return try { db.tx { try {
            val selects = ArrayList<String>()
            val outCols = ArrayList(shared)
            for (t in joined) {
                val alias = ident(t.node)
                val temp = "r_$alias"
                temps.add(temp)
                val measureNames = measures(t)
                val renamed = measureNames.map { "${alias}_$it" }
                val colDefs = (shared + renamed).joinToString(", ") { DataStore.q(it) }
                db.exec("DROP TABLE IF EXISTS ${DataStore.q(temp)}")
                db.exec("CREATE TEMP TABLE ${DataStore.q(temp)} ($colDefs)")
                val src = shared + measureNames
                val idx = src.map { t.columns.indexOf(it) }
                val placeholders = "(" + src.joinToString(", ") { "?" } + ")"
                for (row in t.rows) {
                    val args = idx.map { i -> if (i < 0) null else toArg(row[i]) }
                    db.exec("INSERT INTO ${DataStore.q(temp)} ($colDefs) VALUES $placeholders", args)
                }
                selects.addAll(renamed.map { DataStore.q(it) })
                outCols.addAll(renamed)
            }
            val using = "USING (" + shared.joinToString(", ") { DataStore.q(it) } + ")"
            val from = temps.first().let { DataStore.q(it) } + temps.drop(1).joinToString("") { " JOIN ${DataStore.q(it)} $using" }
            val order = shared.joinToString(", ") { DataStore.q(it) }
            val sql = "SELECT " + (shared.map { DataStore.q(it) } + selects).joinToString(", ") + " FROM $from ORDER BY $order"
            val rows = db.query(sql)
            val out = rows.map { r -> outCols.map { SqlQueryExecutor.toJson(r[it]) } }
            SLog.d(TAG, "joined ${joined.joinToString("+") { it.node }} on $shared -> ${out.size} rows")
            AggTable("joined", joined.joinToString(" + ") { it.node }, outCols, out,
                joined.sumOf { it.rowsScanned }, joined.sumOf { it.rowsHeld }, if (joined.any { it.source == "rollup" }) "rollup" else "sql")
        } finally {
            for (t in temps) try { db.exec("DROP TABLE IF EXISTS ${DataStore.q(t)}") } catch (_: Exception) { }
        } } } catch (e: Exception) {
            SLog.e(TAG, "join failed", e)
            null
        }
    }

    private fun dims(t: AggTable): Set<String> = t.columns.filterNot { isMeasure(it) }.toSet()
    private fun measures(t: AggTable): List<String> = t.columns.filter { isMeasure(it) }

    private fun toArg(v: kotlinx.serialization.json.JsonElement): Any? = when (v) {
        is JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else v.longOrNull ?: v.doubleOrNull ?: v.content
        else -> v.toString()
    }

    companion object {
        private const val TAG = "Joiner"
        private val MEASURE = Regex("^(n|count_.*|sum_.*|avg_.*|min_.*|max_.*)$")
        fun isMeasure(column: String): Boolean = MEASURE.matches(column)
        fun ident(node: String): String = DataStore.normaliseName(node).ifEmpty { "node" }
    }
}
