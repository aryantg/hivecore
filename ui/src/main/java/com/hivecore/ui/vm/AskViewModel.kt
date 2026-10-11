package com.hivecore.ui.vm

import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.SLog
import com.hivecore.engine.deck.Deck
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
        /** Nothing on the phones covers it: the offer to answer from the model's own knowledge waits for a yes or no. */
        data class Offering(val offer: PlanOptions.Offer) : Phase
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
        /** the request being planned or run: the user's latest chat bubble (the composer is already cleared) */
        val inFlight: String? = null,
        val suggestions: List<Suggestion> = AskSuggestions.DEFAULT,
        /** the deck open in the presenter sheet */
        val presenting: Deck? = null,
    ) {
        val busy: Boolean get() = phase is Phase.Planning || phase is Phase.Running
        /** The conversation, oldest first (the chat Home renders it). */
        val thread: List<ChatTurn> get() = ChatThread.of(this)
        /** The plan panel to show: the choice, or the running plan. */
        val panel: PlanOptions.Ready? get() = when (val p = phase) { is Phase.Choosing -> p.options; is Phase.Running -> p.options; else -> null }
    }

    private val _state = MutableStateFlow(UiState(policy = CloudPolicy.of(prefs.getString(SettingsKeys.CLOUD_POLICY, null))))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Opens the Jobs screen on a job (set by the app's navigation). */
    var openJob: (String) -> Unit = { SLog.w(TAG, "openJob not wired: $it") }

    /** Opens the Jobs screen with a recipe selected (a suggestion that needs files, set by the app's navigation). */
    var openRecipe: (String) -> Unit = { SLog.w(TAG, "openRecipe not wired: $it") }

    /** Opens a produced file (a deck's .pptx) in another app; set by the app (a content URI through its file provider). */
    var openFile: (String) -> Unit = { SLog.w(TAG, "openFile not wired: $it") }

    /** Hands a produced file to the share sheet; set by the app. */
    var shareFile: (String) -> Unit = { SLog.w(TAG, "shareFile not wired: $it") }
    /** A request left the composer (the compute points spend on it). */
    var onSubmitted: (String) -> Unit = {}

    private var job: Job? = null
    private var nextId = 1L

    fun setRequest(text: String) { _state.update { it.copy(request = text) } }

    /** Re-reads the cloud policy (the Settings screen may have changed it). */
    fun refreshPolicy() { _state.update { it.copy(policy = CloudPolicy.of(prefs.getString(SettingsKeys.CLOUD_POLICY, null))) } }

    /** Submit: plans the request, then starts per the policy (waits on the plan panel only when nothing can start). */
    fun submit() {
        val q = _state.value.request.trim()
        if (q.isEmpty()) { _state.update { it.copy(phase = Phase.Error("Type a request first")) }; return }
        if (_state.value.busy) return
        refreshPolicy()
        job?.cancel()
        _state.update { it.copy(phase = Phase.Planning, message = null, inFlight = q, request = "") }
        SLog.d(TAG, "plan: $q")
        runCatching { onSubmitted(q) }.onFailure { SLog.w(TAG, "onSubmitted failed: ${it.message}") }
        job = scope.launch {
            val options = try { port.plan(q) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                SLog.e(TAG, "plan failed", e)
                _state.update { it.copy(phase = Phase.Error("Planning failed: ${e.message ?: e.javaClass.simpleName}")) }
                return@launch
            }
            when (options) {
                is PlanOptions.Answered -> record(q, Outcome.Answer(options.answer), null)
                is PlanOptions.Offer -> _state.update { it.copy(phase = Phase.Offering(options)) }
                is PlanOptions.Refused -> record(q, Outcome.Refused(options.refusal), null)
                is PlanOptions.Clarify -> _state.update { it.copy(phase = Phase.Clarifying(options)) }
                is PlanOptions.Ready -> {
                    val pick = defaultMode(_state.value.policy, options)
                    if (pick == null) _state.update { it.copy(phase = Phase.Choosing(options)) }
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

    /** The user said yes to answering from the model's own knowledge: plan the one-step run, then start per the policy. */
    fun acceptOffer() {
        val offer = (_state.value.phase as? Phase.Offering)?.offer ?: return
        _state.update { it.copy(phase = Phase.Planning, message = null) }
        SLog.d(TAG, "knowledge: ${offer.question}")
        job = scope.launch {
            val options = try { port.knowledge(offer.question) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                SLog.e(TAG, "knowledge plan failed", e)
                _state.update { it.copy(phase = Phase.Error("Planning failed: ${e.message ?: e.javaClass.simpleName}")) }
                return@launch
            }
            when (options) {
                is PlanOptions.Ready -> {
                    val pick = defaultMode(_state.value.policy, options)
                    if (pick == null) _state.update { it.copy(phase = Phase.Choosing(options)) } else start(options, pick)
                }
                is PlanOptions.Refused -> record(offer.question, Outcome.Refused(options.refusal), null)
                is PlanOptions.Answered -> record(offer.question, Outcome.Answer(options.answer), null)
                is PlanOptions.Clarify -> _state.update { it.copy(phase = Phase.Clarifying(options)) }
                is PlanOptions.Offer -> record(offer.question, Outcome.Refused(Refusal(options.sentence, detail = options.detail)), null)
            }
        }
    }

    /** The user said no: the exchange ends with the offer's sentence as a plain refusal. */
    fun declineOffer() {
        val offer = (_state.value.phase as? Phase.Offering)?.offer ?: return
        record(offer.question, Outcome.Refused(Refusal("Nothing ran: the question isn't covered by your phones' data, and the model's own knowledge was not used.", detail = offer.detail)), null)
    }

    fun cancel() {
        job?.cancel(); job = null
        _state.update {
            if (it.busy || it.phase is Phase.Choosing || it.phase is Phase.Clarifying || it.phase is Phase.Offering)
                it.copy(phase = Phase.Idle, request = it.request.ifEmpty { it.inFlight.orEmpty() }, inFlight = null)
            else it
        }
    }

    fun clearError() { _state.update { if (it.phase is Phase.Error) it.copy(phase = Phase.Idle, request = it.request.ifEmpty { it.inFlight.orEmpty() }, inFlight = null) else it } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }

    /** "New chat": clears the conversation (a plan or run in flight is cancelled first). */
    fun clearHistory() {
        if (_state.value.busy || _state.value.phase !is Phase.Idle) { job?.cancel(); job = null }
        _state.update { it.copy(history = emptyList(), expandedSteps = emptySet(), phase = Phase.Idle, inFlight = null) }
    }

    /** A suggestion card or chip: asks it at once, or opens Jobs when it needs files. */
    fun useSuggestion(s: Suggestion) {
        when (s.kind) {
            Suggestion.Kind.ASK -> { if (_state.value.busy) return; setRequest(s.request); submit() }
            Suggestion.Kind.JOBS -> s.recipeId?.let { openRecipe(it) }
        }
    }

    /** The composer's policy pill: the same setting as Settings' cloud policy. */
    fun setPolicy(p: CloudPolicy) { prefs.putString(SettingsKeys.CLOUD_POLICY, p.key); _state.update { it.copy(policy = p) } }
    /** "Present": the deck fills the presenter sheet; [closeDeck] puts it away. */
    fun present(deck: Deck) { _state.update { it.copy(presenting = deck) } }
    fun closeDeck() { _state.update { it.copy(presenting = null) } }

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
        _state.update { it.copy(phase = Phase.Idle, inFlight = null, history = listOf(item) + it.history) }
        SLog.d(TAG, "done: ${outcome::class.simpleName}")
    }

    companion object {
        private const val TAG = "AskVM"

        fun estimate(o: PlanOptions.Ready, mode: RunMode): ModeEstimate? = if (mode == RunMode.LOCAL) o.local else o.cloud

        fun blockedReason(o: PlanOptions.Ready, mode: RunMode): String =
            (if (mode == RunMode.LOCAL) o.localBlockedReason else o.cloudBlockedReason)?.let { "${mode.name} is not available: $it" }
                ?: "${mode.name} is not available for this plan"

        /**
         * Spec section 9.2: the policy's default, or null when nothing can start on its own. Home offers no cloud
         * choice, so ASK starts LOCAL at once and waits on the plan panel only when LOCAL is unavailable.
         */
        fun defaultMode(policy: CloudPolicy, o: PlanOptions.Ready): RunMode? = when (policy) {
            CloudPolicy.ASK -> if (o.local != null) RunMode.LOCAL else null
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
