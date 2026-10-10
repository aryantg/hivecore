package com.hivecore.node.gen

/**
 * Seeded Gaussian latents that are identical on every phone and the JVM (R4): SplitMix64 for the bits (no
 * platform RNG), 53-bit uniform doubles, Box–Muller with StrictMath (fdlibm, bit-exact everywhere). Values come
 * in pairs (r·cos θ, r·sin θ) and fill the latent in its tensor order (NHWC [1, 64, 64, 4] for AI Hub's SD 1.5).
 */
object Noise {
    class SplitMix64(seed: Long) {
        private var state = seed
        fun nextLong(): Long {
            state += -0x61c8864680b583ebL            // 0x9E3779B97F4A7C15
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L   // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L   // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
        /** Uniform in (0, 1]: never 0, so log() is finite. */
        fun nextOpenDouble(): Double = ((nextLong() ushr 11) + 1L) * (1.0 / (1L shl 53))
        /** Uniform in [0, 1). */
        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
    }

    /** [n] standard normal samples for [seed]. */
    fun gaussian(seed: Long, n: Int): DoubleArray {
        val rng = SplitMix64(seed)
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            val u1 = rng.nextOpenDouble()
            val u2 = rng.nextDouble()
            val r = StrictMath.sqrt(-2.0 * StrictMath.log(u1))
            val th = 2.0 * StrictMath.PI * u2
            out[i++] = r * StrictMath.cos(th)
            if (i < n) out[i++] = r * StrictMath.sin(th)
        }
        return out
    }
}
