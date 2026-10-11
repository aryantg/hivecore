package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.Compiled
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import com.hivecore.core.TableDef

/**
 * Snaps a compiled query's equality filters on text columns to the values the column really holds (spec amendment
 * "open paths"): the intent model writes "2026" where the column says "FY2026", "blr" for "BLR", "bengaluru" for
 * "Bengaluru". A value that matches no stored value exactly is replaced by the one stored value it matches ignoring
 * case and punctuation, or that contains or ends with it, when that match is unique. Nothing else changes: the SQL
 * and its floor are the compiler's; only the bound arguments move, so an unmatched value still returns the floor
 * refusal the asker would have seen (never a hint that a value exists).
 */
class FilterValues(private val db: SqlDb, private val maxDistinct: Int = 400) {

    fun resolve(q: Compiled.Query, table: TableDef): Compiled.Query {
        if (q.intent.filters.isEmpty() || q.args.isEmpty()) return q
        val args = ArrayList(q.args)
        var cursor = 0
        for (f in q.intent.filters) {
            val n = if (f.op == "in") (f.value as? kotlinx.serialization.json.JsonArray)?.size ?: 1 else 1
            val col = table.columns.firstOrNull { it.name == f.column }
            if (col != null && col.type == ColType.TEXT && (f.op == "eq" || f.op == "neq" || f.op == "in")) {
                for (i in cursor until minOf(cursor + n, args.size)) {
                    val raw = args[i] as? String ?: continue
                    snap(table.name, col.name, raw)?.let { if (it != raw) { SLog.d(TAG, "${table.name}.${col.name}: '$raw' → '$it'"); args[i] = it } }
                }
            }
            cursor += n
        }
        return if (args == q.args) q else q.copy(args = args)
    }

    /** The stored value [raw] stands for, or null when it matches none or more than one. */
    fun snap(table: String, column: String, raw: String): String? {
        val values = distinct(table, column)
        if (values.isEmpty() || raw in values) return raw
        val key = norm(raw)
        if (key.isEmpty()) return null
        values.singleOrNull { norm(it) == key }?.let { return it }
        values.singleOrNull { norm(it).endsWith(key) || norm(it).startsWith(key) }?.let { return it }
        values.singleOrNull { norm(it).contains(key) || key.contains(norm(it)) && norm(it).length >= 3 }?.let { return it }
        return null
    }

    private val cache = HashMap<String, List<String>>()

    private fun distinct(table: String, column: String): List<String> = cache.getOrPut("$table.$column") {
        runCatching {
            db.query("SELECT DISTINCT ${DataStore.q(column)} AS v FROM ${DataStore.q("t_$table")} LIMIT ${maxDistinct + 1}")
                .mapNotNull { it["v"]?.toString() }
        }.getOrElse { emptyList() }.let { if (it.size > maxDistinct) emptyList() else it }
    }

    companion object {
        private const val TAG = "FilterValues"
        /** lowercase letters and digits only: "FY2026" → "fy2026", "Bengaluru." → "bengaluru" */
        fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
