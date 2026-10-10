package com.hivecore.runtime.llm

import com.hivecore.core.AnswerChip
import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeSchemaSource
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmIntentPlannerTest {
    private val hr = FakeSchemaSource.seed(NodeRole.HR)
    private val templates = TestAssets.templates()
    private val good = """{"intents":[{"table":"employees","metric":"avg","column":"commission","filters":[{"column":"region","op":"eq","value":"West"}]}]}"""
    private val q2 = "Average commission in the West region"

    /** A clock the test moves forward; [SlowEngine] advances it on every generate. */
    private class Clock(var now: Long = 1000L) { fun tick(ms: Long) { now += ms } }

    private class SlowEngine(private val inner: FakeLlmEngine, private val clock: Clock, private val msPerCall: Long) : LlmEngine {
        override val backend: StateFlow<LlmBackend?> get() = inner.backend
        override val lastStats: StateFlow<GenStats?> get() = inner.lastStats
        override val status: StateFlow<String> get() = inner.status
        override fun available() = inner.available()
        override suspend fun load(backend: LlmBackend) = inner.load(backend)
        override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> {
            clock.tick(msPerCall); return inner.generate(messages, params, onToken)
        }
        override suspend fun cancel() = inner.cancel()
    }

    private fun planner(engine: LlmEngine, clock: Clock = Clock(), log: MutableList<IntentLogRecord> = mutableListOf()) =
        LlmIntentPlanner(engine, templates, { TestAssets.fewShots(it) }, clock = { clock.now }, today = { "2026-10-10" }).apply { onLogged = { log.add(it) } }

    @Test fun successPath() = runTest {
        val fake = FakeLlmEngine().apply { replies["commission"] = good }
        val log = mutableListOf<IntentLogRecord>()
        val clock = Clock()
        val r = planner(SlowEngine(fake, clock, 800), clock, log).plan(q2, hr).getOrThrow()
        assertEquals(AnswerChip.LLM, r.chip)
        assertEquals("commission", r.intents.intents[0].column)
        assertEquals(good, r.raw)
        assertEquals(800L, r.ms)
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls[0].second.stopOnBalancedJson)
        assertEquals("system", fake.calls[0].first.first().role)
        assertEquals(1, log.size); assertTrue(log[0].parsedOk); assertEquals(1, log[0].attempts); assertEquals(AnswerChip.LLM, log[0].chip)
        assertEquals(NodeRole.HR, log[0].role); assertEquals("CPU", log[0].backend)
    }

    @Test fun retryPathSucceedsOnSecondAttempt() = runTest {
        val fake = FakeLlmEngine().apply {
            replies["previous output was not valid"] = good     // the retry turn
            fallback = "Sorry, here it is: {\"intents\":[{\"table\":\"employees\",\"metric\":\"avg\""   // truncated
        }
        val log = mutableListOf<IntentLogRecord>()
        val r = planner(fake, log = log).plan("Average commission for everyone in the West region", hr).getOrThrow()
        assertEquals(AnswerChip.LLM, r.chip)
        assertEquals(2, fake.calls.size)
        val retryMsgs = fake.calls[1].first
        assertEquals("assistant", retryMsgs[retryMsgs.size - 2].role)
        val retryUser = retryMsgs.last().content
        assertTrue(retryUser.startsWith("Your previous output was not valid: "))
        assertTrue(retryUser.contains("truncated"))
        assertTrue(retryUser.endsWith("Output only the JSON object."))
        assertEquals(2, log[0].attempts); assertTrue(log[0].parsedOk)
    }

    @Test fun retrySkippedWhenFirstAttemptIsSlow() = runTest {
        val fake = FakeLlmEngine().apply { fallback = "not json at all" }
        val clock = Clock()
        val r = planner(SlowEngine(fake, clock, 3600), clock).plan(q2, hr).getOrThrow()
        assertEquals(1, fake.calls.size)                       // no retry
        assertEquals(AnswerChip.TEMPLATE, r.chip)
        assertEquals("commission", r.intents.intents[0].column)
        assertEquals("not json at all", r.raw)
        assertEquals(3600L, r.ms)
    }

    @Test fun retryHappensWhenFirstAttemptIsFastEnough() = runTest {
        val fake = FakeLlmEngine().apply { fallback = "not json at all" }
        val clock = Clock()
        val r = planner(SlowEngine(fake, clock, 3400), clock).plan(q2, hr).getOrThrow()
        assertEquals(2, fake.calls.size)
        assertEquals(AnswerChip.TEMPLATE, r.chip)
    }

    @Test fun templateFallbackAfterTwoFailures() = runTest {
        val fake = FakeLlmEngine().apply { fallback = "{\"intents\":[]}" }
        val log = mutableListOf<IntentLogRecord>()
        val r = planner(fake, log = log).plan("Total invoiced in October?", FakeSchemaSource.seed(NodeRole.ACCOUNTS)).getOrThrow()
        assertEquals(2, fake.calls.size)
        assertEquals(AnswerChip.TEMPLATE, r.chip)
        assertEquals("invoices", r.intents.intents[0].table)
        assertFalse(log[0].parsedOk); assertEquals(AnswerChip.TEMPLATE, log[0].chip); assertTrue(log[0].validation.contains("Q5"))
    }

    @Test fun noTemplateMeansRefusal() = runTest {
        val fake = FakeLlmEngine().apply { fallback = "nope" }
        val log = mutableListOf<IntentLogRecord>()
        val r = planner(fake, log = log).plan("How tall is the Eiffel tower?", hr)
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull() as IntentPlanFailed
        assertEquals("could not understand the question", e.reason)
        assertEquals(2, fake.calls.size)
        assertNull(log[0].chip); assertFalse(log[0].parsedOk)
    }

    @Test fun engineFailureIsNotRetried() = runTest {
        val fake = FakeLlmEngine().apply { failNext = IllegalStateException("no model loaded") }
        val r = planner(fake).plan(q2, hr).getOrThrow()
        assertEquals(1, fake.calls.size)
        assertEquals(AnswerChip.TEMPLATE, r.chip)
        val r2 = planner(FakeLlmEngine().apply { failNext = IllegalStateException("boom") }).plan("Something else entirely", hr)
        assertTrue(r2.isFailure)
    }

    @Test fun notRelevantOverriddenByTemplateForExactTourSentence() = runTest {
        val fake = FakeLlmEngine()   // fallback = not_relevant
        val log = mutableListOf<IntentLogRecord>()
        val r = planner(fake, log = log).plan("average commission in the WEST region!", hr).getOrThrow()
        assertEquals(AnswerChip.TEMPLATE, r.chip)
        assertFalse(r.intents.notRelevant)
        assertEquals("commission", r.intents.intents[0].column)
        assertEquals(1, fake.calls.size)
        assertTrue(log[0].parsedOk); assertTrue(log[0].validation.contains("overridden"))
    }

    @Test fun notRelevantKeptWhenNoTemplateOrOnlyDocTemplate() = runTest {
        val fake = FakeLlmEngine()
        val r = planner(fake).plan("What were last month's unit sales?", hr).getOrThrow()
        assertEquals(AnswerChip.LLM, r.chip); assertTrue(r.intents.notRelevant)
        // Q6 has only a {doc, locator} entry on HR: the intent planner must not turn it into intents
        val r6 = planner(FakeLlmEngine()).plan("How many leave days does a new joiner get?", hr).getOrThrow()
        assertEquals(AnswerChip.LLM, r6.chip); assertTrue(r6.intents.notRelevant)
    }

    @Test fun extraIntentOverAnUnnamedColumnIsDropped() = runTest {
        // Gate 1 sample output for Q1 on the HR node (NPU, greedy): the count is right, the sum salary is invented
        val q1 = "Revenue per region this year, and how many reps per region?"
        val sample = """{"intents":[{"table":"employees","metric":"count","group_by":["region"]},{"table":"employees","metric":"sum","column":"salary","group_by":["region"],"filters":[{"column":"region","op":"eq","value":"South"}]}]}"""
        val fake = FakeLlmEngine().apply { replies["revenue per region"] = sample }
        val log = mutableListOf<IntentLogRecord>()
        val r = planner(fake, log = log).plan(q1, hr).getOrThrow()
        assertEquals(AnswerChip.LLM, r.chip)
        assertEquals(1, r.intents.intents.size)
        assertEquals("count", r.intents.intents[0].metric)
        assertTrue(log[0].validation.contains("dropped 1 intent"))
        assertTrue(log[0].validation.contains("sum salary"))

        // both columns named (plural "salaries", plural "commissions") → both kept
        val both = """{"intents":[{"table":"employees","metric":"avg","column":"salary","group_by":["region"]},{"table":"employees","metric":"avg","column":"commission","group_by":["region"]}]}"""
        val r2 = planner(FakeLlmEngine().apply { replies["salaries and commissions"] = both }).plan("Average salaries and commissions per region", hr).getOrThrow()
        assertEquals(2, r2.intents.intents.size)

        // the fake schema has no column aliases; with the seed manifest's alias "pay" for salary, "pay" counts as naming it
        val aliased = hr.copy(tables = hr.tables.map { t -> t.copy(columns = t.columns.map { c -> if (c.name == "salary") c.copy(aliases = listOf("pay", "wages")) else c }) })
        val r2b = planner(FakeLlmEngine().apply { replies["pay and commissions"] = both }).plan("Average pay and commissions per region", aliased).getOrThrow()
        assertEquals(2, r2b.intents.intents.size)
        val r2c = planner(FakeLlmEngine().apply { replies["pay and commissions"] = both }).plan("Average pay and commissions per region", hr).getOrThrow()
        assertEquals(1, r2c.intents.intents.size)   // without the alias, "pay" does not name salary → the extra intent goes

        // a single intent is never pruned, even over an unnamed column
        val one = """{"intents":[{"table":"employees","metric":"sum","column":"salary"}]}"""
        val r3 = planner(FakeLlmEngine().apply { replies["payroll"] = one }).plan("What is the total payroll?", hr).getOrThrow()
        assertEquals(1, r3.intents.intents.size)

        // the guard can be switched off
        val off = LlmIntentPlanner(FakeLlmEngine().apply { replies["revenue per region"] = sample }, templates, { TestAssets.fewShots(it) },
            today = { "2026-10-10" }, groundExtraIntents = false)
        assertEquals(2, off.plan(q1, hr).getOrThrow().intents.intents.size)
    }

    @Test fun groundingTokens() {
        val t = Grounding.tokens("Average salaries, commissions and wages per region")
        assertTrue("salary" in t); assertTrue("commission" in t); assertTrue("wage" in t); assertTrue("region" in t)
    }

    @Test fun templateOnWrongRoleDoesNotOverride() = runTest {
        // Q2 is an HR template only; on Sales the LLM's not_relevant stands
        val r = planner(FakeLlmEngine()).plan(q2, FakeSchemaSource.seed(NodeRole.SALES)).getOrThrow()
        assertEquals(AnswerChip.LLM, r.chip); assertTrue(r.intents.notRelevant)
    }

    @Test fun groundingDropsAnExtraIntentFilteringOnAnIdentifier() {
        val sales = FakeSchemaSource.seed(NodeRole.SALES)
        val main = com.hivecore.core.Intent("sales", "sum", "revenue", listOf("region"))
        val extra = com.hivecore.core.Intent("sales", "count", null, listOf("region"),
            listOf(com.hivecore.core.Filter("rep_id", "eq", kotlinx.serialization.json.JsonPrimitive("rep"))))
        val (kept, note) = Grounding.pruneExtraIntents(com.hivecore.core.IntentSet(listOf(main, extra)), "Revenue per region this year, and how many reps per region?", sales)
        assertEquals(listOf(main), kept.intents)
        assertTrue(note != null)
        // A single intent is never touched, even with an identifier filter (the compiler decides).
        val (single, _) = Grounding.pruneExtraIntents(com.hivecore.core.IntentSet(listOf(extra)), "x", sales)
        assertEquals(listOf(extra), single.intents)
    }

    @Test fun groundingDropsAnExtraIntentOverATableThisNodeDoesNotHave() {
        val hr = FakeSchemaSource.seed(NodeRole.HR)
        val main = com.hivecore.core.Intent("employees", "count", null, listOf("region"))
        // Measured on HR while tuning the Wave 4 prompt: "revenue" came back as its own table.
        val foreign = com.hivecore.core.Intent("revenue", "sum", "revenue", listOf("region"))
        val (kept, note) = Grounding.pruneExtraIntents(com.hivecore.core.IntentSet(listOf(main, foreign)), "Revenue per region this year, and how many reps per region?", hr)
        assertEquals(listOf(main), kept.intents); assertTrue(note!!.contains("unknown table"))
        // alone it is left for the compiler to refuse
        val (single, _) = Grounding.pruneExtraIntents(com.hivecore.core.IntentSet(listOf(foreign)), "Revenue per region", hr)
        assertEquals(listOf(foreign), single.intents)
    }

    @Test fun groundingDropsAnExtraCountThatRepeatsAnEarlierIntentsGroups() {
        val sales = FakeSchemaSource.seed(NodeRole.SALES)
        val year = listOf(com.hivecore.core.Filter("month", "gte", kotlinx.serialization.json.JsonPrimitive("2026-01")),
            com.hivecore.core.Filter("month", "lte", kotlinx.serialization.json.JsonPrimitive("2026-12")))
        val main = com.hivecore.core.Intent("sales", "sum", "revenue", listOf("region"), year)
        // Measured on the phone after the Q4 drop: the LLM added "count rep_id by region" with the same filters.
        val extra = com.hivecore.core.Intent("sales", "count", "rep_id", listOf("region"), year.reversed())
        val (kept, note) = Grounding.pruneExtraIntents(com.hivecore.core.IntentSet(listOf(main, extra)), "Revenue per region this year, and how many reps per region?", sales)
        assertEquals(listOf(main), kept.intents); assertTrue(note != null)
        // A count with other groups is a different question and stays.
        val byMonth = com.hivecore.core.Intent("sales", "count", null, listOf("month"), year)
        val (both, _) = Grounding.pruneExtraIntents(com.hivecore.core.IntentSet(listOf(main, byMonth)), "Revenue per region and sales count per month", sales)
        assertEquals(listOf(main, byMonth), both.intents)
    }

    /** Streams [text] token by token until cancel(): what the NPU does when the planner's budget stops it. */
    private class StreamingEngine(private val text: String, private val chunks: Int) : LlmEngine by FakeLlmEngine() {
        private val stop = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancels = 0
        override suspend fun generate(messages: List<com.hivecore.core.ChatMsg>, params: com.hivecore.core.GenParams, onToken: ((String) -> Unit)?): Result<com.hivecore.core.GenResult> {
            onToken?.invoke(text.take(chunks))
            stop.await()
            return Result.success(com.hivecore.core.GenResult(text.take(chunks), com.hivecore.core.GenStats(1, 1, 1, 1, 1.0)))
        }
        override suspend fun cancel() { cancels++; stop.complete(Unit) }
    }

    @Test fun aTourSentenceOverItsBudgetKeepsTheCompleteIntentsOrFallsBackToTheTemplate() = runTest {
        val q1 = "Revenue per region this year, and how many reps per region?"
        val sales = FakeSchemaSource.seed(NodeRole.SALES)
        val one = """{"intents":[{"table":"sales","metric":"sum","column":"revenue","group_by":["region"],"filters":[{"column":"month","op":"gte","value":"2026-01"},{"column":"month","op":"lte","value":"2026-12"}]}"""
        // Cut inside the second intent: the first one is complete and kept (chip LLM).
        val engine = StreamingEngine(one + ""","{"table":"sales","metric":"count","col""", 10_000)
        val log = mutableListOf<IntentLogRecord>()
        val r = planner(engine, log = log).plan(q1, sales).getOrThrow()
        assertEquals(1, engine.cancels)
        assertEquals(AnswerChip.LLM, r.chip)
        assertEquals(listOf("sum"), r.intents.intents.map { it.metric })
        assertTrue(log.single().validation.contains("budget"))
        // Cut inside the first intent: nothing complete, so the template answers (chip TEMPLATE, no retry).
        val early = StreamingEngine(one.take(60), 10_000)
        val t = planner(early).plan(q1, sales).getOrThrow()
        assertEquals(AnswerChip.TEMPLATE, t.chip)
        assertEquals(1, early.cancels)
    }

    @Test fun salvagePrefixKeepsOnlyCompleteIntents() {
        assertEquals("""{"intents":[{"a":"}{","b":[1]}]}""", IntentParser.salvagePrefix("""{"intents":[{"a":"}{","b":[1]},{"c":"""))
        assertEquals(null, IntentParser.salvagePrefix("""{"intents":[{"a":1"""))
        assertEquals(null, IntentParser.salvagePrefix("""{"not_relevant":true}"""))
    }

    @Test fun aFilterValueTheQuestionNeverMentionsIsDropped() {
        fun f(c: String, v: String) = com.hivecore.core.Filter(c, "eq", kotlinx.serialization.json.JsonPrimitive(v))
        val copied = com.hivecore.core.Intent("employees", "count", null, listOf("region"), listOf(f("region", "South")))
        val (set, note) = Grounding.dropUngroundedFilters(com.hivecore.core.IntentSet(listOf(copied)), "Revenue per region this year, and how many reps per region?")
        assertEquals(emptyList<com.hivecore.core.Filter>(), set.intents.single().filters); assertTrue(note!!.contains("South"))
        // Mentioned values, month ranges and near forms stay.
        val west = com.hivecore.core.Intent("employees", "avg", "commission", filters = listOf(f("region", "West")))
        assertEquals(null, Grounding.dropUngroundedFilters(com.hivecore.core.IntentSet(listOf(west)), "Average commission in the West region").second)
        val month = com.hivecore.core.Intent("sales", "sum", "revenue", filters = listOf(f("month", "2026-03"), f("region", "North")))
        assertEquals(null, Grounding.dropUngroundedFilters(com.hivecore.core.IntentSet(listOf(month)), "Revenue in March in the northern region").second)
        val rahul = com.hivecore.core.Intent("employees", "avg", "salary", filters = listOf(f("name", "Rahul")))
        assertEquals(null, Grounding.dropUngroundedFilters(com.hivecore.core.IntentSet(listOf(rahul)), "Average salary of reps named Rahul").second)
    }

    @Test fun aBudgetCutAnswerThatDiffersFromTheTemplateGivesWayToIt() = runTest {
        val q1 = "Revenue per region this year, and how many reps per region?"
        val hr = FakeSchemaSource.seed(NodeRole.HR)
        // HR's measured output: a count with a region filter copied from a few-shot, then a cut-off second intent.
        val out = """{"intents":[{"table":"employees","metric":"count","group_by":["region"],"filters":[{"column":"region","op":"eq","value":"South"}]},{"table":"employees","metric":"sum","group_by":["region"],"filters":[{"column":"region","op":"eq","value":"So"""
        val r = planner(StreamingEngine(out, 10_000)).plan(q1, hr).getOrThrow()
        // Grounding drops the South filter, which makes it equal to the template: the LLM's intent stands.
        assertEquals(AnswerChip.LLM, r.chip)
        assertEquals(templates.intents(q1, NodeRole.HR)!!.set.intents, r.intents.intents)
        val wrong = """{"intents":[{"table":"employees","metric":"avg","column":"salary","group_by":["region"]},{"table":"emp"""
        val t = planner(StreamingEngine(wrong, 10_000)).plan(q1, hr).getOrThrow()
        assertEquals(AnswerChip.TEMPLATE, t.chip)
    }
}
