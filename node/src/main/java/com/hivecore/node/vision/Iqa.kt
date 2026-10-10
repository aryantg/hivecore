package com.hivecore.node.vision

import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels

// Swarm plan §4A.3 IQA: pure arithmetic image quality checks. Runs first in every image recipe so a blurry or dark
// photo is sent back ("retake") instead of producing a bad row. Comparison rule: exact.

data class IqaResult(
    /** variance of the 4-neighbour Laplacian of luma, measured at ≤ [Iqa.ANALYSIS_SIDE] px */
    val blur: Double,
    /** share of pixels with luma ≤ [Iqa.DARK_LEVEL] */
    val darkClip: Double,
    /** share of pixels with luma ≥ [Iqa.BRIGHT_LEVEL] */
    val brightClip: Double,
    val meanLuma: Double,
    val width: Int,
    val height: Int,
    /** ok | blurry | dark | overexposed | small */
    val verdict: String,
)

object Iqa {
    const val ANALYSIS_SIDE = 1024
    const val MIN_SIDE = 480
    const val BLUR_MIN = 100.0
    const val DARK_LEVEL = 16
    const val BRIGHT_LEVEL = 240
    const val DARK_MEAN = 45.0
    const val DARK_CLIP = 0.40
    const val BRIGHT_MEAN = 215.0
    const val BRIGHT_CLIP = 0.30

    data class Limits(val minSide: Int = MIN_SIDE, val blurMin: Double = BLUR_MIN, val darkMean: Double = DARK_MEAN,
        val darkClip: Double = DARK_CLIP, val brightMean: Double = BRIGHT_MEAN, val brightClip: Double = BRIGHT_CLIP)

    /** Laplacian variance on the luma plane (borders excluded). */
    fun laplacianVariance(luma: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0L; var sq = 0L; var n = 0L
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = luma[i - 1] + luma[i + 1] + luma[i - w] + luma[i + w] - 4 * luma[i]
            sum += v; sq += v.toLong() * v; n++
        }
        val mean = sum.toDouble() / n
        return sq.toDouble() / n - mean * mean
    }

    fun assess(img: Img, limits: Limits = Limits()): IqaResult {
        val a = Pixels.fitMax(img, ANALYSIS_SIDE)
        val l = Pixels.lumaPlane(a)
        var dark = 0; var bright = 0; var total = 0L
        for (v in l) { if (v <= DARK_LEVEL) dark++; if (v >= BRIGHT_LEVEL) bright++; total += v }
        val n = l.size.toDouble()
        val blur = laplacianVariance(l, a.w, a.h)
        val darkClip = dark / n; val brightClip = bright / n; val mean = total / n
        val verdict = when {
            minOf(img.w, img.h) < limits.minSide -> "small"
            mean < limits.darkMean || darkClip > limits.darkClip -> "dark"
            mean > limits.brightMean || brightClip > limits.brightClip -> "overexposed"
            blur < limits.blurMin -> "blurry"
            else -> "ok"
        }
        return IqaResult(blur, darkClip, brightClip, mean, img.w, img.h, verdict)
    }
}
