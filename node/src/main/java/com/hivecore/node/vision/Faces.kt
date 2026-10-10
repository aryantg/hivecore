package com.hivecore.node.vision

import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels

// Swarm plan §4A.3 (REDACT's face boxes, people counting). AI Hub v0.64.0 mediapipe_face w8a8 face_detector:
// input [1,256,256,3]; outputs box_coords_1 [1,512,16] + box_coords_2 [1,384,16] (raw SSD offsets: dx, dy, w, h,
// then 6 keypoints) and box_scores_1 [1,512,1] + box_scores_2 [1,384,1] (logits). The 896 anchors are MediaPipe's
// 256 px "back" detector layout: stride 16 → 16×16 cells × 2 anchors, stride 32 → 8×8 cells × 6 anchors; fixed
// anchor size, so a box decodes as centre = anchor + raw/256 and size = raw/256 (normalized). For counting and
// blurring only, never identification.

object Faces {
    const val INPUT = 256
    const val SCORE = 0.5
    const val IOU = 0.3

    /** Anchor centres (normalized x, y), in output order. */
    fun anchors(input: Int = INPUT): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for ((stride, per) in listOf(16 to 2, 32 to 6)) {
            val f = (input + stride - 1) / stride
            for (y in 0 until f) for (x in 0 until f) {
                val cx = (x + 0.5) / f; val cy = (y + 0.5) / f
                repeat(per) { out += doubleArrayOf(cx, cy) }
            }
        }
        return out
    }

    private fun sigmoid(v: Double) = 1.0 / (1.0 + StrictMath.exp(-v.coerceIn(-100.0, 100.0)))

    /** [coords] = concatenated box_coords (16 per anchor), [logits] = concatenated box_scores; boxes in model-input px. */
    fun decode(coords: FloatArray, logits: FloatArray, input: Int = INPUT, scoreMin: Double = SCORE, iou: Double = IOU): List<Det> {
        val a = anchors(input)
        require(logits.size >= a.size && coords.size >= a.size * 16) { "face tensors: ${coords.size}/${logits.size} for ${a.size} anchors" }
        val dets = ArrayList<Det>()
        for (i in a.indices) {
            val s = sigmoid(logits[i].toDouble())
            if (s < scoreMin) continue
            val cx = coords[16 * i] / input.toDouble() + a[i][0]
            val cy = coords[16 * i + 1] / input.toDouble() + a[i][1]
            val w = coords[16 * i + 2] / input.toDouble(); val h = coords[16 * i + 3] / input.toDouble()
            dets += Det(0, "face", (cx - w / 2) * input, (cy - h / 2) * input, (cx + w / 2) * input, (cy + h / 2) * input, s, i)
        }
        return Detection.nms(dets, iou)
    }

    /** Pixelate face boxes (source px), each grown by [margin] of its size. */
    fun blur(img: Img, faces: List<Det>, margin: Double = 0.15, block: Int = 0): Img {
        val boxes = faces.map { d ->
            val mx = (d.x2 - d.x1) * margin; val my = (d.y2 - d.y1) * margin
            intArrayOf(Math.floor(d.x1 - mx).toInt(), Math.floor(d.y1 - my).toInt(), Math.ceil(d.x2 + mx).toInt(), Math.ceil(d.y2 + my).toInt())
        }
        val b = if (block > 0) block else maxOf(8, (faces.maxOfOrNull { (it.x2 - it.x1) }?.toInt() ?: 64) / 8)
        return Pixels.pixelate(img, boxes, b)
    }
}
