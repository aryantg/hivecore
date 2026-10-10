package com.hivecore.engine.split

import com.hivecore.core.SplitAxis
import com.hivecore.core.Mergeability
import com.hivecore.core.MergeKind
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.Profile
import com.hivecore.core.Recipe
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkloadSpec
import com.hivecore.engine.SchedulerImpl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A job to split (§4B): one op with pinned params over one input. [owner] holds the data (default: the commander);
 * private inputs never leave it. Sizing overrides: [chunks] (exact number of chunks), [unitsPerChunk], [unitMs]
 * (expected ms per unit when no BENCH p50 is known).
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
) {
    val opKey: String get() = op.name + "@" + (modelId ?: "")

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
    /** phones that may pull chunks (eligible for the op class, plus the commander) */
    val eligible: List<String>,
    /** expected ms per unit on each phone (BENCH p50, or the default) */
    val unitMs: Map<String, Double>,
    val defaultUnitMs: Double,
) {
    fun expectedMs(c: Chunk, node: String): Double = c.units * (unitMs[node] ?: defaultUnitMs)

    /** The map task of [c] (params = job params overlaid with the chunk's slice). */
    fun task(c: Chunk, id: String = "c${c.index}"): TaskSpec = TaskSpec(id, job.op, JsonObject(job.params + c.params),
        owner = if (c.private) owner else null, modelId = job.modelId, blobs = c.blobs)

    /** Expected time of the whole job on [node] alone (the speed-up baseline). */
    fun singlePhoneMs(node: String): Double = chunks.sumOf { expectedMs(it, node) }
}

/**
 * Swarm plan §4B.3 chunk sizing: chunks of about [targetMs] (20–40 s) of work on a median eligible phone, computed
 * from Profile.p50[modelId] (BENCH) or per-class defaults, and always at least 2 × eligible phones chunks (when the
 * input has that many units) so fast phones take more. TREE jobs use a fixed sentence count per chunk
 * (`params.chunkSentences`, default 40) so the tree, and therefore the summary, is the same on 2 or 3 phones.
 * Audio windows are capped at 28 s owned (+1 s overlap each side ≤ 30 s per Whisper call).
 */
class ChunkPlanner(private val targetMs: Double = 30_000.0) {

    fun plan(job: SplitJob, profiles: List<Profile>, self: String): Result<SplitPlan> = runCatching {
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
        val probe = TaskSpec("probe", job.op, job.params, modelId = job.modelId)
        val ps = SchedulerImpl.normalise(profiles)
        val eligible = (SchedulerImpl().candidates(probe, ps, self, emptySet()).map { it.nodeId } + self).distinct().sorted()
        val def = job.unitMs ?: defaultUnitMs(job.op, job.input)
        val unitMs = eligible.associateWith { n -> job.unitMs ?: job.modelId?.let { m -> ps.firstOrNull { it.nodeId == n }?.p50?.get(m) } ?: def }
        val median = unitMs.values.sorted().let { if (it.isEmpty()) def else it[it.size / 2] }
        val n = Splitters.units(job.input)
        require(n > 0) { "the input is empty" }
        val per = when {
            job.unitsPerChunk != null -> job.unitsPerChunk
            job.chunks != null -> ceilDiv(n, job.chunks.coerceIn(1, n))
            kind == MergeKind.TREE -> (job.params["chunkSentences"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_TREE_SENTENCES
            else -> {
                var p = Math.round(targetMs / median).toInt().coerceIn(1, n)
                val minChunks = 2 * eligible.size
                if (ceilDiv(n, p) < minChunks) p = maxOf(1, ceilDiv(n, minChunks))
                p
            }
        }.coerceAtLeast(1)
        val base = job.params
        val chunks = when (val inp = job.input) {
            is SplitInput.Items -> Splitters.items(job.opKey, base, inp, per)
            is SplitInput.Blobs -> Splitters.blobs(job.opKey, base, inp, per)
            is SplitInput.Text -> Splitters.text(job.opKey, base, inp, per, inp.overlap ?: if (kind == MergeKind.TREE) 0 else 1)
            is SplitInput.Audio -> Splitters.audio(job.opKey, base, inp, minOf(per, MAX_AUDIO_S) * 1000L)
            is SplitInput.Video -> Splitters.video(job.opKey, base, inp, per)
            is SplitInput.Image -> Splitters.image(job.opKey, base, inp, per)
            is SplitInput.Table -> Splitters.table(job.opKey, base, inp, per)
            is SplitInput.Seeds -> Splitters.seeds(job.opKey, base, inp, per)
            is SplitInput.FanOut -> Splitters.fanOut(job.opKey, base, inp)
        }
        val tmp = SplitPlan(job, axis, kind, chunks, Dag("", "", emptyList(), ""), owner, eligible, unitMs, def)
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
        const val DEFAULT_TREE_SENTENCES = 40
        const val MAX_AUDIO_S = 28

        fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** Expected ms per unit with no BENCH figure (§6.2 defaults, per unit of the input kind). */
        fun defaultUnitMs(op: Op, input: SplitInput): Double = when (input) {
            is SplitInput.Table -> 0.05                    // per row, SQL partial aggregate
            is SplitInput.Audio -> 100.0                   // per second of audio (Whisper-class ASR)
            is SplitInput.Image -> 50.0                    // per tile
            else -> when (OpClasses.of(op)) {
                OpClass.LLM -> 4_000.0
                OpClass.VLM -> 1_500.0
                OpClass.HEAVY -> SchedulerImpl.DEFAULT_HEAVY_P50_MS
                else -> SchedulerImpl.DEFAULT_P50_MS
            }
        }
    }
}
