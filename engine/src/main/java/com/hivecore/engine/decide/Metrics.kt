package com.hivecore.engine.decide

/** Accuracy, Brier score and expected calibration error of binary predictions (the evaluation harness, §9). */
object Metrics {
    data class Report(val n: Int, val accuracy: Double, val brier: Double, val ece: Double) {
        override fun toString() = "n=$n accuracy=${"%.4f".format(accuracy)} brier=${"%.4f".format(brier)} ece=${"%.4f".format(ece)}"
    }

    /** [p] = predicted probability of the positive class per case, [gold] = the truth; ECE over [bins] equal-width bins. */
    fun report(p: List<Double>, gold: List<Boolean>, bins: Int = 10): Report {
        require(p.size == gold.size) { "${p.size} predictions for ${gold.size} labels" }
        val n = p.size
        if (n == 0) return Report(0, 0.0, 0.0, 0.0)
        var correct = 0; var brier = 0.0
        val binCount = IntArray(bins); val binConf = DoubleArray(bins); val binAcc = DoubleArray(bins)
        for (i in 0 until n) {
            val y = if (gold[i]) 1.0 else 0.0
            val pi = p[i].coerceIn(0.0, 1.0)
            if ((pi >= 0.5) == gold[i]) correct++
            brier += (pi - y) * (pi - y)
            val b = minOf(bins - 1, (pi * bins).toInt())
            binCount[b]++; binConf[b] += pi; binAcc[b] += y
        }
        var ece = 0.0
        for (b in 0 until bins) if (binCount[b] > 0) ece += binCount[b].toDouble() / n * kotlin.math.abs(binAcc[b] / binCount[b] - binConf[b] / binCount[b])
        return Report(n, correct.toDouble() / n, brier / n, ece)
    }
}
