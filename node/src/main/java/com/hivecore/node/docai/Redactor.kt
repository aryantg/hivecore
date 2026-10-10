package com.hivecore.node.docai

import com.hivecore.core.OcrLine

/** One redacted span of the ORIGINAL text, [start] inclusive, [end] exclusive. The value itself is never logged. */
data class Redaction(val kind: String, val start: Int, val end: Int)

data class RedactedText(val text: String, val log: List<Redaction>)

/** An OCR line box to blur (the vision package blurs the pixels), with the kinds found on that line. */
data class RedactBox(val line: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val kinds: List<String>)

/**
 * Swarm plan §4A.3 REDACT (text part): Kotlin rules for Indian identifiers.
 *  - PAN: `[A-Z]{5}[0-9]{4}[A-Z]`;
 *  - Aadhaar: 12 digits, first 2-9, as `dddd dddd dddd` or `dddddddddddd`, and Verhoeff-valid;
 *  - phone: Indian mobiles (`+91`/`91`/`0` prefix optional, 10 digits starting 6-9, one optional space or hyphen
 *    after the fifth digit) and landlines written `0<STD>-<number>`;
 *  - email;
 *  - IFSC: `[A-Z]{4}0[A-Z0-9]{6}`;
 *  - bank account: 9-18 digits within [ACCOUNT_WINDOW] characters after "A/c", "account" or "acct".
 * Overlapping matches: the earlier one wins, then the longer, then the higher [KINDS] priority. Each span is
 * replaced by `[KIND]`.
 */
object Redactor {
    const val PAN = "PAN"
    const val AADHAAR = "AADHAAR"
    const val PHONE = "PHONE"
    const val EMAIL = "EMAIL"
    const val IFSC = "IFSC"
    const val ACCOUNT = "ACCOUNT"
    /** Priority order for spans that start at the same place with the same length. */
    val KINDS = listOf(EMAIL, AADHAAR, PAN, IFSC, ACCOUNT, PHONE)
    const val ACCOUNT_WINDOW = 40

    private val PAN_RE = Regex("(?<![A-Za-z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9])")
    private val AADHAAR_RE = Regex("(?<![0-9])[2-9][0-9]{3}(?:([ ])[0-9]{4}\\1[0-9]{4}|[0-9]{8})(?![0-9])")
    private val MOBILE_RE = Regex("(?<![0-9+])(?:\\+91[ -]?|91[ -]|0)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![0-9])")
    private val LANDLINE_RE = Regex("(?<![0-9])0[1-9][0-9]{1,3}-[0-9]{6,8}(?![0-9])")
    private val EMAIL_RE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val IFSC_RE = Regex("(?<![A-Za-z0-9])[A-Z]{4}0[A-Z0-9]{6}(?![A-Za-z0-9])")
    private val ACCOUNT_KEY_RE = Regex("(?<![A-Za-z])(?:a/c|acct|account)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val ACCOUNT_NO_RE = Regex("(?<![0-9])[0-9]{9,18}(?![0-9])")

    /** Every identifier span in [text], non-overlapping, in order. */
    fun find(text: String): List<Redaction> {
        val all = ArrayList<Redaction>()
        PAN_RE.findAll(text).forEach { all += Redaction(PAN, it.range.first, it.range.last + 1) }
        AADHAAR_RE.findAll(text).forEach { m -> if (verhoeffValid(m.value.filter { it.isDigit() })) all += Redaction(AADHAAR, m.range.first, m.range.last + 1) }
        MOBILE_RE.findAll(text).forEach { all += Redaction(PHONE, it.range.first, it.range.last + 1) }
        LANDLINE_RE.findAll(text).forEach { all += Redaction(PHONE, it.range.first, it.range.last + 1) }
        EMAIL_RE.findAll(text).forEach { all += Redaction(EMAIL, it.range.first, it.range.last + 1) }
        IFSC_RE.findAll(text).forEach { all += Redaction(IFSC, it.range.first, it.range.last + 1) }
        for (k in ACCOUNT_KEY_RE.findAll(text)) {
            val from = k.range.last + 1
            val to = minOf(text.length, from + ACCOUNT_WINDOW)
            ACCOUNT_NO_RE.find(text.substring(from, to))?.let { m ->
                // the window is cut at a fixed length: make sure the number really ends where the regex saw it end
                val end = from + m.range.last + 1
                if (end >= text.length || !text[end].isDigit()) all += Redaction(ACCOUNT, from + m.range.first, end)
            }
        }
        val sorted = all.distinct().sortedWith(compareBy<Redaction> { it.start }.thenByDescending { it.end - it.start }.thenBy { KINDS.indexOf(it.kind) })
        val out = ArrayList<Redaction>()
        var lastEnd = -1
        for (r in sorted) if (r.start >= lastEnd) { out += r; lastEnd = r.end }
        return out
    }

    fun containsIdentifier(text: String): Boolean = find(text).isNotEmpty()

    fun redact(text: String): RedactedText {
        val spans = find(text)
        if (spans.isEmpty()) return RedactedText(text, emptyList())
        val sb = StringBuilder(text.length)
        var at = 0
        for (r in spans) { sb.append(text, at, r.start).append('[').append(r.kind).append(']'); at = r.end }
        sb.append(text, at, text.length)
        return RedactedText(sb.toString(), spans)
    }

    /** The lines joined with '\n' (the text form [boxes] and the REDACT executor work on). */
    fun joinLines(lines: List<OcrLine>): String = lines.joinToString("\n") { it.text }

    /** Boxes of every OCR line that holds (part of) an identifier; detection runs over the joined text so a keyword on one line covers a number on the next. */
    fun boxes(lines: List<OcrLine>): List<RedactBox> {
        val spans = find(joinLines(lines))
        if (spans.isEmpty()) return emptyList()
        val out = ArrayList<RedactBox>()
        var offset = 0
        for ((i, l) in lines.withIndex()) {
            val start = offset; val end = offset + l.text.length
            val kinds = spans.filter { it.start < end && it.end > start }.map { it.kind }.distinct()
            if (kinds.isNotEmpty()) out += RedactBox(i, l.left, l.top, l.right, l.bottom, kinds)
            offset = end + 1
        }
        return out
    }

    // ------------------------------------------------------------------ Verhoeff

    private val D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val INV = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

    /** True when [digits] (digits only, check digit last) passes the Verhoeff check. */
    fun verhoeffValid(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return false
        var c = 0
        for ((i, ch) in digits.reversed().withIndex()) c = D[c][P[i % 8][ch - '0']]
        return c == 0
    }

    /** The Verhoeff check digit to append to [digits]. */
    fun verhoeffCheckDigit(digits: String): Int {
        var c = 0
        for ((i, ch) in digits.reversed().withIndex()) c = D[c][P[(i + 1) % 8][ch - '0']]
        return INV[c]
    }
}
