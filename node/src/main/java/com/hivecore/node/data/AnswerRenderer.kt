package com.hivecore.node.data

import com.hivecore.core.AggTable
import com.hivecore.core.AnswerRenderer
import com.hivecore.core.DocAnswer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic English with Indian digit grouping (plan 7 / 11):
 *  "Average commission in West: 85,760 (15 reps)"
 *  "Revenue by region, 2026: East 5,07,97,500 (246 rows) · North …"
 *  joined: "West: revenue 1,22,05,500, 15 reps · …"
 *  doc: "<sentence> (leave_policy.pdf, p.2)"
 */
class TextAnswerRenderer : AnswerRenderer {

    override fun render(tables: List<AggTable>, joined: AggTable?, docs: List<DocAnswer>): String {
        val parts = ArrayList<String>()
        if (joined != null) parts.add(renderJoined(joined))
        for (t in tables) {
            if (joined != null && joined.columns.any { it.startsWith(SqlJoiner.ident(t.node) + "_") }) continue
            parts.add(renderTable(t))
        }
        for (d in docs) parts.add(renderDoc(d))
        return parts.joinToString("\n")
    }

    override fun refusal(reasons: List<Pair<String, String>>): String =
        if (reasons.isEmpty()) "The question was refused." else reasons.joinToString(" ") { (node, reason) ->
            // An owner's decision already names the node ("declined by HR", "no approval from HR in time"): no "HR refused:" prefix.
            if (reason.startsWith("declined by ") || reason.startsWith("no approval from ")) sentence(reason.replaceFirstChar { it.uppercase() })
            else "$node refused: ${sentence(reason)}"
        }

    override fun noNode(holdings: List<Pair<String, List<String>>>): String {
        val head = "No node holds data about that."
        val listed = holdings.filter { it.second.isNotEmpty() }
        if (listed.isEmpty()) return head
        return head + " " + listed.joinToString(" · ") { (node, holds) -> "$node holds ${holds.joinToString(", ")}" } + "."
    }

    fun renderTable(t: AggTable): String {
        if (t.rows.isEmpty()) return "${t.title}: no rows match"
        val dims = t.columns.indices.filter { !SqlJoiner.isMeasure(t.columns[it]) }
        val nIdx = t.columns.indexOf("n")
        val metrics = t.columns.indices.filter { SqlJoiner.isMeasure(t.columns[it]) && it != nIdx }
        val noun = noun(t.node)
        val rows = t.rows.joinToString(" · ") { r ->
            val key = dims.joinToString(" / ") { text(r[it]) }
            val n = if (nIdx >= 0) asLong(r[nIdx]) else null
            val body = if (metrics.isEmpty()) {
                if (n != null) number(r[nIdx]) else ""
            } else {
                metrics.joinToString(", ") { number(r[it]) } + (if (n != null) " (${count(n, noun)})" else "")
            }
            if (key.isEmpty()) body else "$key $body"
        }
        return "${t.title}: $rows"
    }

    fun renderJoined(j: AggTable): String {
        if (j.rows.isEmpty()) return "${j.title}: no rows match"
        val dims = j.columns.indices.filter { !isJoinedMeasure(j.columns[it]) }
        // Group the measure columns by node prefix, in column order.
        val byNode = LinkedHashMap<String, MutableList<Int>>()
        for (i in j.columns.indices) if (i !in dims) {
            val col = j.columns[i]
            val node = if (col.endsWith("_n")) col.removeSuffix("_n") else nodePrefix(col)
            byNode.getOrPut(node) { ArrayList() }.add(i)
        }
        return j.rows.joinToString(" · ") { r ->
            val key = dims.joinToString(" / ") { text(r[it]) }
            val facts = byNode.entries.joinToString(", ") { (node, idxs) ->
                val nIdx = idxs.firstOrNull { j.columns[it] == "${node}_n" }
                val measures = idxs.filter { it != nIdx }
                if (measures.isEmpty()) count(nIdx?.let { asLong(r[it]) } ?: 0L, noun(node))
                else measures.joinToString(", ") { "${measureName(j.columns[it].removePrefix("${node}_"))} ${number(r[it])}" }
            }
            if (key.isEmpty()) facts else "$key: $facts"
        }
    }

    fun renderDoc(d: DocAnswer): String {
        val text = d.text.trim().let { if (it.isEmpty() || it.last() in ".!?" ) it else "$it." }
        return "$text (${d.doc}, ${d.locator})"
    }

    private fun isJoinedMeasure(c: String): Boolean = c.endsWith("_n") || Regex("^.+_(count|sum|avg|min|max)_.+$").matches(c)
    private fun nodePrefix(c: String): String = Regex("^(.+?)_(count|sum|avg|min|max)_.+$").find(c)?.groupValues?.get(1) ?: c

    private fun measureName(m: String): String = when {
        m.startsWith("sum_") -> IntentText.humanise(m.removePrefix("sum_"))
        m.startsWith("avg_") -> "average " + IntentText.humanise(m.removePrefix("avg_"))
        m.startsWith("min_") -> "minimum " + IntentText.humanise(m.removePrefix("min_"))
        m.startsWith("max_") -> "maximum " + IntentText.humanise(m.removePrefix("max_"))
        m.startsWith("count_") -> "count of " + IntentText.humanise(m.removePrefix("count_"))
        else -> IntentText.humanise(m)
    }

    private fun asLong(v: JsonElement): Long? = (v as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() }

    private fun count(n: Long, noun: String): String = "${indian(n)} " + if (n == 1L) noun.removeSuffix("s") else noun

    private fun sentence(s: String): String = s.trim().let { if (it.isEmpty() || it.last() in ".!?" ) it else "$it." }

    private fun text(v: JsonElement): String = when (v) {
        is JsonNull -> "(blank)"
        is JsonPrimitive -> if (v.isString) v.content else number(v)
        else -> v.toString()
    }

    private fun number(v: JsonElement): String = when (v) {
        is JsonNull -> "–"
        is JsonPrimitive -> if (v.isString) v.content else v.longOrNull?.let { indian(it) } ?: v.doubleOrNull?.let { indian(it) } ?: v.content
        else -> v.toString()
    }

    companion object {
        /** Row noun per node: HR counts reps, Accounts counts invoices, anything else rows. */
        fun noun(node: String): String = when (node.trim().lowercase()) { "hr" -> "reps"; "accounts" -> "invoices"; else -> "rows" }

        /** 12345678 -> "1,23,45,678" (Indian grouping: last three digits, then pairs). */
        fun indian(n: Long): String {
            val neg = n < 0
            val digits = (if (neg) -n else n).toString()
            if (digits.length <= 3) return (if (neg) "-" else "") + digits
            val last3 = digits.takeLast(3)
            val rest = digits.dropLast(3)
            val sb = StringBuilder()
            var i = rest.length
            while (i > 0) {
                val start = maxOf(0, i - 2)
                sb.insert(0, rest.substring(start, i))
                if (start > 0) sb.insert(0, ',')
                i = start
            }
            return (if (neg) "-" else "") + sb + "," + last3
        }

        /** Rounds to two decimals, drops a zero fraction, groups the integer part. */
        fun indian(d: Double): String {
            if (d.isNaN() || d.isInfinite()) return d.toString()
            val bd = BigDecimal(d).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
            val whole = bd.setScale(0, RoundingMode.DOWN).toLong()
            val fraction = bd.subtract(BigDecimal(whole)).abs()
            val head = if (whole == 0L && bd.signum() < 0) "-0" else indian(whole)
            return if (fraction.signum() == 0) head else head + "." + fraction.toPlainString().substringAfter('.')
        }
    }
}
