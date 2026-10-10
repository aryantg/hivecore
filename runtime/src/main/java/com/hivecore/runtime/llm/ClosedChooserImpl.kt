package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg
import com.hivecore.core.ClosedChooser
import com.hivecore.core.Confidence
import com.hivecore.core.Decision
import com.hivecore.core.GenParams
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.SLog
import java.security.MessageDigest
import java.util.Random

/** A [Decision] plus the vote count per original option index (what a clarification needs for the top two readings). */
data class Vote<T>(val decision: Decision<T>, val votes: List<Int>) {
    /** Options with at least one vote, most votes first, ties by original order. */
    fun ranked(): List<T> = votes.indices.filter { votes[it] > 0 }
        .sortedWith(compareByDescending<Int> { votes[it] }.thenBy { it }).map { decision.options[it] }
}

/**
 * Jev-style closed choice (swarm plan §5.3, R3). The options are listed numbered; the model must answer with exactly one
 * option number, or exactly an option's label (its full rendered text, or the key before the first ": " of it).
 * Anything else is a non-vote and is never repaired. GenieX exposes no logprobs, so confidence = agreement: [choose]
 * runs k times with deterministic permutations of the option order and maps each answer back to the original option.
 *
 * Permutations: a base shuffle seeded from sha256(question + labels); run r leads with base[r mod n] (so k <= n runs
 * each put a different option first, which is what exposes position bias) and shuffles the rest with seed + r.
 * Same question + options ⇒ same prompts ⇒ (greedy decoding) same votes.
 *
 * value = the majority option when it is unique and agreeing/k ≥ [Confidence.LOW], else null; confidence = agreeing/k.
 * A single option needs no model call (confidence 1.0, runs 0). Engine failures count as non-votes.
 * The engine's mutex is respected implicitly: every run is a plain [LlmEngine.generate].
 */
class ClosedChooserImpl(
    private val llm: LlmEngine,
    private val maxTokens: Int = MAX_TOKENS,
) : ClosedChooser {

    override suspend fun <T> choose(question: String, options: List<T>, render: (T) -> String, k: Int): Decision<T> =
        vote(question, options, render, k).decision

    suspend fun <T> vote(question: String, options: List<T>, render: (T) -> String, k: Int = 3): Vote<T> {
        val n = options.size
        if (n == 0) return Vote(Decision(null, 0.0, options, 0, 0), emptyList())
        if (n == 1) return Vote(Decision(options[0], 1.0, options, 0, 0), listOf(0))
        val runs = k.coerceAtLeast(1)
        val labels = options.map { render(it).trim() }
        val seed = seed(question, labels)
        val votes = IntArray(n)
        val raw = ArrayList<String>(runs)
        for (r in 0 until runs) {
            val perm = permutation(n, seed, r)
            val shown = perm.map { labels[it] }
            // the grammar makes non-option output impossible on llama.cpp; null on the NPU (parse + validate instead)
            val res = llm.generate(prompt(question, shown), GenParams(maxTokens = maxTokens, grammar = grammarFor(n, llm.backend.value)))
            val text = res.getOrNull()?.text ?: ""
            if (res.isFailure) SLog.w(TAG, "run $r: engine failed: ${res.exceptionOrNull()?.message}")
            raw += text
            val pos = parse(text, shown)
            if (pos == null) SLog.d(TAG, "run $r: non-vote ${text.take(40)}") else votes[perm[pos]]++
        }
        val best = votes.maxOrNull() ?: 0
        val leaders = votes.indices.filter { votes[it] == best }
        val confidence = best.toDouble() / runs
        val value = if (best > 0 && leaders.size == 1 && confidence >= Confidence.LOW) options[leaders[0]] else null
        return Vote(Decision(value, confidence, options, best, runs, raw, votes.toList()), votes.toList())
    }

    companion object {
        const val TAG = "Chooser"
        const val MAX_TOKENS = 8
        const val SYSTEM = "You choose one option for a question. Reply with the number of exactly one option and nothing else."
        private val NUMBER = Regex("^\\[?(\\d{1,3})]?\\.?$")

        fun prompt(question: String, shown: List<String>): List<ChatMsg> = listOf(
            ChatMsg("system", SYSTEM),
            ChatMsg("user", buildString {
                append("Question: ").append(question.trim()).append('\n')
                append("Options:\n")
                shown.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
                append("Reply with one number from 1 to ").append(shown.size).append('.')
            })
        )

        /**
         * Strict parse → 0-based position in [shown], or null (non-vote). Accepted: `2`, `2.`, `[2]` with 1 ≤ 2 ≤ n; or a
         * text equal (case-insensitive) to exactly one shown label or to exactly one label's key (text before ": ").
         */
        fun parse(text: String, shown: List<String>): Int? {
            val t = text.trim()
            if (t.isEmpty()) return null
            NUMBER.matchEntire(t)?.let { m ->
                val i = m.groupValues[1].toInt()
                return if (i in 1..shown.size) i - 1 else null
            }
            val full = shown.indices.filter { shown[it].equals(t, ignoreCase = true) }
            if (full.size == 1) return full[0]
            if (full.size > 1) return null
            val keyed = shown.indices.filter { key(shown[it])?.equals(t, ignoreCase = true) == true }
            return keyed.singleOrNull()
        }

        private fun key(label: String): String? = label.indexOf(": ").takeIf { it > 0 }?.let { label.substring(0, it).trim() }

        fun seed(question: String, labels: List<String>): Long {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(question.trim().toByteArray(Charsets.UTF_8))
            for (l in labels) { md.update(0); md.update(l.toByteArray(Charsets.UTF_8)) }
            val d = md.digest()
            var s = 0L
            for (i in 0 until 8) s = (s shl 8) or (d[i].toLong() and 0xff)
            return s
        }

        /** Deterministic order for run [run]: element i of the result = original index shown at position i. */
        fun permutation(n: Int, seed: Long, run: Int): List<Int> {
            val base = (0 until n).toMutableList().also { java.util.Collections.shuffle(it, Random(seed)) }
            if (run == 0) return base
            val lead = base[run % n]
            val rest = base.filter { it != lead }.toMutableList()
            java.util.Collections.shuffle(rest, Random(seed + run))
            return listOf(lead) + rest
        }

        /**
         * The grammar that would make a non-vote impossible on llama.cpp: option numbers 1..n. Null on the NPU (qairt
         * breaks with any grammar) and when the backend is unknown. Sent as [GenParams.grammar].
         */
        fun grammarFor(n: Int, backend: LlmBackend?): String? =
            if (backend == null || backend == LlmBackend.NPU || n < 1) null else Gbnf.numbers(n)
    }
}
