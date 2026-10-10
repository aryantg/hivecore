package com.hivecore.node.textai

import com.hivecore.engine.text.Sentence
import java.math.BigDecimal
import java.text.Normalizer

/**
 * Abstractive-mode guard (swarm plan §4A.3 SUMMARIZE): every number, date, capitalised name and quoted term in the
 * generated text must appear in the source, after normalisation (NFKC, lowercase, unified quotes and dashes,
 * collapsed whitespace), else the text is rejected. Pure and deterministic.
 *
 * - Numbers compare by value (`1,200` = `1200`, `3.50` = `3.5`); number words zero..twenty and the tens count as
 *   numbers in the source, so "3" may stand for "three".
 * - Dates (ISO `2026-10-10`, `10/10/2026`, `10 October 2026`, `Oct 10`, `October 2026`, a bare month name) match a
 *   source date with the same month and, when the generated one names them, the same day and year (a year missing
 *   from the source date is accepted when that year appears in the source as a number).
 * - Names: every capitalised word (or acronym, or word with a digit like `Q3`) must occur in the source as a word.
 *   A sentence's first word is exempt when it is a common word (the, overall, however, …).
 * - Quoted terms ("…", “…”, ‘…’) must occur verbatim (normalised) in the source.
 */
object GroundCheck {
    data class Violation(val kind: String, val text: String)
    data class Result(val violations: List<Violation>) {
        val ok: Boolean get() = violations.isEmpty()
    }

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december")
    private val MONTH_INDEX: Map<String, Int> = HashMap<String, Int>().apply {
        MONTHS.forEachIndexed { i, m -> put(m, i + 1); put(m.take(3), i + 1) }
        put("sept", 9)
    }
    private const val MON = "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\.?"
    private val ISO = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
    private val SLASH = Regex("\\b(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4}|\\d{2})\\b")
    private val DMY = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MON(?:,?\\s+(\\d{4}))?\\b")
    private val MDY = Regex("\\b$MON\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b")
    private val MY = Regex("\\b$MON(?:,?\\s+(\\d{4}))?\\b")
    private val NUMBER = Regex("(?<![\\p{L}\\p{Nd}.])[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?![\\p{L}\\p{Nd}])")
    private val QUOTED = Regex("\"([^\"]{1,200})\"|‘([^’]{1,200})’")
    private val WORD = Regex("[\\p{L}\\p{Nd}]+(?:['’-][\\p{L}\\p{Nd}]+)*")
    private val NUMBER_WORDS = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90, "hundred" to 100, "dozen" to 12)
    /** Sentence-initial words that need no grounding (function words and discourse markers). */
    val COMMON: Set<String> = com.hivecore.engine.text.Tokenizer.STOP + setOf(
        "also", "overall", "however", "additionally", "finally", "first", "second", "third", "next", "meanwhile",
        "therefore", "thus", "summary", "in", "key", "main", "action", "actions", "decision", "decisions", "items",
        "please", "note", "there", "it", "this", "these", "all", "both", "everyone", "nobody", "team", "they", "we",
        "he", "she", "i", "a", "an", "the", "one", "another", "later", "today", "tomorrow", "yesterday", "earlier",
        "besides", "furthermore", "moreover", "lastly", "then", "after", "before", "while", "since", "although",
    )

    private data class D(val y: Int?, val m: Int, val d: Int?)

    fun check(generated: String, source: String): Result {
        val src = normalize(source)
        val srcWords = WORD.findAll(src).map { it.value }.toHashSet()
        val srcNumbers = HashSet<BigDecimal>()
        for (m in NUMBER.findAll(src)) number(m.value)?.let { srcNumbers += it }
        for (w in srcWords) NUMBER_WORDS[w]?.let { srcNumbers += BigDecimal(it) }
        val srcDates = dates(src).map { it.second }

        val out = ArrayList<Violation>()
        // the original-case text, normalised the same way except for case, for names
        val genCased = normalize(generated, lower = false)
        val gen = genCased.lowercase()
        val consumed = BooleanArray(gen.length)
        fun consume(r: IntRange) { for (i in r) if (i in consumed.indices) consumed[i] = true }

        for (m in QUOTED.findAll(gen)) {
            val q = (m.groups[1] ?: m.groups[2])!!.value.trim()
            if (q.isNotEmpty() && !src.contains(q)) out += Violation("quote", q)
        }
        for ((range, d) in dates(gen)) {
            if (range.any { consumed[it] }) continue
            consume(range)
            val okDate = srcDates.any { s -> d.m == s.m && (d.d == null || d.d == s.d) &&
                (d.y == null || d.y == s.y || (s.y == null && BigDecimal(d.y) in srcNumbers)) }
            if (!okDate) out += Violation("date", gen.substring(range))
        }
        for (m in NUMBER.findAll(gen)) {
            if (m.range.any { consumed[it] }) continue
            val v = number(m.value) ?: continue
            if (v !in srcNumbers && v.abs() !in srcNumbers) out += Violation("number", m.value)
        }
        for (m in WORD.findAll(genCased)) {
            val w = m.value
            if (m.range.any { consumed[it] }) continue
            val nameLike = w[0].isUpperCase() || (w.any { it.isDigit() } && w.any { it.isLetter() })
            if (!nameLike) continue
            val lw = w.lowercase()
            if (lw in MONTH_INDEX) continue   // a bare month is a date, checked above
            if (sentenceStart(genCased, m.range.first) && lw in COMMON) continue
            if (lw == "i") continue
            if (lw !in srcWords) out += Violation("name", w)
        }
        return Result(out)
    }

    /** NFKC, unified quotes/dashes/spaces, collapsed whitespace; lowercase unless [lower] is false. */
    fun normalize(s: String, lower: Boolean = true): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace('“', '"').replace('”', '"').replace('„', '"').replace('«', '"').replace('»', '"')
            .replace('–', '-').replace('—', '-').replace('−', '-').replace(' ', ' ')
            .replace(Regex("\\s+"), " ").trim()
        if (lower) t = t.lowercase()
        return t
    }

    private fun number(s: String): BigDecimal? = runCatching { BigDecimal(s.replace(",", "").removePrefix("+")).stripTrailingZeros() }.getOrNull()

    private fun sentenceStart(t: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (t[i] == ' ' || t[i] == '"' || t[i] == '\'' || t[i] == '(' || t[i] == '‘')) i--
        return i < 0 || t[i] in ".!?:;-•*\n"
    }

    /** Date expressions of lowercased text with their ranges; longer forms first, overlaps skipped. */
    private fun dates(t: String): List<Pair<IntRange, D>> {
        val found = ArrayList<Pair<IntRange, D>>()
        fun add(r: IntRange, d: D?) { if (d != null && found.none { (o, _) -> o.first <= r.last && r.first <= o.last }) found += r to d }
        fun mon(s: String) = MONTH_INDEX[s.trimEnd('.')]
        for (m in ISO.findAll(t)) add(m.range, valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
        for (m in SLASH.findAll(t)) {
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            // day-first (the app's locale) when valid, else month-first
            add(m.range, valid(y, b, a) ?: valid(y, a, b))
        }
        for (m in DMY.findAll(t)) add(m.range, mon(m.groupValues[2])?.let { valid(m.groupValues[3].toIntOrNull(), it, m.groupValues[1].toInt()) })
        for (m in MDY.findAll(t)) add(m.range, mon(m.groupValues[1])?.let { valid(m.groupValues[3].toIntOrNull(), it, m.groupValues[2].toInt()) })
        for (m in MY.findAll(t)) {
            // "may" alone is too often the verb: only with a year
            if (m.groupValues[1] == "may" && m.groupValues[2].isEmpty()) continue
            add(m.range, mon(m.groupValues[1])?.let { D(m.groupValues[2].toIntOrNull(), it, null) })
        }
        return found.sortedBy { it.first.first }
    }

    private fun valid(y: Int?, m: Int, d: Int?): D? =
        if (m in 1..12 && (d == null || d in 1..31)) D(y, m, d) else null
}
