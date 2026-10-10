package com.hivecore.node.devai

import com.hivecore.core.AggTable
import com.hivecore.core.AnswerChip
import com.hivecore.core.AskOutcome
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.Timing
import com.hivecore.core.fakes.FakeAsker
import com.hivecore.core.Canon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class EvaluatorTest {

    private fun tool(name: String): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "tools/$name") }.first { it.isFile }

    private val q50 by lazy { EvalCases.questions50(tool("questions_50.json").readText()) }
    private val comp by lazy { EvalCases.composite(tool("questions_composite.json").readText()) }

    private fun answer(text: String) = AskOutcome.Answer(text, emptyList<AggTable>(), null, emptyList(), AnswerChip.TEMPLATE, emptyList(),
        Timing(totalMs = 5), emptyList(), 0, 0, 0, 1)
    private fun refusal(reason: String) = AskOutcome.Refusal(listOf("HR" to reason), Timing())
    private fun nonode() = AskOutcome.NoNode(listOf("HR" to listOf("employees")), Timing())

    @Test fun loadsBothSets() {
        assertEquals(50, q50.size)
        assertEquals("q50:01", q50.first().id)
        assertEquals(EvalCase.KINDS.toSet(), q50.map { it.expect }.toSet())
        val perRep = q50.single { it.q == "Revenue per rep this year" }
        assertEquals("answer", perRep.expect)
        assertEquals("per_unit", perRep.pattern)
        assertEquals(listOf(3506524.59), perRep.values)
        // 20 composites minus the two bulk workloads
        assertEquals(18, comp.size)
        assertTrue(comp.none { it.q.startsWith("Bulk:") })
        val c1 = comp.first { it.q == "Revenue per rep by region this year" }
        assertEquals(listOf(3386500.0, 3402200.0, 3787968.75, 3430666.67), c1.values)
        assertEquals(listOf(5766.0, 4927.0), comp.first { it.pattern == "top_k_by" }.values)
        assertEquals(listOf(1202900.0, 0.0, 1202900.0), comp.first { it.pattern == "compare_two" }.values)
        assertTrue(comp.filter { it.expect == "refusal" }.all { it.values.isEmpty() })
    }

    @Test fun parsesRenderedNumbersBack() {
        val n = Evaluator.numbersIn("Revenue by region, 2026: East 5,07,97,500 (246 rows) · South 37,87,968.75, -1,200 and 85,309.84.")
        assertTrue(BigDecimal("50797500") in n)
        assertTrue(BigDecimal("246") in n)
        assertTrue(BigDecimal("2026") in n)
        assertTrue(BigDecimal("3787968.75") in n)
        assertTrue(BigDecimal("-1200") in n)
        assertTrue(BigDecimal("85309.84") in n)
    }

    @Test fun gradesCannedOutcomes() {
        val perRep = q50.single { it.q == "Revenue per rep this year" }
        assertTrue(Evaluator.grade(perRep, answer("revenue per n by (all): 21,38,98,000 / 61 = 35,06,524.59")).pass)
        Evaluator.grade(perRep, answer("revenue per rep: 35,06,524.6")).let { assertFalse(it.pass); assertEquals("missing 3506524.59", it.reason) }
        Evaluator.grade(perRep, refusal("cannot group by rep_id")).let { assertFalse(it.pass); assertEquals("expected answer, got refusal", it.reason); assertEquals("HR refused: cannot group by rep_id.", it.gotText) }

        val salaryBill = q50.single { it.q == "Total salary bill by region" }
        assertTrue(Evaluator.grade(salaryBill, refusal("only average and count are allowed on confidential column salary")).pass)
        assertFalse(Evaluator.grade(salaryBill, answer("Salary by region: West 1,76,99,000")).pass)

        val weather = q50.single { it.q.startsWith("What is the weather") }
        assertTrue(Evaluator.grade(weather, nonode()).pass)
        assertFalse(Evaluator.grade(weather, refusal("no")).pass)

        // a doc answer has no expected values: kind alone decides
        val leave = q50.first { it.q.startsWith("How many leave days") }
        assertTrue(Evaluator.grade(leave, answer("18 days (leave_policy.pdf, p.2)")).pass)

        val c1 = comp.first { it.q == "Revenue per rep by region this year" }
        assertTrue(Evaluator.grade(c1, answer("revenue per rep by region: East 33,86,500 · North 34,02,200 · South 37,87,968.75 · West 34,30,666.67")).pass)
        assertFalse(Evaluator.grade(c1, answer("revenue per rep by region: East 33,86,500 · North 34,02,200 · South 37,87,968.75")).pass)
    }

    /** Deterministic fake pipeline: odd case numbers get the right kind, even ones a refusal; time = question length. */
    private class Pipe(cases: List<EvalCase>) {
        var now = 0L
        private val byQ = cases.associateBy { it.q }
        suspend fun ask(q: String): AskOutcome {
            now += q.length
            val c = byQ.getValue(q)
            val n = c.id.substringAfter(':').toInt()
            val right = when (c.expect) {
                "answer" -> AskOutcome.Answer(c.values.joinToString(" · ") { BigDecimal(it.toString()).toPlainString() }, emptyList(), null, emptyList(),
                    AnswerChip.LLM, emptyList(), Timing(), emptyList(), 0, 0, 0, 1)
                "refusal" -> AskOutcome.Refusal(listOf("X" to "no"), Timing())
                else -> AskOutcome.NoNode(emptyList(), Timing())
            }
            return if (n % 2 == 1) right else AskOutcome.Refusal(listOf("X" to "no"), Timing())
        }
    }

    @Test fun shardTotalsSumToUnsplitTotals() = runBlocking {
        val cases = q50 + comp
        val unsplitPipe = Pipe(cases)
        val unsplit = Evaluator(unsplitPipe::ask, { unsplitPipe.now }).run(cases)

        val shards = cases.chunked(7).map { shard -> val p = Pipe(cases); Evaluator(p::ask, { p.now }).run(shard) }
        val items = shards.flatMap { it["items"]!!.jsonArray }
        assertEquals(Canon.canonical(unsplit["items"]!!), Canon.canonical(JsonArray(items)))
        for (k in listOf("cases", "pass", "fail", "ms")) {
            assertEquals(k, unsplit["totals"]!!.jsonObject[k]!!.jsonPrimitive.long, shards.sumOf { it["totals"]!!.jsonObject[k]!!.jsonPrimitive.long })
        }
        val t = unsplit["totals"]!!.jsonObject
        assertEquals(68L, t["cases"]!!.jsonPrimitive.long)
        assertEquals(t["cases"]!!.jsonPrimitive.long, t["pass"]!!.jsonPrimitive.long + t["fail"]!!.jsonPrimitive.long)
        assertEquals(cases.sumOf { it.q.length.toLong() }, t["ms"]!!.jsonPrimitive.long)
        // odd ids pass, even ids fail unless a refusal was expected
        val expectedPass = cases.count { it.id.substringAfter(':').toInt() % 2 == 1 || it.expect == "refusal" }.toLong()
        assertEquals(expectedPass, t["pass"]!!.jsonPrimitive.long)
    }

    @Test fun outputIsDeterministicAndExecutorRunsShards() = runBlocking {
        val asker = FakeAsker().apply {
            answers["Revenue per rep this year"] = answer("Revenue per rep, 2026: 35,06,524.59")
            answers["Total salary bill by region"] = refusal("only average and count are allowed on confidential column salary")
        }
        val shard = q50.filter { it.q in setOf("Revenue per rep this year", "Total salary bill by region", "What is the capital of France?") }
        val task = TaskSpec("e1", Op.EVAL, buildJsonObject { put("cases", JsonArray(shard.map { EvalCases.toJson(it) })) })
        var clock = 0L
        val ex = DevAiExecutor(ask = Evaluator.asking(asker), clock = { clock++ })
        assertEquals(setOf(Op.EVAL), ex.ops)
        val a = ex.execute(task, emptyList(), emptyList()).getOrThrow() as JsonObject
        val b = ex.execute(task, emptyList(), emptyList()).getOrThrow() as JsonObject
        assertEquals(Canon.canonical(a), Canon.canonical(b))
        assertEquals(Evaluator.gradeHash(a), Evaluator.gradeHash(b))
        val items = a["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(shard.map { it.id }, items.map { it["item"]!!.jsonPrimitive.content })
        assertTrue(items.all { it["pass"]!!.jsonPrimitive.content == "true" })
        assertEquals(JsonPrimitive(3506524.59), items.single { it["q"]!!.jsonPrimitive.content == "Revenue per rep this year" }["expected"]!!.jsonObject["values"]!!.jsonArray.single())
        assertEquals(3L, a["totals"]!!.jsonObject["pass"]!!.jsonPrimitive.long)
        assertEquals(3L, a["totals"]!!.jsonObject["ms"]!!.jsonPrimitive.long)
    }
}
