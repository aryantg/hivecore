package com.hivecore.engine.split

import com.hivecore.core.Canon
import com.hivecore.core.Comparator
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.MergeKind
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class ChunkStatus { QUEUED, RUNNING, DUPLICATED, DONE, FAILED }

/** One cell of the chunk grid (spec section 17 Jobs). [nodes] = phones running it now (or the one that finished it). */
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
    /** section 12.1: true = two replicas agreed, false = only one phone was available ("not verified"), null = not a replicated op */
    val verified: Boolean? = null,
    /** section 14: this chunk was halved into two child chunks */
    val recut: Boolean = false,
    /** the parent chunk this child was cut from */
    val recutOf: Int? = null,
    /** section 15.3: the cloud slug that answered this chunk after a verification failure */
    val cloud: String? = null,
)

/** Per-phone throughput and utilisation. [link] = how the phone is reached (section 5.6), null for this phone. */
@Serializable data class NodeStat(val node: String, val chunks: Int, val units: Int, val busyMs: Long, val unitsPerMin: Double, val utilisation: Double,
                                  val link: String? = null)

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
    /** estimate of the whole job on the orchestrator alone */
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
    /** section 14: the job ended with some chunks failed; [partialReason] says which and why in plain words */
    val partial: Boolean = false,
    val partialReason: String? = null,
    /** chunks answered by one phone only ("not verified", never memoized) */
    val unverified: Int = 0,
    /** chunks halved by the adaptive re-cut */
    val recuts: Int = 0,
    /** cloud slugs that answered a chunk or the step (section 15.3 badge) */
    val cloud: List<String> = emptyList(),
    /** the job is journaled and can be resumed after a restart (section 13.1) */
    val resumable: Boolean = false,
) {
    val doneChunks: Int get() = chunks.count { it.status == ChunkStatus.DONE }
}

/**
 * Spec section 11 pull queue, as a pure state machine (the [SplitCommander] does the messaging):
 *  - an ordered chunk queue; each phone holds [slots] chunks (1 running + 1 prefetched) and gets the next one as soon
 *    as it reports a result, so faster phones do more chunks without any prediction;
 *  - fill order (11.2): warm first, then LAN before WAN, then fewest live attempts, then nodeId;
 *  - locality and links: private chunks only on the owner; the owner takes chunks whose items it holds first; a remote
 *    phone skips a blob-carrying chunk when `transfer + remote > local` with `transfer = bytes·8/kbps + 2·rtt`;
 *    HEAVY and blob-carrying chunks never go over Bluetooth or the relay; metered phones get no blobs unless allowed;
 *  - deadlines (11.2 step 3): `max(4 s, 3 × expected + 2 × rtt)`;
 *  - replicas (12.1): LLM / VLM chunks run on two phones at once and must agree; a disagreement brings a third phone;
 *    no agreeing pair among three is a verification failure the commander resolves; a chunk only one phone could
 *    run is accepted "not verified";
 *  - stragglers (11.2 step 5): once the queue is empty, an idle phone (LAN preferred) duplicates the most overdue
 *    running attempt; the first result wins, the second is compared;
 *  - faults (14): timeout, error, yield, disconnect put the chunk back at the front; a chunk that failed on two
 *    different phones is re-cut once into two children whose outputs fill the parent's slot; after [maxAttempts]
 *    the chunk fails and the job ends partial; hash mismatches and missed deadlines are counted per phone and remove
 *    it at three; a lost owner fails every unfinished private chunk.
 * Every job [finished]es: done, or partial with [partialReason].
 */
class WorkQueue(
    val plan: SplitPlan,
    nodes: List<String>,
    private val clock: () -> Long,
    private val comparator: Comparator? = null,
    val slots: Int = SplitConstants.SLOTS,
    val stragglerFactor: Double = SplitConstants.STRAGGLER_FACTOR,
    val minDeadlineMs: Long = SplitConstants.MIN_DEADLINE_MS,
    val deadlineFactor: Double = SplitConstants.DEADLINE_FACTOR,
    val maxAttempts: Int = SplitConstants.MAX_ATTEMPTS,
    private val warm: (String) -> Boolean = { false },
    /** the measured link to each phone (section 5.6); the owner / orchestrator is reached in-process */
    private val linkOf: (String) -> Link = { Link.UNKNOWN },
    /** section 5.6 metered policy: blobs over mobile data only when the swarm allows it */
    private val allowMobileData: Boolean = false,
    /** replicas per chunk (section 12.1) */
    val replicas: Int = plan.replicas,
    /** section 14 adaptive re-cut on (exact merge kinds only) */
    private val recutEnabled: Boolean = true,
) {
    class Attempt(val chunk: Int, val node: String, val no: Int, val duplicate: Boolean, val dispatchedAt: Long, val expectedDoneAt: Long, val deadlineAt: Long)

    data class Assignment(val chunk: Chunk, val node: String, val attempt: Int, val duplicate: Boolean, val deadlineMs: Long) {
        val taskId: String get() = taskId(chunk.index, attempt)
    }

    sealed interface Outcome {
        /** The chunk is settled by this result; [others] are attempts still running it (duplicates / replicas), [verified] as in [ChunkCell.verified]. */
        data class Accepted(val others: List<String>, val verified: Boolean? = null) : Outcome
        /** A later result for a settled chunk, compared with the winner. */
        data class Compared(val agree: Boolean) : Outcome
        /** The first replica; the chunk waits for the second (or a third after a disagreement). */
        data object AwaitingReplica : Outcome
        /** No such live attempt (late, cancelled or unknown). */
        data object Stale : Outcome
    }

    /** A chunk whose replicas (or schema checks) failed verification; the commander escalates or fails it (section 12.1). */
    data class VerificationFailure(val index: Int, val reason: String, val attempts: Int, val outputs: List<JsonElement>)

    private class Held(val node: String, val output: JsonElement, val ms: Long)

    private val chunks = ArrayList(plan.chunks)                  // grows by two per re-cut
    private val start = clock()
    private val queue = ArrayDeque<Int>(chunks.indices.toList())
    private val active = LinkedHashSet<String>(nodes)
    private val live = LinkedHashMap<String, MutableList<Attempt>>()
    private val outputs = HashMap<Int, JsonElement>()
    private val doneBy = HashMap<Int, String>()
    private val doneMs = HashMap<Int, Long>()
    private val memo = HashSet<Int>()
    private val verified = HashMap<Int, Boolean>()
    private val cloudBy = HashMap<Int, String>()
    private val held = HashMap<Int, MutableList<Held>>()
    private val runsWanted = HashMap<Int, Int>()
    private val ranOn = HashMap<Int, MutableSet<String>>()         // phones with a live or held attempt (replica distinctness)
    private val excluded = HashMap<Int, MutableSet<String>>()      // phones that failed the chunk (timeout, error, mismatch)
    private val failedOn = HashMap<Int, MutableSet<String>>()      // phones whose attempt failed, for the re-cut rule
    private val schemaFails = HashMap<Int, MutableList<String>>()
    private val attempts = HashMap<Int, Int>()
    private val duplicated = HashSet<Int>()
    private val failed = LinkedHashMap<Int, String>()
    private val children = HashMap<Int, List<Int>>()
    private val verifyFailed = LinkedHashMap<Int, VerificationFailure>()
    private val reportedFailures = HashSet<Int>()
    private val assembledNew = ArrayList<Int>()
    private val busy = HashMap<String, Long>()
    private val doneChunks = HashMap<String, Int>()
    private val doneUnits = HashMap<String, Int>()
    private val misses = HashMap<String, Int>()
    private val hashMismatches = HashMap<String, Int>()
    private val pausedUntil = HashMap<String, Long>()
    private var bytes = 0L
    private var duplicates = 0
    private var mismatches = 0
    private var reassigned = 0
    private var recuts = 0
    val notes = ArrayList<String>()

    private val state = MutableStateFlow(snapshot())
    val progress: StateFlow<SplitProgress> = state

    private val heavy = OpClasses.of(plan.job.op) == OpClass.HEAVY
    private val originals = plan.chunks.indices

    /** Every original chunk has an output: the merge can run. */
    val done: Boolean get() = originals.all { it in outputs }
    /** Every original chunk is done or failed (section 14: the job ends done or partial, never hangs). */
    val finished: Boolean get() = originals.all { it in outputs || it in failed }
    /** True when some chunk failed for good (the job will end partial). */
    val partial: Boolean get() = failed.keys.any { it in originals }
    /** The first failure reason (null while nothing failed). */
    val error: String? get() = failed.entries.firstOrNull { it.key in originals }?.let { (i, why) -> "chunk $i: $why" }
    /** Plain-language reason for a partial result (section 17): every failed original chunk with its label and why. */
    val partialReason: String? get() = failed.entries.filter { it.key in originals }.takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { (i, why) -> "chunk $i (${Splitters.label(plan.job.input, chunks[i])}) $why" }
    val nodes: Set<String> get() = active
    val recutCount: Int get() = recuts
    /** Original chunks plus re-cut children so far. */
    val chunkCount: Int get() = chunks.size
    /** Parents assembled from their re-cut halves since the last call (each handed out once). */
    fun takeAssembled(): List<Int> = assembledNew.toList().also { assembledNew.clear() }
    fun output(i: Int): JsonElement? = outputs[i]
    fun chunkAt(i: Int): Chunk? = chunks.getOrNull(i)
    /** Outputs of the original chunks that have one, in chunk-index order (all of them when [done]). */
    fun outputsInOrder(): List<JsonElement> = originals.mapNotNull { outputs[it] }
    /** The original chunks that have an output, in index order (the merge inputs; all of them when [done]). */
    fun chunksWithOutput(): List<Chunk> = originals.filter { it in outputs }.map { chunks[it] }
    fun isUnverified(i: Int): Boolean = verified[i] == false
    fun isPaused(node: String): Boolean = (pausedUntil[node] ?: 0L) > clock()

    fun addBytes(n: Long) { bytes += n }

    /** A result already held (memo by chunkId): the chunk is done without running. */
    fun prefill(index: Int, output: JsonElement) {
        if (outputs.containsKey(index)) return
        outputs[index] = output; doneBy[index] = "memo"; memo += index; queue.remove(index)
    }

    fun addNode(node: String) { active += node }

    /** The phone left (disconnect, repeated timeouts, cooldown): its in-flight chunks go back to the front of the queue. */
    fun removeNode(node: String, why: String = "disconnected"): List<Attempt> {
        if (!active.remove(node)) return emptyList()
        val lost = live.remove(node).orEmpty().toList()
        for (a in lost.sortedByDescending { it.chunk }) { ranOn[a.chunk]?.remove(node); requeue(a, why, countFailure = false) }
        if (node == plan.owner) for (i in chunks.indices) if (chunks[i].private && !terminal(i)) fail(i, "its private data is on a phone that left")
        reconcile()
        publish()
        return lost
    }

    /** Section 14: the phone is paused for [ms] (WAN wake grace, relay outage); it gets no chunks until then. */
    fun pause(node: String, ms: Long) { pausedUntil[node] = clock() + ms }

    // ------------------------------------------------------------------ pull

    private fun linkRank(node: String): Int = if (node == plan.owner) -1 else linkOf(node).kind.ordinal

    /** Fills every phone up to [slots] (warm first, LAN before WAN, then the least loaded, then by id) and duplicates stragglers. */
    fun fill(): List<Assignment> {
        val out = ArrayList<Assignment>()
        var progressed = true
        while (progressed) {
            progressed = false
            val order = active.sortedWith(compareByDescending<String> { warm(it) }.thenBy { linkRank(it) }.thenBy { live[it]?.size ?: 0 }.thenBy { it })
            for (n in order) {
                if ((live[n]?.size ?: 0) >= slots) continue
                val i = pick(n) ?: continue
                out += assign(i, n, duplicate = false)
                progressed = true
            }
        }
        val now = clock()
        for (n in active.sortedWith(compareBy<String> { linkRank(it) }.thenBy { it })) {
            if (isPaused(n) || live[n].orEmpty().isNotEmpty() || pick(n) != null) continue
            val cand = live.values.flatten().filter { a ->
                a.node != n && !terminal(a.chunk) && a.chunk !in duplicated && a.chunk !in held &&
                    canTake(a.chunk, n) && liveCount(a.chunk) == 1 && now >= straggleAt(a)
            }.maxWithOrNull(compareBy<Attempt> { now - straggleAt(it) }.thenByDescending { it.chunk }) ?: continue
            duplicated += cand.chunk
            duplicates++
            notes += "chunk ${cand.chunk} duplicated on ${n.take(8)} (running ${now - cand.dispatchedAt} ms on ${cand.node.take(8)})"
            out += assign(cand.chunk, n, duplicate = true)
        }
        if (out.isNotEmpty()) publish()
        return out
    }

    private fun straggleAt(a: Attempt): Long = a.dispatchedAt + (stragglerFactor * (a.expectedDoneAt - a.dispatchedAt)).toLong()

    /** Privacy, replica distinctness, exclusion and link class: may [node] run chunk [i] at all? (No cost rule.) */
    private fun canTake(i: Int, node: String): Boolean {
        val c = chunks[i]
        if (c.private && node != plan.owner) return false
        if (node in ranOn[i].orEmpty() || node in excluded[i].orEmpty()) return false
        if (node != plan.owner) {
            val link = linkOf(node)
            if (heavy && !link.carriesBlobs) return false
            if (c.blobs.isNotEmpty()) {
                if (!link.carriesBlobs) return false
                if (link.metered && !allowMobileData) return false
            }
        }
        return true
    }

    /** The next chunk [node] may take, by locality, privacy, link class and the transfer rule; null = none. */
    private fun pick(node: String): Int? {
        if (isPaused(node)) return null
        // A chunk that already failed on this phone goes to a phone it has not failed on first (section 14 "requeue
        // elsewhere"); once every candidate has failed it, any of them may retry, up to maxAttempts.
        val allowed = queue.filter { i ->
            canTake(i, node) && (node !in failedOn[i].orEmpty() || active.none { o -> o != node && o !in failedOn[i].orEmpty() && canTake(i, o) })
        }
        if (allowed.isEmpty()) return null
        if (node == plan.owner) allowed.firstOrNull { chunks[it].private || chunks[it].blobs.isNotEmpty() }?.let { return it }
        for (i in allowed) {
            val c = chunks[i]
            // Blobs (images, audio) are the costly transfers; inline text, prompts and ids always move.
            if (node != plan.owner && c.blobs.isNotEmpty() && c.bytes > 0 && plan.owner in active) {
                val transfer = linkOf(node).transferMs(c.bytes)
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
        val rtt = if (node == plan.owner) 0 else linkOf(node).rttMs
        val deadline = maxOf(minDeadlineMs, (deadlineFactor * expected + 2.0 * rtt).toLong())
        val no = (attempts[i] ?: 0) + 1
        attempts[i] = no
        val a = Attempt(i, node, no, duplicate, now, now + expected.toLong(), now + deadline)
        live.getOrPut(node) { ArrayList() } += a
        ranOn.getOrPut(i) { HashSet() } += node
        if (!pending(i)) queue.remove(i)
        return Assignment(chunks[i], node, no, duplicate, deadline)
    }

    // ------------------------------------------------------------------ state helpers

    private fun terminal(i: Int): Boolean = i in outputs || i in failed || i in children
    private fun liveCount(i: Int): Int = live.values.sumOf { l -> l.count { it.chunk == i } }
    private fun wanted(i: Int): Int = runsWanted[i] ?: replicas
    /** The chunk still needs a run started (and so sits in the queue). */
    private fun pending(i: Int): Boolean = !terminal(i) && i !in verifyFailed && held[i].orEmpty().size + liveCount(i) < wanted(i)
    private fun anotherPhoneCould(i: Int): Boolean = active.any { canTake(i, it) }
    private fun agree(a: JsonElement, b: JsonElement): Boolean = comparator?.agree(plan.job.op, a, b, null) ?: Canon.equal(a, b)

    private fun enqueueFront(i: Int) { if (i !in queue) queue.addFirst(i) }

    // ------------------------------------------------------------------ results and faults

    fun onResult(node: String, index: Int, attempt: Int, output: JsonElement, ms: Long): Outcome {
        val a = take(node, index, attempt) ?: return Outcome.Stale
        busy[node] = (busy[node] ?: 0L) + ms
        doneChunks[node] = (doneChunks[node] ?: 0) + 1
        doneUnits[node] = (doneUnits[node] ?: 0) + chunks[index].units
        misses.remove(node)
        val winner = outputs[index]
        val r: Outcome = if (winner != null) {
            val ok = agree(winner, output)
            if (!ok) { mismatches++; notes += "chunk $index: ${node.take(8)} disagrees with ${doneBy[index]?.take(8)}" }
            Outcome.Compared(ok)
        } else {
            val h = held.getOrPut(index) { ArrayList() }
            if (a.duplicate && h.isEmpty()) notes += "chunk $index: the duplicate on ${node.take(8)} finished first"
            h += Held(node, output, ms)
            settle(index)
        }
        publish()
        return r
    }

    /** Decides a chunk from its held replica outputs (section 12.1); [force] = no further phone will come. */
    private fun settle(index: Int, force: Boolean = false): Outcome {
        val h = held[index] ?: return Outcome.AwaitingReplica
        val canWait = !force && (liveCount(index) > 0 || anotherPhoneCould(index))
        if (h.size >= 2) {
            for (x in h.indices) for (y in x + 1 until h.size) if (agree(h[x].output, h[y].output)) return accept(index, h[x], verified = true)
            if (h.size == wanted(index) && h.size < SplitConstants.MAX_REPLICAS) {
                mismatches++
                runsWanted[index] = h.size + 1
                notes += "chunk $index: replicas on ${h.map { it.node.take(8) }} disagree; a third phone decides"
            }
            if (h.size < SplitConstants.MAX_REPLICAS && canWait) {
                if (pending(index)) enqueueFront(index)
                return Outcome.AwaitingReplica
            }
            verifyFailed[index] = VerificationFailure(index, "${h.size} phones disagreed on ${Splitters.label(plan.job.input, chunks[index])}", attempts[index] ?: 0, h.map { it.output })
            held.remove(index); queue.remove(index)
            notes += "chunk $index: no agreeing pair among ${h.size} replicas"
            return Outcome.AwaitingReplica
        }
        if (wanted(index) <= 1) return accept(index, h[0], verified = null)
        if (canWait) {
            if (pending(index)) enqueueFront(index)
            return Outcome.AwaitingReplica
        }
        notes += "chunk $index: only one phone could run it; not verified"
        return accept(index, h[0], verified = false)
    }

    private fun accept(i: Int, h: Held, verified: Boolean?): Outcome.Accepted {
        outputs[i] = h.output; doneBy[i] = h.node; doneMs[i] = h.ms
        verified?.let { this.verified[i] = it }
        held.remove(i); failed.remove(i); queue.remove(i)
        val others = live.values.flatten().filter { it.chunk == i }.map { it.node }
        chunks[i].recutOf?.let { assemble(it) }
        return Outcome.Accepted(others, verified)
    }

    /** A cloud answer (section 15.3) or a reclaimed output takes the chunk's slot; [source] = `cloud:<slug>` or `node:<id>`. */
    fun acceptExternal(index: Int, output: JsonElement, source: String): List<String> {
        if (index in outputs) return emptyList()
        outputs[index] = output; doneBy[index] = source; doneMs[index] = 0
        if (source.startsWith("cloud:")) cloudBy[index] = source.removePrefix("cloud:")
        held.remove(index); failed.remove(index); verifyFailed.remove(index); queue.remove(index)
        val others = live.values.flatten().filter { it.chunk == index }.map { it.node }
        chunks[index].recutOf?.let { assemble(it) }
        publish()
        return others
    }

    /** The commander could not resolve a verification failure (private lineage, gate refused): the chunk fails. */
    fun failVerification(index: Int, why: String) {
        verifyFailed.remove(index)
        fail(index, why)
        publish()
    }

    /** Verification failures the commander has not seen yet (each is handed out once; the chunk waits until resolved). */
    fun takeVerificationFailures(): List<VerificationFailure> =
        verifyFailed.values.filter { it.index !in reportedFailures }.onEach { reportedFailures += it.index }

    /** Section 14: parent [p] takes the merge of its two children once both are done (exact merge kinds, so the hash is unchanged). */
    private fun assemble(p: Int) {
        val kids = children[p] ?: return
        if (p in outputs || kids.any { it !in outputs }) return
        val outs = kids.map { outputs.getValue(it) }
        val cs = kids.map { chunks[it] }
        val merged = when (plan.kind) {
            MergeKind.CONCAT -> Mergers.concat(outs)
            MergeKind.STITCH -> Mergers.stitch(outs, cs)
            MergeKind.MONOID -> Mergers.monoid(outs)
            MergeKind.TOPK -> Mergers.topK(outs, plan.job.params)
            else -> return
        }
        outputs[p] = merged; doneBy[p] = "recut"; doneMs[p] = kids.sumOf { doneMs[it] ?: 0L }
        assembledNew += p
        if (kids.all { verified[it] == true }) verified[p] = true else if (kids.any { verified[it] == false }) verified[p] = false
        notes += "chunk $p assembled from its re-cut halves ${kids.joinToString(" and ")}"
    }

    /** A timeout, yield or error: the attempt ends; the chunk goes back to the front unless another attempt runs it. */
    fun onFault(node: String, index: Int, attempt: Int, why: String, errorClass: String? = null): Boolean {
        val a = take(node, index, attempt) ?: return false
        ranOn[index]?.remove(node)
        // A yield (P7) or a message that could not be sent is no fault of the phone: no re-cut count. A timeout or a
        // hash mismatch excludes the phone for that chunk (section 14); an ordinary error may retry anywhere.
        val blameless = errorClass == SplitConstants.ERR_YIELDED || why == SplitConstants.ERR_YIELDED || errorClass == UNREACHABLE
        if (!blameless) failedOn.getOrPut(index) { HashSet() } += node
        if (errorClass == TIMEOUT || errorClass == MISMATCH) excluded.getOrPut(index) { HashSet() } += node
        requeue(a, why, countFailure = !blameless)
        reconcile()
        publish()
        return true
    }

    /** Section 11.4: a missed deadline; true when the phone has now missed [SplitConstants.MISSES_TO_DROP] and should be dropped. */
    fun onMiss(node: String, index: Int, attempt: Int): Boolean {
        onFault(node, index, attempt, "no reply", TIMEOUT)
        val n = (misses[node] ?: 0) + 1
        misses[node] = n
        return n >= SplitConstants.MISSES_TO_DROP
    }

    /** Section 12.1: a recomputed output hash that does not match; the phone is removed at three in one job. */
    fun onHashMismatch(node: String, index: Int, attempt: Int): Boolean {
        if (!onFault(node, index, attempt, "output hash does not match", MISMATCH)) return false
        val n = (hashMismatches[node] ?: 0) + 1
        hashMismatches[node] = n
        if (n >= SplitConstants.MAX_HASH_MISMATCHES) { removeNode(node, "three output hash mismatches"); return true }
        return false
    }

    /** Section 12.1: a schema validation failure; three across two or more phones is a verification failure. */
    fun onSchemaFault(node: String, index: Int, attempt: Int, detail: String): Boolean {
        val fails = schemaFails.getOrPut(index) { ArrayList() }
        fails += node
        val escalate = fails.size >= SplitConstants.SCHEMA_FAILS_TO_ESCALATE && fails.toSet().size >= SplitConstants.SCHEMA_FAIL_MIN_PHONES
        if (!escalate) return onFault(node, index, attempt, "schema: $detail", SplitConstants.ERR_SCHEMA)
        val a = take(node, index, attempt) ?: return false
        ranOn[index]?.remove(node)
        excluded.getOrPut(index) { HashSet() } += node
        verifyFailed[index] = VerificationFailure(index, "schema validation failed ${fails.size} times on ${fails.toSet().size} phones for ${Splitters.label(plan.job.input, chunks[index])}", a.no, held.remove(index).orEmpty().map { it.output })
        queue.remove(index)
        notes += "chunk $index: schema validation failed ${fails.size} times on ${fails.toSet().size} phones"
        publish()
        return true
    }

    private fun requeue(a: Attempt, why: String, countFailure: Boolean) {
        val i = a.chunk
        if (terminal(i) || i in verifyFailed) return
        if (countFailure && recutEnabled && failedOn[i].orEmpty().size >= SplitConstants.RECUT_AFTER_PHONES && chunks[i].recutOf == null && tryRecut(i)) { reassigned++; return }
        if ((attempts[i] ?: 0) >= maxAttempts) { fail(i, "failed $maxAttempts times (last: $why)"); return }
        if (!pending(i)) return
        enqueueFront(i)
        reassigned++
        notes += "chunk $i back in the queue: ${a.node.take(8)} $why"
    }

    private fun tryRecut(i: Int): Boolean {
        if (plan.kind !in RECUT_KINDS) return false
        val kids = plan.recut(chunks[i], chunks.size) ?: return false
        chunks += kids
        children[i] = kids.map { it.index }
        queue.remove(i); held.remove(i)
        for (k in kids.asReversed()) enqueueFront(k.index)
        recuts++
        notes += "chunk $i failed on ${failedOn[i]?.size} phones; re-cut into chunks ${kids[0].index} and ${kids[1].index}"
        return true
    }

    private fun fail(i: Int, why: String) {
        if (i in outputs || i in failed) return
        failed[i] = why
        queue.remove(i); held.remove(i); verifyFailed.remove(i)
        chunks[i].recutOf?.let { p -> fail(p, "its re-cut half (chunk $i) $why") }
        children[i]?.forEach { k -> fail(k, "the parent chunk $i failed") }
    }

    /** After a phone left or an attempt ended: chunks waiting for a replica that no phone can run any more are settled. */
    private fun reconcile() {
        for (i in held.keys.toList()) if (i !in outputs && liveCount(i) == 0 && !anotherPhoneCould(i)) settle(i)
    }

    /**
     * Nothing runs and nothing could be assigned: chunks with a held replica are settled as they stand (accepted "not
     * verified", or a verification failure when the held outputs disagree); the rest fail so the job can end partial.
     */
    fun failStranded(why: String = "no phone can take it") {
        for (i in queue.toList()) if (!terminal(i) && liveCount(i) == 0) {
            if (held[i].orEmpty().isNotEmpty()) settle(i, force = true) else fail(i, why)
        }
        publish()
    }

    private fun take(node: String, index: Int, attempt: Int): Attempt? {
        val l = live[node] ?: return null
        val a = l.firstOrNull { it.chunk == index && it.no == attempt } ?: return null
        l.remove(a)
        return a
    }

    /** The commander cancelled this attempt (its chunk is settled): it is no longer live and a late result is stale. */
    fun cancelAttempt(a: Attempt) { live[a.node]?.remove(a); if (liveCount(a.chunk) == 0 && a.chunk !in held) ranOn[a.chunk]?.remove(a.node) }

    /** Attempts past their deadline now. */
    fun overdue(): List<Attempt> { val now = clock(); return live.values.flatten().filter { it.deadlineAt <= now } }

    /** Every attempt still running (to cancel when the job ends). */
    fun running(): List<Attempt> = live.values.flatten()

    /** Live attempts of chunks that are already settled or failed (a replica or duplicate the commander can cancel). */
    fun superseded(): List<Attempt> = live.values.flatten().filter { it.chunk in outputs || it.chunk in failed }

    /** When the commander must wake up next: the earliest deadline, straggler threshold or pause end (null = only on messages). */
    fun nextWake(): Long? {
        val all = live.values.flatten()
        val now = clock()
        val deadlines = all.map { it.deadlineAt }
        val straggle = if (queue.isEmpty() && all.isNotEmpty()) all.filter { !terminal(it.chunk) && it.chunk !in duplicated }.map { straggleAt(it) } else emptyList()
        val pauses = pausedUntil.values.filter { it > now }
        return (deadlines + straggle.filter { it > now } + pauses).minOrNull()
    }

    // ------------------------------------------------------------------ progress

    fun publish() { state.value = snapshot() }

    fun snapshot(): SplitProgress {
        val now = clock()
        val elapsed = maxOf(1L, now - start)
        val cells = chunks.indices.map { i ->
            val running = live.values.flatten().filter { it.chunk == i }.map { it.node }
            val kids = children[i]
            val status = when {
                i in outputs -> ChunkStatus.DONE
                i in failed -> ChunkStatus.FAILED
                kids != null -> if (kids.any { k -> live.values.flatten().any { it.chunk == k } }) ChunkStatus.RUNNING else ChunkStatus.QUEUED
                running.size > 1 -> ChunkStatus.DUPLICATED
                running.size == 1 -> ChunkStatus.RUNNING
                else -> ChunkStatus.QUEUED
            }
            ChunkCell(i, chunks[i].chunkId, chunks[i].units, status, if (i in outputs) listOfNotNull(doneBy[i]) else running,
                doneBy[i], attempts[i] ?: 0, i in duplicated, doneMs[i] ?: 0L, i in memo, Splitters.label(plan.job.input, chunks[i]),
                verified = verified[i], recut = kids != null, recutOf = chunks[i].recutOf, cloud = cloudBy[i])
        }
        val ns = (active + busy.keys).sorted().map { n ->
            val b = busy[n] ?: 0L
            NodeStat(n, doneChunks[n] ?: 0, doneUnits[n] ?: 0, b,
                round2((doneUnits[n] ?: 0) * 60_000.0 / elapsed), round2(minOf(1.0, b.toDouble() / elapsed)),
                link = if (n == plan.owner) null else linkOf(n).kind.name)
        }
        val single = plan.singlePhoneMs(plan.owner)
        return SplitProgress(plan.job.jobId, plan.job.op.name, plan.kind.name, plan.axis.name, cells, ns, bytes, now - start,
            round2(single), round2(single / elapsed), round2(busy.values.sum().toDouble() / elapsed), duplicates, mismatches, reassigned,
            done = false, error = error, notes = notes.toList(), partial = partial, partialReason = partialReason,
            unverified = verified.count { !it.value }, recuts = recuts, cloud = cloudBy.values.distinct())
    }

    companion object {
        /** Merge kinds whose sub-merge of two halves equals the whole (section 12.3), so a re-cut keeps the result hash. */
        val RECUT_KINDS = setOf(MergeKind.CONCAT, MergeKind.STITCH, MergeKind.MONOID, MergeKind.TOPK)
        /** Fault class of a TASK that could not be sent: blameless for the phone, unlike a result error. */
        const val UNREACHABLE = "unreachable"
        /** Fault classes of the queue's own checks (beside the section 14 result classes). */
        const val TIMEOUT = "timeout"
        const val MISMATCH = "mismatch"

        fun taskId(index: Int, attempt: Int): String = "c$index.a$attempt"

        /** (chunk index, attempt) from a task id made by [taskId]; null for anything else. */
        fun parseTaskId(id: String): Pair<Int, Int>? {
            val m = Regex("^c(\\d+)\\.a(\\d+)$").matchEntire(id) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }

        /** A phone's link for the queue from its profile (section 5.6); unmeasured peers count as the LAN planning figure. */
        fun linkFrom(profile: com.hivecore.core.Profile?): Link =
            profile?.link ?: profile?.linkKbps?.takeIf { it > 0 }?.let { Link(LinkKind.LAN, it.toInt(), Link.UNKNOWN.rttMs) } ?: Link.UNKNOWN

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
