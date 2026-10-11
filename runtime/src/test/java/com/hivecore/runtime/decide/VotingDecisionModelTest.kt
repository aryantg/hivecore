package com.hivecore.runtime.decide

import com.hivecore.core.Answer
import com.hivecore.core.Calibration
import com.hivecore.core.ClosedChooser
import com.hivecore.core.Decision
import com.hivecore.core.DecisionBuild
import com.hivecore.core.DecisionRequest
import com.hivecore.core.LlmBackend
import com.hivecore.core.ModelEntry
import com.hivecore.core.Question
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.llm.ClosedChooserImpl
import com.hivecore.runtime.models.ModelPoolImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VotingDecisionModelTest {
    /** A chooser whose votes per option are scripted by a label substring; the question text is recorded. */
    private class ScriptedChooser(private val votesFor: (List<String>) -> List<Int>) : ClosedChooser {
        val questions = ArrayList<String>()
        override suspend fun <T> choose(question: String, options: List<T>, render: (T) -> String, k: Int): Decision<T> {
            questions += question
            val labels = options.map(render)
            val v = votesFor(labels)
            val best = v.indices.maxByOrNull { v[it] }!!
            return Decision(options[best], v[best].toDouble() / k, options, v[best], k, votes = v)
        }
    }

    private val build = DecisionBuild("m", "a".repeat(64), "cal-1")

    @Test fun noulChoiceAndScoreComeFromCalibratedVoteFractions() = runTest {
        val chooser = ScriptedChooser { labels -> labels.map { l -> when { l.startsWith("true") -> 2; l.startsWith("billing") -> 3; l == "hard" -> 2; l == "moderate" -> 1; else -> 0 } } }
        val m = VotingDecisionModel(chooser, { build })
        val req = DecisionRequest("x", "the invoice is overdue", listOf(
            Question.Noul("n", "The message is urgent."),
            Question.Choice("c", "Which team?", mapOf("technical" to "bugs", "billing" to "payments")),
            Question.Score("s", "How hard?", listOf("trivial", "easy", "moderate", "hard", "very hard")),
        ))
        val res = m.decide(req).getOrThrow()
        assertEquals(0.8333, (res.answers["n"] as Answer.Noul).probability, 1e-9)            // 2 of 3 votes for true, one abstention spread over both
        val c = res.answers["c"] as Answer.Choice
        assertEquals("billing", c.choice); assertEquals(1.0, c.confidence, 1e-9); assertEquals(listOf("billing", "technical"), c.probabilities.keys.toList())
        val s = res.answers["s"] as Answer.Score
        assertEquals(listOf(0.0, 0.0, 0.3333, 0.6667, 0.0), s.probabilities)
        assertEquals(round4(((2 * 0.3333) + (3 * 0.6667)) / 4), s.score, 1e-9)              // expected level / (levels − 1)
        assertEquals(9, res.usage.llmCalls)
        assertEquals("m", res.model)
        assertTrue(chooser.questions.all { it.contains("State:\nthe invoice is overdue") })
    }

    @Test fun groupedNoulsAreOneChoicePerGroupAndStayDeterministic() = runTest {
        val chooser = ScriptedChooser { labels -> labels.map { if (it == "b2") 3 else 0 } }
        val m = VotingDecisionModel(chooser, { build })
        val req = DecisionRequest("x", "s0: a\ns1: b\ns2: c\ns3: d", (0..3).map { Question.Noul("b$it", "topic changes after s$it", group = "w0") })
        val a = m.decide(req).getOrThrow(); val b = m.decide(req).getOrThrow()
        assertEquals(a.answers, b.answers)
        assertEquals(1.0, (a.answers["b2"] as Answer.Noul).probability, 1e-9)
        assertEquals(0.0, (a.answers["b0"] as Answer.Noul).probability, 1e-9)
        assertEquals(3, a.usage.llmCalls)                                                     // one vote for the whole window
        assertEquals(2, chooser.questions.size)                                              // one question per decide() call
        assertTrue(chooser.questions[0].contains("none"))
    }

    @Test fun calibrationBinsRemapVoteFractionsAndTiesGoToOptionOrder() = runTest {
        val cal = Calibration("cal-2", listOf(listOf(0.0, 0.1), listOf(1.0, 0.9)))
        val chooser = ScriptedChooser { labels -> labels.map { 0 } }                      // every run a non-vote
        val m = VotingDecisionModel(chooser, { build }, { cal })
        val res = m.decide(DecisionRequest("x", "s", listOf(Question.Choice("c", "?", mapOf("b" to "2", "a" to "1"))))).getOrThrow()
        val c = res.answers["c"] as Answer.Choice
        assertEquals("a", c.choice)                                                            // 0.1 vs 0.1 → first option in sorted key order
        assertEquals(0.5, c.confidence, 1e-9)
        assertEquals(0.5, cal.apply(0.5), 1e-9)
        assertTrue(Calibration("v", listOf(listOf(0.5, 0.2), listOf(0.2, 0.9))).problems().isNotEmpty())
        assertTrue(Calibration("v", listOf(listOf(0.0, 0.0), listOf(1.0, 1.0))).problems().isEmpty())
    }

    @Test fun grammarOnlyOffTheNpuAndRejectOnTheNpu() = runTest {
        val cpu = FakeLlmEngine(LlmBackend.CPU).apply { fallback = "2" }
        val onCpu = VotingDecisionModel(ClosedChooserImpl(cpu), { build })
        val r = onCpu.decide(DecisionRequest("x", "s", listOf(Question.Noul("n", "yes?")))).getOrThrow()
        assertTrue(cpu.calls.all { it.second.grammar != null })
        assertTrue((r.answers["n"] as Answer.Noul).probability in 0.0..1.0)
        val npu = FakeLlmEngine(LlmBackend.NPU).apply { fallback = "maybe 2" }           // not an option number: a non-vote, never repaired
        val onNpu = VotingDecisionModel(ClosedChooserImpl(npu), { build })
        val n = onNpu.decide(DecisionRequest("x", "s", listOf(Question.Noul("n", "yes?")))).getOrThrow()
        assertTrue(npu.calls.all { it.second.grammar == null })
        assertEquals(0.5, (n.answers["n"] as Answer.Noul).probability, 1e-9)              // no votes at all → uniform
    }

    @Test fun buildComesFromTheLoadedLlmsCatalogEntryAndIsNullWhenUnpinned() = runTest {
        val entry = ModelEntry(ModelPoolImpl.LLM_QAIRT_ID, "llm.text", "qairt", "b".repeat(64), 1, 1, 1, 1, true, calibration = Calibration("cal-7"))
        val catalog = ModelCatalog(listOf(entry, entry.copy(modelId = ModelPoolImpl.LLM_GGUF_ID, runtime = "llama_cpp", sha256 = ModelCatalog.UNPINNED, calibration = null)))
        val llm = FakeLlmEngine(null)
        assertNull(VotingDecisionModel.buildOf(llm, catalog))                                // nothing loaded
        val npu = FakeLlmEngine(LlmBackend.NPU)
        val b = VotingDecisionModel.buildOf(npu, catalog)
        assertNotNull(b); assertEquals(ModelPoolImpl.LLM_QAIRT_ID, b!!.modelId); assertEquals("cal-7", b.calibrationVersion); assertEquals("b".repeat(64) + ":cal-7", b.tag)
        assertNull(VotingDecisionModel.buildOf(FakeLlmEngine(LlmBackend.CPU), catalog))      // the GGUF build is unpinned
        val m = VotingDecisionModel.over(FakeLlmEngine(null), ClosedChooserImpl(FakeLlmEngine(null))) { catalog }
        assertTrue(m.decide(DecisionRequest("x", "s", listOf(Question.Noul("n", "?")))).isFailure)
    }

    private fun round4(v: Double) = Math.round(v * 10_000.0) / 10_000.0
}
