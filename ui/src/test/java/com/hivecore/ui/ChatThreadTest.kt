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

/** The chat Home's conversation: bubbles in order, the turn in flight, suggestions and the policy pill. */
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
        val plan = vm.state.value.thread.last() as ChatTurn.Plan
        assertEquals(null, plan.running)

        vm.choose(RunMode.LOCAL); settle()
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

        vm.setRequest("count the photos"); vm.submit(); settle()
        vm.cancel()
        assertEquals("count the photos", vm.state.value.request)
        assertEquals(null, vm.state.value.inFlight)
    }

    @Test fun suggestionsAskAtOnceOrOpenJobs() = runTest {
        val vm = vm()
        var opened: String? = null
        vm.openRecipe = { opened = it }
        val jobs = AskSuggestions.DEFAULT.single { it.kind == Suggestion.Kind.JOBS }
        vm.useSuggestion(jobs)
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

    @Test fun thePolicyPillWritesTheSetting() = runTest {
        val prefs = MemoryPrefs()
        val vm = AskViewModel(port, prefs, backgroundScope)
        vm.setPolicy(CloudPolicy.FASTEST)
        assertEquals(CloudPolicy.FASTEST.key, prefs.getString(SettingsKeys.CLOUD_POLICY, null))
        assertEquals(CloudPolicy.FASTEST, vm.state.value.policy)
    }
}
