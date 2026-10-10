package com.hivecore.engine.text


/**
 * The relevance gate (plan 9), kept in one place so the ask pipeline, the tests and the UI agree. A chunk is
 * relevant when its BM25 score reaches [MIN_SCORE] and it matches at least `min(2, queryContentTerms)` distinct
 * query terms; a query with no content terms is never relevant.
 */
object Thresholds {
    const val MIN_SCORE = 2.0
    const val MIN_MATCHED_TERMS = 2
    const val K1 = 1.5
    const val B = 0.75

    fun requiredMatches(queryContentTerms: Int): Int = minOf(MIN_MATCHED_TERMS, queryContentTerms)

    fun passes(score: Double, matchedTerms: Int, queryContentTerms: Int): Boolean =
        queryContentTerms > 0 && score >= MIN_SCORE && matchedTerms >= requiredMatches(queryContentTerms)
}

