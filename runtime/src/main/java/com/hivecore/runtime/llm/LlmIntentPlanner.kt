package com.hivecore.runtime.llm

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hivecore.core.AnswerChip
import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.IntentPlanner
import com.hivecore.core.IntentSet
import com.hivecore.core.LlmEngine
import com.hivecore.core.NodeRole
import com.hivecore.core.PlanResult
import com.hivecore.core.SLog
import com.hivecore.core.Schema
import java.time.LocalDate

/** Returned as the failure of [LlmIntentPlanner.plan]; the ask pipeline maps it to `Refused(reason)`. */
class IntentPlanFailed(val reason: String) : Exception(reason)

/** What goes to `_intent_log` (owned by the data package); the planner only reports it via [LlmIntentPlanner.onLogged]. */
data class IntentLogRecord(
    val question: String, val role: NodeRole, val rawOutput: String, val parsedOk: Boolean,
    val validation: String, val ms: Long, val backend: String?, val attempts: Int, val chip: AnswerChip?
)

/**
 * LLM → parse → retry once → template (plan 8).
 * - Retry once with "Your previous output was not valid: <reason>. Output only the JSON object." appended,
 *   unless the first attempt took longer than [slowMs] (3.5 s): then straight to the template so the 8 s
 *   deadline holds. An engine failure (nothing loaded, native error) is not retried either.
 * - Template fallback = normalised exact match of this role's tour sentences; chip TEMPLATE.
 * - A `not_relevant` from the LLM for a sentence that has an intent template on this role is overridden by
 *   the template (chip TEMPLATE); any other parsed answer keeps chip LLM.
 * - Tour sentence (a template exists on this role): the first attempt gets [templateBudgetMs] (4.5 s). Measured
 *   in Wave 3, Qwen3-4B answers the Sales Q1 sentence with a second, redundant intent (121 tokens, 6.6-6.9 s on
 *   the NPU, against the 8 s per-node deadline). At the budget the stream is stopped; the intents complete by
 *   then are kept when they are at least as many as the template has (chip LLM), else the template (chip TEMPLATE).
 * - Nothing left → failure [IntentPlanFailed]("could not understand the question").
 * [clock] is injectable for tests; [today] feeds the date rules in the prompt.
 */
class LlmIntentPlanner(
    private val llm: LlmEngine,
    private val templates: TemplateMatcher,
    private val fewShotsProvider: (NodeRole) -> List<FewShot>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
    private val slowMs: Long = SLOW_MS,
    private val maxTokens: Int = 192,
    private val groundExtraIntents: Boolean = true,
    private val templateBudgetMs: Long = TEMPLATE_BUDGET_MS,
    var onLogged: (IntentLogRecord) -> Unit = {}
) : IntentPlanner {

    override suspend fun plan(question: String, schema: Schema): Result<PlanResult> {
        val role = schema.node
        val t0 = clock()
        val template = templates.intents(question, role)
        val messages = PromptBuilder.intent(schema, question, fewShotsProvider(role), today())
        val params = GenParams(maxTokens = maxTokens, stopOnBalancedJson = true)
        var attempts = 0
        var lastRaw = ""
        var lastError = ""
        var parsed: IntentSet? = null

        // Attempt 1 (time-boxed for tour sentences, which have a template to fall back on)
        attempts++
        var budgetHit = false
        var salvaged: String? = null
        val first = if (template == null || templateBudgetMs <= 0) llm.generate(messages, params) else coroutineScope {
            // Stop only our own stream: while this call still waits for the engine (another job holds it), no token
            // of ours has arrived and the other job's generation must not be cancelled.
            var streaming = false
            val dog = launch { delay(templateBudgetMs); if (streaming) { budgetHit = true; llm.cancel() } }
            try { llm.generate(messages, params) { streaming = true } } finally { dog.cancel() }
        }
        val firstMs = clock() - t0
        if (first.isSuccess) {
            lastRaw = first.getOrThrow().text
            val p = IntentParser.parse(lastRaw)
            if (p.isSuccess) parsed = p.getOrThrow() else lastError = p.exceptionOrNull()?.message ?: "parse error"
            if (parsed == null && budgetHit && template != null) {
                val partial = IntentParser.salvagePrefix(lastRaw)?.let { IntentParser.parse(it).getOrNull() }
                if (partial != null && !partial.notRelevant && partial.intents.size >= template.set.intents.size) {
                    parsed = partial
                    salvaged = "stopped at the ${templateBudgetMs} ms budget; kept ${partial.intents.size} complete intent(s)"
                    SLog.w(TAG, salvaged)
                } else {
                    lastError = "stopped at the ${templateBudgetMs} ms budget"
                }
            }
        } else {
            lastError = "engine: ${first.exceptionOrNull()?.message ?: "generation failed"}"
            SLog.w(TAG, "generate failed: $lastError")
        }

        // Attempt 2: only after a parse failure (not an engine failure) and only when the first attempt was fast
        if (parsed == null && first.isSuccess) {
            if (firstMs > slowMs || budgetHit) {
                SLog.w(TAG, "first attempt took $firstMs ms > $slowMs ms; skipping the retry")
            } else {
                attempts++
                val retry = messages + ChatMsg("assistant", lastRaw) +
                    ChatMsg("user", "Your previous output was not valid: $lastError. Output only the JSON object.")
                val second = llm.generate(retry, params)
                if (second.isSuccess) {
                    lastRaw = second.getOrThrow().text
                    val p = IntentParser.parse(lastRaw)
                    if (p.isSuccess) parsed = p.getOrThrow() else lastError = p.exceptionOrNull()?.message ?: "parse error"
                } else {
                    lastError = "engine: ${second.exceptionOrNull()?.message ?: "generation failed"}"
                }
            }
        }

        val ms = clock() - t0
        val backend = llm.backend.value?.name
        fun log(ok: Boolean, validation: String, chip: AnswerChip?) =
            onLogged(IntentLogRecord(question, role, lastRaw, ok, validation, ms, backend, attempts, chip))

        if (parsed != null) {
            if (parsed.notRelevant && template != null) {
                SLog.d(TAG, "LLM said not_relevant but template ${template.id} covers this sentence on $role → TEMPLATE")
                log(true, "not_relevant overridden by template ${template.id}", AnswerChip.TEMPLATE)
                return Result.success(PlanResult(template.set, AnswerChip.TEMPLATE, lastRaw, ms))
            }
            val (filtered, filterNote) = if (groundExtraIntents) Grounding.dropUngroundedFilters(parsed, question) else parsed to null
            val (grounded, note) = if (groundExtraIntents) Grounding.pruneExtraIntents(filtered, question, schema) else filtered to null
            if (filterNote != null) SLog.w(TAG, filterNote)
            if (note != null) SLog.w(TAG, note)
            // A stream cut at the budget is only half an answer: it stands when it agrees with the sentence's cached
            // intents, otherwise the template answers (measured: HR's cut-off output kept a hallucinated filter).
            if (salvaged != null && template != null && !Grounding.sameIntents(grounded, template.set)) {
                SLog.w(TAG, "budget-cut intents differ from template ${template.id}; template -> TEMPLATE")
                log(true, "$salvaged; differs from template ${template.id}", AnswerChip.TEMPLATE)
                return Result.success(PlanResult(template.set, AnswerChip.TEMPLATE, lastRaw, ms))
            }
            log(true, listOfNotNull(salvaged, filterNote, note).joinToString("; ").ifEmpty { "ok" }, AnswerChip.LLM)
            return Result.success(PlanResult(grounded, AnswerChip.LLM, lastRaw, ms))
        }
        if (template != null) {
            SLog.w(TAG, "LLM output unusable ($lastError); template ${template.id} → TEMPLATE")
            log(false, "$lastError; template ${template.id}", AnswerChip.TEMPLATE)
            return Result.success(PlanResult(template.set, AnswerChip.TEMPLATE, lastRaw, ms))
        }
        log(false, lastError, null)
        return Result.failure(IntentPlanFailed(REFUSAL))
    }

    companion object {
        const val TAG = "Planner"
        const val SLOW_MS = 3500L
        const val TEMPLATE_BUDGET_MS = 4500L
        const val REFUSAL = "could not understand the question"
    }
}

/**
 * Deterministic guard measured at Gate 1: for a two-part question ("Revenue per region … and how many reps per
 * region?") Qwen3-4B keeps the right intent and adds a second one over a column the question never names
 * (sum salary / sum commission, with a filter copied from a few-shot). The compiler would refuse that extra
 * intent and the node would answer with a refusal. So, when an answer has two or more intents, an intent
 * whose sum/avg/min/max column is not named in the question (column name or alias, whole words, plural-
 * insensitive) is dropped. A single intent is never touched; count intents are never touched.
 */
object Grounding {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Same intents regardless of the order of intents and filters (a filter's value compared as JSON). */
    fun sameIntents(a: IntentSet, b: IntentSet): Boolean {
        fun key(i: com.hivecore.core.Intent) = listOf(i.table, i.metric, i.column ?: "", i.groupBy.sorted().joinToString(","),
            i.filters.map { "${it.column}|${it.op}|${it.value}" }.sorted().joinToString(";")).joinToString("#")
        return a.notRelevant == b.notRelevant && a.intents.map(::key).sorted() == b.intents.map(::key).sorted()
    }

    fun pruneExtraIntents(set: IntentSet, question: String, schema: Schema): Pair<IntentSet, String?> {
        if (set.notRelevant || set.intents.size < 2) return set to null
        val words = tokens(question)
        val tables = schema.tables.associateBy { it.name }
        val kept = set.intents.filterIndexed { idx, i ->
            // Wave 4 (HR, two-part question): the measure of another department came back as an intent over a table
            // this node does not have ("table":"revenue"); with a valid intent beside it, it is dropped.
            if (i.table !in tables) return@filterIndexed false
            // Wave 2 (Sales, Q1): an extra intent filtering on an identifier column ("count where rep_id = 'rep'") is a
            // hallucination copied from the question's wording; the first intent is never dropped.
            if (idx > 0 && i.filters.any { f -> tables[i.table]?.columns?.firstOrNull { it.name == f.column }?.identifier == true }) return@filterIndexed false
            // Wave 3 (Sales, Q1 "... how many reps per region?"): an extra count over the same table, groups and filters
            // as an earlier intent repeats the `n` every grouped query already returns ("Sales by region: 593 ...").
            if (idx > 0 && i.metric == "count" && set.intents.take(idx).any { e ->
                    e.table == i.table && e.groupBy == i.groupBy && e.filters.toSet() == i.filters.toSet() }) return@filterIndexed false
            val col = i.column ?: return@filterIndexed true
            if (i.metric == "count") return@filterIndexed true
            val def = schema.tables.firstOrNull { it.name == i.table }?.columns?.firstOrNull { it.name == col } ?: return@filterIndexed true
            (listOf(def.name) + def.aliases).any { alias -> tokens(alias).let { it.isNotEmpty() && it.all { w -> w in words } } }
        }
        if (kept.isEmpty() || kept.size == set.intents.size) return set to null
        val dropped = set.intents - kept.toSet()
        return IntentSet(kept) to "dropped ${dropped.size} intent(s) not grounded in the question (unknown table, identifier filter, repeated count, or metric column not named): " +
            dropped.joinToString { "${it.metric} ${it.column}" }
    }

    /**
     * Wave 3 (HR, Q1): Qwen3-4B copied the few-shot's `region = South` into "Revenue per region this year, and how
     * many reps per region?", so HR answered "South 16". A text filter (`eq`/`neq`/`in` with letters in it) whose
     * value the question never mentions is dropped; a question word that starts with the value or the value with
     * the word ("northern" / "North") counts as a mention. Date-like values ("2026-01") and numbers are kept.
     */
    fun dropUngroundedFilters(set: IntentSet, question: String): Pair<IntentSet, String?> {
        if (set.notRelevant) return set to null
        val words = tokens(question).filter { it.length >= 3 }
        val dropped = ArrayList<String>()
        fun mentioned(v: String): Boolean {
            val parts = tokens(v).filter { it.any(Char::isLetter) }
            if (parts.isEmpty()) return true
            return parts.all { p -> words.any { w -> w.startsWith(p) || (p.length >= 3 && p.startsWith(w)) } }
        }
        val out = set.intents.map { i ->
            val kept = i.filters.filter { f ->
                if (f.op !in setOf("eq", "neq", "in")) return@filter true
                val values = when (val v = f.value) {
                    is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    is kotlinx.serialization.json.JsonPrimitive -> listOfNotNull(v.takeIf { it.isString }?.content)
                    else -> emptyList()
                }
                val ok = values.all { mentioned(it) }
                if (!ok) dropped += "${f.column} ${f.op} ${f.value}"
                ok
            }
            if (kept.size == i.filters.size) i else i.copy(filters = kept)
        }
        if (dropped.isEmpty()) return set to null
        return IntentSet(out) to "dropped filter(s) the question does not mention: ${dropped.joinToString()}"
    }

    /** lowercase words plus their singular forms, so "salaries" matches alias "salaries" and "salary" matches "salary". */
    fun tokens(s: String): Set<String> {
        val out = HashSet<String>()
        for (w in SPLIT.split(s.lowercase())) {
            if (w.isEmpty()) continue
            out += w
            if (w.endsWith("ies") && w.length > 4) out += w.dropLast(3) + "y"
            if (w.endsWith("es") && w.length > 4) out += w.dropLast(2)
            if (w.endsWith("s") && w.length > 3) out += w.dropLast(1)
        }
        return out
    }
}
