package com.hivecore.node.textai

import kotlin.math.sqrt

/**
 * KNN (swarm plan §4A.3): brute-force cosine over int8 vectors (≤ 100k per phone fits in memory). Integer dot
 * products, so the scores are exact and identical on every phone. Scores are rounded to 6 dp, then ordered by
 * score descending and id ascending — the JSON order and the tie-break are the same thing.
 */
object Knn {
    data class Hit(val id: String, val score: Double)

    fun cosine(a: ByteArray, b: ByteArray): Double {
        require(a.size == b.size) { "dimension mismatch ${a.size} vs ${b.size}" }
        var dot = 0L; var na = 0L; var nb = 0L
        for (i in a.indices) { val x = a[i].toLong(); val y = b[i].toLong(); dot += x * y; na += x * x; nb += y * y }
        if (na == 0L || nb == 0L) return 0.0
        return dot / (sqrt(na.toDouble()) * sqrt(nb.toDouble()))
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "dimension mismatch ${a.size} vs ${b.size}" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { val x = a[i].toDouble(); val y = b[i].toDouble(); dot += x * y; na += x * x; nb += y * y }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun r6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    /** Top [k] of [items] by cosine to [query], score ≥ [minScore]. */
    fun topK(query: ByteArray, items: List<Pair<String, ByteArray>>, k: Int, minScore: Double = -1.0): List<Hit> =
        rank(items.map { (id, v) -> Hit(id, r6(cosine(query, v))) }, k, minScore)

    fun rank(hits: List<Hit>, k: Int, minScore: Double = -1.0): List<Hit> =
        hits.filter { it.score >= minScore }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id })
            .take(k.coerceAtLeast(0))
}
