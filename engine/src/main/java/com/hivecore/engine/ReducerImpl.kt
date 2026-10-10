package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.AggTable
import com.hivecore.core.Op
import com.hivecore.core.Reducer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Swarm plan §4 commander ops: pure Kotlin over [AggTable] JSON (R2: the model never does arithmetic). Every output
 * is an AggTable with `node = "swarm"`, encoded by [Canon.encodeTable]; notes (division by zero, unmatched keys) ride
 * in an extra `notes` array. Numbers: Long when exact, else Double rounded half-up to 4 decimals ([Canon.number]).
 * Row order is deterministic: by key ascending (numbers numerically, then text), unless the op orders by value.
 *
 * Params (all strings unless noted):
 *  - JOIN (≥ 2 tables): optional `key` (string or array); default = the non-measure columns all tables share.
 *    Measures are renamed `<node>_<measure>` as in data/Joiner.
 *  - RATIO / DIFF / SHARE (2 tables): `key`, `num` (measure in table 1), `den` (measure in table 2). RATIO = num/den,
 *    DIFF = num − den, SHARE = 100·num/den. SHARE over one table: `num` per row over the column total.
 *  - TREND (1 table): `measure`, optional `month` (default "month"); other non-measure columns group the series.
 *  - RANK (1 table): `measure`, optional `k` (number), `order` asc|desc (default desc); ties by key ascending.
 *  - THRESHOLD (1 table): `measure`, `op` gt|gte|lt|lte, and `value` (number) or `reference: "mean"`.
 *  - RECONCILE (2 tables): optional `key`, `a`, `b` (default: each table's first measure), optional `tol` (number).
 */
class ReducerImpl : Reducer {

    override fun run(op: Op, params: JsonObject, inputs: List<JsonElement>): Result<JsonElement> = runCatching {
        val tables = inputs.mapIndexed { i, e ->
            try { Canon.decodeTable(e) } catch (ex: Exception) { throw IllegalArgumentException("input ${i + 1} is not an AggTable: ${ex.message}") }
        }
        when (op) {
            Op.JOIN -> join(params, tables)
            Op.RATIO, Op.DIFF, Op.SHARE -> arith(op, params, tables)
            Op.TREND -> trend(params, tables)
            Op.RANK -> rank(params, tables)
            Op.THRESHOLD -> threshold(params, tables)
            Op.RECONCILE -> reconcile(params, tables)
            else -> throw IllegalArgumentException("$op is not a reduce op this reducer runs")
        }
    }

    // ------------------------------------------------------------------ JOIN

    private fun join(p: JsonObject, ts: List<AggTable>): JsonElement {
        require(ts.size >= 2) { "JOIN needs at least 2 tables, got ${ts.size}" }
        val key = keys(p) ?: ts.map { dims(it).toSet() }.reduce { a, b -> a intersect b }.let { s -> ts[0].columns.filter { it in s } }
        require(key.isNotEmpty()) { "JOIN: the tables share no key column" }
        ts.forEach { t -> key.forEach { k -> require(k in t.columns) { "JOIN: '${t.title}' has no key column '$k'" } } }
        val prefixes = prefixes(ts)
        val outCols = ArrayList(key)
        ts.forEachIndexed { i, t -> t.columns.filter { it !in key }.forEach { outCols += "${prefixes[i]}_$it" } }
        // Chained inner join: start from table 1's rows, extend with each further table's matching rows.
        var acc: List<Pair<List<JsonElement>, List<JsonElement>>> = ts[0].rows.map { r -> keyOf(ts[0], r, key) to valuesNot(ts[0], r, key) }
        for (t in ts.drop(1)) {
            val index = t.rows.groupBy { Canon.canonical(JsonArray(keyOf(t, it, key))) }
            acc = acc.flatMap { (k, vals) ->
                index[Canon.canonical(JsonArray(k))].orEmpty().map { r -> k to (vals + valuesNot(t, r, key)) }
            }
        }
        val rows = acc.sortedWith { a, b -> compareKeys(a.first, b.first) }.map { it.first + it.second }
        val notes = if (rows.isEmpty() && ts.all { it.rows.isNotEmpty() }) listOf("no key value of ${key.joinToString(", ")} is in every table") else emptyList()
        return out("Join of ${ts.joinToString(" + ") { it.title }} on ${key.joinToString(", ")}", outCols, rows, ts, notes)
    }

    // ------------------------------------------------------------------ RATIO / DIFF / SHARE

    private fun arith(op: Op, p: JsonObject, ts: List<AggTable>): JsonElement {
        val num = str(p, "num") ?: throw IllegalArgumentException("$op needs 'num'")
        if (op == Op.SHARE && ts.size == 1) return shareOfTotal(p, ts[0], num)
        require(ts.size == 2) { "$op needs 2 tables, got ${ts.size}" }
        val den = str(p, "den") ?: throw IllegalArgumentException("$op needs 'den'")
        val (a, b) = ts
        val key = keys(p) ?: dims(a).filter { it in dims(b) && it != num && it != den }
        // Keyless: one figure on each side (no dims at all, e.g. total revenue / headcount) → one row, no key columns.
        val keyless = key.isEmpty() && keys(p) == null && dims(a).isEmpty() && dims(b).isEmpty()
        if (keyless) {
            require(a.rows.size <= 1 && b.rows.size <= 1) { "$op: keyless tables must hold one row each (got ${a.rows.size} and ${b.rows.size})" }
        }
        require(key.isNotEmpty() || keyless) { "$op: no key column" }
        key.forEach { k -> require(k in a.columns && k in b.columns) { "$op: key '$k' is not in both tables" } }
        require(num in a.columns) { "$op: '${a.title}' has no column '$num'" }
        require(den in b.columns) { "$op: '${b.title}' has no column '$den'" }
        val notes = ArrayList<String>()
        val bIndex = LinkedHashMap<String, List<JsonElement>>()
        for (r in b.rows) bIndex.putIfAbsent(Canon.canonical(JsonArray(keyOf(b, r, key))), r)
        val seen = HashSet<String>()
        val rows = ArrayList<List<JsonElement>>()
        for (r in a.rows) {
            val k = keyOf(a, r, key)
            val ks = Canon.canonical(JsonArray(k))
            if (!seen.add(ks)) continue
            val br = bIndex[ks]
            if (br == null) { notes += "${label(k)}: not in '${b.title}'"; continue }
            val x = Canon.decimalLoose(r[a.columns.indexOf(num)])
            val y = Canon.decimalLoose(br[b.columns.indexOf(den)])
            val v: JsonElement = when {
                x == null || y == null -> { notes += "${label(k)}: missing value"; JsonNull }
                op == Op.DIFF -> Canon.number(x - y)
                y.signum() == 0 -> { notes += "${label(k)}: division by zero ($den is 0)"; JsonNull }
                op == Op.RATIO -> Canon.number(Canon.div(x, y))
                else -> Canon.number(Canon.div(x * HUNDRED, y))
            }
            rows += k + listOf(numOrNull(x), numOrNull(y), v)
        }
        for ((ks, br) in bIndex) if (ks !in seen) notes += "${label(keyOf(b, br, key))}: not in '${a.title}'"
        rows.sortWith { r1, r2 -> compareKeys(r1.subList(0, key.size), r2.subList(0, key.size)) }
        val valueCol = when (op) { Op.RATIO -> "ratio"; Op.DIFF -> "diff"; else -> "share_pct" }
        val numCol = if (num == den) "a_$num" else num
        val denCol = if (num == den) "b_$den" else den
        val by = if (key.isEmpty()) "" else " by ${key.joinToString(", ")}"
        val title = when (op) {
            Op.RATIO -> "$num per $den$by"
            Op.DIFF -> "$num minus $den$by"
            else -> "$num as % of $den$by"
        }
        return out(title, key + listOf(numCol, denCol, valueCol), rows, ts, notes)
    }

    private fun shareOfTotal(p: JsonObject, t: AggTable, num: String): JsonElement {
        require(num in t.columns) { "SHARE: '${t.title}' has no column '$num'" }
        val ni = t.columns.indexOf(num)
        val total = t.rows.mapNotNull { Canon.decimalLoose(it[ni]) }.fold(BigDecimal.ZERO, BigDecimal::add)
        val notes = ArrayList<String>()
        if (total.signum() == 0 && t.rows.isNotEmpty()) notes += "division by zero (total $num is 0)"
        val key = keys(p) ?: (dims(t) - num)
        val rows = t.rows.map { r ->
            val x = Canon.decimalLoose(r[ni])
            val v = if (x == null || total.signum() == 0) JsonNull else Canon.number(Canon.div(x * HUNDRED, total))
            keyOf(t, r, key) + listOf(numOrNull(x), v)
        }.sortedWith { a, b -> compareKeys(a.subList(0, key.size), b.subList(0, key.size)) }
        return out("$num as % of total by ${key.joinToString(", ")}", key + listOf(num, "share_pct"), rows, listOf(t), notes)
    }

    // ------------------------------------------------------------------ TREND

    private fun trend(p: JsonObject, ts: List<AggTable>): JsonElement {
        require(ts.size == 1) { "TREND needs 1 table, got ${ts.size}" }
        val t = ts[0]
        val measure = str(p, "measure") ?: throw IllegalArgumentException("TREND needs 'measure'")
        val month = str(p, "month") ?: "month"
        require(month in t.columns) { "TREND: '${t.title}' has no '$month' column" }
        require(measure in t.columns) { "TREND: '${t.title}' has no column '$measure'" }
        val group = dims(t).filter { it != month && it != measure }
        val mi = t.columns.indexOf(month)
        val vi = t.columns.indexOf(measure)
        val notes = ArrayList<String>()
        val series = t.rows.groupBy { keyOf(t, it, group) }.entries.sortedWith { a, b -> compareKeys(a.key, b.key) }
        val rows = ArrayList<List<JsonElement>>()
        for ((g, rs) in series) {
            var prev: BigDecimal? = null
            var first = true
            for (r in rs.sortedWith { a, b -> compareValues(a[mi], b[mi]) }) {
                val v = Canon.decimalLoose(r[vi])
                var delta: JsonElement = JsonNull
                var pct: JsonElement = JsonNull
                val direction = when {
                    first -> "start"
                    v == null || prev == null -> { notes += "${label(g + r[mi])}: missing value"; "unknown" }
                    else -> {
                        val d = v - prev
                        delta = Canon.number(d)
                        if (prev.signum() == 0) notes += "${label(g + r[mi])}: % change from 0 is undefined"
                        else pct = Canon.number(Canon.div(d * HUNDRED, prev.abs()))
                        when (d.signum()) { 1 -> "up"; -1 -> "down"; else -> "flat" }
                    }
                }
                rows += g + listOf(r[mi], numOrNull(v), delta, pct, JsonPrimitive(direction))
                prev = v
                first = false
            }
        }
        return out("Trend of $measure by $month" + (if (group.isEmpty()) "" else " per ${group.joinToString(", ")}"),
            group + listOf(month, "value", "delta", "pct", "direction"), rows, ts, notes)
    }

    // ------------------------------------------------------------------ RANK

    private fun rank(p: JsonObject, ts: List<AggTable>): JsonElement {
        require(ts.size == 1) { "RANK needs 1 table, got ${ts.size}" }
        val t = ts[0]
        val measure = str(p, "measure") ?: throw IllegalArgumentException("RANK needs 'measure'")
        require(measure in t.columns) { "RANK: '${t.title}' has no column '$measure'" }
        val order = (str(p, "order") ?: "desc").lowercase()
        require(order == "asc" || order == "desc") { "RANK: order must be asc or desc" }
        val k = (p["k"] as? JsonPrimitive)?.content?.toIntOrNull()
        require(k == null || k >= 0) { "RANK: k must be ≥ 0" }
        val key = keys(p) ?: (dims(t) - measure)
        val vi = t.columns.indexOf(measure)
        val sorted = t.rows.sortedWith { a, b ->
            val x = Canon.decimalLoose(a[vi]); val y = Canon.decimalLoose(b[vi])
            val c = when {
                x == null && y == null -> 0
                x == null -> 1          // nulls last either way
                y == null -> -1
                order == "desc" -> y.compareTo(x)
                else -> x.compareTo(y)
            }
            if (c != 0) c else compareKeys(keyOf(t, a, key), keyOf(t, b, key))
        }
        val top = if (k == null) sorted else sorted.take(k)
        val rows = top.mapIndexed { i, r -> listOf<JsonElement>(JsonPrimitive((i + 1).toLong())) + r }
        val title = (if (k != null) "Top $k " else "Ranking ") + "by $measure ${if (order == "desc") "(highest first)" else "(lowest first)"}"
        return out(title, listOf("rank") + t.columns, rows, ts, emptyList())
    }

    // ------------------------------------------------------------------ THRESHOLD

    private fun threshold(p: JsonObject, ts: List<AggTable>): JsonElement {
        require(ts.size == 1) { "THRESHOLD needs 1 table, got ${ts.size}" }
        val t = ts[0]
        val measure = str(p, "measure") ?: throw IllegalArgumentException("THRESHOLD needs 'measure'")
        require(measure in t.columns) { "THRESHOLD: '${t.title}' has no column '$measure'" }
        val cmp = (str(p, "op") ?: throw IllegalArgumentException("THRESHOLD needs 'op'")).lowercase()
        require(cmp in setOf("gt", "gte", "lt", "lte")) { "THRESHOLD: op must be gt, gte, lt or lte" }
        val vi = t.columns.indexOf(measure)
        val notes = ArrayList<String>()
        val ref: BigDecimal? = when {
            p["value"] != null -> Canon.decimalLoose(p["value"]) ?: throw IllegalArgumentException("THRESHOLD: value is not a number")
            str(p, "reference") == "mean" -> {
                val vs = t.rows.mapNotNull { Canon.decimalLoose(it[vi]) }
                if (vs.isEmpty()) { notes += "no values to average"; null }
                else Canon.div(vs.fold(BigDecimal.ZERO, BigDecimal::add), BigDecimal(vs.size))
            }
            else -> throw IllegalArgumentException("THRESHOLD needs 'value' or reference \"mean\"")
        }
        val key = keys(p) ?: (dims(t) - measure)
        val rows = if (ref == null) emptyList() else t.rows.filter { r ->
            val x = Canon.decimalLoose(r[vi]) ?: return@filter false
            val c = x.compareTo(ref)
            when (cmp) { "gt" -> c > 0; "gte" -> c >= 0; "lt" -> c < 0; else -> c <= 0 }
        }.sortedWith { a, b -> compareKeys(keyOf(t, a, key), keyOf(t, b, key)) }
        val sym = mapOf("gt" to ">", "gte" to "≥", "lt" to "<", "lte" to "≤").getValue(cmp)
        val refText = ref?.let { Canon.number(it).content } ?: "n/a"
        val title = "$measure $sym $refText" + if (str(p, "reference") == "mean" && p["value"] == null) " (mean)" else ""
        return out(title, t.columns, rows, ts, notes)
    }

    // ------------------------------------------------------------------ RECONCILE

    private fun reconcile(p: JsonObject, ts: List<AggTable>): JsonElement {
        require(ts.size == 2) { "RECONCILE needs 2 tables, got ${ts.size}" }
        val (a, b) = ts
        val key = keys(p) ?: dims(a).filter { it in dims(b) && it != str(p, "a") && it != str(p, "b") }
        require(key.isNotEmpty()) { "RECONCILE: the tables share no key column" }
        key.forEach { k -> require(k in a.columns && k in b.columns) { "RECONCILE: key '$k' is not in both tables" } }
        val am = str(p, "a") ?: a.columns.firstOrNull { it !in key } ?: throw IllegalArgumentException("RECONCILE: '${a.title}' has no measure")
        val bm = str(p, "b") ?: b.columns.firstOrNull { it !in key } ?: throw IllegalArgumentException("RECONCILE: '${b.title}' has no measure")
        require(am in a.columns) { "RECONCILE: '${a.title}' has no column '$am'" }
        require(bm in b.columns) { "RECONCILE: '${b.title}' has no column '$bm'" }
        val tol = Canon.decimalLoose(p["tol"]) ?: BigDecimal.ZERO
        val ai = a.columns.indexOf(am); val bi = b.columns.indexOf(bm)
        val av = LinkedHashMap<String, Pair<List<JsonElement>, JsonElement>>()
        a.rows.forEach { r -> val k = keyOf(a, r, key); av.putIfAbsent(Canon.canonical(JsonArray(k)), k to r[ai]) }
        val bv = LinkedHashMap<String, Pair<List<JsonElement>, JsonElement>>()
        b.rows.forEach { r -> val k = keyOf(b, r, key); bv.putIfAbsent(Canon.canonical(JsonArray(k)), k to r[bi]) }
        val rows = ArrayList<List<JsonElement>>()
        for (ks in (av.keys + bv.keys).distinct()) {
            val k = (av[ks] ?: bv.getValue(ks)).first
            val x = av[ks]?.second?.let { Canon.decimalLoose(it) }
            val y = bv[ks]?.second?.let { Canon.decimalLoose(it) }
            val diff: JsonElement = if (x != null && y != null) Canon.number(x - y) else JsonNull
            val mismatch = x == null || y == null || (x - y).abs() > tol
            if (mismatch) rows += k + listOf(numOrNull(x), numOrNull(y), diff)
        }
        rows.sortWith { r1, r2 -> compareKeys(r1.subList(0, key.size), r2.subList(0, key.size)) }
        val ac = if (am == bm) "a_$am" else am
        val bc = if (am == bm) "b_$bm" else bm
        return out("Mismatches of $am vs $bm by ${key.joinToString(", ")}", key + listOf(ac, bc, "diff"), rows, ts, emptyList())
    }

    // ------------------------------------------------------------------ helpers

    private fun out(title: String, columns: List<String>, rows: List<List<JsonElement>>, ins: List<AggTable>, notes: List<String>): JsonElement =
        Canon.encodeTable(AggTable(NODE, title, columns, rows, ins.sumOf { it.rowsScanned }, ins.sumOf { it.rowsHeld },
            if (ins.any { it.source == "rollup" }) "rollup" else "sql"), notes.distinct())

    private fun numOrNull(x: BigDecimal?): JsonElement = x?.let { Canon.number(it) } ?: JsonNull

    private fun str(p: JsonObject, k: String): String? = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun keys(p: JsonObject): List<String>? = when (val k = p["key"]) {
        is JsonPrimitive -> if (k.isString && k.content.isNotBlank()) listOf(k.content) else null
        is JsonArray -> k.mapNotNull { (it as? JsonPrimitive)?.content }.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun prefixes(ts: List<AggTable>): List<String> {
        val base = ts.map { it.node.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "t" } }
        return base.mapIndexed { i, b -> if (base.count { it == b } > 1) "${b}${i + 1}" else b }
    }

    private fun keyOf(t: AggTable, r: List<JsonElement>, key: List<String>): List<JsonElement> =
        key.map { k -> t.columns.indexOf(k).let { i -> if (i < 0 || i >= r.size) JsonNull else r[i] } }

    private fun valuesNot(t: AggTable, r: List<JsonElement>, key: List<String>): List<JsonElement> =
        t.columns.indices.filter { t.columns[it] !in key }.map { if (it < r.size) r[it] else JsonNull }

    private fun label(k: List<JsonElement>): String = k.joinToString("/") { (it as? JsonPrimitive)?.content ?: it.toString() }

    companion object {
        const val NODE = "swarm"
        private val HUNDRED = BigDecimal(100)
        private val MEASURE = Regex("^(n|count_.*|sum_.*|avg_.*|min_.*|max_.*)$")

        /** Same rule as data/Joiner: measure columns are `n`, `count_*`, `sum_*`, `avg_*`, `min_*`, `max_*`. */
        fun isMeasure(column: String): Boolean = MEASURE.matches(column)

        fun dims(t: AggTable): List<String> = t.columns.filterNot { isMeasure(it) }

        /** Total order on JSON scalars: null first, then numbers numerically, then strings; ties by canonical text. */
        fun compareValues(a: JsonElement, b: JsonElement): Int {
            if (a is JsonNull || b is JsonNull) return (if (a is JsonNull) 0 else 1) - (if (b is JsonNull) 0 else 1)
            val x = Canon.decimal(a); val y = Canon.decimal(b)
            if (x != null && y != null) return x.compareTo(y)
            if (x != null) return -1
            if (y != null) return 1
            val sa = (a as? JsonPrimitive)?.content ?: Canon.canonical(a)
            val sb = (b as? JsonPrimitive)?.content ?: Canon.canonical(b)
            return sa.compareTo(sb)
        }

        fun compareKeys(a: List<JsonElement>, b: List<JsonElement>): Int {
            for (i in 0 until minOf(a.size, b.size)) { val c = compareValues(a[i], b[i]); if (c != 0) return c }
            return a.size - b.size
        }
    }
}
