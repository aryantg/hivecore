package com.hivecore.node.gen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

class NoiseTest {
    @Test fun splitMix64ReferenceValues() {
        // Vigna's reference sequence for seed 1234567
        val r = Noise.SplitMix64(1234567)
        val expected = longArrayOf(6457827717110365317L, 3203168211198807973L, -8629252141511181193L)
        assertArrayEquals(expected, LongArray(3) { r.nextLong() })
    }

    @Test fun sameSeedSameLatent() {
        val a = Noise.gaussian(7, 16384); val b = Noise.gaussian(7, 16384)
        assertArrayEquals(a, b, 0.0)
        assertFalse(a.contentEquals(Noise.gaussian(8, 16384)))
    }

    @Test fun pinnedHash() {
        // Pins the exact bits (StrictMath is fdlibm everywhere, so phones must produce the same).
        val g = Noise.gaussian(7, 16384)
        val bb = ByteBuffer.allocate(g.size * 8); g.forEach { bb.putDouble(it) }
        assertEquals(PINNED, SdPipeline.sha256(bb.array()).take(16))
    }

    @Test fun standardNormalMoments() {
        val g = Noise.gaussian(42, 200_000)
        assertEquals(0.0, g.average(), 0.01)
        assertEquals(1.0, SdPipeline.std(g), 0.01)
        assertEquals(5, Noise.gaussian(1, 5).size)   // odd n
    }

    companion object { const val PINNED = "7fed744357a6609b" }
}
