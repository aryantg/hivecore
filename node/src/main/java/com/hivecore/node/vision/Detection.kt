package com.hivecore.node.vision

import com.hivecore.engine.pixels.Letterbox

// Swarm plan §4A.3 DETECT: decoders for the AI Hub v0.64.0 detectors plus a fixed, deterministic NMS in Kotlin.
//  - YOLOX (w8a8): boxes [1,8400,4] (x1,y1,x2,y2 in model-input pixels), scores [1,8400], class_idx [1,8400];
//    uint8 with scale/zero-point from the manifest outputSpec (class_idx is unquantized).
//  - D-FINE (float): boxes [1,300,4] float32 (x1,y1,x2,y2 in model-input pixels assumed; "cxcywh" and normalized
//    coordinates are selectable via manifest preprocess boxFormat / boxNormalized), scores [1,300], classes [1,300] int32.

/** A detection in some pixel space; [cls] indexes the label list. */
data class Det(val cls: Int, val label: String, val x1: Double, val y1: Double, val x2: Double, val y2: Double, val score: Double, val index: Int = 0) {
    val area: Double get() = maxOf(0.0, x2 - x1) * maxOf(0.0, y2 - y1)
}

object Detection {
    const val IOU = 0.5
    const val SCORE = 0.35
    const val MAX_DETECTIONS = 300

    fun label(labels: List<String>, cls: Int): String = labels.getOrNull(cls) ?: "class_$cls"

    fun decodeYolox(boxes: FloatArray, scores: FloatArray, classIdx: IntArray, labels: List<String>, scoreMin: Double = SCORE): List<Det> {
        val n = scores.size
        require(boxes.size >= n * 4 && classIdx.size >= n) { "yolox tensors disagree: ${boxes.size}/${scores.size}/${classIdx.size}" }
        val out = ArrayList<Det>()
        for (i in 0 until n) {
            val s = scores[i].toDouble()
            if (s < scoreMin) continue
            val c = classIdx[i]
            out += Det(c, label(labels, c), boxes[4 * i].toDouble(), boxes[4 * i + 1].toDouble(), boxes[4 * i + 2].toDouble(), boxes[4 * i + 3].toDouble(), s, i)
        }
        return out
    }

    /** D-FINE: [boxFormat] "xyxy" or "cxcywh"; [normalizedTo] > 0 scales 0..1 coordinates to that many pixels. */
    fun decodeDfine(boxes: FloatArray, scores: FloatArray, classes: IntArray, labels: List<String>, scoreMin: Double = SCORE,
                    boxFormat: String = "xyxy", normalizedW: Int = 0, normalizedH: Int = 0): List<Det> {
        val n = scores.size
        require(boxes.size >= n * 4 && classes.size >= n) { "d-fine tensors disagree" }
        val sx = if (normalizedW > 0) normalizedW.toDouble() else 1.0
        val sy = if (normalizedH > 0) normalizedH.toDouble() else 1.0
        val out = ArrayList<Det>()
        for (i in 0 until n) {
            val s = scores[i].toDouble()
            if (s < scoreMin) continue
            var a = boxes[4 * i] * sx; var b = boxes[4 * i + 1] * sy; var c = boxes[4 * i + 2] * sx; var d = boxes[4 * i + 3] * sy
            if (boxFormat == "cxcywh") { val cx = a; val cy = b; val w = c; val h = d; a = cx - w / 2; b = cy - h / 2; c = cx + w / 2; d = cy + h / 2 }
            out += Det(classes[i], label(labels, classes[i]), a, b, c, d, s, i)
        }
        return out
    }

    fun iou(a: Det, b: Det): Double {
        val ix = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1); val iy = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
        if (ix <= 0 || iy <= 0) return 0.0
        val inter = ix * iy
        val u = a.area + b.area - inter
        return if (u <= 0) 0.0 else inter / u
    }

    /** Deterministic order: score desc, then class asc, then tensor index asc. */
    val ORDER: Comparator<Det> = compareByDescending<Det> { it.score }.thenBy { it.cls }.thenBy { it.index }

    /** Greedy class-wise NMS; result in [ORDER], at most [max]. */
    fun nms(dets: List<Det>, iou: Double = IOU, max: Int = MAX_DETECTIONS, classAgnostic: Boolean = false): List<Det> {
        val sorted = dets.sortedWith(ORDER)
        val kept = ArrayList<Det>()
        for (d in sorted) {
            if (kept.size >= max) break
            if (kept.none { k -> (classAgnostic || k.cls == d.cls) && iou(k, d) > iou }) kept += d
        }
        return kept
    }

    /** Map model-input boxes back to the source image through the letterbox. */
    fun unletterbox(dets: List<Det>, lb: Letterbox): List<Det> = dets.map {
        it.copy(x1 = lb.toSrcX(it.x1), y1 = lb.toSrcY(it.y1), x2 = lb.toSrcX(it.x2), y2 = lb.toSrcY(it.y2))
    }

    /** Label → count, labels sorted. */
    fun counts(dets: List<Det>): Map<String, Int> = dets.groupingBy { it.label }.eachCount().toSortedMap()
}
