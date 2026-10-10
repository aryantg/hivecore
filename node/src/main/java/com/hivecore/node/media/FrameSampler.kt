package com.hivecore.node.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** One sampled frame: time, why it was taken ("fixed" | "scene"), and the content-addressed JPEG it was written to. */
data class SampledFrame(val tMs: Long, val reason: String, val file: String, val sha256: String, val w: Int, val h: Int)

data class FrameSampleResult(val durationMs: Long, val frames: List<SampledFrame>)

/** Video → frames (FRAME_SAMPLE). */
fun interface FrameSource {
    fun sample(video: File, params: FrameParams, outDir: File): FrameSampleResult
}

/**
 * [everyMs]: fixed timestamps 0, every, 2·every, … < duration. [sceneStepMs] > 0: the video is also probed every
 * [sceneStepMs] at a small size; a probe whose colour histogram differs from the previous probe's by more than
 * [sceneThreshold] (L1 distance of normalised 64-bin histograms, 0..2) adds a "scene" frame at that time, unless a
 * frame within [sceneStepMs] is already taken. [maxSide]: long side of the written JPEG.
 */
data class FrameParams(val everyMs: Long = 5_000, val sceneStepMs: Long = 1_000, val sceneThreshold: Double = 0.5,
                       val maxSide: Int = 640, val quality: Int = 85, val maxFrames: Int = 2_000)

/** Pure parts of FRAME_SAMPLE: timestamps, histograms, scene picks. */
object SceneMath {
    fun fixedTimes(durationMs: Long, everyMs: Long): List<Long> {
        require(everyMs > 0) { "everyMs must be > 0" }
        if (durationMs <= 0) return listOf(0L)
        return generateSequence(0L) { it + everyMs }.takeWhile { it < durationMs }.toList()
    }

    /** 4×4×4 RGB histogram of ARGB pixels, normalised to sum 1. */
    fun histogram(argb: IntArray): DoubleArray {
        val h = DoubleArray(64)
        if (argb.isEmpty()) return h
        for (p in argb) {
            val r = (p shr 16 and 0xff) shr 6; val g = (p shr 8 and 0xff) shr 6; val b = (p and 0xff) shr 6
            h[(r shl 4) or (g shl 2) or b] += 1.0
        }
        for (i in h.indices) h[i] /= argb.size
        return h
    }

    fun distance(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += Math.abs(a[i] - b[i]); return s }

    /** Probe times where the histogram jumps by more than [threshold] vs the previous probe. */
    fun sceneTimes(probes: List<Pair<Long, DoubleArray>>, threshold: Double): List<Long> =
        probes.zipWithNext().filter { (a, b) -> distance(a.second, b.second) > threshold }.map { it.second.first }

    /** Fixed ∪ scene times (scene times within [minGapMs] of a taken frame are dropped), sorted. */
    fun merge(fixed: List<Long>, scene: List<Long>, minGapMs: Long): List<Pair<Long, String>> {
        val out = fixed.map { it to "fixed" }.toMutableList()
        for (t in scene.sorted()) if (out.none { Math.abs(it.first - t) < minGapMs }) out += t to "scene"
        return out.sortedBy { it.first }
    }
}

/** MediaMetadataRetriever implementation (Android only; frames at the closest sync-or-exact frame per API). */
class AndroidFrameSource : FrameSource {
    override fun sample(video: File, params: FrameParams, outDir: File): FrameSampleResult {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val fixed = SceneMath.fixedTimes(dur, params.everyMs)
            val scene = if (params.sceneStepMs > 0 && dur > 0) {
                val probes = generateSequence(0L) { it + params.sceneStepMs }.takeWhile { it < dur }.mapNotNull { t ->
                    val bm = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64) ?: return@mapNotNull null
                    val px = IntArray(bm.width * bm.height).also { bm.getPixels(it, 0, bm.width, 0, 0, bm.width, bm.height) }
                    bm.recycle()
                    t to SceneMath.histogram(px)
                }.toList()
                SceneMath.sceneTimes(probes, params.sceneThreshold)
            } else emptyList()
            val picks = SceneMath.merge(fixed, scene, params.sceneStepMs.coerceAtLeast(1)).take(params.maxFrames)
            outDir.mkdirs()
            val frames = picks.mapNotNull { (t, why) ->
                val bm = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@mapNotNull null
                val scale = minOf(1.0, params.maxSide.toDouble() / maxOf(bm.width, bm.height))
                val sb = if (scale < 1.0) Bitmap.createScaledBitmap(bm, Math.round(bm.width * scale).toInt(), Math.round(bm.height * scale).toInt(), true) else bm
                val bytes = ByteArrayOutputStream().also { sb.compress(Bitmap.CompressFormat.JPEG, params.quality, it) }.toByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val name = "$sha.jpg"
                File(outDir, name).let { if (!it.isFile) it.writeBytes(bytes) }
                val f = SampledFrame(t, why, name, sha, sb.width, sb.height)
                if (sb !== bm) sb.recycle(); bm.recycle()
                f
            }
            return FrameSampleResult(dur, frames)
        } finally {
            runCatching { r.release() }
        }
    }
}
