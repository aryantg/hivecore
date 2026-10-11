package com.hivecore.ui.demo

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
import com.hivecore.ui.ports.fakes.FakeAskPort
import com.hivecore.ui.vm.AskSuggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The scripted Ask (Demo mode): the suggestion prompts plan in about 1.5 s across "Sales" and "HR", run with live
 * status lines and answer with provenance; the commission question waits on [onNeedsApproval] (the app puts the real
 * approval screen up). Anything else falls back to [FakeAskPort]'s scripts.
 */
class DemoAskPort(
    private val timeScale: () -> Double = { 1.0 },
    /** true = approved; the app shows the real approval screen and returns its outcome */
    var onNeedsApproval: suspend () -> Boolean = { true },
) : AskPort {
    private class Script(val steps: List<PlanStepView>, val local: ModeEstimate, val cloud: ModeEstimate?, val statuses: List<String>, val answer: String,
                         val provenance: List<Provenance>, val needsApproval: Boolean = false, val cloudStep: String = "NARRATE")

    private var seq = 0
    private fun d(ms: Long): Long = (ms / timeScale().coerceAtLeast(0.1)).toLong()

    private val scripts: Map<String, Script> = AskSuggestions.DEFAULT.associate { it.request.lowercase() to it }.mapNotNull { (q, _) -> script(q)?.let { q to it } }.toMap()

    private fun script(q: String): Script? = when {
        q.startsWith("revenue per rep") -> Script(
            listOf(PlanStepView("t1", "AGG", "Sales", 900, verified = true), PlanStepView("t2", "AGG", "HR", 600, verified = true),
                PlanStepView("t3", "RATIO", "here", 20), PlanStepView("t4", "NARRATE", "here", 300)),
            ModeEstimate(RunMode.LOCAL, 1_800, 0.0, "4 regional totals from each phone; no rows", 2, "AGG on Sales and HR, RATIO and NARRATE here"),
            ModeEstimate(RunMode.CLOUD, 1_500, 0.40, "the 8 totals, for the narration only", 2, "NARRATE on openrouter/qwen-72b; the totals stay on the phones"),
            listOf("Placing 4 steps on Sales and HR…", "Sales: totalling 1,204 sales rows by region…", "HR: counting 61 employees by region…", "Dividing and writing the answer…"),
            "Revenue per rep this year: East ₹33.9 L, North ₹29.4 L, West ₹27.1 L, South ₹24.8 L. East leads by 15 %.",
            listOf(Provenance("Sales", "1,204 rows → 4 regional totals"), Provenance("HR", "61 employees → 4 head counts"), Provenance("This phone", "4 ratios, ranked")))
        q.startsWith("how did revenue change") -> Script(
            listOf(PlanStepView("t1", "AGG", "Sales", 800, verified = true), PlanStepView("t2", "GROWTH", "here", 20), PlanStepView("t3", "NARRATE", "here", 300)),
            ModeEstimate(RunMode.LOCAL, 1_300, 0.0, "12 monthly totals; no rows", 1, "AGG on Sales, GROWTH and NARRATE here"),
            ModeEstimate(RunMode.CLOUD, 1_100, 0.30, "the 12 totals, for the narration only", 1, "NARRATE on openrouter/qwen-72b"),
            listOf("Sales: totalling Q3 by month and region…", "Working out month-on-month change…"),
            "Q3 revenue: July ₹1.12 Cr, August ₹1.21 Cr (+8 %), September ₹1.30 Cr (+7 %). West grew fastest, +14 % over the quarter.",
            listOf(Provenance("Sales", "312 Q3 rows → 12 monthly totals"), Provenance("This phone", "3 growth rates")))
        q.startsWith("which region has the highest") -> Script(
            listOf(PlanStepView("t1", "AGG", "Sales", 900, verified = true), PlanStepView("t2", "AGG", "HR", 700, verified = true, privateLineage = true),
                PlanStepView("t3", "RANK", "here", 20), PlanStepView("t4", "COMPARE", "here", 20)),
            ModeEstimate(RunMode.LOCAL, 2_400, 0.0, "totals and 3 approved averages; no rows", 2, "HR approves 3 figures with one fingerprint"),
            null,
            listOf("Placing 4 steps on Sales and HR…", "Waiting for HR's fingerprint…", "HR approved 3 figures", "Ranking regions and comparing commission…"),
            "East has the highest revenue per rep (₹33.9 L). Its average commission is 7.8 %, above the company average of 6.9 %.",
            listOf(Provenance("Sales", "1,204 rows → 4 regional totals"), Provenance("HR", "3 approved figures (average commission, confidential)"),
                Provenance("This phone", "rank and comparison")), needsApproval = true)
        else -> null
    }

    override suspend fun plan(request: String): PlanOptions {
        delay(d(PLAN_MS))
        val s = scripts[request.trim().lowercase()] ?: return FakeAskPort.defaultPlan(request)
        val plan = AskPlan("demo-plan-${++seq}", "7e1d${seq}a90c44b2f81d3c6e5", request.trim(), s.steps, confidence = 0.94, tier = "T0")
        return PlanOptions.Ready(plan, s.local, s.cloud, cloudBlockedReason = if (s.cloud == null) "the commission column is private on HR's phone" else null)
    }

    override fun run(plan: AskPlan, mode: RunMode): Flow<AskEvent> = flow {
        val s = scripts[plan.request.lowercase()]
        if (s == null) { FakeAskPort.defaultRun(plan, mode).forEach { delay(d(STEP_MS)); emit(it) }; return@flow }
        for (line in s.statuses) {
            if (line.startsWith("HR approved")) continue
            emit(AskEvent.Status(line)); delay(d(STEP_MS))
            if (s.needsApproval && line.startsWith("Waiting for HR")) {
                if (!onNeedsApproval()) { emit(AskEvent.Refused(Refusal("HR declined, so nothing was read from the confidential column.", plan.planHash, "AGG (t2)"))); return@flow }
                emit(AskEvent.Status("HR approved 3 figures")); delay(d(STEP_MS))
            }
        }
        val cloud = mode == RunMode.CLOUD
        emit(AskEvent.Answered(AskAnswer(
            answerId = "ans-${plan.planId}", planHash = plan.planHash, text = s.answer, provenance = s.provenance,
            badge = if (cloud) AnswerBadge.CLOUD else AnswerBadge.VERIFIED,
            steps = plan.steps.map { if (cloud && it.op == s.cloudStep) it.copy(cloud = true, phone = "cloud") else it },
            mode = mode, notes = if (s.needsApproval) listOf("HR approved 3 figures with one fingerprint; no rows left HR's phone") else emptyList(),
            costInr = if (cloud) s.cloud?.costInr ?: 0.0 else 0.0, elapsedMs = (if (cloud) s.cloud?.ms else s.local.ms) ?: s.local.ms,
        )))
    }

    override fun notAccurate(answer: AskAnswer, stepId: String?): Flow<AskEvent> = flow {
        FakeAskPort.defaultNotAccurate(answer, stepId).forEach { delay(d(STEP_MS)); emit(it) }
    }

    private companion object {
        const val PLAN_MS = 1_500L
        const val STEP_MS = 700L
    }
}
