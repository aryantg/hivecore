package com.hivecore.ui

import com.hivecore.core.MemoryPrefs
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.ports.SettingsKeys
import com.hivecore.ui.ports.fakes.FakeAskPort
import com.hivecore.ui.vm.AskSuggestions
import com.hivecore.ui.vm.AskViewModel
import com.hivecore.ui.vm.ChatTurn
import com.hivecore.ui.vm.Suggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat Home's conversation: bubbles in order, the turn in flight, suggestions and the policy setting. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadTest {
    private val port = FakeAskPort()

    private fun TestScope.vm(policy: CloudPolicy = CloudPolicy.ASK): AskViewModel {
        val prefs = MemoryPrefs().apply { putString(SettingsKeys.CLOUD_POLICY, policy.key) }
        return AskViewModel(port, prefs, backgroundScope, clock = { 1_000L })
    }

    @Test fun emptyUntilTheFirstRequest() = runTest {
        assertTrue(vm().state.value.thread.isEmpty())
    }

    @Test fun theRequestBecomesABubbleAndTheComposerClears() = runTest {
        val vm = vm()
        vm.setRequest("count the photos"); vm.submit()
        val s = vm.state.value
        assertEquals("", s.request)
        assertEquals("count the photos", s.inFlight)
        assertEquals(listOf(ChatTurn.User::class, ChatTurn.Typing::class), s.thread.map { it::class })
        settle()
        assertEquals("the plan starts LOCAL without a tap", listOf(RunMode.LOCAL), port.runs.map { it.second })
        val done = vm.state.value
        assertEquals(null, done.inFlight)
        assertEquals(listOf(ChatTurn.User::class, ChatTurn.Answer::class), done.thread.map { it::class })
        assertEquals("count the photos", (done.thread[0] as ChatTurn.User).text)
    }

    @Test fun threadIsOldestFirst() = runTest {
        val vm = vm(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE)
        vm.setRequest("first"); vm.submit(); settle()
        vm.setRequest("second"); vm.submit(); settle()
        val users = vm.state.value.thread.filterIsInstance<ChatTurn.User>().map { it.text }
        assertEquals(listOf("first", "second"), users)
    }

    @Test fun refusalsAndCancelKeepTheText() = runTest {
        val vm = vm()
        vm.setRequest("show every salary"); vm.submit(); settle()
        assertTrue(vm.state.value.thread.last() is ChatTurn.Refused)

        port.delayMs = 10_000
        vm.setRequest("count the photos"); vm.submit(); runCurrent()
        vm.cancel()
        assertEquals("count the photos", vm.state.value.request)
        assertEquals(null, vm.state.value.inFlight)
    }

    @Test fun suggestionsAskAtOnceOrOpenJobs() = runTest {
        val vm = vm()
        var opened: String? = null
        vm.openRecipe = { opened = it }
        val jobs = AskSuggestions.DEFAULT.filter { it.kind == Suggestion.Kind.JOBS }
        assertEquals(listOf("stockist_letters", "photo_text"), jobs.map { it.recipeId })
        jobs.forEach { vm.useSuggestion(it) }
        assertEquals("photo_text", opened)
        assertTrue("a JOBS suggestion does not plan", port.planned.isEmpty())

        val ask = AskSuggestions.DEFAULT.first { it.kind == Suggestion.Kind.ASK }
        vm.useSuggestion(ask); settle()
        assertEquals(listOf(ask.request), port.planned)
    }

    @Test fun newChatClearsEverything() = runTest {
        val vm = vm(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE)
        vm.setRequest("first"); vm.submit(); settle()
        vm.setRequest("second"); vm.submit(); runCurrent()
        vm.clearHistory()
        assertTrue(vm.state.value.thread.isEmpty())
    }

    @Test fun setPolicyWritesTheSetting() = runTest {
        val prefs = MemoryPrefs()
        val vm = AskViewModel(port, prefs, backgroundScope)
        vm.setPolicy(CloudPolicy.FASTEST)
        assertEquals(CloudPolicy.FASTEST.key, prefs.getString(SettingsKeys.CLOUD_POLICY, null))
        assertEquals(CloudPolicy.FASTEST, vm.state.value.policy)
    }
}

/** The deck in the chat: the suggestion that asks for one, and the presenter state. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckInChatTest {
    @Test fun theFirstSuggestionAsksForTheDeck() {
        val s = AskSuggestions.DEFAULT.first()
        assertEquals(Suggestion.Kind.ASK, s.kind)
        assertEquals(AskSuggestions.DECK_REQUEST, s.request)
        assertEquals(6, AskSuggestions.DEFAULT.size)
    }

    @Test fun presentAndCloseDeck() = runTest {
        val vm = AskViewModel(FakeAskPort(), MemoryPrefs(), backgroundScope, clock = { 1_000L })
        val deck = com.hivecore.ui.screens.SampleDeck.DECK
        vm.present(deck)
        assertEquals(deck, vm.state.value.presenting)
        vm.closeDeck()
        assertEquals(null, vm.state.value.presenting)
    }
}
