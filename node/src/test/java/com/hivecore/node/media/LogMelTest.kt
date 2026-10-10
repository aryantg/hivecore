package com.hivecore.node.media

import com.hivecore.runtime.models.TensorIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reference values from an independent numpy implementation (np.fft.rfft, np.pad reflect, librosa-style Slaney
 * filterbank) of the same 1 s test tone: 12000·sin(2π·440t) + 6000·sin(2π·1000t), truncated to int16.
 */
class LogMelTest {
    private val tone = ShortArray(16_000) { n ->
        (12000 * StrictMath.sin(2 * Math.PI * 440 * n / 16000) + 6000 * StrictMath.sin(2 * Math.PI * 1000 * n / 16000)).toInt().toShort()
    }

    @Test fun filterbankMatchesWhisper() {
        val f = LogMel.filters
        assertEquals(80, f.size); assertEquals(201, f[0].size)
        assertEquals(0.024862593984176087, f[0][1], 1e-12)       // openai mel_filters.npz [0,1]
        assertEquals(0.0, f[0][0], 0.0)
        assertEquals(43, f[40].indices.maxByOrNull { f[40][it] })
        assertEquals(192, f[79].indices.maxByOrNull { f[79][it] })
        assertEquals(1.9990241029178577, f.sumOf { it.sum() }, 1e-9)
    }

    @Test fun matchesNumpyReference() {
        val x = LogMel.compute(tone)
        assertEquals(80 * 3000, x.size)
        fun at(m: Int, t: Int) = x[m * 3000 + t].toDouble()
        val ref = listOf(
            Triple(3, 50, -0.6294269869745066), Triple(12, 50, 1.2258937130798724), Triple(25, 50, 1.1229869272531299),
            Triple(12, 0, 1.258535714387239), Triple(12, 1, 1.2294632759411723), Triple(12, 100, 1.1681793250582022),
            Triple(12, 101, 0.2905111552499906), Triple(20, 101, 0.13232053333732485), Triple(12, 102, -0.6294269869745066),
            Triple(10, 2999, -0.6294269869745066),
        )
        for ((m, t, v) in ref) assertEquals("mel[$m,$t]", v, at(m, t), 2e-5)
        assertEquals(1.3705730130254934, x.maxOrNull()!!.toDouble(), 2e-5)
        assertEquals(-0.6294269869745066, x.minOrNull()!!.toDouble(), 2e-5)
        assertEquals(-149345.24985326678, x.sumOf { it.toDouble() }, 0.5)
    }

    @Test fun silenceAndPaddingProperties() {
        val x = LogMel.compute(ShortArray(0))
        assertTrue(x.all { it == x[0] })                    // all frames at log10(1e-10): (−10+4)/4
        assertEquals(-1.5f, x[0], 1e-6f)
        // max − 8 floor: no value is more than 2 below the max after (x+4)/4
        val y = LogMel.compute(tone)
        assertTrue(y.all { it >= y.maxOrNull()!! - 2f - 1e-6f })
        // > 30 s is trimmed: same features as the first 30 s
        val long = ShortArray(31 * 16000) { tone[it % tone.size] }
        assertArrayEquals(LogMel.compute(long.copyOf(480_000)), LogMel.compute(long), 0f)
    }

    @Test fun halfBytesAreDeterministicFloat16() {
        val a = LogMel.halfBytes(tone); val b = LogMel.halfBytes(tone)
        assertEquals(80 * 3000 * 2, a.size)
        assertArrayEquals(a, b)
        val bb = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        val f = LogMel.compute(tone)
        assertEquals(f[12 * 3000 + 50], TensorIo.halfToFloat(bb.getShort((12 * 3000 + 50) * 2)), 1e-3f)
    }
}
