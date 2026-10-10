package com.hivecore.node.media

import com.hivecore.runtime.models.TensorIo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper's log-mel spectrogram (openai/whisper `audio.log_mel_spectrogram`, which the Hugging Face
 * `WhisperFeatureExtractor` used by AI Hub's app reproduces), pure JVM and deterministic:
 *
 *  1. 16 kHz mono samples in [-1, 1) (PCM16 / 32768), trimmed or zero-padded to 30 s = [N_SAMPLES].
 *  2. STFT: periodic Hann window of [N_FFT] = 400, hop [HOP] = 160, centred frames (the signal is reflect-padded by
 *     200 samples on both sides), giving 3001 frames of which the last is dropped ([N_FRAMES] = 3000);
 *     power = |X[k]|², k = 0..200.
 *  3. Mel: an 80 × 201 Slaney filterbank ([melFilters], see there), mel = filters · power.
 *  4. log10(max(mel, 1e-10)), clamped below at (global max − 8), then (x + 4) / 4.
 *
 * Determinism: the window and the DFT twiddles come from [StrictMath] (bit-identical on every JVM/ART), and all
 * arithmetic is IEEE double in a fixed order. Frames whose 400 input samples are all zero (the padding of a short
 * clip) are known to have power 0 and are not transformed, which is exact, not an approximation.
 */
object LogMel {
    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 80
    const val N_FRAMES = 3000
    const val N_SAMPLES = N_FRAMES * HOP          // 480 000 = 30 s
    const val N_BINS = N_FFT / 2 + 1              // 201

    /**
     * The Slaney-style mel filterbank (librosa `filters.mel(sr=16000, n_fft=400, n_mels=80, htk=False,
     * norm="slaney")`, which is what openai/whisper's `mel_filters.npz` holds):
     *  - mel scale: linear below 1 kHz, `mel = f / (200/3)`; logarithmic above, `mel = 15 + ln(f / 1000) / (ln(6.4) / 27)`.
     *  - 82 points equally spaced in mel between mel(0) and mel(8000), converted back to Hz (`melF`).
     *  - FFT bin k is at `k · 16000 / 400` Hz. Filter i is the triangle rising from melF[i] to melF[i+1] and falling
     *    to melF[i+2]: `w = max(0, min((f − melF[i]) / (melF[i+1] − melF[i]), (melF[i+2] − f) / (melF[i+2] − melF[i+1])))`.
     *  - Slaney area normalisation: each row is multiplied by `2 / (melF[i+2] − melF[i])`.
     * Row-major [N_MELS][N_BINS].
     */
    val filters: Array<DoubleArray> by lazy { melFilters() }

    fun melFilters(nMels: Int = N_MELS, nFft: Int = N_FFT, sampleRate: Int = SAMPLE_RATE): Array<DoubleArray> {
        val nBins = nFft / 2 + 1
        val fftFreqs = DoubleArray(nBins) { it * sampleRate.toDouble() / nFft }
        val mMin = hzToMel(0.0); val mMax = hzToMel(sampleRate / 2.0)
        val melF = DoubleArray(nMels + 2) { melToHz(mMin + (mMax - mMin) * it / (nMels + 1)) }
        return Array(nMels) { i ->
            val lo = melF[i]; val c = melF[i + 1]; val hi = melF[i + 2]
            val enorm = 2.0 / (hi - lo)
            DoubleArray(nBins) { k ->
                val f = fftFreqs[k]
                val lower = (f - lo) / (c - lo)
                val upper = (hi - f) / (hi - c)
                maxOf(0.0, minOf(lower, upper)) * enorm
            }
        }
    }

    private const val F_SP = 200.0 / 3
    private const val MIN_LOG_HZ = 1000.0
    private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP   // 15
    private val LOGSTEP = StrictMath.log(6.4) / 27.0

    fun hzToMel(f: Double): Double = if (f < MIN_LOG_HZ) f / F_SP else MIN_LOG_MEL + StrictMath.log(f / MIN_LOG_HZ) / LOGSTEP
    fun melToHz(m: Double): Double = if (m < MIN_LOG_MEL) F_SP * m else MIN_LOG_HZ * StrictMath.exp(LOGSTEP * (m - MIN_LOG_MEL))

    private val window: DoubleArray by lazy { DoubleArray(N_FFT) { 0.5 - 0.5 * StrictMath.cos(2.0 * Math.PI * it / N_FFT) } }
    private val cosT: DoubleArray by lazy { DoubleArray(N_FFT) { StrictMath.cos(2.0 * Math.PI * it / N_FFT) } }
    private val sinT: DoubleArray by lazy { DoubleArray(N_FFT) { StrictMath.sin(2.0 * Math.PI * it / N_FFT) } }

    /** PCM16 → floats in [-1, 1). */
    fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    /** The normalised log-mel features, row-major [N_MELS][N_FRAMES] (what the encoder's `input_features` holds). */
    fun compute(pcm: ShortArray): FloatArray = compute(toFloat(pcm))

    fun compute(audio: FloatArray): FloatArray {
        val n = minOf(audio.size, N_SAMPLES)
        val pad = N_FFT / 2
        // reflect-padded signal of length N_SAMPLES + N_FFT; x[i] = padded[i - pad]
        val x = DoubleArray(N_SAMPLES + N_FFT)
        fun sample(j: Int): Double = if (j in 0 until n) audio[j].toDouble() else 0.0
        for (i in x.indices) {
            var j = i - pad
            if (j < 0) j = -j
            if (j >= N_SAMPLES) j = 2 * (N_SAMPLES - 1) - j
            x[i] = sample(j)
        }
        // last frame that touches a non-zero sample (start i*HOP ≤ n-1+pad, and reflections at the start)
        val lastLive = if (n == 0) -1 else minOf(N_FRAMES - 1, (n - 1 + pad) / HOP)
        val mel = DoubleArray(N_MELS * N_FRAMES)
        val power = DoubleArray(N_BINS)
        val fr = DoubleArray(N_FFT)
        val fb = filters
        for (t in 0 until N_FRAMES) {
            if (t > lastLive) {
                for (m in 0 until N_MELS) mel[m * N_FRAMES + t] = 0.0
                continue
            }
            val off = t * HOP
            for (i in 0 until N_FFT) fr[i] = x[off + i] * window[i]
            for (k in 0 until N_BINS) {
                var re = 0.0; var im = 0.0
                var idx = 0
                for (i in 0 until N_FFT) {
                    re += fr[i] * cosT[idx]
                    im -= fr[i] * sinT[idx]
                    idx += k; if (idx >= N_FFT) idx -= N_FFT
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val row = fb[m]
                var s = 0.0
                for (k in 0 until N_BINS) s += row[k] * power[k]
                mel[m * N_FRAMES + t] = s
            }
        }
        var max = Double.NEGATIVE_INFINITY
        for (i in mel.indices) {
            val v = StrictMath.log10(maxOf(mel[i], 1e-10))
            mel[i] = v
            if (v > max) max = v
        }
        val floor = max - 8.0
        return FloatArray(mel.size) { ((maxOf(mel[it], floor) + 4.0) / 4.0).toFloat() }
    }

    /** Features → float16 little-endian bytes ([1, 80, 3000], the AI Hub encoder's input dtype). */
    fun toHalfBytes(features: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(features.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in features) b.putShort(TensorIo.floatToHalf(v))
        return b.array()
    }

    fun halfBytes(pcm: ShortArray): ByteArray = toHalfBytes(compute(pcm))
}
