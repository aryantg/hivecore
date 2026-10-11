package com.hivecore.ui.vm

import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.SLog
import com.hivecore.core.WorkerActivity
import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Compute points, the UI-only reward loop: this phone earns points for the time its NPU spent on other phones' chunks
 * and spends them on its own requests. Nothing below the screen knows about points yet; the maths is one rule
 * ([pointsFor]: a point per second of compute) over the worker timeline this phone already keeps, so the real
 * accounting can replace this object without touching the screens.
 */
object ComputeCredits {
    /** One point per second of compute, rounded up per job. */
    const val POINTS_PER_SECOND = 1
    /** Each request sent from Home costs this much. */
    const val PROMPT_COST = 5
    /** A new phone starts with this many points, so the first request is never blocked. */
    const val WELCOME = 50
    /** A job whose last chunk went back this long ago counts as finished (the countersigned receipt settles it sooner). */
    const val SETTLE_MS = 8_000L

    /** One job this phone worked on for another phone, settled and paid. */
    data class Award(val planId: String, val commander: String, val commanderName: String, val computeMs: Long, val chunks: Int, val points: Int, val ts: Long) {
        /** "+42 · 41.6 s of compute for Sales" */
        val line: String get() = "+$points · ${Format.duration(computeMs)} of compute for $commanderName"
    }

    /** Points for [computeMs] of work: a point per second, rounded up, at least one when any work was done. */
    fun pointsFor(computeMs: Long, chunks: Int = 1): Int = when {
        computeMs <= 0 && chunks <= 0 -> 0
        else -> (ceil(computeMs / 1000.0).toInt() * POINTS_PER_SECOND).coerceAtLeast(1)
    }

    /**
     * The jobs for other phones that are settled at [now]: the newest event of the plan is an ending (the result went
     * back, failed, was cancelled or yielded) and [SETTLE_MS] passed, or the orchestrator countersigned the receipt.
     * Compute time is the worker's own `RESULT_SENT.ms` per chunk (else the run's wall time). Own jobs are skipped.
     */
    fun settled(events: List<WorkerEvent>, selfId: String, nameOf: (String) -> String, now: Long): List<Award> {
        val forOthers = events.filter { it.commander != selfId }
        val receipts = forOthers.filter { it.kind == WorkerEventKind.RECEIPT_COUNTERSIGNED }.map { it.jobId }.toSet()
        val byPlan = LinkedHashMap<String, MutableList<WorkerEvent>>()
        for (e in forOthers) if (e.kind != WorkerEventKind.RECEIPT_COUNTERSIGNED) byPlan.getOrPut(e.planId) { ArrayList() } += e
        return byPlan.mapNotNull { (planId, es) ->
            val last = es.maxBy { it.ts }
            val done = last.jobId in receipts || (last.kind in ENDINGS && now - last.ts >= SETTLE_MS)
            if (!done) return@mapNotNull null
            val sent = es.filter { it.kind == WorkerEventKind.RESULT_SENT }
            if (sent.isEmpty()) return@mapNotNull null
            val ms = sent.sumOf { r -> r.ms ?: es.firstOrNull { it.taskId == r.taskId && it.kind == WorkerEventKind.CHUNK_RUNNING }?.let { r.ts - it.ts } ?: 0L }
            Award(planId, last.commander, nameOf(last.commander), ms, sent.size, pointsFor(ms, sent.size), last.ts)
        }
    }

    private val ENDINGS = setOf(WorkerEventKind.RESULT_SENT, WorkerEventKind.RESULT_FAILED, WorkerEventKind.CANCELLED, WorkerEventKind.YIELDED)

    // ---- the prefs this balance lives under ----
    const val KEY_BALANCE = "credits_balance"
    const val KEY_EARNED = "credits_earned"
    const val KEY_SPENT = "credits_spent"
    const val KEY_COMPUTE_MS = "credits_compute_ms"
    const val KEY_JOBS = "credits_jobs"
    const val KEY_AWARDED = "credits_awarded"
    const val KEY_LAST = "credits_last"
    /** How many paid plan ids are remembered (so a job is never paid twice while its events are still on the timeline). */
    const val AWARDED_KEPT = 300
}

/**
 * The balance on this phone (persisted in [KeyValuePrefs]), fed by the worker timeline: every settled job for
 * another phone pays once and raises [notifyAward]; every request from Home spends [ComputeCredits.PROMPT_COST].
 * Codes against [WorkerActivity] (null when the split phase is off: the balance still spends and shows).
 */
class CreditsViewModel(
    private val prefs: KeyValuePrefs,
    private val activity: WorkerActivity?,
    private val selfId: String,
    private val nameOf: (String) -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = 5_000L,
) {
    data class UiState(
        val balance: Int = ComputeCredits.WELCOME,
        val earned: Int = 0,
        val spent: Int = 0,
        val computeMs: Long = 0,
        val jobs: Int = 0,
        /** the latest awards, newest first (this session, plus the last one from before) */
        val recent: List<ComputeCredits.Award> = emptyList(),
    ) {
        val lastAward: ComputeCredits.Award? get() = recent.firstOrNull()
        /** fewer points than one request costs */
        val low: Boolean get() = balance < ComputeCredits.PROMPT_COST
        /** "120 points" */
        val balanceLine: String get() = Format.plural(balance, "point")
        /** "Enough for 24 requests" / "Share this phone when idle to earn more" */
        val detail: String get() = if (low) "Not enough for a request (${ComputeCredits.PROMPT_COST} points each). Share this phone when idle to earn more."
            else "Enough for ${Format.plural(balance / ComputeCredits.PROMPT_COST, "request")} at ${ComputeCredits.PROMPT_COST} points each."
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The app posts the "You earned N points" notification here. */
    var notifyAward: (ComputeCredits.Award) -> Unit = {}

    private val awarded: LinkedHashSet<String> = LinkedHashSet(prefs.getString(ComputeCredits.KEY_AWARDED, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())

    init {
        if (activity != null) {
            scope.launch { activity.local.collect { tick() } }
            scope.launch { while (isActive) { delay(pollMs); tick() } }
        }
    }

    /** Pays every newly settled job (idempotent: a plan is paid once). */
    fun tick() {
        val events = activity?.local?.value ?: return
        val fresh = ComputeCredits.settled(events, selfId, nameOf, clock()).filter { it.planId !in awarded }
        if (fresh.isEmpty()) return
        for (a in fresh) {
            awarded += a.planId
            while (awarded.size > ComputeCredits.AWARDED_KEPT) awarded.remove(awarded.first())
            _state.update { s -> s.copy(balance = s.balance + a.points, earned = s.earned + a.points, computeMs = s.computeMs + a.computeMs, jobs = s.jobs + 1, recent = (listOf(a) + s.recent).take(RECENT)) }
            SLog.d(TAG, "awarded ${a.points} points for ${a.computeMs} ms (${a.chunks} chunks) for ${a.commanderName}")
            runCatching { notifyAward(a) }.onFailure { SLog.w(TAG, "notify failed: ${it.message}") }
        }
        save()
    }

    /** A request leaves Home: spends [ComputeCredits.PROMPT_COST] (never below zero). True when the balance covered it. */
    fun spend(): Boolean {
        val before = _state.value.balance
        val cost = minOf(ComputeCredits.PROMPT_COST, before)
        _state.update { it.copy(balance = it.balance - cost, spent = it.spent + cost) }
        save()
        return before >= ComputeCredits.PROMPT_COST
    }

    /** Back to the welcome balance (the demo reset). */
    fun reset() {
        awarded.clear()
        _state.value = UiState()
        save()
    }

    private fun load(): UiState = UiState(
        balance = prefs.getInt(ComputeCredits.KEY_BALANCE, ComputeCredits.WELCOME),
        earned = prefs.getInt(ComputeCredits.KEY_EARNED, 0),
        spent = prefs.getInt(ComputeCredits.KEY_SPENT, 0),
        computeMs = prefs.getString(ComputeCredits.KEY_COMPUTE_MS, null)?.toLongOrNull() ?: 0L,
        jobs = prefs.getInt(ComputeCredits.KEY_JOBS, 0),
        recent = listOfNotNull(prefs.getString(ComputeCredits.KEY_LAST, null)?.let { decode(it) }),
    )

    private fun save() {
        val s = _state.value
        prefs.putInt(ComputeCredits.KEY_BALANCE, s.balance)
        prefs.putInt(ComputeCredits.KEY_EARNED, s.earned)
        prefs.putInt(ComputeCredits.KEY_SPENT, s.spent)
        prefs.putString(ComputeCredits.KEY_COMPUTE_MS, s.computeMs.toString())
        prefs.putInt(ComputeCredits.KEY_JOBS, s.jobs)
        prefs.putString(ComputeCredits.KEY_AWARDED, awarded.joinToString("\n"))
        prefs.putString(ComputeCredits.KEY_LAST, s.lastAward?.let { encode(it) })
    }

    private fun encode(a: ComputeCredits.Award): String = listOf(a.planId, a.commander, a.commanderName, a.computeMs, a.chunks, a.points, a.ts).joinToString("\t")
    private fun decode(s: String): ComputeCredits.Award? {
        val p = s.split('\t')
        if (p.size != 7) return null
        return runCatching { ComputeCredits.Award(p[0], p[1], p[2], p[3].toLong(), p[4].toInt(), p[5].toInt(), p[6].toLong()) }.getOrNull()
    }

    private companion object {
        const val TAG = "Credits"
        const val RECENT = 5
    }
}
