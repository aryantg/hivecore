package com.hivecore.node.vision

// Swarm plan §4A.3 IMG_CLASSIFY: MobileNet-v3-Large / EfficientNet-Lite4 class_logits [1,1000] (uint8 dequantized
// with the manifest scale/zero-point, or float) → top-k over the fixed label set. Softmax uses StrictMath so the
// scores are identical on every phone.

data class Scored(val index: Int, val label: String, val score: Double)

object Classification {
    const val TOP_K = 5
    const val MIN_SCORE = 0.0

    fun softmax(logits: FloatArray): DoubleArray {
        if (logits.isEmpty()) return DoubleArray(0)
        var m = Double.NEGATIVE_INFINITY
        for (v in logits) if (v > m) m = v.toDouble()
        val e = DoubleArray(logits.size) { StrictMath.exp(logits[it] - m) }
        var s = 0.0
        for (v in e) s += v
        for (i in e.indices) e[i] /= s
        return e
    }

    /** Top-k (score desc, index asc on ties) above [minScore]; [probabilities] = softmax over logits first. */
    fun topK(logits: FloatArray, labels: List<String>, k: Int = TOP_K, minScore: Double = MIN_SCORE, probabilities: Boolean = true): List<Scored> {
        val p = if (probabilities) softmax(logits) else DoubleArray(logits.size) { logits[it].toDouble() }
        return p.indices.sortedWith(compareByDescending<Int> { p[it] }.thenBy { it })
            .take(k).filter { p[it] >= minScore }
            .map { Scored(it, Detection.label(labels, it), p[it]) }
    }
}
