package com.hivecore.engine.decide

import kotlin.math.abs

/**
 * The deterministic dynamic-programming cut of decision point A (docs/plans/decision-chunking.md §5): [n] units are
 * cut into consecutive chunks of [min]..[max] units (the last chunk may be shorter) so that the sum of the boundary
 * scores at the chosen cuts minus [lambda] × the relative deviation of each chunk from [target] is maximal.
 * `score(i)` is p(boundary after unit i), i.e. between unit i and unit i + 1. Ties go to the shorter last segment, so
 * the earlier boundary; equal inputs give equal cuts on every phone.
 */
object Cut {
    const val LAMBDA = 0.25

    /** Boundaries = the `ownTo` of every chunk but the last, ascending; empty when the text fits one chunk. */
    fun boundaries(n: Int, min: Int, max: Int, target: Int, score: (Int) -> Double, lambda: Double = LAMBDA): List<Int> {
        require(n >= 1 && min >= 1 && max >= min && target >= 1) { "cut needs n ≥ 1, 1 ≤ min ≤ max, target ≥ 1" }
        if (n <= max) return emptyList()
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 1..n) {
            val lo = if (i == n) 1 else min
            for (len in lo..max) {
                val j = i - len
                if (j < 0) break
                if (best[j] == Double.NEGATIVE_INFINITY) continue
                val gain = (if (i < n) score(i - 1) else 0.0) - lambda * abs(len - target).toDouble() / target
                val v = best[j] + gain
                if (v > best[i]) { best[i] = v; prev[i] = j }
            }
        }
        require(best[n] > Double.NEGATIVE_INFINITY) { "no cut of $n units into chunks of $min..$max" }
        val out = ArrayList<Int>()
        var i = n
        while (prev[i] > 0) { out += prev[i]; i = prev[i] }
        return out.reversed()
    }

    /** The chunk sizes of a boundary list over [n] units (for notes and tests). */
    fun sizes(n: Int, boundaries: List<Int>): List<Int> = (boundaries + n).zip(listOf(0) + boundaries).map { (b, a) -> b - a }
}
