package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.engine.workload.RecipeSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * The items of a `table` recipe (Kavira data amendment): the rows of one of this phone's tables, grouped by the
 * source's key columns, with the measures summed or averaged per key and `by` value. One item per distinct key,
 * `{"id": key, "item": key, "text": "<key line>\n<by>: measure value, …"}`, shipped under `texts` (the key `TextInputs` reads, one unit per `id`) and
 * `LLM_MAP` fills into `{item}`.
 *
 * Only the owner's registry names are used and every value is a bind parameter. Confidential columns and private
 * tables are refused outright: a fan-out over a table is work for other phones, and pay data never travels (§7).
 */
class RecipeItems(private val store: DataStore) {

    fun items(src: RecipeSource): List<JsonElement> {
        val t = store.table(src.table)
            ?: throw IllegalArgumentException("the ${src.table} table is not on this phone; start this job on the phone that holds it")
        if (store.isTablePrivate(t.name)) throw IllegalArgumentException("${t.name} is private on this phone, so its rows cannot be fanned out")
        val cols = t.columns.associateBy { it.name }
        for (c in src.key + src.by + src.sum + src.avg + src.where.keys) {
            val d = cols[c] ?: throw IllegalArgumentException("${t.name} has no column $c")
            if (d.confidential) throw IllegalArgumentException("${t.name}.$c is confidential; a table recipe never reads confidential columns")
        }
        for (c in src.sum + src.avg) if (cols.getValue(c).type == ColType.TEXT) throw IllegalArgumentException("${t.name}.$c is text and cannot be summed or averaged")
        val groups = src.key + src.by
        val q = DataStore::q
        val selects = groups.map { q(it) } +
            src.sum.map { "SUM(${q(it)}) AS ${q("sum_$it")}" } +
            src.avg.map { "AVG(${q(it)}) AS ${q("avg_$it")}" } +
            "COUNT(*) AS n"
        val where = src.where.entries.filter { it.value.isNotEmpty() }
        val sql = "SELECT ${selects.joinToString(", ")} FROM ${q("t_${t.name}")}" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ") { (c, vs) -> "${q(c)} IN (${vs.joinToString(", ") { "?" }})" }) +
            " GROUP BY ${groups.joinToString(", ") { q(it) }} ORDER BY ${groups.joinToString(", ") { q(it) }}"
        val rows = store.db.query(sql, where.flatMap { it.value })
        val byKey = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (r in rows) byKey.getOrPut(src.key.joinToString(" · ") { r[it]?.toString().orEmpty() }) { ArrayList() } += r
        return byKey.entries.take(src.limit).map { (k, rs) -> buildJsonObject { put("id", k); put("item", k); put("text", text(src, rs)) } }
    }

    /** "stockist_id: KA-01; town: Mysuru; branch: BLR\nFY2025: stockist_sales 12,34,000; avg_payment_days 46.0; rows 1\n…" */
    private fun text(src: RecipeSource, rs: List<Map<String, Any?>>): String {
        val sb = StringBuilder()
        val first = rs.first()
        sb.append(src.key.joinToString("; ") { "$it: ${first[it]?.toString().orEmpty()}" })
        for (r in rs) {
            sb.append('\n')
            if (src.by.isNotEmpty()) sb.append(src.by.joinToString(" ") { r[it]?.toString().orEmpty() }).append(": ")
            val parts = src.sum.map { "$it ${num(r["sum_$it"])}" } + src.avg.map { "$it ${num(r["avg_$it"], decimals = 1)}" } + "rows ${num(r["n"])}"
            sb.append(parts.joinToString("; "))
        }
        return sb.toString()
    }

    companion object {
        /** Plain thousands grouping; averages keep one decimal. */
        fun num(v: Any?, decimals: Int = 0): String = when (v) {
            null -> ""
            is Long, is Int -> String.format(Locale.ROOT, "%,d", (v as Number).toLong())
            is Number -> if (decimals == 0 && v.toDouble() == Math.rint(v.toDouble())) String.format(Locale.ROOT, "%,d", v.toLong()) else String.format(Locale.ROOT, "%,.${decimals}f", v.toDouble())
            else -> v.toString()
        }
    }
}
