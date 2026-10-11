package com.hivecore.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hivecore.ui.Format
import com.hivecore.ui.Screen
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.Composer
import com.hivecore.ui.design.HeaderAction
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveButton
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HivePreviews
import com.hivecore.ui.design.HiveScreen
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.InlineNotice
import com.hivecore.ui.design.KeyValue
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.StatTile
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.StepItem
import com.hivecore.ui.design.StepList
import com.hivecore.ui.design.SuggestionCard
import com.hivecore.ui.design.SwarmTurn
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.TypingIndicator
import com.hivecore.ui.design.UserBubble
import com.hivecore.ui.ports.AnswerBadge
import com.hivecore.ui.ports.AskAnswer
import com.hivecore.ui.ports.ModeEstimate
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.ports.fakes.FakeAskPort
import com.hivecore.ui.vm.AskSuggestions
import com.hivecore.ui.vm.AskViewModel
import com.hivecore.ui.vm.ChatTurn
import com.hivecore.ui.vm.Suggestion
import com.hivecore.engine.deck.Deck

/**
 * Ask = the chat Home (spec section 17 Ask, laid out like a chat assistant): a greeting and suggestion
 * cards when empty, then the conversation with the plan, the run option and the answers inline, and the
 * composer pinned at the bottom. [statusLine] is the live swarm line under the greeting ("3 phones ready").
 */
@Composable
fun AskRoute(vm: AskViewModel, onOpenJob: (String) -> Unit = {}, onOpenRecipe: (String) -> Unit = {}, statusLine: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshPolicy(); vm.openJob = onOpenJob; vm.openRecipe = onOpenRecipe }
    AskScreen(state, onRequest = vm::setRequest, onSubmit = vm::submit, onCancel = vm::cancel, onChoose = vm::choose, onReading = vm::answerClarification,
        onNotAccurate = { item, step -> vm.notAccurate(item, step) }, onToggleSteps = vm::toggleSteps, onOpenJob = vm::openJob,
        onClearError = vm::clearError, onClearHistory = vm::clearHistory, onDismissMessage = vm::dismissMessage,
        onSuggestion = vm::useSuggestion, onAttach = { onOpenRecipe("photo_text") }, statusLine = statusLine,
        onPresent = vm::present, onCloseDeck = vm::closeDeck, onOpenFile = { vm.openFile(it) }, onShareFile = { vm.shareFile(it) },
        onAcceptOffer = vm::acceptOffer, onDeclineOffer = vm::declineOffer)
}

@Composable
fun AskScreen(
    state: AskViewModel.UiState,
    onRequest: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onChoose: (RunMode) -> Unit,
    onReading: (String) -> Unit,
    onNotAccurate: (AskViewModel.HistoryItem, String?) -> Unit,
    onToggleSteps: (Long) -> Unit,
    onOpenJob: (AskViewModel.HistoryItem) -> Unit,
    onClearError: () -> Unit,
    onClearHistory: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onSuggestion: (Suggestion) -> Unit = {},
    onAttach: (() -> Unit)? = null,
    statusLine: String? = null,
    onPresent: (Deck) -> Unit = {},
    onCloseDeck: () -> Unit = {},
    onOpenFile: ((String) -> Unit)? = null,
    onShareFile: ((String) -> Unit)? = null,
    onAcceptOffer: () -> Unit = {},
    onDeclineOffer: () -> Unit = {},
) {
    val thread = state.thread
    state.presenting?.let { DeckSheet(it, onOpenFile, onShareFile, onClose = onCloseDeck) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val last = thread.lastOrNull()
    val lastStatus = (last as? ChatTurn.Plan)?.running?.second ?: (last as? ChatTurn.Typing)?.status
    LaunchedEffect(thread.size, last?.key, lastStatus) { if (thread.isNotEmpty()) listState.animateScrollToItem(thread.lastIndex) }
    val choosingOrBusy = state.busy || state.phase is AskViewModel.Phase.Choosing || state.phase is AskViewModel.Phase.Clarifying || state.phase is AskViewModel.Phase.Offering

    HiveScreen(
        Screen.ASK, modifier,
        actions = { if (thread.isNotEmpty()) HeaderAction(HiveIcons.newChat, "New chat", onClick = onClearHistory) },
        message = state.message, onDismissMessage = onDismissMessage,
        listState = listState,
        showEmpty = thread.isEmpty(),
        emptyContent = { Greeting(statusLine, state.suggestions, onSuggestion) },
        bottomBar = {
            Composer(
                value = state.request, onChange = onRequest, onSend = onSubmit, onStop = onCancel, busy = choosingOrBusy,
                placeholder = "Ask your phones anything",
                suggestions = if (thread.isNotEmpty() && !choosingOrBusy) state.suggestions.map { it.title } else emptyList(),
                onSuggest = { title -> state.suggestions.firstOrNull { it.title == title }?.let(onSuggestion) },
                menu = listOfNotNull(
                    onAttach?.let { HiveAction("Read photos into a table", HiveIcons.photo, onClick = it) },
                    HiveAction("New chat", HiveIcons.newChat, enabled = thread.isNotEmpty(), onClick = onClearHistory),
                ),
            )
        },
    ) {
        thread.forEach { t ->
            item(t.key) {
                when (t) {
                    is ChatTurn.User -> UserBubble(t.text)
                    is ChatTurn.Typing -> SwarmTurn { TypingIndicator(t.status) }
                    is ChatTurn.Clarify -> SwarmTurn { ClarifyBody(t.clarify, onReading) }
                    is ChatTurn.Offer -> SwarmTurn { OfferBody(t.offer, onAcceptOffer, onDeclineOffer) }
                    is ChatTurn.Plan -> SwarmTurn { PlanBody(t.options, t.running, onChoose) }
                    is ChatTurn.Answer -> SwarmTurn {
                        AnswerBody(t.item, t.answer, expanded = t.item.id in state.expandedSteps, reRunning = state.reRunningId == t.answer.answerId,
                            onToggle = { onToggleSteps(t.item.id) }, onNotAccurate = { onNotAccurate(t.item, null) }, onOpenJob = { onOpenJob(t.item) },
                            onPresent = onPresent, onOpenFile = onOpenFile, onShareFile = onShareFile)
                    }
                    is ChatTurn.Refused -> SwarmTurn { RefusalCard(t.refusal, title = "Nothing ran") }
                    is ChatTurn.Error -> SwarmTurn {
                        InlineNotice(t.message, Tone.Danger, icon = HiveIcons.warning)
                        HiveButton(HiveAction("Edit and try again", HiveIcons.refresh, onClick = onClearError), ButtonKind.Text)
                    }
                }
            }
        }
    }
}

/** The empty Home: the mark, the greeting, the live swarm line and four suggestion cards. */
@Composable
private fun Greeting(statusLine: String?, suggestions: List<Suggestion>, onSuggestion: (Suggestion) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = HiveSpacing.screenH, vertical = HiveSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(HiveSpacing.iconHero).clip(HiveShape.circle).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(HiveIcons.hexMark, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(HiveSpacing.iconXl))
        }
        Spacer(Modifier.height(HiveSpacing.lg))
        Text("What should your phones do?", style = HiveTextStyles.greeting, textAlign = TextAlign.Center)
        Spacer(Modifier.height(HiveSpacing.xs))
        Text(statusLine ?: "Your phones plan it together. Private data never leaves the phone that owns it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(HiveSpacing.xl))
        Column(Modifier.widthIn(max = HiveSpacing.maxContent), verticalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            suggestions.chunked(2).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
                    row.forEach { s ->
                        SuggestionCard(if (s.kind == Suggestion.Kind.JOBS) HiveIcons.photo else HiveIcons.ask, s.title, s.hint, onClick = { onSuggestion(s) },
                            modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The offer (spec amendment "open paths"): why nothing on the phones covers it, and the yes / no to the model's own knowledge. */
@Composable
private fun OfferBody(o: PlanOptions.Offer, onAccept: () -> Unit, onDecline: () -> Unit) {
    Text(o.sentence, style = MaterialTheme.typography.bodyLarge)
    StatusPill("not from your data", Tone.Warn, icon = HiveIcons.info)
    ActionRow(
        primary = HiveAction("Use the model's knowledge", HiveIcons.ask, onClick = onAccept),
        secondaries = listOf(HiveAction("No, leave it", HiveIcons.close, onClick = onDecline)),
    )
}

@Composable
private fun ClarifyBody(c: PlanOptions.Clarify, onReading: (String) -> Unit) {
    Text(c.question, style = MaterialTheme.typography.bodyLarge)
    StatusPill("confidence ${(c.confidence * 100).toInt()} %", Tone.Warn)
    c.readings.forEach { r -> HiveButton(HiveAction(r, HiveIcons.ask) { onReading(r) }, ButtonKind.Tonal, Modifier.fillMaxWidth()) }
}

/** The plan card (spec section 17): one step per line, then the LOCAL run option. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanBody(o: PlanOptions.Ready, running: Pair<RunMode, String>?, onChoose: (RunMode) -> Unit) {
    val plan = o.plan
    Text(if (running == null) "Here is the plan. Tap to run it on your phones." else "Running ${running.first.name.lowercase()}.", style = MaterialTheme.typography.bodyLarge)
    HiveCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
            StatusPill("plan ${plan.tier}", Tone.Accent)
            StatusPill("confidence ${(plan.confidence * 100).toInt()} %", if (plan.confidence >= 0.8) Tone.Ok else Tone.Neutral)
            StatusPill(Format.plural(plan.steps.size, "step"), Tone.Neutral)
        }
        Spacer(Modifier.height(HiveSpacing.md))
        StepList(plan.steps.map { s ->
            StepItem(s.op, "${s.phone} · ${Format.duration(s.estimateMs)}", cloud = s.cloud, verified = s.verified, a11y = "Step ${s.op} on ${s.phone}")
        })
        if (com.hivecore.ui.design.LocalShowHashes.current) Text("plan ${Format.shortHash(plan.planHash)}", style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ModeOption(o.local, RunMode.LOCAL, o.localBlockedReason, selected = running?.first == RunMode.LOCAL,
        enabled = running == null || running.first != RunMode.LOCAL, onChoose, Modifier.fillMaxWidth())
    if (running != null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TypingIndicator(running.second)
    }
}

@Composable
private fun ModeOption(e: ModeEstimate?, mode: RunMode, blocked: String?, selected: Boolean, enabled: Boolean, onChoose: (RunMode) -> Unit, modifier: Modifier) {
    val available = e != null
    HiveCard(
        modifier, tone = if (mode == RunMode.CLOUD) CardTone.Cloud else CardTone.Tonal,
        onClick = if (available && enabled) { { onChoose(mode) } } else null,
        selected = selected, enabled = available, contentPadding = HiveSpacing.cardTight,
        semantics = { stateDescription = when { selected -> "running"; !available -> "unavailable"; else -> "available" } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (mode == RunMode.CLOUD) HiveIcons.cloud else HiveIcons.phone, contentDescription = null, modifier = Modifier.size(HiveSpacing.iconSm))
            Spacer(Modifier.width(HiveSpacing.xs))
            Text(mode.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        if (e != null) {
            StatTile(Stat("~" + Format.duration(e.ms), "${Format.plural(e.phones, "phone")} · ${Format.inr(e.costInr)}"))
            Spacer(Modifier.height(HiveSpacing.xs))
            Text("Leaves: ${e.leaves}", style = MaterialTheme.typography.bodySmall)
        } else Text("Not available: ${blocked ?: "no estimate"}", style = MaterialTheme.typography.bodySmall)
    }
}

/** The answer with provenance and badges (verified, unverified, cloud), the deck card when the plan produced one, and the "not accurate" action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerBody(item: AskViewModel.HistoryItem, a: AskAnswer, expanded: Boolean, reRunning: Boolean, onToggle: () -> Unit, onNotAccurate: () -> Unit, onOpenJob: () -> Unit,
               onPresent: (Deck) -> Unit = {}, onOpenFile: ((String) -> Unit)? = null, onShareFile: ((String) -> Unit)? = null) {
    Text(a.text, style = MaterialTheme.typography.bodyLarge)
    a.deck?.let { d -> DeckCard(d, onPresent = { onPresent(d) }, onOpenFile = onOpenFile, onShareFile = onShareFile) }
    a.picture?.let { p -> PictureCard(p, onOpenFile = onOpenFile, onShareFile = onShareFile) }
    a.chart?.let { c -> ChartCard(c) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs), verticalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
        when (a.badge) {
            AnswerBadge.VERIFIED -> StatusPill("verified", Tone.Ok, icon = HiveIcons.check)
            AnswerBadge.UNVERIFIED -> StatusPill("unverified", Tone.Warn, icon = HiveIcons.warning)
            AnswerBadge.CLOUD -> StatusPill("cloud", Tone.Cloud, icon = HiveIcons.cloud)
        }
        StatusPill(a.mode.name.lowercase(), Tone.Neutral, icon = if (a.mode == RunMode.CLOUD) HiveIcons.cloud else HiveIcons.phone)
        if (a.costInr > 0) StatusPill(Format.inr(a.costInr), Tone.Neutral)
        if (a.elapsedMs > 0) StatusPill(Format.duration(a.elapsedMs), Tone.Neutral, icon = HiveIcons.history)
    }
    if (a.provenance.isNotEmpty() || a.notes.isNotEmpty() || a.steps.isNotEmpty()) {
        HiveCard(tone = CardTone.Answer, contentPadding = HiveSpacing.cardTight) {
            Text("Where this came from", style = MaterialTheme.typography.labelLarge)
            a.provenance.forEach { p -> KeyValue(p.node, p.line) }
            a.notes.forEach { n -> Text("Note: $n", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.plural(a.steps.size, "step") + if (com.hivecore.ui.design.LocalShowHashes.current) " · plan ${Format.shortHash(a.planHash)}" else "", style = HiveTextStyles.monoSmall, modifier = Modifier.weight(1f))
                HiveButton(HiveAction(if (expanded) "Hide steps" else "Show steps", if (expanded) HiveIcons.collapse else HiveIcons.expand, onClick = onToggle), ButtonKind.Text)
            }
            if (expanded) StepList(a.steps.map { s -> StepItem(s.op, "${s.phone} · ${Format.duration(s.estimateMs)}", cloud = s.cloud, verified = s.verified) })
        }
    }
    ActionRow(secondaries = listOfNotNull(
        HiveAction(if (reRunning) "Re-running…" else "Not accurate", HiveIcons.refresh, enabled = !reRunning, onClick = onNotAccurate),
        if (a.jobId != null) HiveAction("Open job", HiveIcons.jobs, onClick = onOpenJob) else null,
    ))
}

@HivePreviews
@Composable
private fun HomeEmptyPreview() = HiveCoreTheme {
    AskScreen(AskViewModel.UiState(), {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, statusLine = "3 phones ready · qwen3-4b loaded · nothing leaves your phones")
}

@HivePreviews
@Composable
private fun HomeDeckPreview() = HiveCoreTheme {
    val deck = SampleDeck.DECK.copy(file = "/data/decks/deck-3f9a1c.pptx", bytes = 48_120)
    val answer = AskAnswer("a1", "7e1d0a90", "${'$'}{deck.summary} Deck: 5 slides, \"${'$'}{deck.title}\", saved as .pptx.",
        listOf(com.hivecore.core.Provenance("Sales", "2,880 rows → 36 monthly totals"), com.hivecore.core.Provenance("This phone", "5 slides assembled")),
        AnswerBadge.UNVERIFIED, emptyList(), RunMode.LOCAL, deck = deck, elapsedMs = 9_200)
    val item = AskViewModel.HistoryItem(1, AskSuggestions.DECK_REQUEST, AskViewModel.Outcome.Answer(answer), 0L, RunMode.LOCAL)
    AskScreen(AskViewModel.UiState(history = listOf(item)), {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, onOpenFile = {}, onShareFile = {})
}

@HivePreviews
@Composable
private fun HomeChatPreview() = HiveCoreTheme {
    val options = FakeAskPort.defaultPlan("Revenue per rep by region this year") as PlanOptions.Ready
    AskScreen(AskViewModel.UiState(inFlight = "Revenue per rep by region this year", phase = AskViewModel.Phase.Choosing(options), suggestions = AskSuggestions.DEFAULT),
        {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})
}
