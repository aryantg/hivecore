package com.hivecore.ui.vm

import com.hivecore.ui.ports.AskAnswer
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.Refusal
import com.hivecore.ui.ports.RunMode

/** One turn of the chat Home: the user's bubble, or what the swarm says back. */
sealed interface ChatTurn {
    val key: String

    data class User(val text: String, override val key: String) : ChatTurn
    /** Working, with the live status line ("Planning…", "Placing 3 steps…"). */
    data class Typing(val status: String?, override val key: String) : ChatTurn
    data class Clarify(val clarify: PlanOptions.Clarify, override val key: String) : ChatTurn
    /** The plan card with the LOCAL / CLOUD choice; [running] is the chosen mode and its status once started. */
    data class Plan(val options: PlanOptions.Ready, val running: Pair<RunMode, String>?, override val key: String) : ChatTurn
    data class Answer(val item: AskViewModel.HistoryItem, val answer: AskAnswer, override val key: String) : ChatTurn
    data class Refused(val item: AskViewModel.HistoryItem, val refusal: Refusal, override val key: String) : ChatTurn
    data class Error(val message: String, override val key: String) : ChatTurn
}

/** Builds the conversation from the Ask state: finished exchanges oldest first, then the one in flight. Pure. */
object ChatThread {
    fun of(s: AskViewModel.UiState): List<ChatTurn> = buildList {
        for (h in s.history.asReversed()) {
            add(ChatTurn.User(h.request, "u-${h.id}"))
            when (val o = h.outcome) {
                is AskViewModel.Outcome.Answer -> add(ChatTurn.Answer(h, o.answer, "a-${h.id}"))
                is AskViewModel.Outcome.Refused -> add(ChatTurn.Refused(h, o.refusal, "r-${h.id}"))
            }
        }
        val q = s.inFlight ?: return@buildList
        add(ChatTurn.User(q, "u-live"))
        when (val p = s.phase) {
            AskViewModel.Phase.Idle -> Unit
            AskViewModel.Phase.Planning -> add(ChatTurn.Typing("Planning across your phones…", "s-live"))
            is AskViewModel.Phase.Clarifying -> add(ChatTurn.Clarify(p.clarify, "s-live"))
            is AskViewModel.Phase.Choosing -> add(ChatTurn.Plan(p.options, null, "s-live"))
            is AskViewModel.Phase.Running -> {
                val o = p.options
                if (o != null) add(ChatTurn.Plan(o, p.mode to p.status, "s-live")) else add(ChatTurn.Typing(p.status, "s-live"))
            }
            is AskViewModel.Phase.Error -> add(ChatTurn.Error(p.message, "s-live"))
        }
    }
}

/** A ready-made prompt for the chat Home: asked at once, or (JOBS) it opens Jobs on a recipe that needs files. */
data class Suggestion(val title: String, val hint: String, val request: String, val kind: Kind = Kind.ASK, val recipeId: String? = null) {
    enum class Kind { ASK, JOBS }
}

/**
 * The demo prompts. The ASK ones match the planner's patterns (patterns.json: deck_growth, per_unit,
 * growth_over_period, ratio_then_rank + flag_vs_average) over Kavira Pharmaceuticals' seeded tables; the JOBS ones open
 * Jobs on a recipe: the stockist letters (a table recipe over the Sales phone's stockists) and the photo OCR (which
 * needs files, so it cannot plan through Ask, whose recipe path sends none).
 */
object AskSuggestions {
    const val DECK_REQUEST = "Generate a pitch deck showing the increase in sales FY2024-26"

    val DEFAULT: List<Suggestion> = listOf(
        Suggestion("Pitch deck from the sales figures", "Numbers on Sales, words and a picture on the other phones, slides here", DECK_REQUEST),
        Suggestion("Sales per rep by branch", "Sales and HR phones each answer their half", "Sales per rep by branch in FY2026"),
        Suggestion("Month-on-month sales", "FY2026 by branch, from the Sales phone", "How did sales change month on month in FY2026 by branch?"),
        Suggestion("Top branch, with HR's approval", "One fingerprint on HR's phone", "Which branch has the highest sales per rep, and is its average incentive above the company average?"),
        Suggestion("Letters to 48 stockists", "One letter per stockist, written across the phones", "Write a letter to each of our 48 stockists", Suggestion.Kind.JOBS, "stockist_letters"),
        Suggestion("Read 120 invoice photos", "OCR split across every phone", "Read 120 invoice photos into a table", Suggestion.Kind.JOBS, "photo_text"),
    )
}
