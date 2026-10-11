package com.hivecore.runtime.decide

import com.hivecore.core.Answer
import com.hivecore.core.Calibration
import com.hivecore.core.ChatMsg
import com.hivecore.core.ClosedChooser
import com.hivecore.core.DecisionBuild
import com.hivecore.core.DecisionModel
import com.hivecore.core.DecisionRequest
import com.hivecore.core.DecisionResponse
import com.hivecore.core.DecisionUsage
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.OptionScorer
import com.hivecore.core.Question
import com.hivecore.core.SLog
import com.hivecore.core.round4
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.models.ModelPoolImpl
import kotlin.math.exp

/**
 * Phase 1 [DecisionModel] over the local instruct LLM (docs/plans/decision-chunking.md §2): the GenieX SDK exposes no
 * logits, so every question is a closed choice answered with exactly one option number, asked `k` times with
 * deterministic permutations of the option order through [chooser] (GBNF-constrained on llama.cpp, validate-and-reject
 * on qairt, never repaired). The vote fraction per option goes through the build's [Calibration] to a probability.
 * With an [OptionScorer] (a runtime that exposes logits) the votes are replaced by `softmax(score / temperature)`.
 *
 * Determinism: greedy decoding plus permutations seeded from the question and labels ⇒ the same request gives the same
 * votes on one device; the argmax is stable across phones.
 *
 * Batch rule: Noul questions that share a `group` over one state are answered by ONE choice per group ("which
 * boundary is the strongest topic change?", options = the candidates plus `none`), each Noul receiving its calibrated
 * vote share. An approximation of per-statement Noul that keeps a boundary window at `k` LLM calls.
 */
class VotingDecisionModel(
    private val chooser: ClosedChooser,
    private val buildOf: () -> DecisionBuild?,
    private val calibrationOf: () -> Calibration = { Calibration.IDENTITY },
    private val scorer: OptionScorer? = null,
    private val k: Int = RUNS,
    private val temperature: Double = 1.0,
) : DecisionModel {

    override fun build(): DecisionBuild? = buildOf()

    override suspend fun decide(request: DecisionRequest): Result<DecisionResponse> = runCatching {
        val build = buildOf() ?: throw IllegalStateException("decision model unavailable: no pinned LLM build loaded")
        val cal = calibrationOf()
        val t0 = System.nanoTime()
        var calls = 0
        val answers = LinkedHashMap<String, Answer>()
        val grouped = request.questions.filterIsInstance<Question.Noul>().filter { it.group != null }.groupBy { it.group!! }
        for ((_, qs) in grouped) {
            val labels = qs.map { it.id } + NONE
            val (probs, used) = probabilities(groupQuestion(qs, request.state), labels, cal)
            calls += used
            qs.forEachIndexed { i, q -> answers[q.id] = Answer.Noul(probs[i]) }
        }
        for (q in request.questions) {
            if (q is Question.Noul && q.group != null) continue
            val (answer, used) = when (q) {
                is Question.Noul -> {
                    val (p, u) = probabilities(stateful(q.instructions, request.state), listOf(TRUE, FALSE), cal)
                    Answer.Noul(p[0]) to u
                }
                is Question.Choice -> {
                    val keys = q.criteria.keys.sorted()
                    val (p, u) = probabilities(stateful(q.instructions, request.state), keys.map { "$it: ${q.criteria.getValue(it)}" }, cal)
                    val best = p.indices.maxByOrNull { p[it] } ?: 0        // first maximum: ties by option order
                    Answer.Choice(keys[best], p[best], keys.indices.associate { keys[it] to p[it] }) to u
                }
                is Question.Score -> {
                    val (p, u) = probabilities(stateful(q.instructions, request.state), q.criteria, cal)
                    val n = q.criteria.size
                    val expected = if (n <= 1) 0.0 else p.indices.sumOf { it * p[it] } / (n - 1)
                    Answer.Score(round4(expected), p.max(), q.criteria, p) to u
                }
            }
            answers[q.id] = answer
            calls += used
        }
        DecisionResponse(build.modelId, answers, DecisionUsage(request.questions.size, calls, (System.nanoTime() - t0) / 1_000_000))
    }.onFailure { SLog.w(TAG, "decide failed: ${it.message}") }

    /** Calibrated, renormalised, 4-dp probabilities per label, and the LLM calls used. */
    private suspend fun probabilities(question: String, labels: List<String>, cal: Calibration): Pair<List<Double>, Int> {
        val n = labels.size
        if (n == 1) return listOf(1.0) to 0
        val raw: DoubleArray
        val used: Int
        val s = scorer
        if (s != null) {
            val scores = s.scoreOptions(listOf(ChatMsg("system", SYSTEM), ChatMsg("user", question)), labels).getOrThrow()
            require(scores.size == n) { "scorer returned ${scores.size} scores for $n options" }
            val m = scores.max()
            val e = scores.map { exp((it - m) / temperature) }
            val z = e.sum()
            raw = DoubleArray(n) { e[it] / z }
            used = 1
        } else {
            val d = chooser.choose(question, labels, { it }, k)
            val votes = if (d.votes.size == n) d.votes else IntArray(n).also { v -> d.value?.let { v[labels.indexOf(it).coerceAtLeast(0)] = d.agreeing } }.toList()
            val runs = maxOf(1, d.runs)
            // A non-vote (rejected output, engine failure) is an abstention: its mass is spread over the options rather
            // than handed to the voted ones, so "2 of 3 runs said true" is 0.83, not 1.0.
            val abstained = (runs - votes.sum()).coerceAtLeast(0).toDouble() / n
            raw = DoubleArray(n) { (votes[it] + abstained) / runs }
            used = d.runs
        }
        val calibrated = DoubleArray(n) { cal.apply(raw[it]) }
        val sum = calibrated.sum()
        val out = if (sum <= 0.0) List(n) { round4(1.0 / n) } else calibrated.map { round4(it / sum) }
        return out to used
    }

    private fun stateful(instructions: String, state: String): String = instructions.trim() + "\n\nState:\n" + state.trim()

    private fun groupQuestion(qs: List<Question.Noul>, state: String): String =
        "Which of these statements is the strongest true one? Answer $NONE if none is true.\n" +
            qs.joinToString("\n") { "${it.id}: ${it.instructions.trim()}" } + "\n\nState:\n" + state.trim()

    companion object {
        const val TAG = "Decide"
        const val RUNS = 3
        const val TRUE = "true"
        const val FALSE = "false"
        const val NONE = "none"
        const val SYSTEM = "You answer a closed question about the state with exactly one option number and nothing else."

        /**
         * The Phase 1 build identity: the catalog entry of the LLM loaded on [llm] (qairt → `qwen3-4b-npu`, else the
         * GGUF build), null when nothing is loaded or the entry's sha256 is unpinned.
         */
        fun buildOf(llm: LlmEngine, catalog: ModelCatalog): DecisionBuild? {
            val backend = llm.backend.value ?: return null
            val id = if (backend == LlmBackend.NPU) ModelPoolImpl.LLM_QAIRT_ID else ModelPoolImpl.LLM_GGUF_ID
            val e = catalog.entry(id) ?: return null
            if (e.sha256 == ModelCatalog.UNPINNED || !ModelCatalog.isHex64(e.sha256)) return null
            return DecisionBuild(id, e.sha256, e.calibration?.version ?: Calibration.NONE)
        }

        fun calibrationOf(llm: LlmEngine, catalog: ModelCatalog): Calibration {
            val backend = llm.backend.value ?: return Calibration.IDENTITY
            val id = if (backend == LlmBackend.NPU) ModelPoolImpl.LLM_QAIRT_ID else ModelPoolImpl.LLM_GGUF_ID
            return catalog.entry(id)?.calibration ?: Calibration.IDENTITY
        }

        /** The phone's model over the loaded LLM and the catalog. */
        fun over(llm: LlmEngine, chooser: ClosedChooser, catalog: () -> ModelCatalog): VotingDecisionModel =
            VotingDecisionModel(chooser, { buildOf(llm, catalog()) }, { calibrationOf(llm, catalog()) })
    }
}
