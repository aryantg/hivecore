package com.hivecore.ui.vm

import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.SLog
import com.hivecore.ui.ports.AskAnswer
import com.hivecore.ui.ports.AskEvent
import com.hivecore.ui.ports.AskPlan
import com.hivecore.ui.ports.AskPort
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.ModeEstimate
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.Refusal
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.ports.SettingsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ask screen (spec section 17): the request, the plan panel (one line per step), the LOCAL / CLOUD choice with
 * estimates, cost and "what leaves", clarification under 0.5 confidence, and the answers with provenance and badges.
 * Codes against [AskPort] and `KeyValuePrefs` (the cloud policy) only, so it runs on the JVM with the fakes.
 *
 * Policy (spec section 9.2): ASK shows both options and waits; LOCAL_UNLESS_IMPOSSIBLE and FASTEST preselect their
 * default and start at once; the panel stays on screen so the user can cancel and flip the choice.
 */
class AskViewModel(
    private val port: AskPort,
    private val prefs: KeyValuePrefs,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Phase {
        data object Idle : Phase
        data object Planning : Phase
        /** The plan panel with the choice; nothing runs yet. */
        data class Choosing(val options: PlanOptions.Ready) : Phase
        data class Clarifying(val clarify: PlanOptions.Clarify) : Phase
        data class Running(val plan: AskPlan, val mode: RunMode, val status: String, val options: PlanOptions.Ready?) : Phase
        data class Error(val message: String) : Phase
    }

    sealed interface Outcome {
        data class Answer(val answer: AskAnswer) : Outcome
        data class Refused(val refusal: Refusal) : Outcome
    }

    data class HistoryItem(val id: Long, val request: String, val outcome: Outcome, val askedAt: Long, val mode: RunMode?) {
        val answer: AskAnswer? get() = (outcome as? Outcome.Answer)?.answer
    }

    data class UiState(
        val request: String = "",
        val phase: Phase = Phase.Idle,
        val policy: CloudPolicy = CloudPolicy.ASK,
        /** newest first */
        val history: List<HistoryItem> = emptyList(),
        /** the answer whose "not accurate" re-run is in flight */
        val reRunningId: String? = null,
        /** history ids whose step list is expanded */
        val expandedSteps: Set<Long> = emptySet(),
        val message: String? = null,
    ) {
        val busy: Boolean get() = phase is Phase.Planning || phase is Phase.Running
        /** The plan panel to show: the choice, or the running plan. */
        val panel: PlanOptions.Ready? get() = when (val p = phase) { is Phase.Choosing -> p.options; is Phase.Running -> p.options; else -> null }
    }

    private val _state = MutableStateFlow(UiState(policy = CloudPolicy.of(prefs.getString(SettingsKeys.CLOUD_POLICY, null))))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Opens the Jobs screen on a job (set by the app's navigation). */
    var openJob: (String) -> Unit = { SLog.w(TAG, "openJob not wired: $it") }

    private var job: Job? = null
    private var nextId = 1L

    fun setRequest(text: String) { _state.update { it.copy(request = text) } }

    /** Re-reads the cloud policy (the Settings screen may have changed it). */
    fun refreshPolicy() { _state.update { it.copy(policy = CloudPolicy.of(prefs.getString(SettingsKeys.CLOUD_POLICY, null))) } }

    /** Submit: plans the request, then either waits for the choice or starts per the policy. */
    fun submit() {
        val q = _state.value.request.trim()
        if (q.isEmpty()) { _state.update { it.copy(phase = Phase.Error("Type a request first")) }; return }
        if (_state.value.busy) return
        refreshPolicy()
        job?.cancel()
        _state.update { it.copy(phase = Phase.Planning, message = null) }
        SLog.d(TAG, "plan: $q")
        job = scope.launch {
            val options = try { port.plan(q) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                SLog.e(TAG, "plan failed", e)
                _state.update { it.copy(phase = Phase.Error("Planning failed: ${e.message ?: e.javaClass.simpleName}")) }
                return@launch
            }
            when (options) {
                is PlanOptions.Refused -> record(q, Outcome.Refused(options.refusal), null)
                is PlanOptions.Clarify -> _state.update { it.copy(phase = Phase.Clarifying(options)) }
                is PlanOptions.Ready -> {
                    val policy = _state.value.policy
                    val pick = defaultMode(policy, options)
                    if (policy == CloudPolicy.ASK || pick == null) _state.update { it.copy(phase = Phase.Choosing(options)) }
                    else start(options, pick)
                }
            }
        }
    }

    /** Pick LOCAL or CLOUD on the plan panel (also flips a running job: cancels it and starts the other option). */
    fun choose(mode: RunMode) {
        val options = _state.value.panel ?: return
        val est = estimate(options, mode) ?: run { _state.update { it.copy(message = blockedReason(options, mode)) }; return }
        if (_state.value.phase is Phase.Running) job?.cancel()
        SLog.d(TAG, "choose $mode (${est.ms} ms, ${est.costInr} INR)")
        start(options, mode)
    }

    /** The user picked one of the two readings: plan it. */
    fun answerClarification(reading: String) {
        if (_state.value.busy) return
        _state.update { it.copy(request = reading, phase = Phase.Idle) }
        submit()
    }

    fun cancel() {
        job?.cancel(); job = null
        _state.update { if (it.busy || it.phase is Phase.Choosing || it.phase is Phase.Clarifying) it.copy(phase = Phase.Idle) else it }
    }

    fun clearError() { _state.update { if (it.phase is Phase.Error) it.copy(phase = Phase.Idle) else it } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun clearHistory() { _state.update { it.copy(history = emptyList(), expandedSteps = emptySet()) } }
    fun toggleSteps(id: Long) { _state.update { it.copy(expandedSteps = if (id in it.expandedSteps) it.expandedSteps - id else it.expandedSteps + id) } }
    fun openJob(item: HistoryItem) { item.answer?.jobId?.let { openJob(it) } }

    /** "Not accurate" on a result: the gate re-runs the step on the cloud and the new answer replaces the card (spec section 9.4). */
    fun notAccurate(item: HistoryItem, stepId: String? = null) {
        val a = item.answer ?: return
        if (_state.value.reRunningId != null) return
        _state.update { it.copy(reRunningId = a.answerId, message = null) }
        scope.launch {
            try {
                var replaced = false
                port.notAccurate(a, stepId).collect { e ->
                    when (e) {
                        is AskEvent.Status -> _state.update { it.copy(message = e.text) }
                        is AskEvent.Answered -> { replaced = true; _state.update { s -> s.copy(reRunningId = null, message = null,
                            history = s.history.map { h -> if (h.id == item.id) h.copy(outcome = Outcome.Answer(e.answer), mode = e.answer.mode) else h }) } }
                        is AskEvent.Refused -> { replaced = true; _state.update { s -> s.copy(reRunningId = null, message = null,
                            history = s.history.map { h -> if (h.id == item.id) h.copy(outcome = Outcome.Refused(e.refusal)) else h }) } }
                    }
                }
                if (!replaced) _state.update { it.copy(reRunningId = null, message = "The gate did not answer") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SLog.e(TAG, "not-accurate re-run failed", e)
                _state.update { it.copy(reRunningId = null, message = "Re-run failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun start(options: PlanOptions.Ready, mode: RunMode) {
        val plan = options.plan
        _state.update { it.copy(phase = Phase.Running(plan, mode, "Starting…", options)) }
        job = scope.launch {
            try {
                var ended = false
                port.run(plan, mode).collect { e ->
                    when (e) {
                        is AskEvent.Status -> _state.update { s -> (s.phase as? Phase.Running)?.let { r -> s.copy(phase = r.copy(status = e.text)) } ?: s }
                        is AskEvent.Answered -> { ended = true; record(plan.request, Outcome.Answer(e.answer), mode) }
                        is AskEvent.Refused -> { ended = true; record(plan.request, Outcome.Refused(e.refusal), mode) }
                    }
                }
                if (!ended) _state.update { it.copy(phase = Phase.Error("The run ended without an answer")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SLog.e(TAG, "run failed", e)
                record(plan.request, Outcome.Refused(Refusal("The job stopped: ${e.message ?: e.javaClass.simpleName}", plan.planHash)), mode)
            }
        }
    }

    private fun record(request: String, outcome: Outcome, mode: RunMode?) {
        val item = HistoryItem(nextId++, request, outcome, clock(), mode)
        _state.update { it.copy(phase = Phase.Idle, request = "", history = listOf(item) + it.history) }
        SLog.d(TAG, "done: ${outcome::class.simpleName}")
    }

    companion object {
        private const val TAG = "AskVM"

        fun estimate(o: PlanOptions.Ready, mode: RunMode): ModeEstimate? = if (mode == RunMode.LOCAL) o.local else o.cloud

        fun blockedReason(o: PlanOptions.Ready, mode: RunMode): String =
            (if (mode == RunMode.LOCAL) o.localBlockedReason else o.cloudBlockedReason)?.let { "${mode.name} is not available: $it" }
                ?: "${mode.name} is not available for this plan"

        /** Spec section 9.2: the policy's default, or null when the policy wants the user to choose. */
        fun defaultMode(policy: CloudPolicy, o: PlanOptions.Ready): RunMode? = when (policy) {
            CloudPolicy.ASK -> null
            CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE -> when { o.local != null -> RunMode.LOCAL; o.cloud != null -> RunMode.CLOUD; else -> null }
            CloudPolicy.FASTEST -> listOfNotNull(o.local, o.cloud).minByOrNull { it.ms }?.mode
        }

        /** "OCR · Accounts · 1.2 s" (+ " · cloud" for a cloud step, " ✓" when verified). */
        fun stepLine(s: com.hivecore.ui.ports.PlanStepView): String =
            "${s.op} · ${s.phone} · ${com.hivecore.ui.Format.duration(s.estimateMs)}" + (if (s.cloud) " · cloud" else "") + (if (s.verified == true) " ✓" else "")

        /** "LOCAL · about 4.1 s on 3 phones · ₹0 · nothing leaves the phones". */
        fun estimateLine(e: ModeEstimate): String =
            "${e.mode.name} · about ${com.hivecore.ui.Format.duration(e.ms)} on ${com.hivecore.ui.Format.plural(e.phones, "phone")} · ${com.hivecore.ui.Format.inr(e.costInr)} · ${e.leaves}"
    }
}
