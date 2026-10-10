package com.hivecore.node.gen



import kotlin.math.sqrt

// Swarm plan §4A.3 GEN_IMAGE: the denoising schedule, pure Kotlin in double precision (R4: pinned scheduler).
//
// Choice: DPM-Solver++(2M) — the multistep solver diffusers calls DPMSolverMultistepScheduler with
// algorithm_type = "dpmsolver++", solver_order = 2, solver_type = "midpoint", prediction_type = "epsilon",
// final_sigmas_type = "zero" (the last step is first order and lands exactly on the predicted x0).
// It is a VP-space solver: the latent keeps unit variance (init noise sigma = 1, scale_model_input = identity),
// which matches the calibration range of AI Hub's quantized UNet latent input (about ±8).
// Betas: SD 1.5 "scaled_linear" (sqrt-linear from 0.00085 to 0.012 over 1000 training steps).
// Timesteps: "leading" spacing with steps_offset = 1, so 20 steps are 951, 901, …, 51, 1. All stay below the
// UNet timestep input's quantized range (uint16 × 0.01477 ≤ 968), so none are clipped.
// Reference values: tools/sd_scheduler_ref.py (numpy) → SchedulerTest.

class DpmSolverPP2M(
    val steps: Int = 20,
    val trainSteps: Int = 1000,
    val betaStart: Double = 0.00085,
    val betaEnd: Double = 0.012,
) {
    init { require(steps in 1..trainSteps) { "steps must be 1..$trainSteps" } }

    /** Cumulative alpha products of the training schedule. */
    val alphasCumprod: DoubleArray = run {
        val a = sqrt(betaStart); val b = sqrt(betaEnd)
        var prod = 1.0
        DoubleArray(trainSteps) { i ->
            val s = a + (b - a) * i / (trainSteps - 1)
            prod *= 1.0 - s * s
            prod
        }
    }

    /** The UNet timesteps, descending (e.g. 951 … 1). */
    val timesteps: IntArray = (trainSteps / steps).let { ratio -> IntArray(steps) { (steps - 1 - it) * ratio + 1 } }

    /** σ = sqrt((1 − ᾱ)/ᾱ) at each timestep, plus a final 0 (size steps + 1). */
    val sigmas: DoubleArray = DoubleArray(steps + 1) { i ->
        if (i == steps) 0.0 else timesteps[i].let { t -> val ac = alphasCumprod[t]; sqrt((1.0 - ac) / ac) }
    }

    private var prevX0: DoubleArray? = null
    private var index = 0

    /** Index of the next [step] (0 after construction or [reset]). */
    val stepIndex: Int get() = index

    fun reset() { prevX0 = null; index = 0 }

    /** Initial latent: unit Gaussian noise (VP solver, init noise sigma = 1). */
    fun initLatent(noise: DoubleArray): DoubleArray = noise.copyOf()

    /**
     * One solver step from timesteps[stepIndex] with the (guided) noise prediction [eps]. Returns the new sample;
     * after the last step it is the denoised x0 to decode.
     */
    fun step(eps: DoubleArray, sample: DoubleArray): DoubleArray {
        require(index < steps) { "scheduler already finished ($steps steps)" }
        require(eps.size == sample.size) { "eps ${eps.size} vs sample ${sample.size}" }
        val i = index
        val (aS0, sS0) = alphaSigma(sigmas[i])
        val x0 = DoubleArray(sample.size) { (sample[it] - sS0 * eps[it]) / aS0 }
        val out: DoubleArray
        if (sigmas[i + 1] == 0.0) {
            out = x0.copyOf()
        } else {
            val (aT, sT) = alphaSigma(sigmas[i + 1])
            val lamT = StrictMath.log(aT) - StrictMath.log(sT)
            val lamS0 = StrictMath.log(aS0) - StrictMath.log(sS0)
            val h = lamT - lamS0
            val c = sT / sS0
            val d = aT * (StrictMath.exp(-h) - 1.0)
            val prev = prevX0
            out = if (prev == null) {
                DoubleArray(sample.size) { c * sample[it] - d * x0[it] }
            } else {
                val (aS1, sS1) = alphaSigma(sigmas[i - 1])
                val lamS1 = StrictMath.log(aS1) - StrictMath.log(sS1)
                val r0 = (lamS0 - lamS1) / h
                DoubleArray(sample.size) { k ->
                    val d1 = (x0[k] - prev[k]) / r0
                    c * sample[k] - d * x0[k] - 0.5 * d * d1
                }
            }
        }
        prevX0 = x0
        index++
        return out
    }

    companion object {
        /** VP (α_t, σ_t) from the VE sigma: α = 1/sqrt(σ²+1), σ_t = σ·α. */
        fun alphaSigma(sigma: Double): Pair<Double, Double> {
            val a = 1.0 / sqrt(sigma * sigma + 1.0)
            return a to sigma * a
        }
    }
}
