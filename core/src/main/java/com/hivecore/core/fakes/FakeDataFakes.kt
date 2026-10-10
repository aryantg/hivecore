package com.hivecore.core.fakes

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.AggTable
import com.hivecore.core.AnswerChip
import com.hivecore.core.AnswerRenderer
import com.hivecore.core.Compiled
import com.hivecore.core.DataAdmin
import com.hivecore.core.DocAnswer
import com.hivecore.core.Hashing
import com.hivecore.core.Intent
import com.hivecore.core.IntentCompiler
import com.hivecore.core.Joiner
import com.hivecore.core.NodeOutcome
import com.hivecore.core.NodeRole
import com.hivecore.core.QueryExecutor
import com.hivecore.core.Schema
import com.hivecore.core.SqlDb
import com.hivecore.core.TableDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Records what was asked of it; seeding is a no-op. */
class FakeDataAdmin : DataAdmin {
    val seeded = mutableListOf<NodeRole>()
    val deletedTables = mutableListOf<String>()
    val deletedDocs = mutableListOf<String>()
    override suspend fun seed(role: NodeRole) { seeded.add(role) }
    override fun deleteTable(name: String) { deletedTables.add(name) }
    override fun deleteDoc(id: String) { deletedDocs.add(id) }
}

/**
 * A compact version of the plan 7 compile rules (1-9, first failure wins) producing the plan's SQL shape, so
 * `ask` and `ui` tests see realistic Query/Refused/NotRelevant results. The real IntentCompiler in `data` is
 * the reference; this one is intentionally simpler (no rollup signatures, plain descriptions).
 */
class FakeIntentCompiler(val groupFloor: Int = 5) : IntentCompiler {
    private val metrics = setOf("count", "sum", "avg", "min", "max")
    private val ops = mapOf("eq" to "=", "neq" to "<>", "gt" to ">", "gte" to ">=", "lt" to "<", "lte" to "<=")

    override fun compile(intent: Intent, schema: Schema): Compiled {
        val table = schema.tables.firstOrNull { it.name == intent.table || intent.table in it.aliases }
            ?: return Compiled.NotRelevant("unknown table ${intent.table}")
        val cols = table.columns.associateBy { it.name }
        val col = intent.column?.let { cols[it] ?: return Compiled.NotRelevant("unknown column $it") }
        for (g in intent.groupBy) if (g !in cols) return Compiled.NotRelevant("unknown column $g")
        for (f in intent.filters) if (f.column !in cols) return Compiled.NotRelevant("unknown column ${f.column}")
        if (table.isSensitive) intent.filters.firstOrNull { cols.getValue(it.column).identifier }?.let {
            return Compiled.Refused(intent, "filters on ${it.column} are not allowed on confidential data")
        }
        if (intent.metric !in metrics) return Compiled.Refused(intent, "unknown metric ${intent.metric}")
        if (intent.metric != "count") {
            if (col == null) return Compiled.Refused(intent, "${intent.metric} needs a column")
            if (col.type == com.hivecore.core.ColType.TEXT) return Compiled.Refused(intent, "${intent.metric} needs a numeric column")
            if (col.confidential && intent.metric !in setOf("avg", "count"))
                return Compiled.Refused(intent, "only average and count are allowed on confidential column ${col.name}")
        }
        intent.groupBy.firstOrNull { !cols.getValue(it).groupable }?.let {
            return Compiled.Refused(intent, "cannot group by $it; this table allows: ${table.columns.filter { c -> c.groupable }.joinToString { c -> c.name }}")
        }
        for (f in intent.filters) if (f.op !in ops && f.op != "in") return Compiled.Refused(intent, "unknown operator ${f.op}")

        val args = ArrayList<Any?>()
        val where = intent.filters.map { f ->
            if (f.op == "in") {
                val items = (f.value as? JsonArray)?.toList() ?: listOf(f.value)
                items.forEach { args.add(toArg(it)) }
                "${f.column} IN (${items.joinToString { "?" }})"
            } else {
                args.add(toArg(f.value)); "${f.column} ${ops.getValue(f.op)} ?"
            }
        }
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val g = intent.groupBy.joinToString(", ")
        val agg = if (intent.metric == "count" || col == null) "" else ", ${intent.metric.uppercase()}(${col.name}) AS ${intent.metric}_${col.name}"
        val sql = "SELECT " + (if (g.isEmpty()) "" else "$g, ") + "COUNT(*) AS n$agg FROM t_${table.name}$whereSql" +
            (if (g.isEmpty()) "" else " GROUP BY $g") + " HAVING COUNT(*) >= $groupFloor" +
            (if (g.isEmpty()) "" else " ORDER BY $g") + " LIMIT 50"
        val countSql = "SELECT COUNT(*) FROM t_${table.name}$whereSql"
        val touches = table.confidential || col?.confidential == true ||
            intent.groupBy.any { cols.getValue(it).confidential } || intent.filters.any { cols.getValue(it.column).confidential }
        val description = buildString {
            append(when (intent.metric) { "count" -> "Count"; "avg" -> "Average"; "sum" -> "Sum"; "min" -> "Minimum"; else -> "Maximum" })
            if (col != null) append(" of ${col.name}")
            if (intent.groupBy.isNotEmpty()) append(" by ${intent.groupBy.joinToString(", ")}")
            if (intent.filters.isNotEmpty()) append(", where " + intent.filters.joinToString(" and ") { "${it.column} ${it.op} ${it.value}" })
            append(", over ${table.name}")
            if (touches) append(" (confidential: ${col?.name ?: table.name})")
            append(". Each group shows its count; groups under $groupFloor rows are hidden.")
        }
        return Compiled.Query(intent, sql, args, countSql, touches, description, canonical(intent))
    }

    private fun toArg(v: JsonElement): Any? = when (v) {
        is JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else v.longOrNull ?: v.doubleOrNull ?: v.booleanOrNull?.let { if (it) 1L else 0L } ?: v.content
        else -> v.toString()
    }

    companion object {
        /** Canonical JSON of an intent with sorted keys (the hash input). */
        fun canonical(i: Intent): String = buildString {
            append("{\"column\":").append(i.column?.let { "\"$it\"" } ?: "null")
            append(",\"filters\":[").append(i.filters.joinToString(",") { "{\"column\":\"${it.column}\",\"op\":\"${it.op}\",\"value\":${it.value}}" }).append("]")
            append(",\"group_by\":[").append(i.groupBy.joinToString(",") { "\"$it\"" }).append("]")
            append(",\"metric\":\"").append(i.metric).append("\",\"table\":\"").append(i.table).append("\"}")
        }
    }
}

/**
 * Canned [NodeOutcome] per table name; otherwise, when a [SqlDb] is given, runs the query for real (JVM tests
 * with JdbcSqlDb); otherwise an empty table. `rowsScanned` comes from `countSql` when a db is present.
 */
class FakeQueryExecutor(val db: SqlDb? = null, var chip: AnswerChip = AnswerChip.LLM) : QueryExecutor {
    val canned = HashMap<String, NodeOutcome>()
    val runs = mutableListOf<Compiled.Query>()

    override fun run(q: Compiled.Query, table: TableDef, node: String): NodeOutcome {
        runs.add(q)
        canned[table.name]?.let { return it }
        val hash = Hashing.sha256Hex(q.signature)
        val title = q.description.substringBefore(", over").substringBefore(". ")
        if (db == null) return NodeOutcome.Tables(listOf(AggTable(node, title, listOf("n"), emptyList(), 0, table.rowCount, "sql")), chip, hash)
        val scanned = (db.query(q.countSql, q.args).firstOrNull()?.values?.firstOrNull() as? Long) ?: 0L
        val rows = db.query(q.sql, q.args)
        val columns = rows.firstOrNull()?.keys?.toList() ?: listOf("n")
        if (rows.isEmpty() && scanned > 0) return NodeOutcome.Refused("every group has fewer than 5 rows")
        val json = rows.map { r -> columns.map { c -> toJson(r[c]) } }
        return NodeOutcome.Tables(listOf(AggTable(node, title, columns, json, scanned, table.rowCount, "sql")), chip, hash)
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Long -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v.toLong())
        is Double -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v.toDouble())
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}

/** In-memory join of the first table per node on their shared non-measure columns (nested loop). */
class FakeJoiner : Joiner {
    var canned: AggTable? = null
    private val measure = Regex("^(n|count_.*|sum_.*|avg_.*|min_.*|max_.*)$")

    override fun join(tables: List<AggTable>): AggTable? {
        canned?.let { return it }
        val perNode = LinkedHashMap<String, AggTable>()
        for (t in tables) perNode.putIfAbsent(t.node, t)
        if (perNode.size < 2) return null
        val list = perNode.values.toList()
        val shared = list.map { t -> t.columns.filterNot { measure.matches(it) }.toSet() }.reduce { a, b -> a intersect b }.toList()
        if (shared.isEmpty()) return null
        fun key(t: AggTable, row: List<JsonElement>) = shared.map { row[t.columns.indexOf(it)] }
        fun measures(t: AggTable) = t.columns.filter { measure.matches(it) }
        val outCols = shared + list.flatMap { t -> measures(t).map { "${t.node}_$it" } }
        var acc: List<Pair<List<JsonElement>, List<JsonElement>>> = list[0].rows.map { key(list[0], it) to measures(list[0]).map { m -> it[list[0].columns.indexOf(m)] } }
        for (t in list.drop(1)) {
            val index = t.rows.groupBy { key(t, it) }
            acc = acc.flatMap { (k, vals) -> (index[k] ?: emptyList()).map { r -> k to (vals + measures(t).map { m -> r[t.columns.indexOf(m)] }) } }
        }
        return AggTable("joined", list.joinToString(" + ") { it.node }, outCols, acc.map { it.first + it.second },
            list.sumOf { it.rowsScanned }, list.sumOf { it.rowsHeld }, "sql")
    }
}

/** Plain deterministic text, no Indian digit grouping (the real renderer lives in `data`). */
class FakeAnswerRenderer : AnswerRenderer {
    override fun render(tables: List<AggTable>, joined: AggTable?, docs: List<DocAnswer>): String {
        val parts = ArrayList<String>()
        for (t in (joined?.let { listOf(it) } ?: tables)) {
            if (t.rows.isEmpty()) { parts.add("${t.title}: no rows match"); continue }
            parts.add("${t.title}: " + t.rows.joinToString(" · ") { r -> t.columns.indices.joinToString(" ") { i -> "${t.columns[i]}=${r[i]}" } })
        }
        for (d in docs) parts.add("${d.text} (${d.doc}, ${d.locator})")
        return parts.joinToString("\n")
    }
    override fun refusal(reasons: List<Pair<String, String>>): String = reasons.joinToString(" ") { "${it.first} refused: ${it.second}" }
    override fun noNode(holdings: List<Pair<String, List<String>>>): String =
        "No node holds data about that. " + holdings.joinToString(" · ") { "${it.first} holds ${it.second.joinToString(", ")}" }
}
