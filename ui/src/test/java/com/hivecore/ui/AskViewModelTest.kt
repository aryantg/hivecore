package com.hivecore.ui

import com.hivecore.core.MemoryPrefs
import com.hivecore.ui.ports.AnswerBadge
import com.hivecore.ui.ports.AskEvent
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.ModeEstimate
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.Refusal
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.ports.SettingsKeys
import com.hivecore.ui.ports.fakes.FakeAskPort
import com.hivecore.ui.vm.AskViewModel
import com.hivecore.ui.vm.AskViewModel.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    private val port = FakeAskPort()
    private val prefs = MemoryPrefs()

    private fun TestScope.vm(policy: CloudPolicy = CloudPolicy.ASK): AskViewModel {
        prefs.putString(SettingsKeys.CLOUD_POLICY, policy.key)
        return AskViewModel(port, prefs, backgroundScope, clock = { 1_000L })
    }

    /** Every plan comes back with LOCAL unavailable, so the panel waits for a choice. */
    private fun noLocal() {
        port.planFor = { q -> FakeAskPort.defaultPlan(q).let { if (it is PlanOptions.Ready) it.copy(local = null, localBlockedReason = "no phone holds the model") else it } }
    }

    @Test fun askPolicyStartsLocalWithoutAChoice() = runTest {
        val vm = vm()
        port.delayMs = 50
        vm.setRequest("Read the invoice photos into a table")
        vm.submit(); runCurrent()
        assertEquals(Phase.Planning, vm.state.value.phase)
        settle()
        val s = vm.state.value
        assertEquals(Phase.Idle, s.phase)
        assertEquals(listOf(RunMode.LOCAL), port.runs.map { it.second })
        assertEquals(AnswerBadge.VERIFIED, s.history.single().answer!!.badge)
        assertEquals(RunMode.LOCAL, s.history.single().mode)
    }

    @Test fun askPolicyWaitsOnThePanelWhenLocalIsUnavailableThenRunsTheChosenMode() = runTest {
        noLocal()
        val vm = vm()
        port.delayMs = 50
        vm.setRequest("Read the invoice photos into a table"); vm.submit(); settle()
        val choosing = vm.state.value.phase as Phase.Choosing
        assertEquals(3, choosing.options.plan.steps.size)
        assertEquals("OCR · Accounts · 1.2 s ✓", AskViewModel.stepLine(choosing.options.plan.steps[0]))
        assertEquals("CLOUD · about 2.6 s on 1 phone · ₹1.20 · prompt text and 12 KB of released rows for EXTRACT", AskViewModel.estimateLine(choosing.options.cloud!!))
        assertTrue("nothing runs before the choice", port.runs.isEmpty())

        vm.choose(RunMode.CLOUD); runCurrent()
        assertTrue(vm.state.value.phase is Phase.Running)
        settle()
        val s = vm.state.value
        assertEquals(Phase.Idle, s.phase)
        assertEquals("", s.request)
        assertEquals(listOf(choosing.options.plan.planId to RunMode.CLOUD), port.runs)
        val a = s.history.single().answer!!
        assertEquals(AnswerBadge.CLOUD, a.badge)
        assertEquals(listOf("t2"), a.cloudSteps.map { it.id })
        assertEquals(2, a.provenance.size)
        assertEquals(RunMode.CLOUD, s.history.single().mode)
    }

    @Test fun localUnlessImpossibleStartsAtOnceAndFastestPicksTheQuickerOption() = runTest {
        val vm = vm(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE)
        vm.setRequest("count the photos"); vm.submit(); settle()
        assertEquals(listOf(RunMode.LOCAL), port.runs.map { it.second })
        assertEquals(AnswerBadge.VERIFIED, vm.state.value.history.single().answer!!.badge)

        val fast = vm(CloudPolicy.FASTEST)
        fast.setRequest("summarise the contract"); fast.submit(); settle()
        assertEquals(RunMode.CLOUD, port.runs.last().second)

        port.planFor = { PlanOptions.Ready(FakeAskPort.samplePlan(it), local = null, cloud = ModeEstimate(RunMode.CLOUD, 3_000, 2.0, "prompt text", 1), localBlockedReason = "no local model holds a 32k context") }
        val vm2 = vm(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE)
        vm2.setRequest("read a 90-page contract"); vm2.submit(); settle()
        assertEquals(RunMode.CLOUD, port.runs.last().second)
    }

    @Test fun clarificationUnderHalfConfidenceOffersTwoReadings() = runTest {
        val vm = vm()
        vm.setRequest("revenue per rep or revenue by region?"); vm.submit(); settle()
        val c = (vm.state.value.phase as Phase.Clarifying).clarify
        assertEquals(listOf("revenue per rep", "revenue by region"), c.readings)
        assertTrue(c.confidence < 0.5)
        vm.answerClarification("revenue by region"); settle()
        assertEquals(listOf("revenue per rep or revenue by region?", "revenue by region"), port.planned)
        assertEquals(listOf(RunMode.LOCAL), port.runs.map { it.second })
    }

    @Test fun refusalLandsInHistoryInPlainWordsWithPlanHashAndStep() = runTest {
        val vm = vm()
        vm.setRequest("What is Rahul's salary?"); vm.submit(); settle()
        val r = (vm.state.value.history.single().outcome as AskViewModel.Outcome.Refused).refusal
        assertEquals("HR's phone does not allow questions about individual salaries, so nothing ran.", r.sentence)
        assertEquals(listOf("plan 9f2c41ab77e0d3c5", "step AGG (t1)"), r.detailLines)
        assertEquals(Phase.Idle, vm.state.value.phase)
    }

    @Test fun cloudBlockedByPrivateLineageCannotBeChosen() = runTest {
        noLocal()
        val vm = vm()
        vm.setRequest("extract the private rows"); vm.submit(); settle()
        val o = (vm.state.value.phase as Phase.Choosing).options
        assertNull(o.cloud)
        assertEquals("every LLM step has private lineage", o.cloudBlockedReason)
        vm.choose(RunMode.CLOUD); runCurrent()
        assertEquals("CLOUD is not available: every LLM step has private lineage", vm.state.value.message)
        assertTrue(port.runs.isEmpty())
        assertTrue(vm.state.value.phase is Phase.Choosing)
    }

    @Test fun notAccurateReRunsThroughTheGateAndReplacesTheCard() = runTest {
        val vm = vm(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE)
        vm.setRequest("read the invoices"); vm.submit(); settle()
        val item = vm.state.value.history.single()
        assertEquals(AnswerBadge.VERIFIED, item.answer!!.badge)
        vm.notAccurate(item, "t2"); settle()
        assertEquals(listOf(item.answer!!.answerId to "t2"), port.notAccurateCalls)
        val again = vm.state.value.history.single()
        assertEquals(item.id, again.id)
        assertEquals(AnswerBadge.CLOUD, again.answer!!.badge)
        assertTrue(again.answer!!.steps.first { it.id == "t2" }.cloud)
        assertNull(vm.state.value.reRunningId)

        port.notAccurateFor = { _, _ -> listOf(AskEvent.Refused(Refusal("Two phones disagreed on the extraction and the data is private, so no cloud re-run.", "abc", "EXTRACT (t2)"))) }
        vm.notAccurate(again); settle()
        assertTrue(vm.state.value.history.single().outcome is AskViewModel.Outcome.Refused)
    }

    @Test fun emptyRequestErrorsAndCancelReturnsToIdle() = runTest {
        val vm = vm()
        vm.submit(); runCurrent()
        assertTrue(vm.state.value.phase is Phase.Error)
        vm.clearError()
        assertEquals(Phase.Idle, vm.state.value.phase)
        port.delayMs = 10_000
        vm.setRequest("slow"); vm.submit(); runCurrent()
        assertEquals(Phase.Planning, vm.state.value.phase)
        vm.cancel()
        assertEquals(Phase.Idle, vm.state.value.phase)
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test fun runFailureBecomesAPlainWordsRefusal() = runTest {
        port.runFor = { _, _ -> throw IllegalStateException("every phone left the swarm") }
        val vm = vm(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE)
        vm.setRequest("tag the photos"); vm.submit(); settle()
        val r = (vm.state.value.history.single().outcome as AskViewModel.Outcome.Refused).refusal
        assertEquals("The job stopped: every phone left the swarm", r.sentence)
        assertNotNull(r.planHash)
    }

    @Test fun aGuideAnswerLandsInHistoryWithoutARun() = runTest {
        port.planFor = { PlanOptions.Answered(com.hivecore.ui.ports.guideAnswer("I can draw an illustration of anything you describe.", "Accounts")) }
        val vm = vm()
        vm.setRequest("what can you create an image of"); vm.submit()
        runCurrent()
        val h = vm.state.value.history.single()
        val a = (h.outcome as AskViewModel.Outcome.Answer).answer
        assertEquals("I can draw an illustration of anything you describe.", a.text)
        assertEquals(AnswerBadge.VERIFIED, a.badge)
        assertTrue(a.steps.isEmpty())
        assertEquals(Phase.Idle, vm.state.value.phase)
        assertTrue("nothing ran", port.runs.isEmpty())
    }

    @Test fun defaultModeFollowsThePolicy() {
        val plan = FakeAskPort.samplePlan("q")
        val local = ModeEstimate(RunMode.LOCAL, 5_000, 0.0, "nothing", 3)
        val cloud = ModeEstimate(RunMode.CLOUD, 2_000, 1.0, "prompt", 1)
        val both = PlanOptions.Ready(plan, local, cloud)
        assertEquals(RunMode.LOCAL, AskViewModel.defaultMode(CloudPolicy.ASK, both))
        assertNull(AskViewModel.defaultMode(CloudPolicy.ASK, both.copy(local = null)))
        assertEquals(RunMode.LOCAL, AskViewModel.defaultMode(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE, both))
        assertEquals(RunMode.CLOUD, AskViewModel.defaultMode(CloudPolicy.FASTEST, both))
        assertEquals(RunMode.LOCAL, AskViewModel.defaultMode(CloudPolicy.FASTEST, both.copy(cloud = null)))
        assertNull(AskViewModel.defaultMode(CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE, both.copy(local = null, cloud = null)))
    }
}
