package com.hivecore.engine.split

import com.hivecore.core.SplitAxis
import com.hivecore.core.Mergeability
import com.hivecore.core.MergeKind
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.OpRegistry
import com.hivecore.core.Profile
import com.hivecore.core.Recipe
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkloadSpec
import com.hivecore.engine.SchedulerImpl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A job to split (spec section 10.1): one op with pinned params over one input. [owner] holds the data (default: the
 * orchestrator); private inputs never leave it. Sizing overrides: [chunks] (exact number of chunks), [unitsPerChunk],
 * [unitMs] (expected ms per unit when no benchmark exists). [capability] names the catalog capability (default: the
 * registry's for the op) and [modelSha256] the model build placement resolved (section 10.3: part of every chunk id;
 * [modelId] stands in for it until placement ran). [replicas] pins the replicas per chunk (default: the registry's,
 * 2 for LLM and VLM ops, section 12.1).
 */
@Serializable data class SplitJob(
    val jobId: String,
    val op: Op,
    val params: JsonObject = JsonObject(emptyMap()),
    val modelId: String? = null,
    val input: SplitInput,
    val merge: MergeKind? = null,
    val owner: String? = null,
    val chunks: Int? = null,
    val unitsPerChunk: Int? = null,
    val unitMs: Double? = null,
    val capability: String? = null,
    val modelSha256: String? = null,
    val replicas: Int? = null,
) {
    /** `op@modelSha256` (section 10.3); the model id stands in until placement resolved the build. */
    val opKey: String get() = op.name + "@" + (modelSha256 ?: modelId ?: "")
    /** The capability the op needs (section 4.3), from the job or the op registry. */
    val cap: String? get() = capability ?: OpRegistry[op].capability

    companion object {
        /**
         * One split job per recipe stage over the workload's items (released items only when `release == "items"`;
         * otherwise every chunk stays on the owner: private mode).
         */
        fun forWorkload(spec: WorkloadSpec, recipe: Recipe): List<SplitJob> = recipe.stages.map { st ->
            SplitJob("${spec.jobId}/${st.id}", st.op, st.params, st.modelId,
                SplitInput.Blobs(spec.items, private = spec.items.map { spec.release != "items" }), owner = spec.owner)
        }
    }
}

/** The output of [ChunkPlanner.plan]: the chunks, their merge, the DAG (map tasks + merge task(s)) and the cost model used. */
data class SplitPlan(
    val job: SplitJob,
    val axis: SplitAxis,
    val kind: MergeKind,
    val chunks: List<Chunk>,
    val dag: Dag,
    /** the data holder: private chunks run only here */
    val owner: String,
    /** phones that may pull chunks (MODEL_READY for the capability, or eligible for the op class, plus the orchestrator) */
    val eligible: List<String>,
    /** expected ms per unit on each phone (job override, profile p50, catalog unitMsP50, or the default) */
    val unitMs: Map<String, Double>,
    val defaultUnitMs: Double,
    /** replicas per chunk (section 12.1): 2 for LLM / VLM, else 1 */
    val replicas: Int = 1,
    /** the sentence overlap the text splitter used (a re-cut text chunk is cut the same way) */
    val overlap: Int = 0,
) {
    fun expectedMs(c: Chunk, node: String): Double = c.units * (unitMs[node] ?: defaultUnitMs)

    /** The map task of [c] (params = job params overlaid with the chunk's slice). */
    fun task(c: Chunk, id: String = "c${c.index}"): TaskSpec = TaskSpec(id, job.op, JsonObject(job.params + c.params),
        owner = if (c.private) owner else null, modelId = job.modelId, blobs = c.blobs)

    /** Expected time of the whole job on [node] alone (the speed-up baseline). */
    fun singlePhoneMs(node: String): Double = chunks.sumOf { expectedMs(it, node) }

    /** Section 14: halves [c] once at a unit boundary; the children take indices from [firstIndex]. Null when it cannot be halved. */
    fun recut(c: Chunk, firstIndex: Int): List<Chunk>? = Splitters.recut(job.opKey, job.params, job.input, c, overlap, firstIndex)
}

/**
 * Spec section 10.5 chunk sizing: chunks of about [targetMs] (30 s) of work on a median eligible phone, and always at
 * least 2 × eligible phones chunks (when the input has that many units) so fast phones take more.
 *
 *  - Eligible phones = the caller's `ready` set (phones with MODEL_READY for the op's capability, from placement)
 *    plus the orchestrator; with no ready set (no placement ran) the profile view: phones eligible for the op class
 *    plus the orchestrator.
 *  - Expected ms per unit per phone = `job.unitMs`, else that phone's profile `p50` for the model (or capability),
 *    else the catalog `unitMsP50` through `unitMsOf(nodeId, capability)`, else the default by kind (section 20.2).
 *    The median across eligible phones drives sizing.
 *  - TREE uses a fixed 40 sentences per chunk so the tree, and therefore the summary, is identical on any phone
 *    count; audio windows are capped at 28 s owned (+1 s overlap each side).
 */
class ChunkPlanner(private val targetMs: Double = SplitConstants.TARGET_CHUNK_MS) {

    fun plan(
        job: SplitJob,
        profiles: List<Profile>,
        self: String,
        ready: Set<String>? = null,
        unitMsOf: (nodeId: String, capability: String) -> Double? = { _, _ -> null },
    ): Result<SplitPlan> = runCatching {
        val axis = when (job.input) {
            is SplitInput.Items, is SplitInput.Blobs -> SplitAxis.SHARD
            is SplitInput.Seeds, is SplitInput.FanOut -> SplitAxis.FANOUT
            else -> SplitAxis.CHUNK
        }
        val kind = job.merge ?: when (axis) {
            SplitAxis.SHARD -> Mergeability.of(job.op)
            SplitAxis.CHUNK -> Mergeability.chunkKind(job.op)
            SplitAxis.FANOUT -> Mergeability.fanOutKind(job.op)
        } ?: throw IllegalArgumentException("${job.op} cannot be split along the ${axis.name.lowercase()} axis")
        val owner = job.owner ?: self
        val ps = SchedulerImpl.normalise(profiles)
        val cap = job.cap
        val byProfile = { SchedulerImpl().candidates(TaskSpec("probe", job.op, job.params, modelId = job.modelId), ps, self, emptySet()).map { it.nodeId } }
        val eligible = ((ready?.toList() ?: byProfile()) + self).distinct().sorted()
        val def = job.unitMs ?: defaultUnitMs(job.op, job.input)
        val unitMs = eligible.associateWith { n ->
            val p = ps.firstOrNull { it.nodeId == n }
            job.unitMs
                ?: job.modelId?.let { m -> p?.p50?.get(m) }
                ?: cap?.let { c -> p?.p50?.get(c) ?: unitMsOf(n, c) }
                ?: def
        }
        val median = unitMs.values.sorted().let { if (it.isEmpty()) def else it[it.size / 2] }
        val n = Splitters.units(job.input)
        require(n > 0) { "the input is empty" }
        val per = when {
            job.unitsPerChunk != null -> job.unitsPerChunk
            job.chunks != null -> ceilDiv(n, job.chunks.coerceIn(1, n))
            kind == MergeKind.TREE -> (job.params["chunkSentences"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_TREE_SENTENCES
            else -> {
                var p = Math.round(targetMs / median).toInt().coerceIn(1, n)
                val minChunks = SplitConstants.MIN_CHUNKS_PER_PHONE * eligible.size
                if (ceilDiv(n, p) < minChunks) p = maxOf(1, ceilDiv(n, minChunks))
                p
            }
        }.coerceAtLeast(1)
        val base = job.params
        val overlap = (job.input as? SplitInput.Text)?.let { it.overlap ?: if (kind == MergeKind.TREE) 0 else 1 } ?: 0
        val chunks = when (val inp = job.input) {
            is SplitInput.Items -> Splitters.items(job.opKey, base, inp, per)
            is SplitInput.Blobs -> Splitters.blobs(job.opKey, base, inp, per)
            is SplitInput.Text -> Splitters.text(job.opKey, base, inp, per, overlap)
            is SplitInput.Audio -> Splitters.audio(job.opKey, base, inp, minOf(per, MAX_AUDIO_S) * 1000L)
            is SplitInput.Video -> Splitters.video(job.opKey, base, inp, per)
            is SplitInput.Image -> Splitters.image(job.opKey, base, inp, per)
            is SplitInput.Table -> Splitters.table(job.opKey, base, inp, per)
            is SplitInput.Seeds -> Splitters.seeds(job.opKey, base, inp, per)
            is SplitInput.FanOut -> Splitters.fanOut(job.opKey, base, inp)
        }
        val replicas = (job.replicas ?: OpRegistry[job.op].replicas).coerceIn(1, SplitConstants.MAX_REPLICAS)
        val tmp = SplitPlan(job, axis, kind, chunks, Dag("", "", emptyList(), ""), owner, eligible, unitMs, def, replicas, overlap)
        tmp.copy(dag = dag(tmp))
    }

    /** Map tasks c0..cN-1 plus the merge: one `merge` task, or for TREE a pairwise tree m<level>_<j>. */
    private fun dag(p: SplitPlan): Dag {
        val tasks = ArrayList<TaskSpec>()
        p.chunks.forEach { tasks += p.task(it) }
        val mergeParams = JsonObject(p.job.params + ("merge" to JsonPrimitive(p.kind.name)))
        var output: String
        if (p.kind == MergeKind.TREE && p.chunks.size > 1) {
            var level = p.chunks.map { "c${it.index}" }
            var l = 1
            while (level.size > 1) {
                val next = ArrayList<String>()
                for (i in level.indices step 2) {
                    if (i + 1 >= level.size) { next += level[i]; continue }
                    val id = "m${l}_${i / 2}"
                    tasks += TaskSpec(id, p.job.op, mergeParams, inputs = listOf(level[i], level[i + 1]))
                    next += id
                }
                level = next; l++
            }
            output = level.single()
        } else {
            tasks += TaskSpec("merge", p.job.op, mergeParams, inputs = p.chunks.map { "c${it.index}" })
            output = "merge"
        }
        return Dag("split:${p.kind.name.lowercase()}", PATTERNS_VERSION, tasks, output)
    }

    companion object {
        const val PATTERNS_VERSION = "split-1"
        const val DEFAULT_TREE_SENTENCES = SplitConstants.TREE_SENTENCES
        const val MAX_AUDIO_S = SplitConstants.MAX_AUDIO_S

        fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** Expected ms per unit with no benchmark or catalog figure (section 20.2 defaults, per unit of the input kind). */
        fun defaultUnitMs(op: Op, input: SplitInput): Double = when (input) {
            is SplitInput.Table -> SplitConstants.UNIT_MS_ROW
            is SplitInput.Audio -> SplitConstants.UNIT_MS_AUDIO_S
            is SplitInput.Image -> SplitConstants.UNIT_MS_TILE
            else -> when (OpClasses.of(op)) {
                OpClass.LLM -> SplitConstants.UNIT_MS_LLM
                OpClass.VLM -> SplitConstants.UNIT_MS_VLM
                OpClass.HEAVY -> SplitConstants.UNIT_MS_HEAVY
                else -> SplitConstants.UNIT_MS_OTHER
            }
        }
    }
}
