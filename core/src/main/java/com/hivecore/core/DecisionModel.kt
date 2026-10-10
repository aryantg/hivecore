package com.hivecore.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A System One decision model (docs/plans/decision-chunking.md §3): typed closed questions answered with calibrated
// probabilities in one call. It never generates text. The request and answer shapes follow the Clef / SystemOne API
// (`{model, state, questions}` → `{model, answers, usage}`) so journaled requests replay against Clef-flash on a laptop.
// `core.Decision<T>` is the closed-choice vote result of the planner; the journaled record here is [Decided].

/** One typed question over the request's `state`. [id] keys the answer; [instructions] is the question text. */
@Serializable sealed interface Question {
    val id: String
    val instructions: String

    /** Pick one of [criteria] (key → description); the sorted keys are the option order. */
    @Serializable @SerialName("choice")
    data class Choice(override val id: String, override val instructions: String, val criteria: Map<String, String>) : Question

    /** A position on the ordered rubric [criteria] (2..10 levels), reported as the expected level in [0, 1]. */
    @Serializable @SerialName("score")
    data class Score(override val id: String, override val instructions: String, val criteria: List<String>) : Question

    /** The probability that the statement is true. [group] batches sibling statements over one state (the boundary windows). */
    @Serializable @SerialName("noul")
    data class Noul(override val id: String, override val instructions: String, val group: String? = null) : Question
}

@Serializable data class DecisionRequest(val model: String, val state: String, val questions: List<Question>)

@Serializable sealed interface Answer {
    @Serializable @SerialName("choice")
    data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>) : Answer
    @Serializable @SerialName("score")
    data class Score(val score: Double, val confidence: Double, val legend: List<String>, val probabilities: List<Double>) : Answer
    @Serializable @SerialName("noul")
    data class Noul(val probability: Double) : Answer

    /** The probability behind the answer: p(true), the argmax's probability, or the Score's confidence. */
    val confidenceValue: Double get() = when (this) { is Choice -> confidence; is Score -> confidence; is Noul -> probability }
}

@Serializable data class DecisionUsage(val questions: Int, val llmCalls: Int, val ms: Long)

@Serializable data class DecisionResponse(val model: String, val answers: Map<String, Answer>, val usage: DecisionUsage)

/** Identity of a decision model build: chunk ids and journal rows carry [tag] (`<sha256>:<calibrationVersion>`). */
@Serializable data class DecisionBuild(val modelId: String, val sha256: String, val calibrationVersion: String) {
    val tag: String get() = "$sha256:$calibrationVersion"
}

interface DecisionModel {
    /** The build answering now, or null when unavailable (no model loaded, unpinned build): callers fall back. */
    fun build(): DecisionBuild?
    /** Answers every question of [request] in one call. Probabilities are rounded to 4 dp; equal requests give equal answers on one device. */
    suspend fun decide(request: DecisionRequest): Result<DecisionResponse>
}

/** A runtime that can score the allowed [options] from next-token logits (none today); the voting model uses it when present. */
interface OptionScorer {
    suspend fun scoreOptions(messages: List<ChatMsg>, options: List<String>): Result<List<Double>>
}

/** A journaled decision: which point asked, the request hash, the answers and the build. [hash] = sha256 over the rest. */
@Serializable data class Decided(val point: String, val requestHash: String, val response: DecisionResponse, val build: DecisionBuild, val hash: String)

/**
 * Per-build calibration (catalog `calibration`): a monotone piecewise-linear map from a raw score in [0, 1] (vote
 * fraction, or softmax probability) to a calibrated probability. [bins] are `[x, y]` pairs sorted by x; outside the
 * first and last x the nearest y holds. Absent ⇒ identity, version [NONE].
 */
@Serializable data class Calibration(val version: String, val bins: List<List<Double>> = emptyList()) {
    fun apply(x: Double): Double {
        val pts = bins.filter { it.size == 2 }
        if (pts.isEmpty()) return x.coerceIn(0.0, 1.0)
        if (x <= pts.first()[0]) return pts.first()[1].coerceIn(0.0, 1.0)
        if (x >= pts.last()[0]) return pts.last()[1].coerceIn(0.0, 1.0)
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            if (x <= x1) { val t = if (x1 == x0) 1.0 else (x - x0) / (x1 - x0); return (y0 + t * (y1 - y0)).coerceIn(0.0, 1.0) }
        }
        return x.coerceIn(0.0, 1.0)
    }

    /** Problems with this table (empty = valid): points must be `[x, y]` in [0, 1] with non-decreasing x and y. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (version.isBlank()) out += "calibration version is blank"
        var px = -1.0; var py = -1.0
        for ((i, p) in bins.withIndex()) {
            if (p.size != 2) { out += "calibration bin $i is not [x, y]"; continue }
            val (x, y) = p
            if (x !in 0.0..1.0 || y !in 0.0..1.0) out += "calibration bin $i is outside [0, 1]"
            if (x < px || y < py) out += "calibration bin $i is not monotone"
            px = x; py = y
        }
        return out
    }

    companion object {
        const val NONE = "none"
        val IDENTITY = Calibration(NONE)
    }
}

/** 4-decimal rounding of a probability (what the Clef API reports). */
fun round4(p: Double): Double = Math.round(p * 10_000.0) / 10_000.0
