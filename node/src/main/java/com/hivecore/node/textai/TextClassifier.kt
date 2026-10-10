package com.hivecore.node.textai

import com.hivecore.core.ClosedChooser
import com.hivecore.core.Decision

/**
 * TEXT_CLASSIFY (swarm plan §4A.3): the label set is the closed answer space.
 * - [classify]: one text through [ClosedChooser] (k permuted runs, confidence = agreement; null value = refuse, R6).
 * - [NearestCentroid]: for large batches with labelled examples — mean of each label's unit embeddings, then cosine.
 */
class TextClassifier(private val chooser: ClosedChooser) {

    suspend fun classify(text: String, labels: List<String>, question: String? = null, k: Int = 3): Decision<String> {
        require(labels.isNotEmpty()) { "TEXT_CLASSIFY needs a label set" }
        require(labels.toSet().size == labels.size) { "duplicate labels" }
        return chooser.choose(prompt(text, question), labels, { it }, k)
    }

    companion object {
        const val MAX_TEXT_CHARS = 1200

        fun prompt(text: String, question: String?): String = buildString {
            append(question?.trim()?.takeIf { it.isNotEmpty() } ?: "Which label fits this text best?")
            append("\nText: \"")
            val t = text.replace(Regex("\\s+"), " ").trim()
            append(if (t.length <= MAX_TEXT_CHARS) t else t.take(MAX_TEXT_CHARS - 1) + "…")
            append('"')
        }
    }
}

/**
 * Nearest-centroid classifier over embeddings. Centroid = L2-normalised mean of the label's example vectors.
 * [predict] returns the closest label (ties by label order), its cosine, and the margin to the runner-up; a margin
 * below [minMargin] or a score below [minScore] gives a null label (refuse rather than guess).
 */
class NearestCentroid(examples: Map<String, List<FloatArray>>, private val minScore: Double = 0.0, private val minMargin: Double = 0.0) {
    data class Prediction(val label: String?, val score: Double, val margin: Double, val best: String)

    val labels: List<String> = examples.keys.toList()
    private val centroids: List<FloatArray>

    init {
        require(labels.isNotEmpty()) { "no labelled examples" }
        centroids = labels.map { l ->
            val vs = examples.getValue(l)
            require(vs.isNotEmpty()) { "label $l has no examples" }
            val dim = vs[0].size
            val acc = DoubleArray(dim)
            for (v in vs) { require(v.size == dim) { "dimension mismatch in $l" }; for (j in 0 until dim) acc[j] += v[j].toDouble() }
            Embedder.l2(FloatArray(dim) { (acc[it] / vs.size).toFloat() })
        }
    }

    fun predict(v: FloatArray): Prediction {
        val scores = centroids.map { Knn.r6(Knn.cosine(v, it)) }
        val order = scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        val best = order[0]
        val margin = if (order.size > 1) Knn.r6(scores[best] - scores[order[1]]) else 1.0
        val ok = scores[best] >= minScore && margin >= minMargin && (order.size == 1 || margin > 0.0)
        return Prediction(if (ok) labels[best] else null, scores[best], margin, labels[best])
    }
}
