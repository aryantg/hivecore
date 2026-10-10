package com.hivecore.node.media



/** A span of 16 kHz samples [start, end); [speech] = some frame in it is above the speech threshold. */
data class AudioSegment(val index: Int, val start: Int, val end: Int, val speech: Boolean) {
    val startMs: Long get() = start * 1000L / Vad.SAMPLE_RATE
    val endMs: Long get() = end * 1000L / Vad.SAMPLE_RATE
}

/**
 * Deterministic energy VAD (swarm plan §4A.3 VAD_SPLIT; sherpa's silero VAD is gone, §15 Q7). Integer arithmetic
 * only (sum of squared PCM16 samples per 20 ms frame as a Long), so every phone cuts at the same sample.
 *
 * [split]: long audio → segments of at most [maxSec] seconds. Each cut is placed at the quietest frame (lowest
 * energy, ties → the latest frame) in the last [searchSec] seconds before the 30 s limit, and the next segment starts
 * [overlapSec] before the cut, so a word cut at a boundary appears whole in one of the two ([Stitch] removes the
 * duplicate). Audio of at most [maxSec] is one segment.
 */
object Vad {
    const val SAMPLE_RATE = 16_000
    const val FRAME = 320                       // 20 ms
    /** Mean-square threshold for speech: RMS ≈ 0.01 full scale (−40 dBFS) = 328² ≈ 107 584. */
    const val SPEECH_MEAN_SQ = 107_584L

    fun frameEnergies(pcm: ShortArray, frame: Int = FRAME): LongArray {
        val n = (pcm.size + frame - 1) / frame
        return LongArray(n) { f ->
            var s = 0L
            val end = minOf(pcm.size, (f + 1) * frame)
            for (i in f * frame until end) { val x = pcm[i].toLong(); s += x * x }
            s / frame
        }
    }

    fun split(pcm: ShortArray, maxSec: Int = 30, overlapSec: Int = 1, searchSec: Int = 5,
              speechMeanSq: Long = SPEECH_MEAN_SQ): List<AudioSegment> {
        require(overlapSec < searchSec && searchSec < maxSec) { "need overlap < search < max" }
        val n = pcm.size
        if (n == 0) return emptyList()
        val e = frameEnergies(pcm)
        val maxS = maxSec * SAMPLE_RATE; val ov = overlapSec * SAMPLE_RATE; val search = searchSec * SAMPLE_RATE
        val cuts = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (true) {
            if (n - start <= maxS) { cuts += start to n; break }
            val limit = start + maxS
            // candidate cut points: frame starts in (limit - search, limit], quietest frame wins
            val fLo = (limit - search) / FRAME + 1
            val fHi = limit / FRAME
            var best = fHi; var bestE = Long.MAX_VALUE
            for (f in fLo..fHi) {
                val en = if (f < e.size) e[f] else 0L
                if (en <= bestE) { bestE = en; best = f }
            }
            // a quiet frame [f*FRAME, (f+1)*FRAME) is cut at its start so the segment stays ≤ maxS
            val cut = best * FRAME
            cuts += start to cut
            start = cut - ov
        }
        return cuts.mapIndexed { i, (s, t) ->
            val speech = (s / FRAME until minOf(e.size, (t + FRAME - 1) / FRAME)).any { e[it] >= speechMeanSq }
            AudioSegment(i, s, t, speech)
        }
    }
}

/**
 * Streaming end-of-utterance detector for the microphone (WhisperStt): a frame is speech when its mean square is
 * above max([minMeanSq], [noiseFactor] × the quietest frame seen so far), or above [loudMeanSq]; the utterance ends after
 * [silenceMs] of frames below the threshold following speech, or at [maxMs]. Frames of any length; pure.
 */
class EnergyGate(
    private val silenceMs: Int = 1200,
    private val maxMs: Int = 30_000,
    private val noSpeechMs: Int = 8_000,
    private val minMeanSq: Long = Vad.SPEECH_MEAN_SQ / 4,
    private val noiseFactor: Long = 4,
    /** Always speech, whatever the floor (≈ −34 dBFS), so audio that starts mid-word is not missed. */
    private val loudMeanSq: Long = Vad.SPEECH_MEAN_SQ * 4,
) {
    var heardSpeech = false; private set
    var totalMs = 0; private set
    private var silentMs = 0
    private var floor = Long.MAX_VALUE

    /** Feeds one frame; true = stop recording now. */
    fun feed(frame: ShortArray): Boolean {
        if (frame.isEmpty()) return false
        var s = 0L
        for (x in frame) s += x.toLong() * x.toLong()
        val ms = s / frame.size
        val dur = frame.size * 1000 / Vad.SAMPLE_RATE
        totalMs += dur
        if (ms < floor) floor = ms
        val thr = maxOf(minMeanSq, floor * noiseFactor)
        val speech = (ms >= thr && ms > floor) || ms >= loudMeanSq
        if (speech) { heardSpeech = true; silentMs = 0 } else if (heardSpeech) silentMs += dur
        return totalMs >= maxMs || (heardSpeech && silentMs >= silenceMs) || (!heardSpeech && totalMs >= noSpeechMs)
    }
}
