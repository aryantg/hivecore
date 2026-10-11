package com.hivecore.engine.decide

import com.hivecore.core.Answer
import com.hivecore.core.Canon
import com.hivecore.core.DecisionBuild
import com.hivecore.core.DecisionModel
import com.hivecore.core.DecisionRequest
import com.hivecore.core.DecisionResponse
import com.hivecore.core.Decided
import com.hivecore.core.MergeKind
import com.hivecore.core.OpRegistry
import com.hivecore.core.Question
import com.hivecore.core.SLog
import com.hivecore.engine.journal.DecisionRow
import com.hivecore.engine.split.Chunk
import com.hivecore.engine.split.SplitConstants
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitPlan
import com.hivecore.engine.split.Splitters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per-job budget for decisions (docs/plans/decision-chunking.md §3): LLM calls, and their share of the job's single-phone estimate. */
data class DecisionBudget(val maxLlmCalls: Int = 64, val maxShareOfJobMs: Double = 0.10, val callMsEstimate: Double = 1_000.0)

/** What a decision point produced: the rows to journal and the usage. */
class Outcome<T>(val value: T, val rows: List<DecisionRow>, val calls: Int)

/**
 * The decision points of the chunking stage (docs/plans/decision-chunking.md §5), all run on the orchestrator once per
 * job before dispatch (A, B) or on a settled chunk (E). Every call is deterministic for equal inputs, never touches an
 * input the orchestrator may not see (a private input of another owner), and falls back to null when the model is
 * unavailable, the budget is spent or the request fails: the caller then keeps the deterministic cut.
 */
class ChunkDecisions(
    private val model: DecisionModel,
    private val selfId: String,
    private val budget: DecisionBudget = DecisionBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** LLM calls spent per job id (the budget). */
    private val spent = HashMap<String, Int>()
    val notes = ArrayList<String>()

    fun build(): DecisionBuild? = model.build()

    /** The decider tag a decided job carries (`<sha256>:<calibration>+t<templates>`), null when the model is unavailable. */
    fun tag(): String? = model.build()?.let { Templates.tag(it.tag) }

    /** May the orchestrator decide over this input? Public, or private data it owns itself. */
    fun mayDecide(private: Boolean, owner: String): Boolean = !private || owner == selfId

    private fun remaining(jobId: String, plan: SplitPlan?): Int {
        val used = spent[jobId] ?: 0
        val byShare = plan?.let { (it.singlePhoneMs(it.owner) * budget.maxShareOfJobMs / budget.callMsEstimate).toInt() } ?: Int.MAX_VALUE
        return minOf(budget.maxLlmCalls, byShare) - used
    }

    private fun spend(jobId: String, calls: Int) { spent[jobId] = (spent[jobId] ?: 0) + calls }

    // ------------------------------------------------------------------ A

    /**
     * Decision point A: the semantic cut of a text job. Returns the job with `cuts` and `decider` set, or null when the
     * point does not apply (not a text input, STITCH / TREE only, overrides, private input of another owner, resumed
     * job, model unavailable, too short, over budget, failed request).
     */
    suspend fun decideCut(job: SplitJob, plan: SplitPlan): Outcome<SplitJob>? {
        val input = job.input as? SplitInput.Text ?: return null
        if (job.cuts != null || job.decider != null || job.chunks != null || job.unitsPerChunk != null) return null
        if (plan.kind != MergeKind.STITCH && plan.kind != MergeKind.TREE) return null
        if (!mayDecide(input.private, plan.owner)) return null
        val tag = tag() ?: return null
        val units = Splitters.sentenceUnits(input.doc, input.text)
        val n = units.size
        val (min, max, target) = if (plan.kind == MergeKind.TREE) {
            val t = plan.chunks.firstOrNull()?.units?.takeIf { plan.chunks.size > 1 } ?: SplitConstants.TREE_SENTENCES
            Triple(maxOf(1, t - TREE_SLACK), t, t)
        } else {
            val per = plan.chunks.maxOfOrNull { it.units } ?: n
            Triple(maxOf(1, (per + 1) / 2), per, per)
        }
        if (n <= max) return null
        val windows = (0 until n - 1).chunked(max)
        val calls = windows.size * RUNS_PER_GROUP
        if (calls > remaining(job.jobId, plan)) { note("${job.jobId}: boundary decisions over budget ($calls calls); fixed cut kept"); return null }
        val scores = DoubleArray(n)
        val rows = ArrayList<DecisionRow>()
        var used = 0
        for ((w, idx) in windows.withIndex()) {
            val from = idx.first(); val to = idx.last() + 1           // sentences from..to+... visible
            val state = (maxOf(0, from) .. minOf(n - 1, to + 1)).joinToString("\n") { i -> "s$i: " + text(units[i]) }
            val group = "w$w"
            val req = DecisionRequest(Templates.MODEL, state, idx.map { Templates.boundary(it, group) })
            val res = model.decide(req).getOrElse { note("${job.jobId}: boundary window $w failed: ${it.message}; fixed cut kept"); return null }
            used += res.usage.llmCalls
            for (i in idx) scores[i] = (res.answers["b$i"] as? Answer.Noul)?.probability ?: 0.0
            rows += rowsOf(job.jobId, Templates.BOUNDARY, req, res)
        }
        spend(job.jobId, used)
        val cuts = Cut.boundaries(n, min, max, target, score = { i -> scores[i] })
        note("${job.jobId}: ${cuts.size + 1} chunks by decided boundaries (sizes ${Cut.sizes(n, cuts)})")
        return Outcome(job.copy(cuts = cuts, decider = tag), rows, used)
    }

    // ------------------------------------------------------------------ B

    /**
     * Decision point B: difficulty per chunk → cost factor and replicas (`Chunk.difficulty`, `costFactor`, `replicas`),
     * the small / large build recorded in `Chunk.build`. Chunks of a private input of another owner are left as they are.
     */
    suspend fun decideDifficulty(job: SplitJob, plan: SplitPlan, smallBuild: String? = null, largeBuild: String? = null): Outcome<SplitPlan>? {
        if (model.build() == null) return null
        val candidates = plan.chunks.filter { mayDecide(it.private, plan.owner) }
        if (candidates.isEmpty()) return null
        val batches = candidates.chunked(BATCH)
        val calls = candidates.size * RUNS_PER_GROUP
        if (calls > remaining(job.jobId, plan)) { note("${job.jobId}: difficulty decisions over budget ($calls calls); registry replicas kept"); return null }
        val info = OpRegistry[job.op]
        val difficulty = HashMap<Int, Double>()
        val rows = ArrayList<DecisionRow>()
        var used = 0
        for (batch in batches) {
            // the batch shares one prompt: each chunk gets its share of BATCH_STATE_CHARS (phone finding: six 1,500-char
            // states overflowed the NPU model's prompt, "Input prompt too long", and every vote abstained)
            val per = maxOf(MIN_CHUNK_STATE_CHARS, BATCH_STATE_CHARS / batch.size)
            val state = batch.joinToString("\n\n") { c -> "chunk ${c.index}:\n" + chunkState(c, per) }
            val req = DecisionRequest(Templates.MODEL, state, batch.map { Templates.difficulty(it.index, job.op.name) })
            val res = model.decide(req).getOrElse { note("${job.jobId}: difficulty batch failed: ${it.message}; registry replicas kept"); return null }
            used += res.usage.llmCalls
            for (c in batch) (res.answers["d${c.index}"] as? Answer.Score)?.let { difficulty[c.index] = it.score }
            rows += rowsOf(job.jobId, Templates.DIFFICULTY, req, res)
        }
        spend(job.jobId, used)
        val chunks = plan.chunks.map { c ->
            val d = difficulty[c.index] ?: return@map c
            val replicas = when {
                c.private -> null                                                          // never raised: one phone can run it
                d >= HARD && plan.eligible.size >= 2 -> minOf(info.replicas + 1, SplitConstants.MAX_REPLICAS)
                d <= EASY -> info.minReplicas
                else -> null
            }
            val build = when { d >= HARD -> largeBuild; d <= EASY -> smallBuild; else -> null }
            c.copy(difficulty = d, costFactor = round2(0.75 + 0.5 * d), replicas = replicas, build = build)
        }
        return Outcome(plan.copy(chunks = chunks), rows, used)
    }

    // ------------------------------------------------------------------ E

    /**
     * Decision point E as a tie-break: among [candidates] (node → output, every one past the hash and schema checks) the
     * one the model finds most consistent, if its probability reaches [VERDICT_MIN]; null keeps the existing
     * verification-failure path. Never for a private input of another owner, never over budget.
     */
    suspend fun verdict(job: SplitJob, plan: SplitPlan, c: Chunk, candidates: List<Pair<String, JsonElement>>): Outcome<String?>? {
        if (model.build() == null || !mayDecide(c.private, plan.owner)) return null
        val calls = candidates.size * RUNS_PER_GROUP
        if (calls > remaining(job.jobId, plan)) { note("${job.jobId}: verdict over budget; no tie-break"); return null }
        val rows = ArrayList<DecisionRow>()
        var used = 0
        var best: Pair<String, Double>? = null
        for ((node, out) in candidates) {
            val req = DecisionRequest(Templates.MODEL, verdictState(plan, c, out), listOf(Templates.verdict(c.index, node)))
            val res = model.decide(req).getOrElse { note("${job.jobId}: verdict for chunk ${c.index} failed: ${it.message}"); return null }
            used += res.usage.llmCalls
            val p = (res.answers.values.firstOrNull() as? Answer.Noul)?.probability ?: 0.0
            rows += rowsOf(job.jobId, Templates.VERDICT, req, res)
            if (best == null || p > best.second) best = node to p
        }
        spend(job.jobId, used)
        val winner = best?.takeIf { it.second >= VERDICT_MIN }?.first
        note("${job.jobId}: chunk ${c.index} verdicts ${candidates.map { it.first.take(8) }} → ${best?.let { "${it.first.take(8)} p=${it.second}" } ?: "none"}" +
            (if (winner == null) " (below $VERDICT_MIN, no tie-break)" else ""))
        return Outcome(winner, rows, used)
    }

    /** Decision point E as an annotation: p(consistent) of an accepted output, within budget; null when not judged. */
    suspend fun annotate(job: SplitJob, plan: SplitPlan, c: Chunk, node: String, out: JsonElement): Outcome<Double>? {
        if (model.build() == null || !mayDecide(c.private, plan.owner)) return null
        if (RUNS_PER_GROUP > remaining(job.jobId, plan)) return null
        val req = DecisionRequest(Templates.MODEL, verdictState(plan, c, out), listOf(Templates.verdict(c.index, node)))
        val res = model.decide(req).getOrElse { return null }
        spend(job.jobId, res.usage.llmCalls)
        val p = (res.answers.values.firstOrNull() as? Answer.Noul)?.probability ?: return null
        return Outcome(p, rowsOf(job.jobId, Templates.VERDICT, req, res), res.usage.llmCalls)
    }

    // ------------------------------------------------------------------ helpers

    private fun chunkState(c: Chunk, max: Int = STATE_CHARS): String {
        val inline = Canon.canonical(c.params)
        val blobs = if (c.blobs.isEmpty()) "" else "\nitems: ${c.blobs.joinToString { it.take(12) }} (${c.bytes} bytes)"
        return inline.take(max) + blobs
    }

    private fun verdictState(plan: SplitPlan, c: Chunk, out: JsonElement): String {
        val schema = plan.job.params["schema"]?.let { "\nSchema:\n" + Canon.canonical(it).take(VERDICT_PART_CHARS) } ?: ""
        return "Input:\n" + Canon.canonical(plan.task(c).params).take(VERDICT_PART_CHARS) + "\nOutput:\n" + Canon.canonical(out).take(VERDICT_PART_CHARS) + schema
    }

    private fun text(unit: JsonObject): String = (unit["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    private fun note(s: String) { notes += s; SLog.d(TAG, s) }

    /** One journal row per question (the decided record of §7). */
    fun rowsOf(jobId: String, point: String, req: DecisionRequest, res: DecisionResponse): List<DecisionRow> {
        val build = model.build() ?: return emptyList()
        val requestHash = requestHash(req)
        val ts = clock()
        return req.questions.map { q ->
            val a = res.answers[q.id]
            val answer = a?.let { Canon.json.encodeToJsonElement(Answer.serializer(), it) } ?: buildJsonObject {}
            val probs: JsonElement = when (a) {
                is Answer.Noul -> buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(a.probability)) }
                is Answer.Choice -> buildJsonObject { a.probabilities.forEach { (k, v) -> put(k, v) } }
                is Answer.Score -> buildJsonArray { a.probabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                null -> buildJsonArray {}
            }
            DecisionRow(jobId, point, q.id, requestHash, answer, probs, build.sha256, Templates.tag(build.calibrationVersion), res.usage.llmCalls, ts)
        }
    }

    companion object {
        const val TAG = "Decide"
        /** The TREE band: `TREE_SENTENCES - TREE_SLACK .. TREE_SENTENCES`, phone-count independent. */
        const val TREE_SLACK = 10
        const val HARD = 0.6
        const val EASY = 0.3
        const val VERDICT_MIN = 0.7
        const val BATCH = 16
        const val STATE_CHARS = 1_500
        /** the whole difficulty batch's state: about 700 tokens, well inside the phone model's prompt */
        const val BATCH_STATE_CHARS = 2_400
        const val MIN_CHUNK_STATE_CHARS = 120
        /** each of a verdict's input, output and schema: the three stay near BATCH_STATE_CHARS together */
        const val VERDICT_PART_CHARS = 700
        /** LLM calls one grouped question costs in Phase 1 (k permuted runs). */
        const val RUNS_PER_GROUP = 3

        fun requestHash(req: DecisionRequest): String = Canon.sha256Hex(Canon.canonical(Canon.json.encodeToJsonElement(DecisionRequest.serializer(), req)))

        /** The journaled record of one response (its hash covers point, request hash, response and build). */
        fun decided(point: String, req: DecisionRequest, res: DecisionResponse, build: DecisionBuild): Decided {
            val body = Decided(point, requestHash(req), res, build, "")
            val hash = Canon.sha256Hex(Canon.canonical(Canon.json.encodeToJsonElement(Decided.serializer(), body)))
            return body.copy(hash = hash)
        }

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
