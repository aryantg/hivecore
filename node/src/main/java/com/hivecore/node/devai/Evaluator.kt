package com.hivecore.node.devai

import com.hivecore.core.AskOutcome
import com.hivecore.core.AskProgress
import com.hivecore.core.Asker
import com.hivecore.node.data.TextAnswerRenderer
import com.hivecore.core.Canon
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * EVAL (swarm plan §4A.3): runs each case of a shard through [ask] (the node's normal question pipeline) and grades
 * the outcome against the case. A case passes when the outcome kind equals `expect` and, for an answer with expected
 * [EvalCase.values], every value appears among the numbers of the answer text once the renderer's Indian grouping is
 * parsed back (both sides rounded to two decimals, as the renderer rounds).
 *
 * Output (mergeable, §4B.2): `{op:"EVAL", items:[{item, q, pass, reason, got:{kind,text}, expected:{kind,values}, ms}],
 * totals:{cases, pass, fail, ms}}` with items in shard order and totals as plain sums, so CONCAT of the items and
 * a sum of the totals over shards equal one unsplit run. `ms` is wall time (from [clock]) and is the only field
 * that differs between replicas; [gradeHash] hashes everything else.
 */
class Evaluator(
    private val ask: suspend (String) -> AskOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Grade(val pass: Boolean, val reason: String, val gotKind: String, val gotText: String)

    suspend fun run(cases: List<EvalCase>): JsonObject {
        val items = ArrayList<JsonObject>(cases.size)
        var pass = 0L; var ms = 0L
        for (c in cases) {
            val t0 = clock()
            val g = try { grade(c, ask(c.q)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Grade(false, "ask failed: ${e.message ?: e.javaClass.simpleName}", "error", "")
            }
            val dt = (clock() - t0).coerceAtLeast(0)
            if (g.pass) pass++
            ms += dt
            items += buildJsonObject {
                put("item", c.id); put("q", c.q); put("pass", g.pass); put("reason", g.reason)
                putJsonObject("got") { put("kind", g.gotKind); put("text", g.gotText) }
                putJsonObject("expected") {
                    put("kind", c.expect)
                    put("values", JsonArray(c.values.map { Canon.number(round2(BigDecimal(it.toString()))) }))
                }
                put("ms", dt)
            }
        }
        return buildJsonObject {
            put("op", "EVAL")
            put("items", JsonArray(items))
            putJsonObject("totals") { put("cases", cases.size.toLong()); put("pass", pass); put("fail", cases.size - pass); put("ms", ms) }
        }
    }

    companion object {
        private val renderer = TextAnswerRenderer()
        private val NUMBER = Regex("\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?")

        /** Adapter from the app's [Asker] (AskOrchestrator or the Graph's asker): the flow's final outcome. */
        fun asking(asker: Asker): suspend (String) -> AskOutcome = { q ->
            asker.ask(q, false).filterIsInstance<AskProgress.Done>().first().outcome
        }

        fun kindOf(o: AskOutcome): String = when (o) { is AskOutcome.Answer -> "answer"; is AskOutcome.Refusal -> "refusal"; is AskOutcome.NoNode -> "nonode" }

        fun textOf(o: AskOutcome): String = when (o) {
            is AskOutcome.Answer -> o.text
            is AskOutcome.Refusal -> renderer.refusal(o.reasons)
            is AskOutcome.NoNode -> renderer.noNode(o.holdings)
        }

        fun grade(c: EvalCase, o: AskOutcome): Grade {
            val kind = kindOf(o)
            val text = textOf(o)
            if (kind != c.expect) return Grade(false, "expected ${c.expect}, got $kind", kind, text)
            if (kind == "answer" && c.values.isNotEmpty()) {
                val shown = numbersIn(text).map { round2(it) }.toSet()
                val missing = c.values.map { round2(BigDecimal(it.toString())) }.filter { it !in shown }
                if (missing.isNotEmpty()) return Grade(false, "missing " + missing.joinToString(", ") { it.toPlainString() }, kind, text)
            }
            return Grade(true, "ok", kind, text)
        }

        /** Every number in a rendered answer: "5,07,97,500" → 50797500, "33,86,500.67" → 3386500.67, "-1,200" → -1200. */
        fun numbersIn(text: String): List<BigDecimal> = NUMBER.findAll(text).mapNotNull { m ->
            val neg = m.range.first > 0 && text[m.range.first - 1] == '-' && (m.range.first < 2 || !text[m.range.first - 2].isLetterOrDigit())
            runCatching { BigDecimal(m.value.replace(",", "")).let { if (neg) it.negate() else it } }.getOrNull()
        }.toList()

        fun round2(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

        /** Hash of an EVAL output without its timings: equal across replicas that graded the same outcomes. */
        fun gradeHash(out: JsonElement): String = Canon.hash(stripMs(out))

        private fun stripMs(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "ms" }.mapValues { stripMs(it.value) })
            is JsonArray -> JsonArray(e.map { stripMs(it) })
            else -> e
        }

        /** The cases of a task: `params.cases` (or `params.items`), else the first input carrying `cases`/`items` or an array. */
        fun casesOf(params: JsonObject, inputs: List<JsonElement>): List<EvalCase> {
            val fromParams = (params["cases"] ?: params["items"]) as? JsonArray
            val arr = fromParams ?: inputs.firstNotNullOfOrNull { e ->
                when (e) {
                    is JsonArray -> e
                    is JsonObject -> (e["cases"] ?: e["items"]) as? JsonArray
                    else -> null
                }
            } ?: return emptyList()
            return arr.map { EvalCases.fromJson(it) }
        }

    }
}
