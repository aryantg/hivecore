package com.hivecore.node.gen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values from tools/sd_scheduler_ref.py (numpy float64). */
class SchedulerTest {
    private val sigmas20 = doubleArrayOf(
        11.02833116477522, 8.390684770218579, 6.5063978375308205, 5.134431117517536, 4.116696479276065,
        3.3477635806615926, 2.7561958540300786, 2.292853821788702, 1.9234433148710193, 1.623692081515863,
        1.3761789107458724, 1.1682159550479787, 0.9904088768268802, 0.8356528080331495, 0.6983980911630334,
        0.5740502594628525, 0.458332700961321, 0.34618670638077037, 0.2281467364477799, 0.04131441199678309, 0.0)

    @Test fun timesteps20() {
        val s = DpmSolverPP2M(20)
        assertArrayEquals(IntArray(20) { 951 - 50 * it }, s.timesteps)
        assertEquals(21, s.sigmas.size)
        for (i in sigmas20.indices) assertEquals("sigma[$i]", sigmas20[i], s.sigmas[i], 1e-12 * maxOf(1.0, sigmas20[i]))
        assertTrue("all timesteps inside the UNet's quantized timestep range (≤ 968)", s.timesteps.all { it in 0..968 })
    }

    @Test fun timesteps4() {
        val s = DpmSolverPP2M(4)
        assertArrayEquals(intArrayOf(751, 501, 251, 1), s.timesteps)
        val ref = doubleArrayOf(4.116696479276065, 1.623692081515863, 0.6983980911630334, 0.04131441199678309, 0.0)
        for (i in ref.indices) assertEquals(ref[i], s.sigmas[i], 1e-12)
    }

    @Test fun alphasCumprodEndpoints() {
        val s = DpmSolverPP2M()
        assertEquals(1 - 0.00085, s.alphasCumprod[0], 1e-15)
        assertEquals(0.004660098513077238, s.alphasCumprod[999], 1e-12)   // SD 1.5's ᾱ_999
    }

    private fun toy(steps: Int): DoubleArray {
        val s = DpmSolverPP2M(steps)
        var x = doubleArrayOf(1.0, -0.5, 0.25, 2.0)
        for (i in 0 until steps) {
            val t = s.timesteps[i]
            val eps = DoubleArray(x.size) { 0.1 * x[it] + 0.05 * Math.sin(t / 100.0) }
            x = s.step(eps, x)
        }
        return x
    }

    @Test fun toyRunsMatchNumpy() {
        val r20 = doubleArrayOf(7.983433381449779, -4.218021529494635, 1.8827059259775707, 16.117736655412713)
        val r4 = doubleArrayOf(3.2861487849286557, -1.7415769786124418, 0.7722859031581074, 6.6379659606227195)
        assertArrayEquals(r20, toy(20), 1e-9)
        assertArrayEquals(r4, toy(4), 1e-9)
    }

    @Test fun deterministicAndFinishes() {
        assertArrayEquals(toy(20), toy(20), 0.0)
        val s = DpmSolverPP2M(1)
        s.step(doubleArrayOf(0.0), doubleArrayOf(1.0))
        assertEquals(1, s.stepIndex)
        assertTrue(runCatching { s.step(doubleArrayOf(0.0), doubleArrayOf(1.0)) }.isFailure)
    }

    @Test fun perfectEpsRecoversX0() {
        // sample = α·x0 + σ_t·ε with the true ε at every step ⇒ the solver returns x0 exactly (up to rounding)
        val s = DpmSolverPP2M(20)
        val x0 = doubleArrayOf(0.3, -1.2); val e = doubleArrayOf(0.7, 0.1)
        var x = DoubleArray(2) { val (a, sg) = DpmSolverPP2M.alphaSigma(s.sigmas[0]); a * x0[it] + sg * e[it] }
        for (i in 0 until 20) x = s.step(e, x)
        assertArrayEquals(x0, x, 1e-9)
    }
}
