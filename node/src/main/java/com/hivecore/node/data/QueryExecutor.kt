package com.hivecore.node.data

import com.hivecore.core.AggTable
import com.hivecore.core.AnswerChip
import com.hivecore.core.Compiled
import com.hivecore.core.Hashing
import com.hivecore.core.NodeOutcome
import com.hivecore.core.QueryExecutor
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import com.hivecore.core.TableDef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs a compiled query (plan 7). `rowsScanned` is the sum of `n` over the groups actually returned (never the
 * unfloored count: that would reveal the size of a hidden group), `rowsHeld` is the table's row count. An empty
 * result -> Refused with the floor reason, whether no row matched or every group fell under the floor: the two
 * must be indistinguishable to the asker (a 0 vs 1-4 difference is a membership test). Sensitive tables never
 * read from a rollup, because a rollup holds row counts and the floor on those tables counts people.
 *
 * Rollup short-circuit — the contract the evening close's `Rollups` must follow when it fills `_rollups`:
 *  - `_rollups.signature` = `{"group_by":[<base group columns, sorted>],"table":"<table>"}` (compact JSON);
 *  - the rollup table `<name>` has those group columns, `n` = COUNT(*) per group, and one column per numeric
 *    measure of the base table holding SUM(<column>) per group, built with NO floor (`HAVING` is applied here).
 * A query uses a rollup when its table matches, its group columns are a subset of the rollup's, its filters are
 * only on `month` (a range: eq/gt/gte/lt/lte) with `month` among the rollup's columns, and its metric is count,
 * sum or avg of a column the rollup holds. The answer is then re-aggregated from the rollup, `source = "rollup"`.
 */
class SqlQueryExecutor(private val db: SqlDb, private val chip: () -> AnswerChip = { AnswerChip.LLM }) : QueryExecutor {
    private val values = FilterValues(db)

    override fun run(q0: Compiled.Query, table: TableDef, node: String): NodeOutcome {
        // the intent model's filter values snap to the column's stored values ("2026" → "FY2026") before anything runs
        val q = runCatching { values.resolve(q0, table) }.getOrDefault(q0)
        val hash = Hashing.sha256Hex(q.signature)
        val title = IntentText.title(q.intent)
        val perPerson = RuleIntentCompiler.floorColumns(table).isNotEmpty()
        return try {
            val rollup = if (table.isSensitive) null else findRollup(q)
            val (rows, source) = if (rollup != null) db.query(rollup.sql, rollup.args) to "rollup" else db.query(q.sql, q.args) to "sql"
            if (rows.isEmpty()) {
                SLog.d(TAG, "$node: floor refusal for ${q.signature}")
                return NodeOutcome.Refused(floorRefusal(perPerson))
            }
            val names = rows[0].keys.toList()
            val json = rows.map { r -> names.map { toJson(r[it]) } }
            val scanned = rows.sumOf { (it["n"] as? Number)?.toLong() ?: 0L }
            SLog.d(TAG, "$node: ${rows.size} aggregates from $source, scanned=$scanned held=${table.rowCount}")
            NodeOutcome.Tables(listOf(AggTable(node, title, names, json, scanned, table.rowCount, source)), chip(), hash)
        } catch (e: Exception) {
            SLog.e(TAG, "query failed: ${q.sql}", e)
            NodeOutcome.Refused("query failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private class RollupQuery(val sql: String, val args: List<Any?>)

    private fun findRollup(q: Compiled.Query): RollupQuery? {
        val i = q.intent
        if (i.metric !in setOf("count", "sum", "avg")) return null
        if (i.filters.any { it.column != "month" || it.op !in setOf("eq", "gt", "gte", "lt", "lte") }) return null
        val candidates = try { db.query("SELECT name, signature FROM _rollups WHERE tbl = ?", listOf(i.table)) } catch (_: Exception) { return null }
        for (c in candidates) {
            val name = c["name"] as String
            val groups = parseSignatureGroups(c["signature"] as String, i.table) ?: continue
            if (!groups.containsAll(i.groupBy)) continue
            if (i.filters.isNotEmpty() && "month" !in groups) continue
            val cols = try { db.query("PRAGMA table_info(${DataStore.q(name)})").map { it["name"] as String }.toSet() } catch (_: Exception) { continue }
            if ("n" !in cols) continue
            val measure = i.column
            if (i.metric != "count" && (measure == null || measure !in cols)) continue
            val g = i.groupBy.joinToString(", ") { DataStore.q(it) }
            val agg = when (i.metric) {
                "sum" -> ", SUM(${DataStore.q(measure!!)}) AS ${DataStore.q("sum_$measure")}"
                "avg" -> ", CAST(SUM(${DataStore.q(measure!!)}) AS REAL) / SUM(n) AS ${DataStore.q("avg_$measure")}"
                else -> ""
            }
            val args = ArrayList<Any?>()
            val where = i.filters.map { f -> args.add(IntentText.valueText(f.value)); "month ${RuleIntentCompiler.OPS.getValue(f.op)} ?" }
            val sql = "SELECT " + (if (g.isEmpty()) "" else "$g, ") + "SUM(n) AS n$agg FROM ${DataStore.q(name)}" +
                (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                (if (g.isEmpty()) "" else " GROUP BY $g") + " HAVING SUM(n) >= ${RuleIntentCompiler.GROUP_FLOOR}" +
                (if (g.isEmpty()) "" else " ORDER BY $g") + " LIMIT ${RuleIntentCompiler.ROW_LIMIT}"
            SLog.d(TAG, "rollup $name serves ${q.signature}")
            return RollupQuery(sql, args)
        }
        return null
    }

    companion object {
        private const val TAG = "QueryExecutor"

        /** The one refusal for "nothing to show": no row matched, or every group fell under the floor. */
        fun floorRefusal(perPerson: Boolean): String =
            "every group has fewer than ${RuleIntentCompiler.GROUP_FLOOR} ${if (perPerson) "people" else "rows"}"

        /** The rollup signature for a table and its group columns (sorted), as `_rollups.signature` stores it. */
        fun rollupSignature(table: String, groupBy: Collection<String>): String =
            "{\"group_by\":[" + groupBy.sorted().joinToString(",") { JsonPrimitive(it).toString() } + "],\"table\":" + JsonPrimitive(table) + "}"

        /** Group columns from a stored signature when it is for [table]; null otherwise. */
        fun parseSignatureGroups(signature: String, table: String): List<String>? {
            val m = Regex("\"group_by\":\\[([^]]*)]").find(signature) ?: return null
            val t = Regex("\"table\":\"([^\"]*)\"").find(signature)?.groupValues?.get(1) ?: return null
            if (t != table) return null
            return m.groupValues[1].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        }

        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Long -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v.toLong())
            is Double -> JsonPrimitive(v)
            is Float -> JsonPrimitive(v.toDouble())
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
}
