package com.hivecore.node.media

/** One detection in a frame (DETECT output: label + box x1,y1,x2,y2 in source pixels). */
data class TBox(val label: String, val x1: Double, val y1: Double, val x2: Double, val y2: Double, val score: Double = 1.0) {
    val cx: Double get() = (x1 + x2) / 2
    val cy: Double get() = (y1 + y2) / 2
    val area: Double get() = maxOf(0.0, x2 - x1) * maxOf(0.0, y2 - y1)
}

/** Detections of one sampled frame at [tMs]; [w]/[h] are the frame size (0 = unknown). */
data class TFrame(val tMs: Long, val boxes: List<TBox>, val w: Int = 0, val h: Int = 0)

data class Track(val id: Int, val label: String, val firstMs: Long, var lastMs: Long, var frames: Int, var last: TBox,
                 var crossedMs: Long? = null, var missed: Int = 0)

/**
 * A counting line in normalised frame coordinates (0..1) from (x1,y1) to (x2,y2). A track "enters" when its box
 * centre moves from the negative side (cross product < 0) to the non-negative side between two consecutive
 * observations; [reverse] flips the direction. Default: the horizontal mid-line, entering downwards.
 */
data class CountLine(val x1: Double = 0.0, val y1: Double = 0.5, val x2: Double = 1.0, val y2: Double = 0.5, val reverse: Boolean = false) {
    fun side(x: Double, y: Double): Double {
        val s = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
        return if (reverse) -s else s
    }
}

data class TrackResult(val tracks: List<Track>, /** (minute, label) → entering count */ val counts: Map<Pair<Long, String>, Int>)

/**
 * Pure IoU tracker for TRACK_AGG (swarm plan §4A.3, recipe `cctv_count`). Frames are processed in time order. In
 * each frame every (live track, detection) pair of the same label with IoU ≥ [iouMin] is a candidate; candidates are
 * taken greedily by IoU desc, then track id asc, then detection index asc (deterministic). Unmatched detections start
 * new tracks (ids 1, 2, … in creation order); a track unmatched for more than [maxMissed] consecutive frames ends.
 * With a [line], counts are line crossings in the entering direction, at the minute (floor(tMs / 60 000)) of the
 * frame where the crossing was seen, once per track. Without a line, every new track counts at its first minute.
 * Box coordinates are normalised by the frame size when it is known, so the line is resolution independent.
 */
class Tracker(private val iouMin: Double = 0.3, private val maxMissed: Int = 2, private val line: CountLine? = null) {

    fun run(frames: List<TFrame>): TrackResult {
        val all = ArrayList<Track>()
        val live = ArrayList<Track>()
        val counts = java.util.TreeMap<Pair<Long, String>, Int>(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second })
        var nextId = 1
        for (f in frames.sortedBy { it.tMs }) {
            val boxes = f.boxes.map { normalise(it, f) }
            val cand = ArrayList<Triple<Double, Track, Int>>()
            for (t in live) for ((j, b) in boxes.withIndex()) {
                if (b.label != t.label) continue
                val iou = iou(t.last, b)
                if (iou >= iouMin) cand += Triple(iou, t, j)
            }
            cand.sortWith(compareByDescending<Triple<Double, Track, Int>> { it.first }.thenBy { it.second.id }.thenBy { it.third })
            val usedT = HashSet<Int>(); val usedD = HashSet<Int>()
            for ((_, t, j) in cand) {
                if (t.id in usedT || j in usedD) continue
                usedT += t.id; usedD += j
                val b = boxes[j]
                if (line != null && t.crossedMs == null && line.side(t.last.cx, t.last.cy) < 0 && line.side(b.cx, b.cy) >= 0) {
                    t.crossedMs = f.tMs
                    counts.merge(minute(f.tMs) to t.label, 1, Int::plus)
                }
                t.last = b; t.lastMs = f.tMs; t.frames++; t.missed = 0
            }
            for (t in live) if (t.id !in usedT) t.missed++
            live.removeAll { it.missed > maxMissed }
            for ((j, b) in boxes.withIndex()) if (j !in usedD) {
                val t = Track(nextId++, b.label, f.tMs, f.tMs, 1, b)
                all += t; live += t
                if (line == null) counts.merge(minute(f.tMs) to b.label, 1, Int::plus)
            }
        }
        return TrackResult(all, counts)
    }

    private fun normalise(b: TBox, f: TFrame): TBox =
        if (f.w > 0 && f.h > 0) b.copy(x1 = b.x1 / f.w, y1 = b.y1 / f.h, x2 = b.x2 / f.w, y2 = b.y2 / f.h) else b

    companion object {
        fun minute(tMs: Long): Long = Math.floorDiv(tMs, 60_000L)

        fun iou(a: TBox, b: TBox): Double {
            val ix = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1); val iy = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
            if (ix <= 0 || iy <= 0) return 0.0
            val inter = ix * iy
            val u = a.area + b.area - inter
            return if (u <= 0) 0.0 else inter / u
        }
    }
}
