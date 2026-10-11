package com.hivecore.ui.demo

import com.hivecore.core.ChunkState
import com.hivecore.core.JobRequest
import com.hivecore.ui.ports.AskEvent
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.settle
import com.hivecore.ui.vm.AskSuggestions
import com.hivecore.ui.vm.ResultView
import com.hivecore.ui.vm.ResultViews
import com.hivecore.ui.vm.Suggestion
import com.hivecore.ui.vm.SwarmViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo mode's scripts: deterministic, believable, and shaped like the real thing. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoScriptTest {
    private val photos = JobRequest("Photo text (OCR)", recipeId = "photo_text", inputs = List(120) { "/cache/picked/" + InvoiceTexts.fileName(it) })

    @Test fun theJobSplitsAcrossThreePhonesWithAStraggler() {
        val frames = DemoJobScript.frames(photos, "j")
        assertTrue("time only moves forward", frames.zipWithNext().all { (a, b) -> b.atMs >= a.atMs })
        val first = frames.first().progress
        assertEquals(24, first.chunks.size)
        assertTrue(first.chunks.all { it.state == ChunkState.QUEUED })
        val end = frames.last().progress
        assertTrue(end.done)
        assertTrue(end.chunks.all { it.state == ChunkState.DONE })
        assertEquals(24, end.nodes.sumOf { it.chunksDone })
        assertEquals(setOf("This phone", "Sales", "Accounts"), end.nodes.map { it.name }.toSet())
        assertTrue("one chunk was copied when Accounts left", frames.any { f -> f.atMs >= 20_000 && f.progress.chunks.any { it.state == ChunkState.DUPLICATED } })
        val speed = end.speedup!!
        assertTrue("speed-up $speed", speed in 2.0..3.2)
        assertNotNull(end.resultRef)
    }

    @Test fun deterministicAndIdenticalToOnePhone() {
        val a = DemoJobScript.frames(photos, "j").last().progress
        val b = DemoJobScript.frames(photos, "j").last().progress
        assertEquals(a, b)
        val solo = DemoJobScript.frames(photos.copy(private = true), "k").last().progress
        assertEquals(1, solo.nodes.size)
        assertEquals("same items, same hash", a.resultRef, solo.resultRef)
        assertTrue(solo.speedup!! in 0.9..1.1)
    }

    @Test fun theResultReadsAsTheInvoiceText() {
        val v = ResultViews.of(DemoJobScript.frames(photos, "j").last().progress, null) as ResultView.Texts
        assertEquals(120, v.items.size)
        assertEquals("sample_0001.png", v.items[0].item)
        assertEquals(InvoiceTexts.text(0).lines.joinToString("\n"), v.items[0].text)
    }

    @Test fun theRunnerReplaysOnTheClockAndCancels() = runTest {
        val runner = DemoJobRunner { 1.0 }
        val seen = mutableListOf<Long>()
        val job = launch { runner.run(photos).collect { seen += it.elapsedMs } }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertTrue(seen.isNotEmpty() && seen.all { it <= 5_000 })
        assertFalse(runner.jobs.value.single().done)
        advanceTimeBy(120_000); runCurrent()
        assertTrue(runner.jobs.value.single().done)
        job.cancel()

        val fast = DemoJobRunner { 3.0 }
        val p = launch { fast.run(photos).collect {} }
        val total = DemoJobScript.frames(photos, "x").last().atMs
        advanceTimeBy(total / 3 + 1_000); runCurrent()
        assertTrue("3x finishes in a third of the time", fast.jobs.value.single().done)
        p.cancel()

        val c = DemoJobRunner { 1.0 }
        val events = mutableListOf<String?>()
        val r = launch { c.run(photos).collect { events += it.error } }
        advanceTimeBy(3_000); runCurrent()
        c.cancel(c.jobs.value.single().jobId)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("Job cancelled", events.last())
        r.cancel()
    }

    @Test fun accountsDropsOffAndComesBack() {
        val now = 1_000_000L
        fun accounts(t: Long) = DemoSwarm.profilesAt(t, now).single { it.nodeId == "accounts" }
        assertTrue(SwarmViewModel.isOnline(accounts(0), now))
        assertFalse(SwarmViewModel.isOnline(accounts(100_000), now))
        assertTrue(SwarmViewModel.isOnline(accounts(130_000), now))
        val sales = (0..10).map { DemoSwarm.profilesAt(it * 60_000L, now).single { p -> p.nodeId == "sales" }.batteryPct }
        assertTrue("battery only drifts down", sales.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun everySuggestionPlansAndAnswers() = runTest {
        var asked = 0
        val port = DemoAskPort(timeScale = { 1.0 }).apply { onNeedsApproval = { asked++; true } }
        for (s in AskSuggestions.DEFAULT.filter { it.kind == Suggestion.Kind.ASK }) {
            val plan = port.plan(s.request) as PlanOptions.Ready
            val events = port.run(plan.plan, RunMode.LOCAL).toList()
            val answer = events.last() as AskEvent.Answered
            assertTrue(s.request, answer.answer.provenance.isNotEmpty())
        }
        assertEquals("only the commission question asks HR", 1, asked)
    }

    @Test fun aDeclineRefusesInPlainWords() = runTest {
        val port = DemoAskPort(timeScale = { 1.0 }).apply { onNeedsApproval = { false } }
        val q = AskSuggestions.DEFAULT.single { it.title.contains("approval") }.request
        val plan = port.plan(q) as PlanOptions.Ready
        assertEquals(null, plan.cloud)
        val last = port.run(plan.plan, RunMode.LOCAL).toList().last()
        assertTrue(last is AskEvent.Refused)
        settle()
    }
}
