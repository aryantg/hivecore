package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.Compiled
import com.hivecore.core.DifferencingGuard
import com.hivecore.core.Intent
import com.hivecore.core.IntentCompiler
import com.hivecore.core.SLog
import com.hivecore.core.Schema
import com.hivecore.core.SqlDb
import com.hivecore.core.TableDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Owner-side differencing check for composite plans (swarm plan §7, rule R7).
 *
 * Two figures over the same sensitive table can be subtracted: "average commission in West" and "average commission
 * in West for reps who joined before 2024-01" together give the average of the (few) reps who joined later. For every
 * pair of intents on the same sensitive table the guard counts, in SQL, the people in the symmetric difference of the
 * two row sets (rows matching A but not B, plus B but not A), as `COUNT(DISTINCT <identifier>)` for every identifier
 * column (rows when the table has none, like the compiler's floor). If any count is above 0 and below
 * [RuleIntentCompiler.GROUP_FLOOR] the whole plan is refused.
 *
 * Group-by intents release one figure per group: every released group is a cell whose row set is the intent's
 * filters plus `group column = group value`. Cells under the group floor are hidden by the compiler's HAVING and are
 * not released, so they are skipped. Two released cells that share no row differ by at least 2 x floor people, so only
 * overlapping cell pairs (found with one SQL query per intent pair) are counted.
 *
 * Across plans the guard remembers answered intents per requester for [WINDOW_MS] in `_answered`, a table it creates
 * itself, and checks new intents against them as well.
 *
 * Intents are resolved through the compiler (aliases to registry names). A [Compiled.Refused] intent that resolved is
 * still checked: refusing too much is safe, and the guard does not rely on the compiler's filter rules (defence in
 * depth). Identifiers come only from the schema; every value is a bind parameter.
 */
class SqlDifferencingGuard(
    private val db: SqlDb,
    private val compiler: IntentCompiler = RuleIntentCompiler(),
    private val clock: () -> Long = System::currentTimeMillis,
) : DifferencingGuard {

    @Volatile private var tableReady = false

    private fun ensureTable() {
        if (tableReady) return
        db.exec("CREATE TABLE IF NOT EXISTS _answered (requester TEXT NOT NULL, intentJson TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.exec("CREATE INDEX IF NOT EXISTS _answered_requester_ts ON _answered (requester, ts)")
        tableReady = true
    }

    fun check(requester: String, intents: List<Intent>, schema: Schema): String? = check(requester, intents, schema, clock())
    fun remember(requester: String, intents: List<Intent>) = remember(requester, intents, clock())

    override fun check(requester: String, intents: List<Intent>, schema: Schema, nowMs: Long): String? {
        val fresh = intents.mapNotNull { resolve(it, schema) }
        val past = answered(requester, nowMs).mapNotNull { resolve(it, schema) }
        val all = fresh + past
        val cells = HashMap<Int, List<Cell>>()
        for (i in fresh.indices) for (j in i + 1 until all.size) {
            val a = all[i]
            val b = all[j]
            if (a.table.name != b.table.name || !a.table.isSensitive) continue
            val ca = cells.getOrPut(i) { releasedCells(a) }
            val cb = cells.getOrPut(j) { releasedCells(b) }
            val n = smallestDifference(a, ca, b, cb) ?: continue
            SLog.w(TAG, "refused for $requester: ${IntentText.canonical(a.intent)} vs ${IntentText.canonical(b.intent)} " +
                "differ by $n people" + if (j >= fresh.size) " (answered earlier)" else "")
            return REFUSAL
        }
        return null
    }

    override fun remember(requester: String, intents: List<Intent>, nowMs: Long) {
        ensureTable()
        db.tx {
            db.exec("DELETE FROM _answered WHERE ts <= ?", listOf(nowMs - WINDOW_MS))
            for (i in intents) db.exec("INSERT INTO _answered (requester, intentJson, ts) VALUES (?, ?, ?)",
                listOf(requester, JSON.encodeToString(Intent.serializer(), i), nowMs))
        }
    }

    /** Intents answered for [requester] within the window ending at [nowMs]. */
    fun answered(requester: String, nowMs: Long): List<Intent> {
        ensureTable()
        return db.query("SELECT intentJson FROM _answered WHERE requester = ? AND ts > ? AND ts <= ? ORDER BY ts",
            listOf(requester, nowMs - WINDOW_MS, nowMs))
            .mapNotNull { r -> runCatching { JSON.decodeFromString(Intent.serializer(), r["intentJson"] as String) }.getOrNull() }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A resolved intent: its table, the WHERE condition with its bound values, and its group columns. */
    private class Resolved(val intent: Intent, val table: TableDef, val where: String, val args: List<Any?>, val groups: List<ColumnDef>)

    /** One released figure: the group values (empty for an ungrouped intent). */
    private class Cell(val key: List<Any?>)

    private fun resolve(raw: Intent, schema: Schema): Resolved? {
        val intent = when (val c = compiler.compile(raw, schema)) {
            is Compiled.Query -> c.intent
            is Compiled.Refused -> c.intent ?: return null
            is Compiled.NotRelevant -> return null
        }
        val table = schema.tables.firstOrNull { it.name == intent.table } ?: return null
        val col = { n: String -> table.columns.firstOrNull { it.name == n } }
        val groups = intent.groupBy.map { col(it) ?: return null }
        val parts = ArrayList<String>()
        val args = ArrayList<Any?>()
        for (f in intent.filters) {
            val c = col(f.column) ?: return null
            val op = f.op.trim().lowercase()
            if (op == "in") {
                val items = (f.value as? JsonArray)?.toList() ?: listOf(f.value)
                if (items.isEmpty()) return null
                items.forEach { args.add(bindValue(it, c.type)) }
                parts.add("${DataStore.q(c.name)} IN (${items.joinToString(", ") { "?" }})")
            } else {
                val sqlOp = RuleIntentCompiler.OPS[op] ?: return null
                if (f.value is JsonArray || f.value is JsonObject) return null
                args.add(bindValue(f.value, c.type))
                parts.add("${DataStore.q(c.name)} $sqlOp ?")
            }
        }
        val where = if (parts.isEmpty()) "1" else parts.joinToString(" AND ")
        return Resolved(intent, table, where, args, groups)
    }

    private fun tbl(r: Resolved) = DataStore.q("t_${r.table.name}")

    /** `COUNT(DISTINCT id) AS p0, ...` over every identifier, or `COUNT(*) AS p0` when the table has none. */
    private fun peopleCols(table: TableDef): String {
        val ids = RuleIntentCompiler.floorColumns(table)
        return if (ids.isEmpty()) "COUNT(*) AS p0" else ids.mapIndexed { i, c -> "COUNT(DISTINCT ${DataStore.q(c)}) AS p$i" }.joinToString(", ")
    }

    private fun people(row: Map<String, Any?>): List<Long> = row.filterKeys { it.startsWith("p") && it.drop(1).all(Char::isDigit) }.values.map { (it as Number).toLong() }

    /** The cells the compiler's query would release (every people count at or above the floor). */
    private fun releasedCells(r: Resolved): List<Cell> {
        val g = r.groups.mapIndexed { i, c -> "${DataStore.q(c.name)} AS g$i" }
        val sql = "SELECT " + (if (g.isEmpty()) "" else g.joinToString(", ") + ", ") + peopleCols(r.table) +
            " FROM ${tbl(r)} WHERE ${r.where}" + (if (g.isEmpty()) "" else " GROUP BY " + r.groups.joinToString(", ") { DataStore.q(it.name) })
        return db.query(sql, r.args)
            .filter { row -> people(row).all { it >= RuleIntentCompiler.GROUP_FLOOR } }
            .map { row -> Cell(r.groups.indices.map { row["g$it"] }) }
    }

    /** The row condition of one cell: the intent's WHERE plus `group column = value` (IS NULL for a null group). */
    private fun cellCondition(r: Resolved, cell: Cell): Pair<String, List<Any?>> {
        val parts = arrayListOf("(${r.where})")
        val args = ArrayList(r.args)
        r.groups.forEachIndexed { i, c ->
            val v = cell.key[i]
            if (v == null) parts.add("${DataStore.q(c.name)} IS NULL") else { parts.add("${DataStore.q(c.name)} = ?"); args.add(v) }
        }
        return parts.joinToString(" AND ") to args
    }

    /** The smallest people count in (0, floor) over every overlapping pair of released cells, or null. */
    private fun smallestDifference(a: Resolved, ca: List<Cell>, b: Resolved, cb: List<Cell>): Long? {
        if (ca.isEmpty() || cb.isEmpty()) return null
        val ga = a.groups.mapIndexed { i, c -> "${DataStore.q(c.name)} AS a$i" }
        val gb = b.groups.mapIndexed { i, c -> "${DataStore.q(c.name)} AS b$i" }
        val sel = (ga + gb).ifEmpty { listOf("1 AS one") }.joinToString(", ")
        val overlaps = db.query("SELECT DISTINCT $sel FROM ${tbl(a)} WHERE (${a.where}) AND (${b.where})", a.args + b.args)
        val releasedA = ca.map { it.key }.toSet()
        val releasedB = cb.map { it.key }.toSet()
        var worst: Long? = null
        for (row in overlaps) {
            val ka = a.groups.indices.map { row["a$it"] }
            val kb = b.groups.indices.map { row["b$it"] }
            if (ka !in releasedA || kb !in releasedB) continue
            val (condA, argsA) = cellCondition(a, Cell(ka))
            val (condB, argsB) = cellCondition(b, Cell(kb))
            val inA = "COALESCE(($condA), 0)"
            val inB = "COALESCE(($condB), 0)"
            val counts = db.query("SELECT ${peopleCols(a.table)} FROM ${tbl(a)} WHERE $inA + $inB = 1", argsA + argsB)
                .firstOrNull()?.let { people(it) } ?: continue
            for (n in counts) if (n in 1 until RuleIntentCompiler.GROUP_FLOOR) worst = minOf(worst ?: n, n)
        }
        return worst
    }

    /** A JSON value to its bound form, coerced towards the column's type (same rules as the compiler). */
    private fun bindValue(v: JsonElement, type: ColType): Any? {
        if (v is JsonNull) return null
        if (v !is JsonPrimitive) return v.toString()
        val raw: Any = when {
            v.isString -> v.content
            else -> v.longOrNull ?: v.doubleOrNull ?: v.booleanOrNull?.let { if (it) 1L else 0L } ?: v.content
        }
        return when (type) {
            ColType.INTEGER -> when (raw) { is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong() else raw; is String -> raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull() ?: raw; else -> raw }
            ColType.REAL -> when (raw) { is Long -> raw.toDouble(); is String -> raw.trim().toDoubleOrNull() ?: raw; else -> raw }
            ColType.TEXT -> when (raw) { is String -> raw; is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong().toString() else raw.toString(); else -> raw.toString() }
        }
    }

    companion object {
        private const val TAG = "Differencing"
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        val REFUSAL = "these figures together would reveal fewer than ${RuleIntentCompiler.GROUP_FLOOR} people"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
