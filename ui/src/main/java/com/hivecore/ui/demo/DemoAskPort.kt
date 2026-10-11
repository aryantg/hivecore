package com.hivecore.ui.demo

import com.hivecore.core.Provenance
import com.hivecore.engine.deck.Deck
import com.hivecore.engine.guide.PhoneFacts
import com.hivecore.engine.guide.SwarmFacts
import com.hivecore.engine.guide.SwarmGuide
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
import com.hivecore.ui.ports.guideAnswer
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
    /** writes the scripted deck's .pptx (and draws its illustration) on the device; null = the deck structure only */
    var materialiseDeck: (suspend (Deck) -> Deck)? = null,
) : AskPort {
    private class Script(val steps: List<PlanStepView>, val local: ModeEstimate, val cloud: ModeEstimate?, val statuses: List<String>, val answer: String,
                         val provenance: List<Provenance>, val needsApproval: Boolean = false, val cloudStep: String = "NARRATE", val deck: Boolean = false)

    private var seq = 0
    private fun d(ms: Long): Long = (ms / timeScale().coerceAtLeast(0.1)).toLong()

    private val scripts: Map<String, Script> = AskSuggestions.DEFAULT.associate { it.request.lowercase() to it }.mapNotNull { (q, _) -> script(q)?.let { q to it } }.toMap()

    private fun script(q: String): Script? = when {
        q.startsWith("sales per rep") -> Script(
            listOf(PlanStepView("t1", "AGG", "Sales", 900, verified = true), PlanStepView("t2", "AGG", "HR", 600, verified = true),
                PlanStepView("t3", "RATIO", "here", 20), PlanStepView("t4", "NARRATE", "here", 300)),
            ModeEstimate(RunMode.LOCAL, 1_800, 0.0, "3 branch totals from each phone; no rows", 2, "AGG on Sales and HR, RATIO and NARRATE here"),
            ModeEstimate(RunMode.CLOUD, 1_500, 0.40, "the 6 totals, for the narration only", 2, "NARRATE on openrouter/qwen-72b; the totals stay on the phones"),
            listOf("Placing 4 steps on Sales and HR…", "Sales: totalling 1,755 FY2026 sales rows by branch…", "HR: counting 110 medical reps by branch…", "Dividing and writing the answer…"),
            "Sales per medical rep in FY2026: Bengaluru ₹63.7 L (48 reps), Chennai ₹57.6 L (38), Kochi ₹29.4 L (24). Kochi is still ramping up after opening in July 2023.",
            listOf(Provenance("Sales", "1,755 rows → 3 branch totals"), Provenance("HR", "280 staff → 3 rep counts"), Provenance("This phone", "3 ratios, ranked")))
        q.startsWith("how did sales change") -> Script(
            listOf(PlanStepView("t1", "AGG", "Sales", 800, verified = true), PlanStepView("t2", "GROWTH", "here", 20), PlanStepView("t3", "NARRATE", "here", 300)),
            ModeEstimate(RunMode.LOCAL, 1_300, 0.0, "36 monthly totals; no rows", 1, "AGG on Sales, GROWTH and NARRATE here"),
            ModeEstimate(RunMode.CLOUD, 1_100, 0.30, "the 36 totals, for the narration only", 1, "NARRATE on openrouter/qwen-72b"),
            listOf("Sales: totalling FY2026 by month and branch…", "Working out month-on-month change…"),
            "FY2026, April to March: Bengaluru ₹2.0 Cr to ₹2.9 Cr (+42 %), Kochi ₹50 L to ₹63 L (+26 %). Chennai swings with the Tamil Nadu tender lots, delivered every other month, and ends at ₹1.5 Cr.",
            listOf(Provenance("Sales", "1,755 FY2026 rows → 36 monthly totals"), Provenance("This phone", "33 growth rates")))
        q.startsWith("generate a pitch deck") -> Script(
            listOf(PlanStepView("a", "AGG", "Sales", 900, verified = true), PlanStepView("w", "LLM_MAP", "Accounts", 2_400),
                PlanStepView("i", "GEN_IMAGE", "HR", 6_500), PlanStepView("d", "RENDER", "here", 120)),
            ModeEstimate(RunMode.LOCAL, 9_200, 0.0, "36 monthly totals; no rows", 3, "AGG on Sales, slide text on Accounts, the picture on HR (SD 1.5), the deck here"),
            ModeEstimate(RunMode.CLOUD, 7_100, 0.90, "the 36 totals, for the slide text only", 3, "LLM_MAP on openrouter/qwen-72b; the figures and the picture stay on the phones"),
            listOf("Placing 4 steps on Sales, Accounts and HR…", "Sales: totalling 4,770 sales rows by month…", "Accounts: writing the slide text (qwen3-4b)…",
                "HR: drawing the illustration (SD 1.5 on the NPU)…", "Assembling 5 slides and the .pptx…"),
            "",
            listOf(Provenance("Sales", "4,770 rows → 36 monthly totals"), Provenance("Accounts", "slide text (LLM_MAP)"), Provenance("HR", "1 illustration (GEN_IMAGE)"),
                Provenance("This phone", "5 slides assembled, .pptx written")), cloudStep = "LLM_MAP", deck = true)
        q.startsWith("which branch has the highest") -> Script(
            listOf(PlanStepView("t1", "AGG", "Sales", 900, verified = true), PlanStepView("t2", "AGG", "HR", 700, verified = true, privateLineage = true),
                PlanStepView("t3", "RANK", "here", 20), PlanStepView("t4", "COMPARE", "here", 20)),
            ModeEstimate(RunMode.LOCAL, 2_400, 0.0, "totals and 3 approved averages; no rows", 2, "HR approves 3 figures with one fingerprint"),
            null,
            listOf("Placing 4 steps on Sales and HR…", "Waiting for HR's fingerprint…", "HR approved 3 figures", "Ranking branches and comparing incentives…"),
            "Bengaluru has the highest sales per rep (₹63.7 L). Its reps' average FY2026 incentive is ₹55,000, above the company average of ₹52,000.",
            listOf(Provenance("Sales", "1,755 rows → 3 branch totals"), Provenance("HR", "3 approved figures (average incentive, confidential)"),
                Provenance("This phone", "rank and comparison")), needsApproval = true)
        else -> null
    }

    override suspend fun plan(request: String): PlanOptions {
        // a question about the app itself is answered from the scripted swarm's facts, as the real port answers from the catalog
        SwarmGuide.answer(request, FACTS)?.let { return PlanOptions.Answered(guideAnswer(it, "This phone")) }
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
        val deck = if (s.deck) DemoDeck.build().let { d -> materialiseDeck?.invoke(d) ?: d } else null
        val text = if (deck != null) "${deck.summary} Deck: ${deck.slides.size} slides, \"${deck.title}\"" + (if (deck.file != null) ", saved as .pptx." else ".") else s.answer
        emit(AskEvent.Answered(AskAnswer(
            answerId = "ans-${plan.planId}", planHash = plan.planHash, text = text, provenance = s.provenance,
            badge = if (cloud) AnswerBadge.CLOUD else if (deck != null) AnswerBadge.UNVERIFIED else AnswerBadge.VERIFIED, deck = deck,
            steps = plan.steps.map { if (cloud && it.op == s.cloudStep) it.copy(cloud = true, phone = "cloud") else it },
            mode = mode, notes = if (s.needsApproval) listOf("HR approved 3 figures with one fingerprint; no rows left HR's phone") else emptyList(),
            costInr = if (cloud) s.cloud?.costInr ?: 0.0 else 0.0, elapsedMs = (if (cloud) s.cloud?.ms else s.local.ms) ?: s.local.ms,
        )))
    }

    override fun notAccurate(answer: AskAnswer, stepId: String?): Flow<AskEvent> = flow {
        FakeAskPort.defaultNotAccurate(answer, stepId).forEach { delay(d(STEP_MS)); emit(it) }
    }

    companion object {
        private const val PLAN_MS = 1_500L
        private const val STEP_MS = 700L

        /** The scripted swarm as the guide describes it: the Kavira seed's three phones, an image model on HR. */
        val FACTS = SwarmFacts(
            phones = listOf(
                PhoneFacts("Sales", listOf("sales_monthly", "products", "branch_master", "sales_force", "events", "stockists"), listOf("kavira_company_snapshot.md")),
                PhoneFacts("HR", listOf("staff", "compensation", "salary_history", "devices"), listOf("onboarding_checklist.docx", "kavira_hr_access_policy.md")),
                PhoneFacts("Accounts", listOf("financials", "receivables", "budget_vs_actual"), self = true),
            ),
            canDraw = true,
            patterns = listOf("per_unit", "compare_two", "growth_over_period", "top_k_by", "flag_vs_average", "reconcile_counts", "ratio_then_rank",
                "multi_doc_extract", "deck_growth", "make_image").associateWith { it },
            recipes = AskSuggestions.DEFAULT.filter { it.kind == com.hivecore.ui.vm.Suggestion.Kind.JOBS }.map { it.title },
            examples = AskSuggestions.DEFAULT.filter { it.kind == com.hivecore.ui.vm.Suggestion.Kind.ASK }.map { it.request },
        )
    }
}
