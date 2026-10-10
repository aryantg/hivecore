package com.hivecore.ui

import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.JobRunner
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.NodeLoad
import com.hivecore.core.Profile
import com.hivecore.core.fakes.FakeJobRunner
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.engine.workload.Recipes
import com.hivecore.ui.vm.JobFormat
import com.hivecore.ui.vm.JobsViewModel
import com.hivecore.ui.vm.JobsViewModel.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModelTest {
    private val recipes = Recipes.fromDir(listOf(File("../engine/src/main/assets/swarm/recipes"), File("engine/src/main/assets/swarm/recipes")).first { it.isDirectory })

    private val profiles = MutableStateFlow(listOf(
        Profile("a", 0, link = Link.defaultFor(LinkKind.LAN)),
        Profile("s", 0, link = Link.defaultFor(LinkKind.WAN_RELAY)),
    ))

    private fun chunks(n: Int, done: Int, running: Int = 0, cloud: Set<Int> = emptySet()) = List(n) { i ->
        val st = when { i < done -> ChunkState.DONE; i < done + running -> ChunkState.RUNNING; else -> ChunkState.QUEUED }
        val node = if (st == ChunkState.QUEUED) null else if (i in cloud) "cloud:qwen-72b" else listOf("Accounts", "Sales", "HR")[i % 3]
        ChunkView(i, "c$i", st, node, if (st == ChunkState.DONE) 3000L else null, "invoices ${i * 5 + 1}–${i * 5 + 5}")
    }

    private fun progress(done: Int, finished: Boolean = false, error: String? = null, cloud: Set<Int> = emptySet()) = JobProgress("job-1", "Invoice inbox", "shard", "concat",
        chunks(6, done, if (finished) 0 else 2, cloud), listOf(NodeLoad("a", "Accounts", done, 9000, 1.7), NodeLoad("s", "Sales", done / 2, 4000, 0.9)),
        bytesMoved = 1000L * done, elapsedMs = 1000L * done,
        singlePhoneMs = if (finished) 18_600 else null, speedup = if (finished) 3.1 else null, identicalToSingle = if (finished) true else null,
        done = finished, resultSummary = if (finished) "30 invoices → 30 rows" else null, resultRef = if (finished) "t_invoices" else null, error = error)

    /** Emits only what the test sends, so intermediate states are observable. */
    private class SteppedRunner : JobRunner {
        val all = MutableStateFlow<List<JobProgress>>(emptyList())
        override val jobs: StateFlow<List<JobProgress>> = all
        val steps = Channel<JobProgress>(Channel.UNLIMITED)
        val requests = mutableListOf<JobRequest>()
        val cancelled = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        override fun run(request: JobRequest): Flow<JobProgress> { requests += request; return steps.receiveAsFlow() }
        override fun cancel(jobId: String) { cancelled += jobId }
        override fun resume(jobId: String): Flow<JobProgress> { resumed += jobId; return steps.receiveAsFlow() }
    }

    private fun TestScope.vm(runner: JobRunner, load: () -> List<RecipeDef> = { recipes }, hash: (JobProgress) -> String? = { null }) =
        JobsViewModel(runner, profiles, load, backgroundScope, StandardTestDispatcher(testScheduler), hash)

    @Test fun idleToRunningWithGridUpdatesToDone() = runTest {
        val runner = SteppedRunner()
        val vm = vm(runner, hash = { "ab12cd34ef56" + "0".repeat(52) })
        assertTrue(vm.state.value.loadingRecipes)
        vm.loadRecipes(); settle()
        val s0 = vm.state.value
        assertFalse(s0.loadingRecipes)
        assertEquals(Recipes.BUILT_IN.size, s0.recipes.size)
        assertEquals(Phase.IDLE, s0.phase)
        assertFalse(s0.canRun)

        vm.selectRecipe("invoice_inbox")
        assertFalse("no items yet", vm.state.value.canRun)
        vm.addItems(listOf("/f/a.jpg", "/f/b.jpg", "/f/a.jpg"))
        assertEquals(listOf("/f/a.jpg", "/f/b.jpg"), vm.state.value.items)
        vm.setPrivate(true)
        assertTrue(vm.state.value.canRun)

        vm.run(); settle()
        assertEquals(Phase.RUNNING, vm.state.value.phase)
        assertNull(vm.state.value.current)
        val req = runner.requests.single()
        assertEquals("invoice_inbox", req.recipeId)
        assertEquals(listOf("/f/a.jpg", "/f/b.jpg"), req.inputs)
        assertTrue(req.private)

        runner.steps.send(progress(1)); settle()
        assertEquals(Phase.RUNNING, vm.state.value.phase)
        assertEquals(1, vm.state.value.current!!.chunks.count { it.state == ChunkState.DONE })
        assertEquals("1 of 6 chunks done · 2 running", JobFormat.chunkLine(vm.state.value.current!!))
        assertEquals(listOf("LAN", "relay"), vm.state.value.lanes.map { it.linkWord })
        assertFalse("cannot start another while running", vm.state.value.canRun)
        assertEquals(setOf("job-1"), vm.state.value.rerunnable)

        runner.steps.send(progress(4)); settle()
        assertEquals(4, vm.state.value.current!!.chunks.count { it.state == ChunkState.DONE })
        vm.selectChunk(2)
        assertEquals("Chunk 3 · invoices 11–15 · done on HR · 3.0 s", JobFormat.chunkDetail(vm.state.value.current!!.chunks[vm.state.value.selectedChunk!!]))

        runner.steps.send(progress(6, finished = true)); runner.steps.close(); settle()
        val done = vm.state.value
        assertEquals(Phase.DONE, done.phase)
        assertEquals("3.1× faster than this phone alone", JobFormat.speedup(done.current!!.speedup))
        assertEquals("6.0 s elapsed · about 18.6 s on this phone alone", JobFormat.elapsedLine(done.current!!))
        assertEquals("ab12cd34ef56", Format.shortHash(done.resultHash))
        assertNull(done.refusal)

        var opened: String? = null
        vm.openResult = { opened = it }
        vm.openResult(); settle()
        assertEquals("t_invoices", opened)
    }

    @Test fun linkKindFollowsTheProfilesLive() = runTest {
        val runner = SteppedRunner()
        val vm = vm(runner)
        vm.loadRecipes(); settle()
        vm.selectRecipe("gate2_eval"); vm.run(); settle()
        runner.steps.send(progress(2)); settle()
        assertEquals("relay", vm.state.value.lanes[1].linkWord)
        profiles.value = listOf(Profile("a", 0, link = Link.defaultFor(LinkKind.LAN)), Profile("s", 0, link = Link.defaultFor(LinkKind.P2P))); settle()
        assertEquals("Nearby", vm.state.value.lanes[1].linkWord)
    }

    @Test fun cloudChunksAreBadgedAndCountedAndPartialJobsGetAPlainRefusal() = runTest {
        val p = progress(6, finished = true, cloud = setOf(4))
        assertEquals(1, JobFormat.cloudCount(p))
        assertEquals("6 of 6 chunks done · 1 on the cloud", JobFormat.chunkLine(p))
        assertEquals("☁", JobFormat.initial("cloud:qwen-72b"))
        assertEquals("Chunk 5 · invoices 21–25 · done on the cloud (qwen-72b) · 3.0 s", JobFormat.chunkDetail(p.chunks[4]))

        val partial = p.copy(chunks = p.chunks.mapIndexed { i, c -> if (i == 1) c.copy(state = ChunkState.FAILED, node = "HR") else c })
        val r = JobFormat.refusal(partial)!!
        assertEquals("1 chunk could not be processed, so the result is partial.", r.sentence)
        assertEquals("chunk 2", r.step)
        assertEquals("Every phone that could help left or went busy, so the job stopped.", JobFormat.refusal(p.copy(error = "every phone left the swarm"))!!.sentence)
        assertEquals("Some odd failure.", JobFormat.plainWords("some odd failure"))
    }

    @Test fun resumeGoesThroughTheRunnerAndResumableListsJournaledJobs() = runTest {
        val runner = SteppedRunner()
        val vm = vm(runner)
        runner.all.value = listOf(progress(3), progress(6, finished = true).copy(jobId = "job-0")); settle()
        assertEquals(listOf("job-1"), vm.state.value.resumable.map { it.jobId })
        vm.resume("job-1"); settle()
        assertEquals(listOf("job-1"), runner.resumed)
        assertEquals(Phase.RUNNING, vm.state.value.phase)
        runner.steps.send(progress(4)); settle()
        assertTrue("the running job is not offered for resume", vm.state.value.resumable.isEmpty())
        runner.steps.send(progress(6, finished = true)); runner.steps.close(); settle()
        assertEquals(Phase.DONE, vm.state.value.phase)

        val unsupported = object : JobRunner by FakeJobRunner() {}
        val vm2 = vm(unsupported)
        vm2.resume("job-9"); settle()
        assertEquals(Phase.FAILED, vm2.state.value.phase)
        assertTrue(vm2.state.value.current!!.error!!.contains("resume is not available"))
    }

    @Test fun rerunNeedsAKnownRequest() = runTest {
        val runner = FakeJobRunner { r -> listOf(progress(6, finished = true).copy(title = r.title)) }
        val vm = vm(runner)
        vm.loadRecipes(); settle()
        vm.rerun("job-1")
        assertTrue(vm.state.value.message!!.contains("not known"))
        vm.selectRecipe("gate2_eval"); vm.run(); settle()
        assertEquals(Phase.DONE, vm.state.value.phase)
        vm.rerun("job-1"); settle()
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals(listOf("job-1"), runner.jobs.value.map { it.jobId })
    }

    @Test fun promptIsSentAsParamAndNoneRecipesNeedNoItems() = runTest {
        val runner = SteppedRunner()
        val vm = vm(runner)
        vm.loadRecipes(); settle()
        vm.selectRecipe("gate2_eval")
        assertTrue(vm.state.value.canRun)
        vm.run(); settle()
        assertEquals(emptyList<String>(), runner.requests.last().inputs)
        vm.cancel()
        vm.selectRecipe("synth_questions"); vm.setPrompt("questions about sales")
        vm.run(); settle()
        assertEquals("questions about sales", runner.requests.last().params["prompt"]!!.jsonPrimitive.content)
    }

    @Test fun errorProgressFailsAndCancelCallsRunner() = runTest {
        val runner = SteppedRunner()
        val vm = vm(runner)
        vm.loadRecipes(); settle()
        vm.selectRecipe("gate2_eval"); vm.run(); settle()
        runner.steps.send(progress(1)); settle()
        vm.cancel(); settle()
        assertEquals(listOf("job-1"), runner.cancelled)
        assertEquals(Phase.IDLE, vm.state.value.phase)
        assertEquals("Job cancelled", vm.state.value.message)

        val failing = FakeJobRunner { listOf(progress(1), progress(1, error = "every phone left")) }
        val vm2 = vm(failing)
        vm2.loadRecipes(); settle()
        vm2.selectRecipe("bench_all"); vm2.run(); settle()
        assertEquals(Phase.FAILED, vm2.state.value.phase)
        assertEquals("every phone left", vm2.state.value.current?.error)
        assertNotNull(vm2.state.value.refusal)

        val throwing = object : JobRunner by FakeJobRunner() {
            override fun run(request: JobRequest): Flow<JobProgress> = flow { throw IllegalStateException("no swarm") }
        }
        val vm3 = vm(throwing)
        vm3.loadRecipes(); settle()
        vm3.selectRecipe("bench_all"); vm3.run(); settle()
        assertEquals(Phase.FAILED, vm3.state.value.phase)
        assertEquals("no swarm", vm3.state.value.current?.error)
    }

    @Test fun historyOpensAndRecipeLoadErrorIsShown() = runTest {
        val runner = SteppedRunner()
        val vm = vm(runner)
        runner.all.value = listOf(progress(6, finished = true)); settle()
        vm.openHistory("job-1")
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals("job-1", vm.state.value.current?.jobId)
        vm.newJob()
        assertEquals(Phase.IDLE, vm.state.value.phase)

        val vm2 = vm(FakeJobRunner(), load = { throw IllegalArgumentException("bad.json: unknown axis 'x'") })
        vm2.loadRecipes(); settle()
        assertFalse(vm2.state.value.loadingRecipes)
        assertTrue(vm2.state.value.recipesError!!.contains("unknown axis"))
    }

    @Test fun formatting() {
        assertEquals("3.1× faster than this phone alone", JobFormat.speedup(3.06))
        assertEquals("1.0× faster than this phone alone", JobFormat.speedup(1.0))
        assertEquals("0.8× (slower than this phone alone)", JobFormat.speedup(0.8))
        assertEquals("Speed-up not measured", JobFormat.speedup(null))
        assertEquals("Identical to single-phone result: not checked", JobFormat.identical(null))
        assertEquals("✗ differs from the single-phone result", JobFormat.identical(false))
        assertEquals("512 B", JobFormat.bytes(512))
        assertEquals("3.3 MB", JobFormat.bytes(3_450_000))
        assertEquals("850 ms", JobFormat.ms(850))
        assertEquals("42.3 s", JobFormat.ms(42_300))
        assertEquals("2 min 11 s", JobFormat.ms(131_000))
        assertEquals("A", JobFormat.initial("accounts"))
        assertEquals("", JobFormat.initial(null))
        assertTrue(JobFormat.isFolder("/files/jobs/j1"))
        assertFalse(JobFormat.isFolder("t_invoices"))
        assertEquals("Tree summary of 6 chunks", JobFormat.mergeNote(progress(6, true).copy(merge = "tree")))
        assertNull(JobFormat.mergeNote(progress(6, true)))
        assertEquals("Shard · concatenated in order", JobFormat.axisMerge("shard", "concat"))
    }
}
