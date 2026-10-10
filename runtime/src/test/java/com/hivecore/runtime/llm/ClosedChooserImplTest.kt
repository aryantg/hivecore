package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedChooserImplTest {
    private val options = listOf("alpha", "beta", "gamma")

    /** Replies from a fixed sequence (one per call), or a function of the shown options. */
    private class ScriptEngine(val reply: (call: Int, shown: List<String>) -> String) : LlmEngine {
        override val backend: StateFlow<LlmBackend?> = MutableStateFlow(LlmBackend.NPU)
        override val lastStats: StateFlow<GenStats?> = MutableStateFlow(null)
        override val status: StateFlow<String> = MutableStateFlow("script")
        val prompts = mutableListOf<List<ChatMsg>>()
        override fun available() = setOf(LlmBackend.NPU)
        override suspend fun load(backend: LlmBackend) = Result.success(Unit)
        override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> {
            prompts += messages
            val shown = messages.last().content.lines().mapNotNull { Regex("^\\d+\\. (.*)$").matchEntire(it)?.groupValues?.get(1) }
            return Result.success(GenResult(reply(prompts.size - 1, shown), GenStats(1, 1, 1, 1, 1.0)))
        }
        override suspend fun cancel() {}
    }

    @Test fun majorityByLabelIsPermutationProof() = runTest {
        val llm = FakeLlmEngine().apply { replies["favourite letter"] = "beta" }
        val d = ClosedChooserImpl(llm).choose("Which is the favourite letter?", options, { it }, 3)
        assertEquals("beta", d.value)
        assertEquals(1.0, d.confidence, 1e-9)
        assertEquals(3, d.agreeing); assertEquals(3, d.runs)
        assertEquals(listOf("beta", "beta", "beta"), d.raw)
        assertEquals(3, llm.calls.size)
        assertTrue(llm.calls.all { it.second.maxTokens == ClosedChooserImpl.MAX_TOKENS })
        // each run shows a different option first
        val firsts = llm.calls.map { c -> c.first.last().content.lines().first { it.startsWith("1. ") } }
        assertEquals(3, firsts.toSet().size)
    }

    @Test fun numberAnswerMapsBackThroughThePermutation() = runTest {
        // the engine always picks whichever position shows "gamma": the vote is unanimous despite the shuffles
        val llm = ScriptEngine { _, shown -> (shown.indexOf("gamma") + 1).toString() }
        val d = ClosedChooserImpl(llm).choose("q", options, { it }, 3)
        assertEquals("gamma", d.value)
        assertEquals(1.0, d.confidence, 1e-9)
    }

    @Test fun positionBiasSplitsTheVoteAndGivesNoValue() = runTest {
        val llm = FakeLlmEngine().apply { fallback = "1" }
        val v = ClosedChooserImpl(llm).vote("Which one?", options, { it }, 3)
        assertNull(v.decision.value)
        assertEquals(1.0 / 3, v.decision.confidence, 1e-9)
        assertEquals(listOf(1, 1, 1), v.votes)
        assertEquals(3, v.ranked().size)
    }

    @Test fun nonVotesAreNeverRepaired() = runTest {
        for (bad in listOf("I think beta", "2 because", "{\"choice\":2}", "4", "0", "", "beta!", "Beta gamma")) {
            val llm = FakeLlmEngine().apply { fallback = bad }
            val d = ClosedChooserImpl(llm).choose("q", options, { it }, 3)
            assertNull("'$bad'", d.value)
            assertEquals("'$bad'", 0, d.agreeing)
            assertEquals(0.0, d.confidence, 1e-9)
        }
    }

    @Test fun engineFailureIsANonVote() = runTest {
        val llm = FakeLlmEngine().apply { replies["q"] = "beta"; failNext = RuntimeException("npu") }
        val d = ClosedChooserImpl(llm).choose("q", options, { it }, 3)
        assertEquals("beta", d.value)
        assertEquals(2.0 / 3, d.confidence, 1e-9)
        assertEquals("", d.raw[0])
    }

    @Test fun lowConfidenceAndTies() = runTest {
        // 2 of 3 agree → value with confidence 0.67 (≥ LOW)
        val seq = listOf("beta", "beta", "gamma")
        val d = ClosedChooserImpl(ScriptEngine { i, _ -> seq[i] }).choose("q", options, { it }, 3)
        assertEquals("beta", d.value); assertEquals(2.0 / 3, d.confidence, 1e-9)
        // 1:1 tie with k = 2 → no value even though confidence is 0.5
        val tie = listOf("alpha", "gamma")
        val t = ClosedChooserImpl(ScriptEngine { i, _ -> tie[i] }).choose("q", options, { it }, 2)
        assertNull(t.value); assertEquals(0.5, t.confidence, 1e-9)
        // 2 of 5 agree, 3 non-votes → below LOW
        val five = listOf("beta", "beta", "x", "y", "z")
        val l = ClosedChooserImpl(ScriptEngine { i, _ -> five[i] }).choose("q", options, { it }, 5)
        assertNull(l.value); assertEquals(0.4, l.confidence, 1e-9)
    }

    @Test fun parseIsStrict() {
        val shown = listOf("per_unit: a measure per count", "top_k_by: the top groups", "none: none of these fits")
        assertEquals(1, ClosedChooserImpl.parse("2", shown))
        assertEquals(1, ClosedChooserImpl.parse(" [2] ", shown))
        assertEquals(1, ClosedChooserImpl.parse("2.", shown))
        assertEquals(0, ClosedChooserImpl.parse("per_unit", shown))
        assertEquals(2, ClosedChooserImpl.parse("NONE", shown))
        assertEquals(1, ClosedChooserImpl.parse("top_k_by: the top groups", shown))
        assertNull(ClosedChooserImpl.parse("3 per_unit", shown))
        assertNull(ClosedChooserImpl.parse("per", shown))
        assertNull(ClosedChooserImpl.parse("4", shown))
        // an ambiguous label is a non-vote
        assertNull(ClosedChooserImpl.parse("x", listOf("x: one", "x: two")))
    }

    @Test fun permutationsAreDeterministicAndLeadWithDifferentOptions() {
        val seed = ClosedChooserImpl.seed("q", listOf("a", "b", "c", "d"))
        assertEquals(seed, ClosedChooserImpl.seed("q", listOf("a", "b", "c", "d")))
        assertNotEquals(seed, ClosedChooserImpl.seed("q2", listOf("a", "b", "c", "d")))
        val perms = (0 until 4).map { ClosedChooserImpl.permutation(4, seed, it) }
        for (p in perms) assertEquals((0 until 4).toList(), p.sorted())
        assertEquals(4, perms.map { it[0] }.toSet().size)
        assertEquals(perms, (0 until 4).map { ClosedChooserImpl.permutation(4, seed, it) })
    }

    @Test fun singleOrNoOptionNeedsNoModel() = runTest {
        val llm = FakeLlmEngine()
        val one = ClosedChooserImpl(llm).choose("q", listOf("only"), { it }, 3)
        assertEquals("only", one.value); assertEquals(1.0, one.confidence, 1e-9)
        val none = ClosedChooserImpl(llm).choose("q", emptyList<String>(), { it }, 3)
        assertNull(none.value)
        assertEquals(0, llm.calls.size)
    }

    @Test fun grammarNeverGoesToTheNpu() {
        assertNull(ClosedChooserImpl.grammarFor(3, LlmBackend.NPU))
        assertNull(ClosedChooserImpl.grammarFor(3, null))
        assertEquals("root ::= \"1\" | \"2\" | \"3\"", ClosedChooserImpl.grammarFor(3, LlmBackend.CPU))
    }
}
