package com.hivecore.node.textai

import com.hivecore.engine.text.Extractive
import com.hivecore.engine.text.Sentences
import com.hivecore.engine.text.Sentence
import com.hivecore.core.Hashing
import java.util.Random

/**
 * CLUSTER (swarm plan §4A.3): k-means++ with the seed fixed to hash(jobId) and a fixed number of Lloyd iterations,
 * over vectors sorted by id (so the input order never matters). java.util.Random is a specified LCG and every sum
 * runs in a fixed order, so two phones produce the same assignments. Clusters are numbered by size descending, then
 * by their smallest member id. Distance = squared Euclidean (on unit vectors, monotone in cosine).
 */
object Cluster {
    data class Result(
        /** id → cluster number, ids ascending */
        val assignments: Map<String, Int>,
        val centroids: List<FloatArray>,
        val members: List<List<String>>,
        val iterations: Int,
    ) {
        val k: Int get() = centroids.size
    }

    data class Theme(val cluster: Int, val size: Int, val exemplar: String, val label: Sentence?)

    const val ITERATIONS = 20
    const val THEME_MEMBERS = 20

    /** First 8 bytes of sha256(jobId) as a long. */
    fun seed(jobId: String): Long = Hashing.sha256Hex(jobId).take(16).toULong(16).toLong()

    fun kmeans(items: List<Pair<String, FloatArray>>, k: Int, seed: Long, iterations: Int = ITERATIONS): Result {
        require(k >= 1) { "k must be ≥ 1" }
        require(items.map { it.first }.toSet().size == items.size) { "duplicate ids" }
        if (items.isEmpty()) return Result(emptyMap(), emptyList(), emptyList(), 0)
        val sorted = items.sortedBy { it.first }
        val dim = sorted[0].second.size
        require(sorted.all { it.second.size == dim }) { "vectors differ in dimension" }
        val x = sorted.map { (_, v) -> DoubleArray(dim) { v[it].toDouble() } }
        val n = x.size
        val kk = minOf(k, n)
        val rnd = Random(seed)

        // k-means++ seeding
        val centers = ArrayList<DoubleArray>()
        centers += x[rnd.nextInt(n)].copyOf()
        val d2 = DoubleArray(n) { dist(x[it], centers[0]) }
        while (centers.size < kk) {
            val total = d2.sum()
            val pick = if (total <= 0.0) {
                (0 until n).firstOrNull { i -> centers.none { dist(x[i], it) == 0.0 } } ?: break
            } else {
                var r = rnd.nextDouble() * total
                var chosen = n - 1
                for (i in 0 until n) { r -= d2[i]; if (r < 0) { chosen = i; break } }
                chosen
            }
            centers += x[pick].copyOf()
            for (i in 0 until n) d2[i] = minOf(d2[i], dist(x[i], centers.last()))
        }

        // Lloyd iterations (fixed count; stop early only when nothing moves, which gives the same result)
        val assign = IntArray(n) { -1 }
        var it = 0
        while (it < iterations) {
            it++
            var moved = false
            for (i in 0 until n) {
                var best = 0; var bd = Double.MAX_VALUE
                for (c in centers.indices) { val d = dist(x[i], centers[c]); if (d < bd) { bd = d; best = c } }
                if (assign[i] != best) { assign[i] = best; moved = true }
            }
            for (c in centers.indices) {
                val acc = DoubleArray(dim); var cnt = 0
                for (i in 0 until n) if (assign[i] == c) { cnt++; for (j in 0 until dim) acc[j] += x[i][j] }
                if (cnt > 0) for (j in 0 until dim) centers[c][j] = acc[j] / cnt   // empty cluster keeps its centre
            }
            if (!moved) break
        }

        // renumber: size desc, then smallest member id; drop empty clusters
        val groups = centers.indices.map { c -> (0 until n).filter { assign[it] == c } }.withIndex()
            .filter { it.value.isNotEmpty() }
            .sortedWith(compareByDescending<IndexedValue<List<Int>>> { it.value.size }.thenBy { sorted[it.value.first()].first })
        val assignments = LinkedHashMap<String, Int>()
        val newIndex = HashMap<Int, Int>()
        groups.forEachIndexed { ni, g -> newIndex[g.index] = ni }
        for (i in 0 until n) assignments[sorted[i].first] = newIndex.getValue(assign[i])
        return Result(
            assignments,
            groups.map { g -> FloatArray(dim) { centers[g.index][it].toFloat() } },
            groups.map { g -> g.value.map { sorted[it].first } },
            it,
        )
    }

    /** Near-duplicate groups: connected components of pairs with cosine ≥ [threshold]; singletons omitted. */
    fun dedupe(items: List<Pair<String, ByteArray>>, threshold: Double): List<List<String>> {
        val sorted = items.sortedBy { it.first }
        val parent = IntArray(sorted.size) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            if (Knn.r6(Knn.cosine(sorted[i].second, sorted[j].second)) >= threshold) {
                val a = find(i); val b = find(j); if (a != b) parent[maxOf(a, b)] = minOf(a, b)
            }
        }
        return sorted.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            .map { g -> g.map { sorted[it].first } }.sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first() })
    }

    /**
     * Theme per cluster: the exemplar is the member closest to the centroid (ties by id); the label is the most central
     * sentence ([Extractive.rankByCentrality]) among the [THEME_MEMBERS] members closest to the centroid. Members
     * without text give no label candidates.
     */
    fun themes(result: Result, vectors: Map<String, FloatArray>, texts: Map<String, String>): List<Theme> =
        result.members.mapIndexed { c, members ->
            val centroid = result.centroids[c]
            val near = members.sortedWith(compareByDescending<String> { Knn.r6(Knn.cosine(vectors.getValue(it), centroid)) }.thenBy { it })
            val pool = near.take(THEME_MEMBERS).flatMap { id -> texts[id]?.let { Sentences.of(id, "text", it) } ?: emptyList() }
            val label = if (pool.isEmpty()) null else pool[Extractive.rankByCentrality(pool).first()]
            Theme(c, members.size, near.first(), label)
        }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return s
    }
}
