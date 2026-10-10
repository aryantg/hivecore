package com.hivecore.engine.decide

import com.hivecore.core.Answer
import com.hivecore.core.DecisionModel
import com.hivecore.core.DecisionRequest
import com.hivecore.core.Question
import com.hivecore.core.fakes.FakeDecisionModel
import com.hivecore.engine.split.SplitConstants
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The evaluation harness (docs/plans/decision-chunking.md §9): the held-out boundary and verdict sets with gold labels,
 * scored by accuracy, Brier score and ECE for the current heuristics, for a lexical baseline on the JVM, and for any
 * answers file (`-Ddecide.answers=<file>`: the phone's `VotingDecisionModel` run or a Clef-flash replay by
 * tools/decide/replay_clef.py). The report prints to the test output; the assertions pin the gold sets and the
 * heuristic figures so a regenerated set or a changed cutter is noticed.
 */
class DecisionEvalTest {
    private val json = Json { ignoreUnknownKeys = true }

    class Case(val id: String, val request: DecisionRequest, val gold: Map<String, Boolean>, val schemaValid: Boolean)

    private fun load(name: String): List<Case> {
        val text = javaClass.getResourceAsStream("/decide/$name")!!.bufferedReader().readText()
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val o = json.parseToJsonElement(line).jsonObject
            val req = o["request"]!!.jsonObject
            val questions = req["questions"]!!.let { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Question.serializer()), it) }
            Case(o["id"]!!.jsonPrimitive.content, DecisionRequest(req["model"]!!.jsonPrimitive.content, req["state"]!!.jsonPrimitive.content, questions),
                o["gold"]!!.jsonObject.mapValues { it.value.jsonPrimitive.booleanOrNull ?: false }, o["schemaValid"]?.jsonPrimitive?.booleanOrNull ?: true)
        }.toList()
    }

    /** p(true) per labelled question from a model, in gold order. */
    private suspend fun predictions(cases: List<Case>, model: DecisionModel): Pair<List<Double>, List<Boolean>> {
        val p = ArrayList<Double>(); val g = ArrayList<Boolean>()
        for (c in cases) {
            val res = model.decide(c.request).getOrThrow()
            for ((q, gold) in c.gold) { p += (res.answers[q] as? Answer.Noul)?.probability ?: 0.5; g += gold }
        }
        return p to g
    }

    /** Today's boundary heuristic: a fixed cut every TREE_SENTENCES sentences (p = 1 there, 0 elsewhere). */
    private fun fixedCut(cases: List<Case>): Pair<List<Double>, List<Boolean>> {
        val p = ArrayList<Double>(); val g = ArrayList<Boolean>()
        for (c in cases) for ((q, gold) in c.gold) { val i = q.removePrefix("b").toInt(); p += if ((i + 1) % SplitConstants.TREE_SENTENCES == 0) 1.0 else 0.0; g += gold }
        return p to g
    }

    /** A lexical baseline through the DecisionModel seam: the next sentence is short and capitalised (heading-like) or starts a numbered clause. */
    private fun lexical() = FakeDecisionModel().apply {
        boundaryRule = { q, state ->
            val i = q.id.removePrefix("b").toInt()
            val next = state.lineSequence().firstOrNull { it.startsWith("s${i + 1}: ") }?.removePrefix("s${i + 1}: ") ?: ""
            when {
                next.length < 60 && next.firstOrNull()?.isUpperCase() == true && !next.endsWith(".") -> 0.8
                Regex("^(Every|Each|One|Two|Three|No|A|An|The) [a-z]").containsMatchIn(next) -> 0.35
                else -> 0.15
            }
        }
    }

    /** Today's verdict heuristic: hash and schema pass ⇒ consistent (1.0); a schema failure ⇒ 0.0. */
    private fun hashAndSchema(cases: List<Case>): Pair<List<Double>, List<Boolean>> {
        val p = ArrayList<Double>(); val g = ArrayList<Boolean>()
        for (c in cases) for ((_, gold) in c.gold) { p += if (c.schemaValid) 1.0 else 0.0; g += gold }
        return p to g
    }

    /** An answers file (phone run or Clef replay): `{"id", "answers": {qid: p | {probability} | {...}}}` per line. */
    private fun answersFile(cases: List<Case>): Pair<List<Double>, List<Boolean>>? {
        val path = System.getProperty("decide.answers")?.takeIf { it.isNotBlank() } ?: return null
        val byId = File(path).readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val p = ArrayList<Double>(); val g = ArrayList<Boolean>()
        for (c in cases) {
            val answers = byId[c.id]?.get("answers")?.jsonObject ?: continue
            for ((q, gold) in c.gold) {
                val a = answers[q] ?: continue
                p += when (a) { is JsonPrimitive -> a.doubleOrNull ?: 0.5; is JsonObject -> a["probability"]?.jsonPrimitive?.doubleOrNull ?: a["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5; else -> 0.5 }
                g += gold
            }
        }
        return if (p.isEmpty()) null else p to g
    }

    @Test fun boundaryGoldSetAndBaselines() = runTest {
        val cases = load("boundaries.jsonl")
        assertTrue("gold set has ${cases.size} windows", cases.size >= 40)
        val labelled = cases.sumOf { it.gold.size }
        val positives = cases.sumOf { c -> c.gold.count { it.value } }
        println("boundaries: ${cases.size} windows, $labelled labelled boundaries, $positives topic changes")
        val fixed = fixedCut(cases).let { Metrics.report(it.first, it.second) }
        val lex = predictions(cases, lexical()).let { Metrics.report(it.first, it.second) }
        val uninformed = Metrics.report(List(labelled) { 0.5 }, cases.flatMap { it.gold.values })
        println("  fixed 40-sentence cut (today): $fixed")
        println("  lexical baseline (DecisionModel seam): $lex")
        println("  uninformed 0.5: $uninformed")
        answersFile(cases)?.let { println("  answers file: " + Metrics.report(it.first, it.second)) }
        assertEquals(labelled, fixed.n)
        assertEquals(labelled, lex.n)
        // Today's fixed cut carries no information about topic changes: its Brier score is that of always-wrong-half-the-time.
        assertTrue("fixed cut brier ${fixed.brier}", fixed.brier >= uninformed.brier)
        // A model earns its place by beating both lines above on this set (the phone run or the Clef replay reports here).
    }

    @Test fun verdictGoldSetAndBaselines() = runTest {
        val cases = load("verdicts.jsonl")
        assertTrue(cases.size >= 40 && cases.size % 4 == 0)                               // readable invoices × {ok, total, vendor, schema}
        val heur = hashAndSchema(cases).let { Metrics.report(it.first, it.second) }
        val uninformed = Metrics.report(List(cases.size) { 0.5 }, cases.flatMap { it.gold.values })
        println("verdicts: ${cases.size} cases")
        println("  hash + schema (today): $heur")
        println("  uninformed 0.5: $uninformed")
        answersFile(cases)?.let { println("  answers file: " + Metrics.report(it.first, it.second)) }
        assertEquals(0.5, heur.accuracy, 1e-9)                                           // today's checks see the schema break only; swapped values pass
        assertTrue(heur.ece > uninformed.ece)                                             // and are badly calibrated on the swaps
    }
}
