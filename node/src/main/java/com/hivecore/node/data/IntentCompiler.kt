package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.Compiled
import com.hivecore.core.Filter
import com.hivecore.core.Intent
import com.hivecore.core.IntentCompiler
import com.hivecore.core.SLog
import com.hivecore.core.Schema
import com.hivecore.core.TableDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Plan 7 rules 1-9, first failure wins. Identifiers come only from the schema; every value is a bind parameter.
 * Table and column names in the intent may be aliases (case-insensitive); the compiled SQL and the signature use
 * the resolved registry names.
 *
 * Sensitive tables ([TableDef.isSensitive]) get the tighter rules of the security review:
 *  - filters only on groupable, non-confidential, non-identifier columns (no binary search on a confidential
 *    value, no "all but one person" differencing), with `=` or `in`; ranges only on the derived `month`;
 *  - no `min` / `max` on any column (a 5-person group's maximum is one person's value);
 *  - the group floor counts people, not rows: `HAVING COUNT(DISTINCT <identifier>) >= 5` for every identifier
 *    column, so a table with several rows per person cannot leak one person through a 5-row group.
 */
class RuleIntentCompiler : IntentCompiler {

    override fun compile(intent: Intent, schema: Schema): Compiled {
        // Rule 1: unknown table / column / group / filter column -> NotRelevant.
        val table = resolveTable(schema, intent.table) ?: return Compiled.NotRelevant("unknown table ${intent.table}")
        val metric = intent.metric.trim().lowercase()
        val metricCol: ColumnDef? = if (metric == "count") null else intent.column?.let { c ->
            resolveColumn(table, c) ?: return Compiled.NotRelevant("unknown column $c")
        }
        val groups = ArrayList<ColumnDef>()
        for (g in intent.groupBy) {
            val c = resolveColumn(table, g) ?: return Compiled.NotRelevant("unknown column $g")
            if (groups.none { it.name == c.name }) groups.add(c)
        }
        val filters = intent.filters.map { f ->
            val c = resolveColumn(table, f.column) ?: return Compiled.NotRelevant("unknown column ${f.column}")
            Triple(c, f.op.trim().lowercase(), f.value)
        }
        val resolved = Intent(table.name, metric, metricCol?.name, groups.map { it.name },
            filters.map { (c, op, v) -> Filter(c.name, op, v) })

        // Rule 2 (sensitive tables): a filter may only name a groupable column that is neither an identifier nor
        // confidential (checked before the metric rule: "Rahul's maximum salary" gets the story reason), and may
        // only use = or in; a range is allowed on the derived month column alone.
        val sensitive = table.isSensitive
        if (sensitive) {
            filters.firstOrNull { (c, _, _) -> c.identifier || c.confidential || !c.groupable }?.let { (c, _, _) ->
                return refuse(resolved, "filters on ${c.name} are not allowed on confidential data")
            }
            filters.firstOrNull { (c, op, _) -> op in OPS && op != "eq" && op != "in" && !(c.name == MONTH_COLUMN && op in RANGE_OPS) }?.let { (c, op, _) ->
                return refuse(resolved, "${c.name} ${IntentText.opSymbol(op)} is not allowed on confidential data; only = and in filters (and month ranges) are allowed")
            }
        }
        // Rule 3: metric.
        if (metric !in METRICS) return refuse(resolved, "unknown metric ${intent.metric}")
        if (metric != "count") {
            if (metricCol == null) return refuse(resolved, "$metric needs a column")
            if (metricCol.type == ColType.TEXT) return refuse(resolved, "$metric needs a numeric column; ${metricCol.name} is text")
        }
        // Rule 4: confidential column -> avg / count only; on a sensitive table min / max are out for every column.
        if (metricCol != null && metricCol.confidential && metric != "avg" && metric != "count")
            return refuse(resolved, "only average and count are allowed on confidential column ${metricCol.name}")
        if (sensitive && (metric == "min" || metric == "max"))
            return refuse(resolved, "minimum and maximum are not allowed on confidential data")
        // Rule 5: group_by must be groupable.
        groups.firstOrNull { !it.groupable }?.let { g ->
            val allowed = table.columns.filter { it.groupable }.map { it.name }
            return refuse(resolved, "cannot group by ${g.name}; this table allows: " + (if (allowed.isEmpty()) "no grouping" else allowed.joinToString(", ")))
        }
        // Rule 6: operators; values become bind parameters.
        val args = ArrayList<Any?>()
        val where = ArrayList<String>()
        for ((c, op, v) in filters) {
            if (op !in OPS) return refuse(resolved, "unknown operator $op")
            if (op == "in") {
                val items = (v as? JsonArray)?.toList() ?: listOf(v)
                if (items.isEmpty()) return refuse(resolved, "empty list for ${c.name}")
                items.forEach { args.add(bindValue(it, c.type)) }
                where.add("${DataStore.q(c.name)} IN (${items.joinToString(", ") { "?" }})")
            } else {
                if (v is JsonArray || v is JsonObject) return refuse(resolved, "value for ${c.name} must be a single value")
                args.add(bindValue(v, c.type))
                where.add("${DataStore.q(c.name)} ${OPS.getValue(op)} ?")
            }
        }
        // Rule 7.
        val touches = table.confidential || metricCol?.confidential == true || groups.any { it.confidential } || filters.any { it.first.confidential }
        // Rule 8: SQL.
        val tbl = DataStore.q("t_${table.name}")
        val g = groups.joinToString(", ") { DataStore.q(it.name) }
        val agg = if (metricCol == null) "" else ", ${metric.uppercase()}(${DataStore.q(metricCol.name)}) AS ${DataStore.q("${metric}_${metricCol.name}")}"
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val floorCols = floorColumns(table)
        val having = if (floorCols.isEmpty()) "COUNT(*) >= $GROUP_FLOOR"
            else floorCols.joinToString(" AND ") { "COUNT(DISTINCT ${DataStore.q(it)}) >= $GROUP_FLOOR" }
        val sql = "SELECT " + (if (g.isEmpty()) "" else "$g, ") + "COUNT(*) AS n$agg FROM $tbl$whereSql" +
            (if (g.isEmpty()) "" else " GROUP BY $g") + " HAVING $having" +
            (if (g.isEmpty()) "" else " ORDER BY $g") + " LIMIT $ROW_LIMIT"
        // Informational only: the executor reports rows scanned as the sum of the groups it returns (never the
        // unfloored count, which would reveal the size of a hidden group).
        val countSql = "SELECT COUNT(*) FROM $tbl$whereSql"
        // Rule 9.
        val confidentialNames = LinkedHashSet<String>()
        if (metricCol?.confidential == true) confidentialNames.add(metricCol.name)
        groups.filter { it.confidential }.forEach { confidentialNames.add(it.name) }
        filters.filter { it.first.confidential }.forEach { confidentialNames.add(it.first.name) }
        if (table.confidential && confidentialNames.isEmpty()) confidentialNames.add(table.name)
        val description = IntentText.description(resolved, touches, confidentialNames.toList(), perPerson = floorCols.isNotEmpty())
        val signature = IntentText.canonical(resolved)
        SLog.d(TAG, "compiled ${signature} -> $sql args=$args")
        return Compiled.Query(resolved, sql, args, countSql, touches, description, signature)
    }

    private fun refuse(intent: Intent, reason: String): Compiled {
        SLog.d(TAG, "refused ${IntentText.canonical(intent)}: $reason")
        return Compiled.Refused(intent, reason)
    }

    private fun resolveTable(schema: Schema, name: String): TableDef? {
        val n = name.trim()
        if (n.isEmpty()) return null
        schema.tables.firstOrNull { it.name == n }?.let { return it }
        val l = n.lowercase().removePrefix("t_")
        schema.tables.firstOrNull { it.name.lowercase() == l }?.let { return it }
        return schema.tables.firstOrNull { t -> t.aliases.any { it.lowercase() == l } }
    }

    private fun resolveColumn(table: TableDef, name: String): ColumnDef? {
        val n = name.trim()
        if (n.isEmpty()) return null
        table.columns.firstOrNull { it.name == n }?.let { return it }
        val l = n.lowercase()
        table.columns.firstOrNull { it.name.lowercase() == l }?.let { return it }
        val norm = DataStore.normaliseName(n)
        table.columns.firstOrNull { it.name == norm }?.let { return it }
        return table.columns.firstOrNull { c -> c.aliases.any { it.lowercase() == l } }
    }

    /** A JSON value to its bound form, coerced towards the column's type (SQLite affinity would do it anyway). */
    private fun bindValue(v: JsonElement, type: ColType): Any? {
        if (v is JsonNull) return null
        if (v !is JsonPrimitive) return v.toString()
        val raw: Any = when {
            v.isString -> v.content
            else -> v.longOrNull ?: v.doubleOrNull ?: v.booleanOrNull?.let { if (it) 1L else 0L } ?: v.content
        }
        return when (type) {
            ColType.INTEGER -> when (raw) { is Long -> raw; is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong() else raw; is String -> raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull() ?: raw; else -> raw }
            ColType.REAL -> when (raw) { is Long -> raw.toDouble(); is Double -> raw; is String -> raw.trim().toDoubleOrNull() ?: raw; else -> raw }
            ColType.TEXT -> when (raw) { is String -> raw; is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong().toString() else raw.toString(); else -> raw.toString() }
        }
    }

    companion object {
        private const val TAG = "IntentCompiler"
        const val GROUP_FLOOR = 5
        const val ROW_LIMIT = 50
        /** The derived month column (plan 7): the one column a sensitive table accepts a range filter on. */
        const val MONTH_COLUMN = "month"
        val METRICS = setOf("count", "sum", "avg", "min", "max")
        val OPS = mapOf("eq" to "=", "neq" to "<>", "gt" to ">", "gte" to ">=", "lt" to "<", "lte" to "<=", "in" to "IN")
        val RANGE_OPS = setOf("gt", "gte", "lt", "lte")

        /**
         * The columns the group floor counts distinct values of: every identifier column of a sensitive table.
         * Empty means the floor counts rows (non-sensitive tables, or a sensitive table without identifiers).
         */
        fun floorColumns(table: TableDef): List<String> =
            if (table.isSensitive) table.columns.filter { it.identifier }.map { it.name } else emptyList()
    }
}

/** Deterministic text for intents: the canonical signature, the owner-side description and answer titles. */
object IntentText {
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val PLACE_COLUMNS = setOf("region", "city", "state", "country", "zone", "area", "territory", "branch", "office", "location")

    /** Canonical JSON with sorted keys (the hash input). `group_by` keeps its order; values are compact JSON. */
    fun canonical(i: Intent): String = buildString {
        append("{\"column\":").append(i.column?.let { JsonPrimitive(it).toString() } ?: "null")
        append(",\"filters\":[").append(i.filters.joinToString(",") {
            "{\"column\":${JsonPrimitive(it.column)},\"op\":${JsonPrimitive(it.op)},\"value\":${it.value}}"
        }).append("]")
        append(",\"group_by\":[").append(i.groupBy.joinToString(",") { JsonPrimitive(it).toString() }).append("]")
        append(",\"metric\":").append(JsonPrimitive(i.metric).toString())
        append(",\"table\":").append(JsonPrimitive(i.table).toString()).append("}")
    }

    /**
     * "Average of commission by region, where region = West, over employees (confidential: commission). Each group
     * shows its count; groups under 5 people are hidden." ("rows" when the floor counts rows, see
     * [RuleIntentCompiler.floorColumns]).
     */
    fun description(i: Intent, touchesConfidential: Boolean, confidentialNames: List<String>, perPerson: Boolean = false): String = buildString {
        append(when (i.metric) { "count" -> "Count"; "avg" -> "Average"; "sum" -> "Sum"; "min" -> "Minimum"; "max" -> "Maximum"; else -> i.metric })
        if (i.column != null && i.metric != "count") append(" of ").append(i.column)
        if (i.groupBy.isNotEmpty()) append(" by ").append(i.groupBy.joinToString(", "))
        if (i.filters.isNotEmpty()) append(", where ").append(i.filters.joinToString(" and ") { "${it.column} ${opSymbol(it.op)} ${valueText(it.value)}" })
        append(", over ").append(i.table)
        if (touchesConfidential && confidentialNames.isNotEmpty()) append(" (confidential: ").append(confidentialNames.joinToString(", ")).append(")")
        append(". Each group shows its count; groups under ").append(RuleIntentCompiler.GROUP_FLOOR).append(if (perPerson) " people" else " rows").append(" are hidden.")
    }

    /**
     * The answer heading the renderer prints before the values: "Employees by region", "Revenue by region, 2026",
     * "Average commission in West", "Total by status, October 2026".
     */
    fun title(i: Intent): String = buildString {
        append(when (i.metric) {
            "count" -> humanise(i.table).replaceFirstChar { it.uppercase() }
            "sum" -> humanise(i.column ?: "total").replaceFirstChar { it.uppercase() }
            "avg" -> "Average " + humanise(i.column ?: "")
            "min" -> "Minimum " + humanise(i.column ?: "")
            "max" -> "Maximum " + humanise(i.column ?: "")
            else -> i.metric
        }.trimEnd())
        if (i.groupBy.isNotEmpty()) append(" by ").append(i.groupBy.joinToString(" and ") { humanise(it) })
        val month = i.filters.filter { it.column == "month" || it.column == "date" }
        val other = i.filters.filter { it.column != "month" && it.column != "date" }
        for (f in other) {
            val v = valueText(f.value)
            when {
                f.op == "eq" && f.column in PLACE_COLUMNS -> append(" in ").append(v)
                f.op == "eq" -> append(" with ").append(humanise(f.column)).append(" ").append(v)
                f.op == "in" -> append(" with ").append(humanise(f.column)).append(" in ").append(v)
                else -> append(" with ").append(humanise(f.column)).append(" ").append(opSymbol(f.op)).append(" ").append(v)
            }
        }
        periodText(month)?.let { append(", ").append(it) }
    }

    /** "2026", "October 2026", "Jul–Sep 2026", "from Jan 2026", "to 2026-03-15" for month/date filters. */
    fun periodText(filters: List<Filter>): String? {
        if (filters.isEmpty()) return null
        val eq = filters.firstOrNull { it.op == "eq" }
        if (eq != null) return monthLong(valueText(eq.value))
        var lo: String? = null
        var hi: String? = null
        for (f in filters) when (f.op) { "gte", "gt" -> lo = valueText(f.value); "lte", "lt" -> hi = valueText(f.value) }
        if (lo == null && hi == null) return filters.joinToString(" and ") { "${humanise(it.column)} ${opSymbol(it.op)} ${valueText(it.value)}" }
        if (lo != null && hi != null) {
            val ly = lo.take(4); val hy = hi.take(4)
            if (lo.length == 7 && hi.length == 7 && ly == hy && lo.endsWith("-01") && hi.endsWith("-12")) return ly
            if (lo.length == 7 && hi.length == 7) return if (ly == hy) "${monthShort(lo)}–${monthShort(hi)} $ly" else "${monthShort(lo)} $ly–${monthShort(hi)} $hy"
            return "$lo to $hi"
        }
        return if (lo != null) "from ${monthLong(lo)}" else "to ${monthLong(hi!!)}"
    }

    private fun monthLong(v: String): String {
        if (v.length == 7 && v[4] == '-') v.substring(5).toIntOrNull()?.let { m -> if (m in 1..12) return "${MONTHS[m - 1]} ${v.take(4)}" }
        return v
    }

    private fun monthShort(v: String): String {
        if (v.length == 7 && v[4] == '-') v.substring(5).toIntOrNull()?.let { m -> if (m in 1..12) return MONTHS[m - 1].take(3) }
        return v
    }

    fun humanise(name: String): String = name.removePrefix("c_").replace('_', ' ')

    fun opSymbol(op: String): String = when (op) { "eq" -> "="; "neq" -> "≠"; "gt" -> ">"; "gte" -> "≥"; "lt" -> "<"; "lte" -> "≤"; "in" -> "in"; else -> op }

    fun valueText(v: JsonElement): String = when (v) {
        is JsonNull -> "null"
        is JsonPrimitive -> v.content
        is JsonArray -> "(" + v.joinToString(", ") { valueText(it) } + ")"
        else -> v.toString()
    }
}
