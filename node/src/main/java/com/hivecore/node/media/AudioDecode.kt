package com.hivecore.node.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio file → 16 kHz mono PCM16 (the format every media op works on). */
fun interface AudioDecoder { fun decode(file: File): ShortArray }

/** Pure PCM helpers (JVM-testable). */
object PcmMath {
    /** Interleaved PCM16 with [channels] channels → mono by integer average (truncated toward zero). */
    fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val n = pcm.size / channels
        return ShortArray(n) { i -> var s = 0; for (c in 0 until channels) s += pcm[i * channels + c]; (s / channels).toShort() }
    }

    /**
     * Linear resampling [from] Hz → [to] Hz: output sample k sits at source position k·from/to (exact rational
     * arithmetic on Longs), interpolated between its two neighbours and rounded half away from zero.
     */
    fun resampleLinear(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val outN = (pcm.size.toLong() * to / from).toInt()
        return ShortArray(outN) { k ->
            val num = k.toLong() * from
            val i = (num / to).toInt()
            val frac = (num % to).toDouble() / to
            val a = pcm[minOf(i, pcm.size - 1)].toDouble()
            val b = pcm[minOf(i + 1, pcm.size - 1)].toDouble()
            val v = a + (b - a) * frac
            Math.round(v).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun to16kMono(pcm: ShortArray, rate: Int, channels: Int): ShortArray = resampleLinear(downmix(pcm, channels), rate, Vad.SAMPLE_RATE)
}

/** RIFF/WAVE PCM16 reader (any rate/channels → 16 kHz mono through [PcmMath]); pure. */
object Wav {
    data class Pcm(val samples: ShortArray, val rate: Int, val channels: Int)

    fun isWav(bytes: ByteArray): Boolean = bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

    fun read(bytes: ByteArray): Pcm {
        require(isWav(bytes)) { "not a WAV file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 0; var rate = 0; var bits = 0; var fmt = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII); val len = b.getInt(pos + 4)
            if (id == "fmt ") { fmt = b.getShort(pos + 8).toInt() and 0xffff; channels = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
            if (id == "data") {
                require(bits == 16 && (fmt == 1 || fmt == 0xFFFE)) { "need 16-bit PCM WAV, got format $fmt, $bits bit" }
                val n = minOf(len.toLong() and 0xffffffffL, (bytes.size - pos - 8).toLong()).toInt() / 2
                val s = ShortArray(n) { b.getShort(pos + 8 + it * 2) }
                return Pcm(s, rate, maxOf(1, channels))
            }
            pos += 8 + len + (len and 1)
        }
        error("WAV has no data chunk")
    }

    fun read16kMono(bytes: ByteArray): ShortArray = read(bytes).let { PcmMath.to16kMono(it.samples, it.rate, it.channels) }

    /** 16 kHz mono PCM16 → a WAV file's bytes. */
    fun encode(pcm: ShortArray, rate: Int = Vad.SAMPLE_RATE): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size * 2); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}

/**
 * Android decoder: WAV is read directly ([Wav]); anything else (m4a, mp3, ogg, the audio track of a video) through
 * MediaExtractor + MediaCodec, then downmixed and linearly resampled to 16 kHz mono ([PcmMath]). Codec output may be
 * float PCM on some devices; it is converted to PCM16 (×32767, clamped).
 */
class AndroidAudioDecoder : AudioDecoder {
    override fun decode(file: File): ShortArray {
        val head = file.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
        if (Wav.isWav(head + ByteArray(1))) return Wav.read16kMono(file.readBytes())
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("${file.name}: no audio track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val out = ByteArrayOutputStream()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size).also { buf.get(it) }
                        out.write(if (floatPcm) floatToPcm16(chunk) else chunk)
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            val bytes = out.toByteArray()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = ShortArray(bytes.size / 2) { bb.getShort(it * 2) }
            return PcmMath.to16kMono(pcm, rate, channels)
        } finally {
            ex.release()
        }
    }

    private fun floatToPcm16(b: ByteArray): ByteArray {
        val f = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val o = ByteBuffer.allocate(b.size / 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until b.size / 4) o.putShort((f.getFloat(i * 4) * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        return o.array()
    }
}
