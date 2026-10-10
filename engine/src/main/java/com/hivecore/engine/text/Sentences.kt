package com.hivecore.engine.text

/**
 * One citable unit of source text: a paragraph, a slide, a page or an ASR segment. [doc] names the document (or the
 * released item), [unit] its locator inside it ("¶ 3", "slide 2", "seg 14"). [key] identifies the unit as an item
 * (classification and embedding results are keyed by it): `doc|unit` unless the caller gave its own id.
 */
data class SourceUnit(val doc: String, val unit: String, val text: String, val key: String = "$doc${Sentences.SEP}$unit")

/** A sentence with a stable id `doc|unit|ordinal` (ordinal 0-based within its unit). */
data class Sentence(val doc: String, val unit: String, val ordinal: Int, val text: String) {
    val id: String get() = Sentences.id(doc, unit, ordinal)
}

/**
 * Deterministic sentence splitter (swarm plan §4 EXTRACT: sentences are enumerated with ids, the model picks ids).
 * Pure: same text ⇒ same sentences ⇒ same ids on every phone.
 *
 * Rules, in order:
 *  1. Blank lines and bullet / numbered-list lines start a new block; other line breaks are spaces.
 *  2. Inside a block, whitespace is collapsed and a sentence ends at `.`, `!`, `?` or `…` (plus any closing quotes
 *     or brackets) followed by whitespace and then an uppercase letter, a digit, a quote or an opening bracket.
 *  3. No break after a known abbreviation (Mr., Dr., e.g., etc., Inc., …), a single-letter initial (J. Smith) or
 *     when the next word starts lowercase. Decimals (3.5) never break because there is no whitespace.
 *  4. A sentence without any letter or digit is dropped.
 */
object Sentences {
    const val SEP = "|"

    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "inc", "ltd", "co", "corp", "no", "nos",
        "fig", "figs", "approx", "dept", "est", "e.g", "i.e", "eg", "ie", "cf", "al", "jan", "feb", "mar", "apr", "jun",
        "jul", "aug", "sep", "sept", "oct", "nov", "dec", "rs", "govt", "pvt", "ref", "vol", "pp", "p", "ch", "sec",
    )
    private val BULLET = Regex("^\\s*(?:[-*•▪◦·]|\\d{1,3}[.)])\\s+")
    private val WS = Regex("\\s+")
    private val CONTENT = Regex("[\\p{L}\\p{Nd}]")
    private const val CLOSERS = "\"'”’)]}»"
    private const val OPENERS = "\"'“‘([{«"

    fun id(doc: String, unit: String, ordinal: Int): String = "$doc$SEP$unit$SEP$ordinal"

    /** Sentences of one unit, ordinals 0.. in order. */
    fun of(doc: String, unit: String, text: String): List<Sentence> =
        split(text).mapIndexed { i, s -> Sentence(doc, unit, i, s) }

    /** Sentences of every unit, in unit order. */
    fun of(units: List<SourceUnit>): List<Sentence> = units.flatMap { of(it.doc, it.unit, it.text) }

    /** The sentence texts of [text], trimmed, in order. */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (block in blocks(text)) splitBlock(block, out)
        return out
    }

    private fun blocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { val b = cur.toString().replace(WS, " ").trim(); if (b.isNotEmpty()) blocks += b; cur.setLength(0) }
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            when {
                line.isBlank() -> flush()
                BULLET.containsMatchIn(line) -> { flush(); cur.append(line.replaceFirst(BULLET, "")) }
                else -> { if (cur.isNotEmpty()) cur.append(' '); cur.append(line) }
            }
        }
        flush()
        return blocks
    }

    private fun splitBlock(b: String, out: MutableList<String>) {
        var start = 0
        var i = 0
        while (i < b.length) {
            val c = b[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < b.length && (b[end] == '.' || b[end] == '!' || b[end] == '?')) end++
                while (end < b.length && b[end] in CLOSERS) end++
                if (end < b.length && b[end] == ' ' && end + 1 < b.length && startsSentence(b[end + 1]) && !(c == '.' && abbreviation(b, i))) {
                    add(b.substring(start, end), out)
                    start = end + 1
                    i = end + 1
                    continue
                }
                i = end
                continue
            }
            i++
        }
        if (start < b.length) add(b.substring(start), out)
    }

    private fun startsSentence(c: Char): Boolean = c.isUpperCase() || c.isDigit() || c in OPENERS

    /** The word ending at the period at [dot] is an abbreviation or a single-letter initial. */
    private fun abbreviation(b: String, dot: Int): Boolean {
        var s = dot
        while (s > 0 && !b[s - 1].isWhitespace() && b[s - 1] !in OPENERS) s--
        val word = b.substring(s, dot).lowercase()
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter()) return true
        return word in ABBREV
    }

    private fun add(s: String, out: MutableList<String>) {
        val t = s.trim()
        if (t.isNotEmpty() && CONTENT.containsMatchIn(t)) out += t
    }
}
