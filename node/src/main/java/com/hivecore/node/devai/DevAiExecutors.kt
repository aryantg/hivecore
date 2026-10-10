package com.hivecore.node.devai

import com.hivecore.core.AskOutcome
import com.hivecore.core.Catalog
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.core.Canon
import com.hivecore.node.textai.GenText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Swarm plan §4A.3 "AI development" executors: EVAL, BENCH. [ops] lists only what the
 * given dependencies allow. Every output is `{op, items:[{item, …}], totals:{…}}` with items in shard order and
 * totals as counts/sums, so the splitter merges shards with CONCAT (items) + MONOID sum (totals) (§4B.2).
 *
 * Params:
 *  - EVAL: `cases` (or `items`): [EvalCase JSON] — the shard; or an input carrying `cases`.
 *  - BENCH: `models` (or `items`): [modelId], else `task.modelId`; `runs` (10); `graph` (optional file stem).
 *  - SYNTH_GEN: `items` (candidate indices) or `n` + `offset`; `catalog` (Catalog JSON) else the local catalog; `maxTokens`.
 *  - SYNTH_VALIDATE: `candidates` (or `items`), else the inputs' `items` (SYNTH_GEN outputs).
 */
class DevAiExecutor(
    /** The node's own question pipeline; see [Evaluator.asking] for the Asker adapter. */
    private val ask: (suspend (String) -> AskOutcome)? = null,
    private val bench: Bench? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : OpExecutor {

    override val ops: Set<Op> = buildSet {
        if (ask != null) add(Op.EVAL)
        if (bench != null) add(Op.BENCH)
    }

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = try {
        require(task.op in ops) { "DevAiExecutor cannot run ${task.op} here" }
        val p = task.params
        Result.success(when (task.op) {
            Op.EVAL -> Evaluator(ask!!, clock).run(Evaluator.casesOf(p, inputs))
            Op.BENCH -> {
                val models = ((p["models"] ?: p["items"]) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: listOfNotNull(task.modelId ?: (p["modelId"] as? JsonPrimitive)?.contentOrNull)
                require(models.isNotEmpty()) { "BENCH needs models or a modelId" }
                bench!!.run(models, (p["runs"] as? JsonPrimitive)?.intOrNull ?: Bench.DEFAULT_RUNS, (p["graph"] as? JsonPrimitive)?.contentOrNull)
            }
            else -> error("unreachable")
        })
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
