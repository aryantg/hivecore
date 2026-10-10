package com.hivecore.node.vision

import com.hivecore.engine.pixels.Img

// Swarm plan §4A.3 SEGMENT. AI Hub v0.64.0 shapes:
//  - DeepLabV3+ MobileNet w8a8: mask [1,520,520] uint8, already the class index per pixel (21 VOC labels).
//  - FFNet-40s w8a8: mask [1,128,256,19] uint8 quantized logits (19 Cityscapes labels) → argmax per pixel.

data class SegMask(val w: Int, val h: Int, val cls: IntArray)

data class ClassShare(val index: Int, val label: String, val pixels: Int, val share: Double)

object Segmentation {
    /** A class-index mask tensor [1,H,W] (or [H,W]). */
    fun fromIndexMask(values: IntArray, h: Int, w: Int): SegMask {
        require(values.size >= w * h) { "mask has ${values.size} < ${w * h}" }
        return SegMask(w, h, values.copyOf(w * h))
    }

    /** NHWC logits [1,H,W,C] → argmax per pixel; ties go to the lower class index. Works on raw or dequantized values. */
    fun argmax(logits: FloatArray, h: Int, w: Int, c: Int): SegMask {
        require(logits.size >= h * w * c) { "logits too small" }
        val out = IntArray(h * w)
        for (p in 0 until h * w) {
            var best = 0; var bv = logits[p * c]
            for (k in 1 until c) { val v = logits[p * c + k]; if (v > bv) { bv = v; best = k } }
            out[p] = best
        }
        return SegMask(w, h, out)
    }

    /** Per-class pixel share, largest first (ties by class index); classes with no pixels are omitted. */
    fun shares(mask: SegMask, labels: List<String>): List<ClassShare> {
        val counts = HashMap<Int, Int>()
        for (v in mask.cls) counts[v] = (counts[v] ?: 0) + 1
        val n = mask.cls.size.toDouble()
        return counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { ClassShare(it.key, Detection.label(labels, it.key), it.value, it.value / n) }
    }

    /** Grey PNG-able image whose value is the class index (0..255). */
    fun maskImage(mask: SegMask): Img = Img(mask.w, mask.h, IntArray(mask.cls.size) { Img.gray(mask.cls[it].coerceIn(0, 255)) })
}
