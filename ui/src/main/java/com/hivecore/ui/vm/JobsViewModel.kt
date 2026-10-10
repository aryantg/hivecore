package com.hivecore.ui.vm

import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.JobRunner
import com.hivecore.core.LinkKind
import com.hivecore.core.NodeLoad
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.WorkerActivity
import com.hivecore.core.WorkerEvent
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.engine.workload.Recipes
import com.hivecore.ui.Format
import com.hivecore.ui.ports.Refusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Jobs screen (spec section 17): the live chunk grid, per-phone load with the link kind, bytes moved, elapsed vs the
 * single-phone estimate, the speed-up, the result summary and hash, a cloud badge on escalated chunks, resumable jobs
 * after a restart, and cancel / resume / open / re-run. Codes against [JobRunner], [JobProgress] and the peer profiles
 * (for `Profile.link.kind`) only. A small recipe picker stays so the split gates can start a job without the Ask screen.
 */
class JobsViewModel(
    private val runner: JobRunner,
    private val profiles: StateFlow<List<Profile>>,
    private val loadRecipes: () -> List<RecipeDef>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** the merged result's hash when the runner exposes it (JobProgress carries none yet); null = unknown */
    private val resultHash: (JobProgress) -> String? = { null },
    /** the worker timeline: this phone's chunks for other phones, and (mirrored) its workers' for its own jobs; null = none */
    private val activity: WorkerActivity? = null,
    private val selfId: String = "",
    /** nodeId → display name (the orchestrator a card works for) */
    private val nameOf: (String) -> String = { it.take(8) },
) {
    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    /** One phone's lane: its load, how it is reached, and its latest worker timeline event (model boot, chunk, sent back). */
    data class PhoneLane(val load: NodeLoad, val link: LinkKind?, val activity: WorkerEvent? = null) {
        val linkWord: String get() = Format.linkWord(link)
        val activityLine: String? get() = activity?.let { WorkerTimeline.phrase(it) }
        val busy: Boolean get() = activity?.let { WorkerTimeline.busy(it) } == true
    }

    data class UiState(
        val loadingRecipes: Boolean = true,
        val recipes: List<RecipeDef> = emptyList(),
        val recipesError: String? = null,
        val selectedId: String? = null,
        val items: List<String> = emptyList(),
        val prompt: String = "",
        val private: Boolean = false,
        val phase: Phase = Phase.IDLE,
        /** the job on screen: the running one, the one just finished, or one opened from history */
        val current: JobProgress? = null,
        val selectedChunk: Int? = null,
        val history: List<JobProgress> = emptyList(),
        val lanes: List<PhoneLane> = emptyList(),
        val resultHash: String? = null,
        /** jobIds whose request this session knows (re-run is possible) */
        val rerunnable: Set<String> = emptySet(),
        val message: String? = null,
        /** the result sheet is open on [current] */
        val viewingResult: Boolean = false,
        /** jobs this phone is working on (or just worked on) for other phones, most recent first */
        val working: List<WorkerTimeline.WorkingFor> = emptyList(),
        /** while this phone's job is placed and has no chunk grid yet: each worker's latest event (name, event) */
        val preparing: List<Pair<String, WorkerEvent>> = emptyList(),
    ) {
        val selected: RecipeDef? get() = recipes.firstOrNull { it.id == selectedId }
        val canRun: Boolean get() = phase != Phase.RUNNING && selected?.let { r ->
            when (r.input) { "prompt" -> prompt.isNotBlank(); "none" -> true; else -> items.isNotEmpty() }
        } == true
        val running: Boolean get() = phase == Phase.RUNNING
        val currentRecipe: RecipeDef? get() = current?.let { c -> recipes.firstOrNull { it.title == c.title } }
        /** Spec section 13.3: journaled jobs that neither finished nor failed and are not running now. */
        val resumable: List<JobProgress> get() = history.filter { !it.done && it.error == null && !(running && it.jobId == current?.jobId) }
        /** The plain-words refusal of a failed or partial job (spec section 17). */
        val refusal: Refusal? get() = current?.let { JobFormat.refusal(it) }
        val cloudChunks: Int get() = current?.let { JobFormat.cloudCount(it) } ?: 0
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Opens a table or folder result (set by the app's navigation). */
    var openResult: (String) -> Unit = { SLog.w(TAG, "openResult not wired: $it") }

    private val requests = HashMap<String, JobRequest>()

    /** Copies text to the clipboard (set by the app). */
    var copyText: (String) -> Unit = { SLog.w(TAG, "copyText not wired") }

    /** Spec section 17 "identical to single-phone": the last finished hash per request, to compare a one-phone run with a swarm run. */
    private data class Finished(val jobId: String, val hash: String, val phones: Int)
    private val finished = HashMap<String, Finished>()
    private val verdicts = HashMap<String, Boolean>()
    private var runJob: Job? = null

    init {
        scope.launch { runner.jobs.collect { list -> _state.update { it.copy(history = list) } } }
        scope.launch { profiles.collect { _state.update { s -> s.copy(lanes = s.current?.let { lanes(it) } ?: emptyList()) } } }
        activity?.let { a ->
            scope.launch { a.local.collect { evs -> _state.update { it.copy(working = WorkerTimeline.build(evs, selfId, nameOf)) } } }
            scope.launch { a.peers.collect { _ -> _state.update { s -> s.copy(lanes = s.current?.let { lanes(it) } ?: emptyList(), preparing = preparing(s)) } } }
        }
    }

    fun loadRecipes() {
        _state.update { it.copy(loadingRecipes = true, recipesError = null) }
        scope.launch {
            runCatching { withContext(io) { loadRecipes.invoke() } }
                .onSuccess { list -> _state.update { s -> s.copy(loadingRecipes = false, recipes = list, selectedId = s.selectedId?.takeIf { id -> list.any { it.id == id } }) } }
                .onFailure { e ->
                    SLog.e(TAG, "recipes failed", e)
                    _state.update { it.copy(loadingRecipes = false, recipesError = "Could not load the recipes: ${e.message ?: e.javaClass.simpleName}") }
                }
        }
    }

    fun selectRecipe(id: String) {
        _state.update { s -> if (s.selectedId == id) s else s.copy(selectedId = id, items = if (s.selected?.input == s.recipes.firstOrNull { it.id == id }?.input) s.items else emptyList()) }
    }

    fun setPrompt(text: String) { _state.update { it.copy(prompt = text) } }
    fun setPrivate(on: Boolean) { _state.update { it.copy(private = on) } }
    fun addItems(paths: List<String>) { _state.update { s -> s.copy(items = (s.items + paths).distinct()) } }
    fun removeItem(path: String) { _state.update { s -> s.copy(items = s.items - path) } }
    fun clearItems() { _state.update { it.copy(items = emptyList()) } }

    fun run() {
        val s = _state.value
        val r = s.selected ?: return
        if (!s.canRun) return
        val params = if (r.input == "prompt") JsonObject(mapOf("prompt" to JsonPrimitive(s.prompt.trim()))) else JsonObject(emptyMap())
        submit(JobRequest(title = r.title, recipeId = r.id, params = params, inputs = if (r.input in FILE_INPUTS) s.items else emptyList(), private = s.private))
    }

    /** Runs any request (the Ask screen's split jobs arrive here too). */
    fun submit(request: JobRequest) {
        SLog.d(TAG, "run ${request.recipeId ?: request.op} items=${request.inputs.size} private=${request.private}")
        follow(request.title, runner.run(request)) { p -> requests[p.jobId] = request }
    }

    /** Spec section 13.3: resumes a journaled job after a restart (RECLAIM, then requeue). */
    fun resume(jobId: String) {
        if (_state.value.running) return
        SLog.d(TAG, "resume $jobId")
        val title = _state.value.history.firstOrNull { it.jobId == jobId }?.title ?: "Job"
        follow(title, runner.resume(jobId))
    }

    /** Re-run (spec section 17): the memo makes unchanged chunks free; needs the request this session saw. */
    fun rerun(jobId: String) {
        if (_state.value.running) return
        val req = requests[jobId] ?: run { _state.update { it.copy(message = "This job's request is not known on this phone; start it again from Ask") }; return }
        submit(req)
    }

    private fun follow(title: String, flow: Flow<JobProgress>, onFirst: (JobProgress) -> Unit = {}) {
        runJob?.cancel()
        _state.update { it.copy(phase = Phase.RUNNING, current = null, selectedChunk = null, lanes = emptyList(), resultHash = null, message = null) }
        runJob = scope.launch {
            try {
                var last: JobProgress? = null
                flow.collect { p ->
                    if (last == null) onFirst(p)
                    last = p
                    show(p, phaseOf(p))
                }
                val end = last
                if (end == null) _state.update { it.copy(phase = Phase.FAILED, message = "The job produced no progress") }
                else if (!end.done && end.error == null) _state.update { it.copy(phase = Phase.DONE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SLog.e(TAG, "job failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(phase = Phase.FAILED, current = it.current?.copy(error = msg) ?: JobProgress("", title, "", "", emptyList(), emptyList(), error = msg)) }
            }
        }
    }

    fun cancel() {
        val id = _state.value.current?.jobId
        if (id != null) runner.cancel(id)
        runJob?.cancel(); runJob = null
        _state.update { it.copy(phase = Phase.IDLE, message = "Job cancelled") }
    }

    /** Back to the picker (keeps the recipe and items). */
    fun newJob() {
        if (_state.value.running) return
        _state.update { it.copy(phase = Phase.IDLE, current = null, selectedChunk = null, lanes = emptyList(), resultHash = null) }
    }

    fun openHistory(jobId: String) {
        if (_state.value.running) return
        val p = _state.value.history.firstOrNull { it.jobId == jobId } ?: return
        show(p, phaseOf(p).let { ph -> if (ph == Phase.RUNNING) Phase.DONE else ph })
        _state.update { it.copy(selectedChunk = null) }
    }

    fun selectChunk(index: Int?) { _state.update { s -> s.copy(selectedChunk = if (s.selectedChunk == index) null else index) } }

    /** Opens the result sheet when the merged output is on hand, else hands the ref to the app. */
    fun openResult() {
        val c = _state.value.current ?: return
        if (c.result != null) _state.update { it.copy(viewingResult = true) } else c.resultRef?.let { openResult(it) }
    }

    fun closeResult() { _state.update { it.copy(viewingResult = false) } }

    fun copyResult(text: String) { copyText(text); _state.update { it.copy(message = "Result copied as CSV") } }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun show(p0: JobProgress, phase: Phase) {
        val p = withIdentical(p0)
        _state.update { it.copy(current = p, phase = phase, lanes = lanes(p), preparing = emptyList(), resultHash = if (p.done) runCatching { resultHash(p) }.getOrNull() else null, rerunnable = requests.keys.toSet()) }
    }

    /**
     * Sets `identicalToSingle` honestly: when the same request finished before and either run used one phone, the
     * two result hashes are compared; otherwise it stays null and the screen shows no badge.
     */
    private fun withIdentical(p: JobProgress): JobProgress {
        if (!p.done || p.error != null || p.identicalToSingle != null) return p
        verdicts[p.jobId]?.let { return p.copy(identicalToSingle = it) }
        val req = requests[p.jobId] ?: return p
        val hash = runCatching { resultHash(p) }.getOrNull() ?: p.resultRef ?: return p
        val key = requestKey(req)
        val before = finished[key]
        if (before == null || before.jobId != p.jobId) finished[key] = Finished(p.jobId, hash, p.nodes.size)
        if (before == null || before.jobId == p.jobId || (before.phones != 1 && p.nodes.size != 1)) return p
        val same = before.hash == hash
        verdicts[p.jobId] = same
        return p.copy(identicalToSingle = same)
    }

    private fun lanes(p: JobProgress): List<PhoneLane> {
        val ps = profiles.value
        val mirror = mirrorOf(p.jobId)
        return p.nodes.map { n -> PhoneLane(n, ps.firstOrNull { it.nodeId == n.nodeId }?.link?.kind, mirror[n.nodeId]) }
    }

    /** The workers' latest events for [jobId] (a recipe stage "<job>/<stage>" matches its job). */
    private fun mirrorOf(jobId: String): Map<String, WorkerEvent> {
        val root = jobId.substringBefore('/')
        return activity?.peers?.value.orEmpty().filterValues { it.jobId.substringBefore('/') == root }
    }

    private fun preparing(s: UiState): List<Pair<String, WorkerEvent>> =
        if (!s.running || s.current != null) emptyList()
        else activity?.peers?.value.orEmpty().values.sortedBy { it.node }.map { (if (it.node == selfId) "This phone" else nameOf(it.node)) to it }

    companion object {
        private const val TAG = "JobsVM"

        /** Two runs are "the same job" when the recipe (or op), the items and the parameters match; private mode may differ. */
        fun requestKey(r: JobRequest): String = "${r.recipeId ?: r.op}|${r.inputs.sorted().joinToString("|")}|${r.params}"
        val FILE_INPUTS = setOf("files", "images")

        fun phaseOf(p: JobProgress): Phase = when {
            p.error != null -> Phase.FAILED
            p.done -> Phase.DONE
            else -> Phase.RUNNING
        }
    }
}

/** Pure text for the Jobs screen (JVM-tested). */
object JobFormat {
    /** Spec section 9.4 / 15.3 (C5): a chunk that ran on the cloud carries `node = "cloud:<slug>"`. */
    const val CLOUD_PREFIX = "cloud:"

    fun isCloud(c: ChunkView): Boolean = c.node?.startsWith(CLOUD_PREFIX) == true
    fun cloudCount(p: JobProgress): Int = p.chunks.count { isCloud(it) }

    /** "3.1× faster than this phone alone"; below 1× it says slower; null = not measured yet. */
    fun speedup(x: Double?): String = when {
        x == null || x.isNaN() || x <= 0.0 -> "Speed-up not measured"
        x >= 1.0 -> String.format(Locale.US, "%.1f× faster than this phone alone", x)
        else -> String.format(Locale.US, "%.1f× (slower than this phone alone)", x)
    }

    /** The badge text, or null when nothing was compared (the screen then shows no badge). */
    fun identicalOrNull(v: Boolean?): String? = v?.let { identical(it) }

    fun identical(v: Boolean?): String = when (v) {
        true -> "✓ identical to single-phone result"
        false -> "✗ differs from the single-phone result"
        null -> "Identical to single-phone result: not checked"
    }

    fun bytes(n: Long): String = Format.bytes(n)
    fun ms(ms: Long): String = Format.duration(ms)
    fun rate(perSec: Double): String = String.format(Locale.US, "%.1f items/s", perSec)

    fun progress(p: JobProgress): Float {
        if (p.done) return 1f
        if (p.chunks.isEmpty()) return 0f
        return p.chunks.count { it.state == ChunkState.DONE }.toFloat() / p.chunks.size
    }

    /** "12 of 40 chunks done · 3 running · 1 duplicated · 0 failed · 2 on the cloud" */
    fun chunkLine(p: JobProgress): String {
        val by = p.chunks.groupingBy { it.state }.eachCount()
        val parts = mutableListOf("${by[ChunkState.DONE] ?: 0} of ${p.chunks.size} chunks done")
        by[ChunkState.RUNNING]?.let { parts += "$it running" }
        by[ChunkState.DUPLICATED]?.let { parts += "$it duplicated" }
        by[ChunkState.FAILED]?.let { parts += "$it failed" }
        cloudCount(p).takeIf { it > 0 }?.let { parts += "$it on the cloud" }
        return parts.joinToString(" · ")
    }

    /** "18.7 s elapsed · about 2 min 11 s on this phone alone" (the estimate part only once known). */
    fun elapsedLine(p: JobProgress): String =
        "${ms(p.elapsedMs)} elapsed" + (p.singlePhoneMs?.let { " · about ${ms(it)} on this phone alone" } ?: "")

    /** A TREE merge is labelled as such, never passed off as a single-pass result (spec section 12.2). */
    fun mergeNote(p: JobProgress): String? = if (p.merge == "tree") "Tree summary of ${p.chunks.size} chunks" else null

    fun axisMerge(axis: String, merge: String): String = "${Recipes.axisLabel(axis)} · ${Recipes.mergeLabel(merge)}"

    /** Table refs are bare names (t_invoices); folders are paths. */
    fun isFolder(ref: String): Boolean = ref.contains('/') || ref.contains('\\')

    /** The cell glyph: the phone's initial, or "☁" for a cloud chunk. */
    fun initial(node: String?): String = when {
        node == null -> ""
        node.startsWith(CLOUD_PREFIX) -> "☁"
        else -> node.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
    }

    fun chunkDetail(c: ChunkView): String = listOfNotNull(
        "Chunk ${c.index + 1}",
        c.label.ifBlank { null },
        when (c.state) {
            ChunkState.QUEUED -> "queued"
            ChunkState.RUNNING -> "running on ${where(c)}"
            ChunkState.DONE -> "done on ${where(c)}"
            ChunkState.DUPLICATED -> "duplicated (straggler) on ${where(c)}"
            ChunkState.FAILED -> "failed on ${where(c)}"
        },
        c.ms?.let { ms(it) },
    ).joinToString(" · ")

    private fun where(c: ChunkView): String = c.node?.let { if (it.startsWith(CLOUD_PREFIX)) "the cloud (${it.removePrefix(CLOUD_PREFIX)})" else it } ?: "?"

    /**
     * Spec section 17 "Refusals in plain words": one sentence for a failed or partial job, with the failed chunk as
     * the step. Null while the job is fine.
     */
    fun refusal(p: JobProgress): Refusal? {
        val failed = p.chunks.filter { it.state == ChunkState.FAILED }
        val err = p.error
        return when {
            err != null -> Refusal(plainWords(err), null, failed.firstOrNull()?.let { "chunk ${it.index + 1} ${it.label}".trim() }, err)
            p.done && failed.isNotEmpty() -> Refusal("${Format.plural(failed.size, "chunk")} could not be processed, so the result is partial.", null,
                failed.joinToString(", ") { "chunk ${it.index + 1}" }, failed.mapNotNull { it.node }.distinct().joinToString(", ").ifBlank { null })
            else -> null
        }
    }

    /** Engine error strings → one sentence a non-developer understands; unknown ones pass through with a full stop. */
    fun plainWords(error: String): String {
        val e = error.trim()
        val low = e.lowercase(Locale.ROOT)
        return when {
            low.contains("every phone left") || low.contains("no eligible") -> "Every phone that could help left or went busy, so the job stopped."
            low.contains("private") && low.contains("cloud") -> "Two phones disagreed on the extraction and the data is private, so no cloud re-run."
            low.contains("owner") && (low.contains("left") || low.contains("gone")) -> "The phone holding the data left before its rows were processed."
            low.contains("cancel") -> "You cancelled the job."
            low.contains("deadline") || low.contains("timeout") -> "A phone did not answer in time and no other phone could take its chunks."
            else -> e.replaceFirstChar { it.uppercase() }.trimEnd('.') + "."
        }
    }
}
