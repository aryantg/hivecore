package com.hivecore.engine.text

/**
 * Lowercase, keep letters and digits (so "q3", "48", "2026" survive), drop the 120 stop words, stem lightly:
 * `ies → y`, then one of `ing`, `ed`, `es`, `s` stripped when the word is longer than four letters (so "days" and
 * "news" stay, "priorities" → "priority", "accounts" → "account", "accrued" → "accru", "joining" → "join").
 * Used for both chunks and queries, so a query term matches exactly when its stem matches.
 */
object Tokenizer {
    private val WORD = Regex("[\\p{L}\\p{Nd}]+")

    val STOP: Set<String> = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are", "as", "at",
        "be", "because", "been", "before", "being", "below", "between", "both", "but", "by",
        "can", "could", "did", "do", "does", "down", "during",
        "each", "few", "for", "from", "further", "get", "had", "has", "have", "having", "he", "her", "here", "hers",
        "him", "his", "how", "i", "if", "in", "into", "is", "it", "its", "just",
        "many", "me", "more", "most", "much", "my", "no", "nor", "not", "now",
        "of", "off", "on", "once", "only", "or", "other", "our", "out", "over", "own", "per",
        "same", "she", "should", "so", "some", "such", "than", "that", "the", "their", "them", "then", "there",
        "these", "they", "this", "those", "through", "to", "too", "under", "until", "up", "us", "very", "via",
        "was", "we", "were", "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with",
        "would", "you", "your"
    )

    /** Content terms in order of appearance (duplicates kept; callers count them for tf). */
    fun tokens(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it !in STOP }.map { stem(it) }.filter { it.isNotEmpty() }.toList()

    fun stem(w: String): String {
        if (w.length <= 4) return w
        if (w.endsWith("ies")) return w.dropLast(3) + "y"
        if (w.endsWith("ing") && w.length > 5) return w.dropLast(3)
        if (w.endsWith("ed")) return w.dropLast(2)
        if (w.endsWith("es")) return w.dropLast(2)
        if (w.endsWith("s") && !w.endsWith("ss")) return w.dropLast(1)
        return w
    }
}
