package com.hivecore.core.fakes

import com.hivecore.core.Answer
import com.hivecore.core.DecisionBuild
import com.hivecore.core.DecisionModel
import com.hivecore.core.DecisionRequest
import com.hivecore.core.DecisionResponse
import com.hivecore.core.DecisionUsage
import com.hivecore.core.Question
import com.hivecore.core.round4

/**
 * A scripted [DecisionModel] for JVM tests: deterministic, no model. [rules] are tried in order against the question id;
 * the first match answers. Without a match: a Noul answers [defaultNoul] (or [boundaryRule] over the state for grouped
 * boundary questions), a Score the middle level, a Choice its first option. [build] null = unavailable (fallback path).
 */
class FakeDecisionModel(var build: DecisionBuild? = DecisionBuild("fake-decider", "f".repeat(64), "cal-fake")) : DecisionModel {
    val rules = ArrayList<Pair<Regex, (Question, String) -> Answer>>()
    val requests = ArrayList<DecisionRequest>()
    var failNext: Throwable? = null
    var defaultNoul = 0.5
    /** Grouped (boundary) Noul questions: p from the state and the question, e.g. 0.95 when the next sentence starts a section. */
    var boundaryRule: ((Question.Noul, String) -> Double)? = null
    /** Simulated LLM calls per question, for budget tests. */
    var callsPerQuestion = 3

    fun noul(idPattern: String, p: Double) { rules += Regex(idPattern) to { _, _ -> Answer.Noul(round4(p)) } }
    fun score(idPattern: String, score: Double) { rules += Regex(idPattern) to { q, _ -> scoreAnswer(q as Question.Score, score) } }
    fun choice(idPattern: String, key: String) { rules += Regex(idPattern) to { q, _ -> choiceAnswer(q as Question.Choice, key) } }

    override fun build(): DecisionBuild? = build

    override suspend fun decide(request: DecisionRequest): Result<DecisionResponse> {
        requests += request
        failNext?.let { failNext = null; return Result.failure(it) }
        val answers = LinkedHashMap<String, Answer>()
        for (q in request.questions) {
            val rule = rules.firstOrNull { it.first.matches(q.id) }?.second
            answers[q.id] = rule?.invoke(q, request.state) ?: when (q) {
                is Question.Noul -> Answer.Noul(round4(if (q.group != null) boundaryRule?.invoke(q, request.state) ?: defaultNoul else defaultNoul))
                is Question.Score -> scoreAnswer(q, 0.5)
                is Question.Choice -> choiceAnswer(q, q.criteria.keys.sorted().first())
            }
        }
        // Usage as the voting model reports it: one vote (k calls) per grouped window, k calls per other question.
        val groups = request.questions.filterIsInstance<Question.Noul>().mapNotNull { it.group }.distinct().size
        val single = request.questions.count { !(it is Question.Noul && it.group != null) }
        return Result.success(DecisionResponse(build?.modelId ?: "fake", answers, DecisionUsage(request.questions.size, (groups + single) * callsPerQuestion, 0)))
    }

    companion object {
        /** A Score answer whose expected level is [score] in [0, 1]: mass on the two nearest levels. */
        fun scoreAnswer(q: Question.Score, score: Double): Answer.Score {
            val n = q.criteria.size
            val pos = score.coerceIn(0.0, 1.0) * (n - 1)
            val lo = pos.toInt().coerceIn(0, n - 1); val hi = minOf(n - 1, lo + 1)
            val t = pos - lo
            val probs = DoubleArray(n).also { it[lo] += 1 - t; it[hi] += t }.map { round4(it) }
            return Answer.Score(round4(score.coerceIn(0.0, 1.0)), probs.max(), q.criteria, probs)
        }

        fun choiceAnswer(q: Question.Choice, key: String): Answer.Choice {
            val keys = q.criteria.keys.sorted()
            val chosen = if (key in keys) key else keys.first()
            return Answer.Choice(chosen, 1.0, keys.associateWith { if (it == chosen) 1.0 else 0.0 })
        }
    }
}
