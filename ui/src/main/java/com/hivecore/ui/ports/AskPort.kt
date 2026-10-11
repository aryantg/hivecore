package com.hivecore.ui.ports

import com.hivecore.core.Provenance
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.picture.Picture
import kotlinx.coroutines.flow.Flow

// Spec section 17 (Ask) and section 9 (LOCAL / CLOUD): what the Ask screen needs from the planner, the compiler and
// the commander. The integration wave implements [AskPort] over the planner tiers and the graph engine; [FakeAskPort]
// drives the screen and the JVM tests until then.

/** Spec section 9.1: where a plan runs. */
enum class RunMode { LOCAL, CLOUD }

/** Spec section 17: the badge on an answer. CLOUD marks a result with at least one cloud-sourced step (section 9.4). */
enum class AnswerBadge { VERIFIED, UNVERIFIED, CLOUD }

/** One line of the plan panel: op, phone, estimate. [cloud] marks a step the CLOUD option sends out (or that the gate re-ran). */
data class PlanStepView(
    val id: String,
    val op: String,
    val phone: String,
    val estimateMs: Long,
    val cloud: Boolean = false,
    /** null = not verifiable (reduce / render), else whether a replica or a local check agreed */
    val verified: Boolean? = null,
    /** spec section 9.3: private lineage; never cloud-eligible */
    val privateLineage: Boolean = false,
)

/** Spec section 9.1: the estimate shown for one execution option. */
data class ModeEstimate(
    val mode: RunMode,
    val ms: Long,
    /** rupees; 0 for LOCAL */
    val costInr: Double,
    /** "what leaves": "nothing leaves the phones", "prompt text and 12 KB of released rows for 2 steps" */
    val leaves: String,
    val phones: Int,
    /** one line of detail: which phones, which models load / which steps go to which cloud model */
    val detail: String = "",
)

/** A compiled plan (spec section 7.3): the steps in execution order, the planner's confidence and the plan hash. */
data class AskPlan(
    val planId: String,
    val planHash: String,
    val request: String,
    val steps: List<PlanStepView>,
    val confidence: Double,
    /** "T0" | "T1" | "T2" */
    val tier: String = "T1",
)

/** What planning a request produced (spec sections 7.2, 9.2 and the Ask row of section 17). */
sealed interface PlanOptions {
    /**
     * Confidence at or above 0.5: the plan with both options. An option is null when it cannot run, with the reason
     * beside it ("every LLM step has private lineage", "no local model holds a 32k context").
     */
    data class Ready(
        val plan: AskPlan,
        val local: ModeEstimate?,
        val cloud: ModeEstimate?,
        val localBlockedReason: String? = null,
        val cloudBlockedReason: String? = null,
        /** spec section 9.2: which option the signals push toward; the policy and the user still decide */
        val suggested: RunMode = RunMode.LOCAL,
    ) : PlanOptions

    /** Confidence under 0.5 (spec section 5.3 / Confidence.LOW): the two readings to pick from; never "none of these". */
    data class Clarify(val question: String, val readings: List<String>, val confidence: Double) : PlanOptions

    /** The compiler or the owner refused (spec section 7.4, 7.6). */
    data class Refused(val refusal: Refusal) : PlanOptions

    /**
     * A question about the swarm itself ("what can you do", "what can you create an image of"), answered at once from
     * the catalog and the capability summary by `SwarmGuide`: no plan, no run, no model.
     */
    data class Answered(val answer: AskAnswer) : PlanOptions
}

/** The answer card: text, provenance per phone, badge, and the steps (cloud ones flagged) for "not accurate". */
data class AskAnswer(
    val answerId: String,
    val planHash: String,
    val text: String,
    val provenance: List<Provenance>,
    val badge: AnswerBadge,
    val steps: List<PlanStepView>,
    val mode: RunMode,
    /** the split job behind it when there is one (opens on the Jobs screen) */
    val jobId: String? = null,
    val notes: List<String> = emptyList(),
    val costInr: Double = 0.0,
    val elapsedMs: Long = 0,
    /** the slide deck a deck plan produced (spec amendment "decks"): shown as slides under the answer, with its .pptx */
    val deck: Deck? = null,
    /** the illustration a picture plan produced (spec amendment "pictures"): shown under the answer, with Open / Share */
    val picture: Picture? = null,
) {
    val cloudSteps: List<PlanStepView> get() = steps.filter { it.cloud }
}

/** The guide's answer to a question about the swarm (`PlanOptions.Answered`): no steps, badged verified because it is read from the catalog. */
fun guideAnswer(text: String, phone: String): AskAnswer = AskAnswer(
    java.util.UUID.randomUUID().toString(), "", text,
    listOf(Provenance(phone, "answered from the swarm's catalog and capabilities; no model ran")),
    AnswerBadge.VERIFIED, emptyList(), RunMode.LOCAL,
)

/** Progress of a running plan. */
sealed interface AskEvent {
    data class Status(val text: String) : AskEvent
    data class Answered(val answer: AskAnswer) : AskEvent
    data class Refused(val refusal: Refusal) : AskEvent
}

interface AskPort {
    /** Plans the request (T0/T1 under 2 s, spec section 17 flows). Never throws for a bad request: returns [PlanOptions.Refused]. */
    suspend fun plan(request: String): PlanOptions

    /** Runs a planned request in [mode]; the flow ends after one [AskEvent.Answered] or [AskEvent.Refused]. */
    fun run(plan: AskPlan, mode: RunMode): Flow<AskEvent>

    /**
     * "Not accurate" on a result (spec section 9.4, 15.1): re-runs [stepId] (or the answer's last LLM step when null)
     * through the escalation gate; the gate may refuse (private lineage, caps, no key).
     */
    fun notAccurate(answer: AskAnswer, stepId: String?): Flow<AskEvent>
}
