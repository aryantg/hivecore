package com.hivecore.engine.text

import com.hivecore.core.ChatMsg
import com.hivecore.core.ClosedChooser
import com.hivecore.core.GenParams
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.SLog
import com.hivecore.engine.text.Thresholds
import com.hivecore.engine.text.Tokenizer
import com.hivecore.runtime.llm.Gbnf
import kotlin.math.ln

/**
 * The result of an extractive pick. [picks] are in document order (the order of the candidate list given), never in
 * the model's order. [rejected] non-null = the model's output was outside the closed id list (R3: never repaired) or
 * had no confident choice; the caller refuses (R6). [method]: "llm" (ids list), "chooser" (single pick by agreement),
 * "centrality" (summary fallback), "bm25" (question fallback).
 */
data class Extraction(
    val method: String,
    val picks: List<Sentence>,
    val candidates: Int,
    val rejected: String? = null,
    val raw: String? = null,
    val confidence: Double? = null,
) {
    val ok: Boolean get() = rejected == null
    val ids: List<String> get() = picks.map { it.id }
    /** "doc · unit" of each pick, distinct, in pick order. */
    val citations: List<String> get() = picks.map { "${it.doc} · ${it.unit}" }.distinct()
}

/**
 * EXTRACT / SUMMARIZE extractive mode (swarm plan §4, §4A.3). The candidates are listed numbered 1..n; the LLM may only
 * answer with a comma-separated list of those numbers (or `0` = none, for a question). On llama.cpp a GBNF grammar
 * makes anything else impossible; on the NPU (no grammar) the reply is parsed strictly and anything else is rejected.
 * Without an engine (or when generation fails) a deterministic ranking is used: BM25 centrality for a summary, BM25
 * relevance for a question. Greedy decoding + fixed prompt bytes ⇒ same picks on every phone.
 */
class Extractive(
    private val llm: LlmEngine?,
    private val chooser: ClosedChooser? = null,
    private val maxCandidates: Int = MAX_CANDIDATES,
) {
    /** Pick up to [k] sentences that summarise [sentences]. */
    suspend fun summarize(sentences: List<Sentence>, k: Int = 5): Extraction {
        require(k >= 1) { "k must be ≥ 1" }
        if (sentences.isEmpty()) return Extraction("centrality", emptyList(), 0)
        val cands = preselect(sentences, centrality(sentences))
        if (cands.size <= k) return Extraction("all", cands, cands.size)
        val task = "Choose the $k sentences that best summarise the text: the decisions, actions, figures and conclusions. Skip greetings and filler."
        return pickIds(task, cands, k, allowNone = false) { fallbackTop(cands, centrality(cands), k, "centrality") }
    }

    /** Pick up to [maxPick] sentences that answer [question]; none is a valid answer. */
    suspend fun extract(question: String, sentences: List<Sentence>, maxPick: Int = 3): Extraction {
        require(maxPick >= 1) { "maxPick must be ≥ 1" }
        require(question.isNotBlank()) { "EXTRACT needs a question" }
        if (sentences.isEmpty()) return Extraction("bm25", emptyList(), 0)
        val rel = relevance(question, sentences)
        val cands = preselect(sentences, DoubleArray(sentences.size) { rel[it].first })
        if (maxPick == 1 && chooser != null) return chooseOne(question, cands)
        val task = "Choose the sentences that answer this question: ${question.trim()}"
        return pickIds(task, cands, maxPick, allowNone = true) {
            val r = relevance(question, cands)
            val q = Tokenizer.tokens(question).distinct().size
            val need = Thresholds.requiredMatches(q)
            val order = cands.indices.filter { r[it].second >= need && r[it].first > 0.0 }
                .sortedWith(compareByDescending<Int> { r[it].first }.thenBy { it })
            Extraction("bm25", pickDistinct(cands, order, maxPick), cands.size)
        }
    }

    // --------------------------------------------------------------------------------------------------------

    /** At most [maxCandidates], the best by [score] (ties by position), returned in document order. */
    private fun preselect(sentences: List<Sentence>, score: DoubleArray): List<Sentence> {
        if (sentences.size <= maxCandidates) return sentences
        return sentences.indices.sortedWith(compareByDescending<Int> { score[it] }.thenBy { it })
            .take(maxCandidates).sorted().map { sentences[it] }
    }

    private suspend fun pickIds(task: String, cands: List<Sentence>, maxPick: Int, allowNone: Boolean, fallback: () -> Extraction): Extraction {
        val engine = llm ?: return fallback()
        val backend = engine.backend.value
        val params = GenParams(maxTokens = 5 * maxPick + 8, grammar = grammar(cands.size, maxPick, allowNone, backend))
        val res = engine.generate(prompt(task, cands, maxPick, allowNone), params)
        val text = res.getOrElse {
            SLog.w(TAG, "engine failed, deterministic fallback: ${it.message}")
            return fallback()
        }.text
        return when (val p = parse(text, cands.size, maxPick, allowNone)) {
            is Parsed.Ok -> Extraction("llm", p.positions.sorted().map { cands[it] }, cands.size, raw = text)
            is Parsed.Bad -> Extraction("llm", emptyList(), cands.size, rejected = p.reason, raw = text)
        }
    }

    private suspend fun chooseOne(question: String, cands: List<Sentence>): Extraction {
        val none = cands.size
        val d = chooser!!.choose(question.trim(), (0..none).toList(), { i -> if (i == none) "None of these sentences answers it" else cands[i].text })
        val v = d.value ?: return Extraction("chooser", emptyList(), cands.size, rejected = "no confident choice", confidence = d.confidence)
        return Extraction("chooser", if (v == none) emptyList() else listOf(cands[v]), cands.size, confidence = d.confidence)
    }

    private fun fallbackTop(cands: List<Sentence>, score: DoubleArray, k: Int, method: String): Extraction {
        val order = cands.indices.sortedWith(compareByDescending<Int> { score[it] }.thenBy { it })
        return Extraction(method, pickDistinct(cands, order, k), cands.size)
    }

    /** First [k] of [order] skipping near-duplicates (term-set Jaccard ≥ 0.8 with one already taken), in document order. */
    private fun pickDistinct(cands: List<Sentence>, order: List<Int>, k: Int): List<Sentence> {
        val taken = ArrayList<Int>()
        val sets = HashMap<Int, Set<String>>()
        fun terms(i: Int) = sets.getOrPut(i) { Tokenizer.tokens(cands[i].text).toSet() }
        for (i in order) {
            if (taken.size >= k) break
            val t = terms(i)
            if (taken.any { j -> jaccard(t, terms(j)) >= 0.8 }) continue
            taken += i
        }
        return taken.sorted().map { cands[it] }
    }

    sealed interface Parsed {
        data class Ok(val positions: List<Int>) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    companion object {
        const val TAG = "Extractive"
        const val MAX_CANDIDATES = 40
        const val MAX_SHOWN_CHARS = 400
        private val LIST = Regex("^\\[?\\d{1,4}]?(\\s*,\\s*\\[?\\d{1,4}]?)*\\.?$")

        fun prompt(task: String, cands: List<Sentence>, maxPick: Int, allowNone: Boolean): List<ChatMsg> = listOf(
            ChatMsg("system", "You select sentences from a numbered list. Reply with the numbers of the chosen sentences, separated by commas, and nothing else." +
                if (allowNone) " If no sentence fits, reply 0." else ""),
            ChatMsg("user", buildString {
                append("Task: ").append(task).append('\n')
                append("Sentences:\n")
                cands.forEachIndexed { i, s -> append(i + 1).append(". ").append(shown(s.text)).append('\n') }
                append("Reply with at most ").append(maxPick).append(if (maxPick == 1) " number" else " numbers")
                append(" from 1 to ").append(cands.size)
                if (allowNone) append(", or 0")
                append('.')
            }),
        )

        private fun shown(t: String): String = if (t.length <= MAX_SHOWN_CHARS) t else t.take(MAX_SHOWN_CHARS - 1) + "…"

        /**
         * Strict parse of an ids list → 0-based positions. Accepted: `3`, `3, 7`, `[3], [7]`, optional final period;
         * `0` alone when [allowNone]. Rejected: anything else, a number out of 1..n, a duplicate, more than [maxPick].
         */
        fun parse(text: String, n: Int, maxPick: Int, allowNone: Boolean): Parsed {
            val t = text.trim()
            if (t.isEmpty()) return Parsed.Bad("empty reply")
            if (!LIST.matches(t)) return Parsed.Bad("not an ids list: ${t.take(60)}")
            val nums = Regex("\\d+").findAll(t).map { it.value.toInt() }.toList()
            if (nums == listOf(0)) return if (allowNone) Parsed.Ok(emptyList()) else Parsed.Bad("0 is not an option")
            if (nums.any { it < 1 || it > n }) return Parsed.Bad("id out of range 1..$n: $nums")
            if (nums.toSet().size != nums.size) return Parsed.Bad("duplicate id: $nums")
            if (nums.size > maxPick) return Parsed.Bad("${nums.size} ids, at most $maxPick allowed")
            return Parsed.Ok(nums.map { it - 1 })
        }

        /** GBNF for 1..[maxPick] distinct-unchecked ids from 1..[n] (or `0`); null on the NPU or an unknown backend. */
        fun grammar(n: Int, maxPick: Int, allowNone: Boolean, backend: LlmBackend?): String? {
            if (backend == null || backend == LlmBackend.NPU || n < 1) return null
            var tail = ""
            repeat(maxPick.coerceAtMost(n) - 1) { tail = if (tail.isEmpty()) "(sep id)?" else "(sep id $tail)?" }
            val list = if (tail.isEmpty()) "id" else "id $tail"
            return buildString {
                append("root ::= ").append(if (allowNone) "\"0\" | $list" else list).append('\n')
                append("id ::= ").append((1..n).joinToString(" | ") { Gbnf.literal(it.toString()) }).append('\n')
                append("sep ::= \",\" \" \"?")
            }
        }

        /** Mean BM25 of each sentence (as a query) against every other sentence. Single sentence → 0. */
        fun centrality(sentences: List<Sentence>): DoubleArray {
            val n = sentences.size
            if (n < 2) return DoubleArray(n)
            val idx = Bm25(sentences.map { it.text })
            return DoubleArray(n) { i ->
                val q = idx.terms[i].keys.sorted()   // fixed summation order: same doubles on every runtime
                var sum = 0.0
                for (j in 0 until n) if (j != i) sum += idx.score(q, j).first
                sum / (n - 1)
            }
        }

        /** BM25 of [question] against each sentence: (score, matched distinct terms). */
        fun relevance(question: String, sentences: List<Sentence>): List<Pair<Double, Int>> {
            val idx = Bm25(sentences.map { it.text })
            val q = Tokenizer.tokens(question).distinct()
            return sentences.indices.map { idx.score(q, it) }
        }

        /** Indices of [sentences] ordered by centrality, ties by position. */
        fun rankByCentrality(sentences: List<Sentence>): List<Int> {
            val c = centrality(sentences)
            return sentences.indices.sortedWith(compareByDescending<Int> { c[it] }.thenBy { it })
        }

        private fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val inter = a.count { it in b }
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }

    /** BM25 over short texts using the docs [Tokenizer] and [Thresholds] k1/b. */
    private class Bm25(texts: List<String>) {
        val terms: List<Map<String, Int>>
        private val len: IntArray
        private val df = HashMap<String, Int>()
        private val avg: Double
        private val n = texts.size.toDouble()

        init {
            terms = texts.map { t -> HashMap<String, Int>().also { m -> Tokenizer.tokens(t).forEach { m[it] = (m[it] ?: 0) + 1 } } }
            len = IntArray(texts.size) { i -> terms[i].values.sum() }
            for (m in terms) for (t in m.keys) df[t] = (df[t] ?: 0) + 1
            avg = if (texts.isEmpty()) 0.0 else len.sum().toDouble() / texts.size
        }

        fun score(query: Collection<String>, j: Int): Pair<Double, Int> {
            var s = 0.0
            var matched = 0
            val norm = if (avg > 0) 1.0 - Thresholds.B + Thresholds.B * (len[j] / avg) else 1.0
            for (t in query) {
                val tf = terms[j][t] ?: continue
                matched++
                val d = df[t] ?: 0
                val idf = ln((n - d + 0.5) / (d + 0.5) + 1.0)
                s += idf * (tf * (Thresholds.K1 + 1.0)) / (tf + Thresholds.K1 * norm)
            }
            return s to matched
        }
    }
}
