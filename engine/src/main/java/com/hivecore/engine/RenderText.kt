package com.hivecore.engine

import com.hivecore.core.DocAnswer
import java.math.BigDecimal
import java.math.RoundingMode

/** Deterministic text helpers the commander's renderer shares with the owner-side answer renderer (spec section 12.4). */
object RenderText {
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

    fun renderDoc(d: DocAnswer): String {
        val text = d.text.trim().let { if (it.isEmpty() || it.last() in ".!?") it else "$it." }
        return "$text (${d.doc}, ${d.locator})"
    }
}
