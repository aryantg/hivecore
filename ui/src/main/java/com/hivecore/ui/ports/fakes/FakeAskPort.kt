package com.hivecore.ui.ports.fakes

import com.hivecore.core.Provenance
import com.hivecore.ui.ports.AnswerBadge
import com.hivecore.ui.ports.AskAnswer
import com.hivecore.ui.ports.AskEvent
import com.hivecore.ui.ports.AskPlan
import com.hivecore.ui.ports.AskPort
import com.hivecore.ui.ports.ModeEstimate
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.PlanStepView
import com.hivecore.ui.ports.Refusal
import com.hivecore.ui.ports.RunMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted [AskPort] for the JVM tests and the app before the integration wave. Defaults: a request mentioning a
 * salary is refused in plain words; a request with " or " in it and a question mark asks for clarification; anything
 * else plans three steps with both options. Tests override [planFor] / [runFor] / [notAccurateFor].
 */
class FakeAskPort(var delayMs: Long = 0) : AskPort {
    val planned = mutableListOf<String>()
    val runs = mutableListOf<Pair<String, RunMode>>()
    val notAccurateCalls = mutableListOf<Pair<String, String?>>()

    var planFor: (String) -> PlanOptions = { defaultPlan(it) }
    var runFor: (AskPlan, RunMode) -> List<AskEvent> = { plan, mode -> defaultRun(plan, mode) }
    var notAccurateFor: (AskAnswer, String?) -> List<AskEvent> = { a, step -> defaultNotAccurate(a, step) }

    override suspend fun plan(request: String): PlanOptions {
        planned += request
        if (delayMs > 0) delay(delayMs)
        return planFor(request)
    }

    override fun run(plan: AskPlan, mode: RunMode): Flow<AskEvent> = flow {
        runs += plan.planId to mode
        for (e in runFor(plan, mode)) { if (delayMs > 0) delay(delayMs); emit(e) }
    }

    override fun notAccurate(answer: AskAnswer, stepId: String?): Flow<AskEvent> = flow {
        notAccurateCalls += answer.answerId to stepId
        for (e in notAccurateFor(answer, stepId)) { if (delayMs > 0) delay(delayMs); emit(e) }
    }

    companion object {
        private var seq = 0

        fun samplePlan(request: String, confidence: Double = 0.9): AskPlan = AskPlan(
            planId = "plan-${++seq}", planHash = "9f2c41ab77e0d3c5a1b2c3d4", request = request, confidence = confidence,
            steps = listOf(
                PlanStepView("t1", "OCR", "Accounts", 1_200, verified = true),
                PlanStepView("t2", "EXTRACT", "Sales", 2_400, verified = true),
                PlanStepView("t3", "RENDER", "here", 3, verified = null),
            ),
        )

        fun defaultPlan(request: String): PlanOptions {
            val q = request.trim()
            return when {
                q.contains("salary", ignoreCase = true) -> PlanOptions.Refused(Refusal(
                    "HR's phone does not allow questions about individual salaries, so nothing ran.", "9f2c41ab77e0d3c5", "AGG (t1)"))
                q.endsWith("?") && q.contains(" or ") -> PlanOptions.Clarify("This can be read two ways.",
                    q.removeSuffix("?").split(" or ").map { it.trim() }.filter { it.isNotEmpty() }.take(2), 0.4)
                else -> {
                    val plan = samplePlan(q)
                    val privateOnly = q.contains("private", ignoreCase = true)
                    PlanOptions.Ready(
                        plan,
                        local = ModeEstimate(RunMode.LOCAL, 4_100, 0.0, "nothing leaves the phones", 3, "OCR on Accounts, EXTRACT on Sales, RENDER here; qwen3-4b loads on Sales"),
                        cloud = if (privateOnly) null else ModeEstimate(RunMode.CLOUD, 2_600, 1.20, "prompt text and 12 KB of released rows for EXTRACT", 1, "EXTRACT on openrouter/qwen-72b; OCR and RENDER stay local"),
                        cloudBlockedReason = if (privateOnly) "every LLM step has private lineage" else null,
                    )
                }
            }
        }

        fun defaultRun(plan: AskPlan, mode: RunMode): List<AskEvent> = listOf(
            AskEvent.Status("Placing ${plan.steps.size} steps…"),
            AskEvent.Status(if (mode == RunMode.LOCAL) "Running on 3 phones" else "Running EXTRACT on the cloud"),
            AskEvent.Answered(AskAnswer(
                answerId = "ans-${plan.planId}", planHash = plan.planHash,
                text = "120 invoices read; 118 rows extracted, 2 sent back as blurry.",
                provenance = listOf(Provenance("Accounts", "120 photos → 120 OCR pages"), Provenance("Sales", "120 pages → 118 rows")),
                badge = if (mode == RunMode.CLOUD) AnswerBadge.CLOUD else AnswerBadge.VERIFIED,
                steps = plan.steps.map { if (mode == RunMode.CLOUD && it.op == "EXTRACT") it.copy(cloud = true, phone = "cloud") else it },
                mode = mode, jobId = "job-${plan.planId}", costInr = if (mode == RunMode.CLOUD) 1.20 else 0.0, elapsedMs = 3_900,
            )),
        )

        fun defaultNotAccurate(a: AskAnswer, stepId: String?): List<AskEvent> {
            val step = a.steps.firstOrNull { it.id == stepId } ?: a.steps.lastOrNull { it.op != "RENDER" } ?: a.steps.last()
            return listOf(
                AskEvent.Status("Re-running ${step.op} through the gate…"),
                AskEvent.Answered(a.copy(answerId = a.answerId + "-cloud", badge = AnswerBadge.CLOUD,
                    text = a.text + " (re-run on the cloud)", notes = a.notes + "${step.op} re-run on the cloud after you marked the result as not accurate",
                    steps = a.steps.map { if (it.id == step.id) it.copy(cloud = true, phone = "cloud") else it }, costInr = a.costInr + 0.8)),
            )
        }
    }
}
