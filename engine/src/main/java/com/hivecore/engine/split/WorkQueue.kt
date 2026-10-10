package com.hivecore.engine.split

import com.hivecore.core.Comparator
import com.hivecore.engine.SchedulerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class ChunkStatus { QUEUED, RUNNING, DUPLICATED, DONE, FAILED }

/** One cell of the chunk grid (§4B.4). [nodes] = phones running it now (or the one that finished it). */
@Serializable data class ChunkCell(
    val index: Int,
    val chunkId: String,
    val units: Int,
    val status: ChunkStatus,
    val nodes: List<String> = emptyList(),
    val doneBy: String? = null,
    val attempts: Int = 0,
    val duplicated: Boolean = false,
    val ms: Long = 0,
    val memo: Boolean = false,
    /** human label of the chunk's slice ("items 41–60", "audio 00:30–01:00", "tiles 1–4", "seed 7") */
    val label: String = "",
)

/** Per-phone throughput and utilisation (§4B.4). */
@Serializable data class NodeStat(val node: String, val chunks: Int, val units: Int, val busyMs: Long, val unitsPerMin: Double, val utilisation: Double)

/** The job view: chunk grid, per-phone throughput, bytes moved, speed-up versus one phone, and finally the result. */
@Serializable data class SplitProgress(
    val jobId: String,
    val op: String,
    val kind: String,
    val axis: String,
    val chunks: List<ChunkCell>,
    val nodes: List<NodeStat>,
    val bytesMoved: Long,
    val elapsedMs: Long,
    /** BENCH/default estimate of the whole job on the commander alone */
    val singlePhoneMs: Double,
    /** singlePhoneMs / elapsedMs */
    val speedup: Double,
    /** Σ busy ms of every phone / elapsedMs (how many phones were working on average) */
    val parallelism: Double,
    val duplicates: Int,
    val mismatches: Int,
    val reassigned: Int,
    val done: Boolean,
    val result: JsonElement? = null,
    val resultHash: String? = null,
    val error: String? = null,
    val notes: List<String> = emptyList(),
) {
    val doneChunks: Int get() = chunks.count { it.status == ChunkStatus.DONE }
}

/**
 * Swarm plan §4B.3 pull queue, as a pure state machine (the [SplitCommander] does the messaging):
 *  - an ordered chunk queue; each phone holds [slots] chunks (1 running + 1 prefetched) and gets the next one as soon
 *    as it reports a result, so faster phones do more chunks without any prediction (work stealing);
 *  - locality: private chunks only on the owner; the owner takes chunks whose items it holds first; warm phones are
 *    served first; a remote phone skips a chunk when `transfer + remote time > owner's queue time`;
 *  - stragglers: when a phone is idle and nothing is left for it, a chunk running longer than [stragglerFactor] ×
 *    its expected time elsewhere is duplicated on it; the first result wins, the second is compared ([Comparator]);
 *  - faults (timeout, yield, disconnect) put the chunk back at the front of the queue; finished chunks are kept, so a
 *    lost phone costs only its in-flight chunks; results are idempotent by (chunk, attempt).
 */
class WorkQueue(
    val plan: SplitPlan,
    nodes: List<String>,
    private val clock: () -> Long,
    private val comparator: Comparator? = null,
    val slots: Int = 2,
    val stragglerFactor: Double = 1.5,
    val minDeadlineMs: Long = 4_000L,
    val deadlineFactor: Double = 3.0,
    val maxAttempts: Int = 6,
    private val warm: (String) -> Boolean = { false },
    private val linkKbps: (String) -> Double = { SchedulerImpl.DEFAULT_LINK_KBPS },
) {
    class Attempt(val chunk: Int, val node: String, val no: Int, val duplicate: Boolean, val dispatchedAt: Long, val expectedDoneAt: Long, val deadlineAt: Long)

    data class Assignment(val chunk: Chunk, val node: String, val attempt: Int, val duplicate: Boolean, val deadlineMs: Long) {
        val taskId: String get() = taskId(chunk.index, attempt)
    }

    sealed interface Outcome {
        /** First result for the chunk; [others] are attempts still running it (duplicates). */
        data class Accepted(val others: List<String>) : Outcome
        /** A later result for a finished chunk, compared with the winner. */
        data class Compared(val agree: Boolean) : Outcome
        /** No such live attempt (late, cancelled or unknown). */
        data object Stale : Outcome
    }

    private val chunks = plan.chunks
    private val start = clock()
    private val queue = ArrayDeque<Int>(chunks.indices.toList())
    private val active = LinkedHashSet<String>(nodes)
    private val live = LinkedHashMap<String, MutableList<Attempt>>()
    private val outputs = HashMap<Int, JsonElement>()
    private val doneBy = HashMap<Int, String>()
    private val doneMs = HashMap<Int, Long>()
    private val memo = HashSet<Int>()
    private val attempts = IntArray(chunks.size)
    private val duplicated = BooleanArray(chunks.size)
    private val failed = HashMap<Int, String>()
    private val busy = HashMap<String, Long>()
    private val doneChunks = HashMap<String, Int>()
    private val doneUnits = HashMap<String, Int>()
    private var bytes = 0L
    private var duplicates = 0
    private var mismatches = 0
    private var reassigned = 0
    val notes = ArrayList<String>()

    private val state = MutableStateFlow(snapshot())
    val progress: StateFlow<SplitProgress> = state

    val done: Boolean get() = outputs.size == chunks.size
    /** Non-null when the job cannot finish (a chunk failed too often, or its private owner is gone). */
    val error: String? get() = failed.entries.minByOrNull { it.key }?.let { (i, why) -> "chunk $i: $why" }
    val nodes: Set<String> get() = active
    fun output(i: Int): JsonElement? = outputs[i]
    /** Outputs in chunk-index order (only when [done]). */
    fun outputsInOrder(): List<JsonElement> = chunks.indices.map { outputs.getValue(it) }

    fun addBytes(n: Long) { bytes += n }

    /** A result already held (memo by chunkId): the chunk is done without running. */
    fun prefill(index: Int, output: JsonElement) {
        if (outputs.containsKey(index)) return
        outputs[index] = output; doneBy[index] = "memo"; memo += index; queue.remove(index)
    }

    fun addNode(node: String) { active += node }

    /** The phone left (disconnect, repeated timeouts): its in-flight chunks go back to the front of the queue. */
    fun removeNode(node: String, why: String = "disconnected"): List<Attempt> {
        if (!active.remove(node)) return emptyList()
        val lost = live.remove(node).orEmpty().toList()
        for (a in lost.sortedByDescending { it.chunk }) requeue(a, why)
        if (node == plan.owner) for (i in chunks.indices) if (chunks[i].private && i !in outputs) failed.putIfAbsent(i, "its private data is on a phone that left")
        return lost
    }

    // ------------------------------------------------------------------ pull

    /** Fills every phone up to [slots] (warm first, then the least loaded, then by id) and duplicates stragglers. */
    fun fill(): List<Assignment> {
        val out = ArrayList<Assignment>()
        var progressed = true
        while (progressed) {
            progressed = false
            val order = active.sortedWith(compareByDescending<String> { warm(it) }.thenBy { live[it]?.size ?: 0 }.thenBy { it })
            for (n in order) {
                if ((live[n]?.size ?: 0) >= slots) continue
                val i = pick(n) ?: continue
                queue.remove(i)
                out += assign(i, n, duplicate = false)
                progressed = true
            }
        }
        val now = clock()
        for (n in active.sorted()) {
            if (live[n].orEmpty().isNotEmpty() || pick(n) != null) continue
            val cand = live.values.flatten().filter { a ->
                a.node != n && a.chunk !in outputs && !duplicated[a.chunk] &&
                    (!chunks[a.chunk].private || n == plan.owner) &&
                    live.values.sumOf { l -> l.count { it.chunk == a.chunk } } == 1 && now >= straggleAt(a)
            }.maxWithOrNull(compareBy<Attempt> { now - straggleAt(it) }.thenByDescending { it.chunk }) ?: continue
            duplicated[cand.chunk] = true
            duplicates++
            notes += "chunk ${cand.chunk} duplicated on ${n.take(8)} (running ${now - cand.dispatchedAt} ms on ${cand.node.take(8)})"
            out += assign(cand.chunk, n, duplicate = true)
        }
        if (out.isNotEmpty()) publish()
        return out
    }

    private fun straggleAt(a: Attempt): Long = a.dispatchedAt + (stragglerFactor * (a.expectedDoneAt - a.dispatchedAt)).toLong()

    /** The next chunk [node] may take, by locality, privacy and the transfer rule; null = none. */
    private fun pick(node: String): Int? {
        val allowed = queue.filter { !chunks[it].private || node == plan.owner }
        if (allowed.isEmpty()) return null
        if (node == plan.owner) allowed.firstOrNull { chunks[it].private || chunks[it].blobs.isNotEmpty() }?.let { return it }
        for (i in allowed) {
            val c = chunks[i]
            // Blobs (images, audio) are the costly transfers; inline text, prompts and ids always move.
            if (node != plan.owner && c.blobs.isNotEmpty() && c.bytes > 0 && plan.owner in active) {
                val transfer = c.bytes * 8.0 / linkKbps(node)
                val remote = backlog(node) + plan.expectedMs(c, node)
                val local = backlog(plan.owner) + plan.expectedMs(c, plan.owner)
                if (transfer + remote > local) continue
            }
            return i
        }
        return null
    }

    private fun backlog(node: String): Double {
        val now = clock()
        return live[node].orEmpty().sumOf { maxOf(0L, it.expectedDoneAt - now).toDouble() }
    }

    private fun assign(i: Int, node: String, duplicate: Boolean): Assignment {
        val now = clock()
        val expected = backlog(node) + plan.expectedMs(chunks[i], node)
        val deadline = maxOf(minDeadlineMs, (deadlineFactor * expected).toLong())
        val no = ++attempts[i]
        val a = Attempt(i, node, no, duplicate, now, now + expected.toLong(), now + deadline)
        live.getOrPut(node) { ArrayList() } += a
        return Assignment(chunks[i], node, no, duplicate, deadline)
    }

    // ------------------------------------------------------------------ results and faults

    fun onResult(node: String, index: Int, attempt: Int, output: JsonElement, ms: Long): Outcome {
        val a = take(node, index, attempt) ?: return Outcome.Stale
        busy[node] = (busy[node] ?: 0L) + ms
        doneChunks[node] = (doneChunks[node] ?: 0) + 1
        doneUnits[node] = (doneUnits[node] ?: 0) + chunks[index].units
        val held = outputs[index]
        val r = if (held == null) {
            outputs[index] = output; doneBy[index] = node; doneMs[index] = ms
            failed.remove(index)
            Outcome.Accepted(live.values.flatten().filter { it.chunk == index }.map { it.node })
        } else {
            val agree = comparator?.agree(plan.job.op, held, output, null) ?: (com.hivecore.core.Canon.equal(held, output))
            if (!agree) { mismatches++; notes += "chunk $index: ${node.take(8)} disagrees with ${doneBy[index]?.take(8)}" }
            Outcome.Compared(agree)
        }
        if (a.duplicate && held == null) notes += "chunk $index: the duplicate on ${node.take(8)} finished first"
        publish()
        return r
    }

    /** A timeout, yield or error: the attempt ends; the chunk goes back to the front unless another attempt runs it. */
    fun onFault(node: String, index: Int, attempt: Int, why: String): Boolean {
        val a = take(node, index, attempt) ?: return false
        requeue(a, why)
        publish()
        return true
    }

    private fun requeue(a: Attempt, why: String) {
        val i = a.chunk
        if (i in outputs) return
        if (live.values.any { l -> l.any { it.chunk == i } }) return
        if (attempts[i] >= maxAttempts) { failed[i] = "failed $maxAttempts times (last: $why)"; return }
        if (i !in queue) queue.addFirst(i)
        reassigned++
        notes += "chunk $i back in the queue: ${a.node.take(8)} $why"
    }

    private fun take(node: String, index: Int, attempt: Int): Attempt? {
        val l = live[node] ?: return null
        val a = l.firstOrNull { it.chunk == index && it.no == attempt } ?: return null
        l.remove(a)
        return a
    }

    /** Attempts past their deadline now. */
    fun overdue(): List<Attempt> { val now = clock(); return live.values.flatten().filter { it.deadlineAt <= now } }

    /** Every attempt still running (to cancel when the job ends). */
    fun running(): List<Attempt> = live.values.flatten()

    /** When the commander must wake up next: the earliest deadline or straggler threshold (null = only on messages). */
    fun nextWake(): Long? {
        val all = live.values.flatten()
        val deadlines = all.map { it.deadlineAt }
        val straggle = if (queue.isEmpty() && all.isNotEmpty()) all.filter { it.chunk !in outputs && !duplicated[it.chunk] }.map { straggleAt(it) } else emptyList()
        val now = clock()
        return (deadlines + straggle.filter { it > now }).minOrNull()
    }

    // ------------------------------------------------------------------ progress

    fun publish() { state.value = snapshot() }

    fun snapshot(): SplitProgress {
        val now = clock()
        val elapsed = maxOf(1L, now - start)
        val cells = chunks.indices.map { i ->
            val running = live.values.flatten().filter { it.chunk == i }.map { it.node }
            val status = when {
                i in outputs -> ChunkStatus.DONE
                i in failed -> ChunkStatus.FAILED
                running.size > 1 -> ChunkStatus.DUPLICATED
                running.size == 1 -> ChunkStatus.RUNNING
                else -> ChunkStatus.QUEUED
            }
            ChunkCell(i, chunks[i].chunkId, chunks[i].units, status, if (i in outputs) listOfNotNull(doneBy[i]) else running,
                doneBy[i], attempts[i], duplicated[i], doneMs[i] ?: 0L, i in memo, Splitters.label(plan.job.input, chunks[i]))
        }
        val ns = (active + busy.keys).sorted().map { n ->
            val b = busy[n] ?: 0L
            NodeStat(n, doneChunks[n] ?: 0, doneUnits[n] ?: 0, b,
                round2((doneUnits[n] ?: 0) * 60_000.0 / elapsed), round2(minOf(1.0, b.toDouble() / elapsed)))
        }
        val single = plan.singlePhoneMs(plan.owner)
        return SplitProgress(plan.job.jobId, plan.job.op.name, plan.kind.name, plan.axis.name, cells, ns, bytes, now - start,
            round2(single), round2(single / elapsed), round2(busy.values.sum().toDouble() / elapsed), duplicates, mismatches, reassigned,
            done = false, error = error, notes = notes.toList())
    }

    companion object {
        fun taskId(index: Int, attempt: Int): String = "c$index.a$attempt"

        /** (chunk index, attempt) from a task id made by [taskId]; null for anything else. */
        fun parseTaskId(id: String): Pair<Int, Int>? {
            val m = Regex("^c(\\d+)\\.a(\\d+)$").matchEntire(id) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
