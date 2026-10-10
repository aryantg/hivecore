package com.hivecore.node

import com.hivecore.core.Canon
import com.hivecore.core.MergeKind
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeRelay
import com.hivecore.engine.journal.ChunkStates
import com.hivecore.engine.journal.JobState
import com.hivecore.engine.split.ChunkStatus
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.Splitters
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Spec section 19 phase 9, the soak harness on the JVM: a seeded sequence of split jobs over the 3-phone fake swarm
 * (plus a relay-only phone in another city on the relay rig) while a chaos driver, fed by `Random(seed)`, between and
 * during the jobs kills and relaunches a worker (its process dies; its key, journal and memo survive), puts a worker
 * to sleep (its transport goes away and comes back), makes a worker yield (the owner picked the phone up) and
 * restarts or downs the relay. Exit gate: every job ends done or partial-with-reason, none hangs (a virtual-time
 * budget per job), every completed job of an exact merge kind (CONCAT, STITCH, MONOID, TOPK) hashes like the same
 * job run undisturbed on one phone, and the orchestrator's journal agrees with what it reported. The clock is
 * virtual, so an hour of chaos is seconds of wall time; the orchestrator (HR) is the phone in the user's hand and is
 * never disturbed.
 */
class SoakTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    enum class Kind { CONCAT, STITCH, MONOID, TOPK }

    private companion object {
        const val BASE = 1_700_000_000_000L
        /** A job that has not ended after this much virtual time is hung (the longest engine wait is the 10-minute cooldown). */
        const val JOB_BUDGET_MS = 15 * 60_000L
        val SPEEDS = listOf(5L, 10L, 20L, 40L)
    }

    @Test fun seed1OnTheLanRig() = soak(seed = 1, relayRig = false, jobs = 10)
    @Test fun seed2OnTheLanRig() = soak(seed = 2, relayRig = false, jobs = 10)
    @Test fun seed3OnTheRelayRig() = soak(seed = 3, relayRig = true, jobs = 8)
    @Test fun seed4OnTheRelayRig() = soak(seed = 4, relayRig = true, jobs = 8)

    // ------------------------------------------------------------------ jobs

    /** The same stand-in executors the split tests use, on every phone; [msPerItem] is the EMBED speed of the phone. */
    private fun register(n: SwarmHarness.Node, msPerItem: Long) {
        n.runner.register(SplitMeshTest.HashEmbed(msPerItem))
        n.runner.register(SplitMeshTest.RowAgg())
        n.runner.register(SplitMeshTest.Knn())
    }

    private fun texts(n: Int, salt: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", "item $i of batch $salt") } }

    /** Job [i] of the sequence: its size and cut come from [rnd]; its content is salted with [i] so no memo answers it. */
    private fun job(i: Int, kind: Kind, rnd: Random): SplitJob {
        val id = "soak$i-${kind.name.lowercase()}"
        return when (kind) {
            Kind.CONCAT -> SplitJob(id, Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(texts(rnd.nextInt(24, 61), i)), chunks = rnd.nextInt(6, 16))
            Kind.STITCH -> {
                val text = (1..rnd.nextInt(40, 81)).joinToString(" ") { "Sentence $it of memo $i talks about site ${it % 4}." }
                SplitJob(id, Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Text(text, "memo$i"), unitsPerChunk = rnd.nextInt(5, 10))
            }
            Kind.MONOID -> SplitJob(id, Op.IQA, JsonObject(emptyMap()), input = SplitInput.Table("t_soak$i", rnd.nextInt(300, 1501)), merge = MergeKind.MONOID, unitMs = 1.0)
            Kind.TOPK -> {
                val corpus = (0 until rnd.nextInt(40, 121)).map { buildJsonObject { put("id", "d%03d".format(it) + "-$i") } }
                SplitJob(id, Op.KNN, buildJsonObject { put("k", rnd.nextInt(3, 10)) }, input = SplitInput.Items(corpus, key = "corpus"), unitMs = 50.0)
            }
        }
    }

    /** The undisturbed one-phone run: one task over the whole input on the executor, hashed as the merge is. */
    private suspend fun reference(j: SplitJob): String {
        val ex: OpExecutor = when (j.op) { Op.EMBED -> SplitMeshTest.HashEmbed(0); Op.IQA -> SplitMeshTest.RowAgg(); else -> SplitMeshTest.Knn() }
        val whole = Splitters.whole(j.opKey, j.params, j.input)
        return Canon.hash(ex.execute(TaskSpec("w", j.op, JsonObject(j.params + whole.params)), emptyList(), emptyList()).getOrThrow())
    }

    // ------------------------------------------------------------------ chaos

    /**
     * The chaos driver. Every action schedules its undo at a random later virtual time; [tick] runs the undos that
     * are due, [drain] runs them all. [log] is the story for a failure message.
     */
    private inner class Chaos(val h: SwarmHarness, val workerIds: List<String>, val lanIds: List<String>, val relay: FakeRelay?,
                              val rnd: Random, val speeds: Map<String, Long>, val skip: Set<String> = emptySet()) {
        val log = ArrayList<String>()
        var actions = 0
        val dead = HashSet<String>()
        val asleep = HashSet<String>()
        val yielded = HashSet<String>()
        val killedEver = LinkedHashSet<String>()
        var relayDown = false
        private val undo = ArrayList<Pair<Long, suspend () -> Unit>>()

        private fun now() = h.now()
        private fun note(s: String) { log += "t+${(now() - BASE) / 1000.0}s $s" }
        private fun after(ms: Long, f: suspend () -> Unit) { undo += (now() + ms) to f }

        suspend fun maybeAct(p: Double) { if (rnd.nextDouble() < p) act() }

        suspend fun act() {
            val choices = ArrayList<String>().apply {
                workerIds.filter { it !in dead }.forEach { add("kill:$it") }
                lanIds.filter { it !in dead && it !in asleep }.forEach { add("sleep:$it") }
                workerIds.filter { it !in dead && it !in yielded }.forEach { add("yield:$it") }
                if (relay != null && !relayDown) { add("relay:restart"); add("relay:down") }
            }
            choices.removeAll { c -> skip.any { c.startsWith(it) } }
            if (choices.isEmpty()) return
            val (what, id) = choices[rnd.nextInt(choices.size)].split(":")
            actions++
            when (what) {
                "kill" -> {
                    dead += id; killedEver += id
                    val fresh = h.restart(h.nodes.getValue(id))
                    note("kill $id")
                    after(rnd.nextLong(500, 20_000)) {
                        h.relaunch(fresh) { register(it, speeds.getValue(id)) }; dead -= id; note("relaunch $id")
                        // A phone that woke while its process was down comes back on the network only now, once it
                        // listens (on a real LAN the TCP link exists only after the app accepts it).
                        if (id !in asleep && id in h.bus.offline) { h.bus.setOffline(id, false); h.nodes.getValue(id).swarm.beacon!!.announce() }
                    }
                }
                "sleep" -> {
                    asleep += id; h.bus.setOffline(id, true)
                    note("sleep $id")
                    after(rnd.nextLong(500, 30_000)) {
                        asleep -= id
                        if (id !in dead) { h.bus.setOffline(id, false); h.nodes.getValue(id).swarm.beacon!!.announce() }   // a dead phone rejoins at relaunch
                        note("wake $id")
                    }
                }
                "yield" -> {
                    yielded += id; h.nodes.getValue(id).baseRunner.yieldAll()
                    note("yield $id")
                    after(rnd.nextLong(1_000, 30_000)) { h.nodes.getValue(id).baseRunner.resumeWorkers(); yielded -= id; note("resume $id") }
                }
                "relay" -> if (id == "restart") { relay!!.killAll(); note("relay restart") } else {
                    relayDown = true; relay!!.goDown(); note("relay down")
                    after(rnd.nextLong(1_000, 45_000)) { relay!!.comeBack(); relayDown = false; note("relay back") }
                }
            }
        }

        suspend fun tick() {
            val t = now()
            val due = undo.filter { it.first <= t }.sortedBy { it.first }
            undo.removeAll(due.toSet())
            for ((_, f) in due) f()
        }

        suspend fun drain() {
            val all = undo.sortedBy { it.first }
            undo.clear()
            for ((_, f) in all) f()
        }
    }

    // ------------------------------------------------------------------ the soak

    private class Tally { var done = 0; var partial = 0; val doneKinds = HashSet<Kind>(); val partialReasons = ArrayList<String>() }

    private fun soak(seed: Int, relayRig: Boolean, jobs: Int, skip: Set<String> = emptySet()) = runTest(timeout = 120.seconds) {
      try {
        val rnd = Random(seed)
        val relay = if (relayRig) FakeRelay(clock = { BASE + testScheduler.currentTime }) else null
        val h = SwarmHarness(this, memoize = true, relay = relay).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val remote = if (relayRig) h.node("remote", "Remote", NodeRole.ACCOUNTS, remote = true) else null
        val all = listOfNotNull(hr, sales, accounts, remote)
        val speeds = all.associate { it.id to SPEEDS[rnd.nextInt(SPEEDS.size)] }
        all.forEach { register(it, speeds.getValue(it.id)) }
        h.start()
        if (relayRig) {                                                     // relay auth, sessions, HELLOs, PROFILEs
            advanceTimeBy(3_000)
            all.forEach { it.swarm.beacon!!.announce() }
            advanceTimeBy(500)
        }
        val rig = if (relayRig) "relay" else "lan"
        val chaos = Chaos(h, workerIds = all.drop(1).map { it.id }, lanIds = listOf(sales.id, accounts.id), relay = relay, rnd = rnd, speeds = speeds, skip = skip)
        val kinds = Kind.values()
        val order = (0 until jobs).map { i -> if (i < kinds.size) kinds[i] else kinds[rnd.nextInt(kinds.size)] }   // every exact kind at least once
        val tally = Tally()

        for ((i, kind) in order.withIndex()) {
            repeat(rnd.nextInt(0, 3)) { chaos.act() }                        // chaos between jobs
            advanceTimeBy(rnd.nextLong(1, 5_000)); runCurrent(); chaos.tick()
            val j = job(i, kind, rnd)
            val where = "seed $seed ($rig rig), job $i ${j.jobId}"
            val p = runDisturbed(hr, j, chaos, rnd, pDuring = 0.12, where = where)
            gate(p, j, reference(j), hr, where, chaos, tally, kind)
        }

        // The storm passes: every phone is back and a last undisturbed job runs with all of them.
        chaos.drain()
        advanceTimeBy(20_000); runCurrent()                                  // past the 15 s a phone that yielded is offered nothing (spec 14)
        repeat(2) { all.forEach { h.nodes.getValue(it.id).swarm.beacon!!.announce() }; advanceTimeBy(2_000); runCurrent() }   // relaunched phones re-announce
        val last = job(jobs, Kind.CONCAT, rnd).copy(chunks = 12)
        val where = "seed $seed ($rig rig), final undisturbed job ${last.jobId}"
        val p = runDisturbed(hr, last, chaos, rnd, pDuring = 0.0, where = where)
        gate(p, last, reference(last), hr, where, chaos, tally, Kind.CONCAT)
        assertFalse("$where ended partial: ${p.partialReason}; story ${chaos.log}", p.partial)
        // Every phone that was killed is back in the orchestrator's view (placement may still leave a slow phone out, spec 8.2).
        val seen = hr.swarm.beacon!!.snapshot().map { it.nodeId }.toSet()
        for (id in chaos.killedEver) {
            val n = h.nodes.getValue(id)
            assertTrue("$where: relaunched phone $id (${n.nodeId.take(8)}) is not in the orchestrator's profiles ${seen.map { it.take(8) }} (router connected=${hr.router.connectedNodeIds().map { it.take(8) }}, last profile from it at ${hr.swarm.beacon!!.lastOf(n.nodeId)?.ts}, now ${h.now()}); story ${chaos.log}", n.nodeId in seen)
        }

        assertEquals("$where: not every exact merge kind completed undisturbed-equal at least once; story ${chaos.log}", kinds.toSet(), tally.doneKinds)
        println("soak seed=$seed rig=$rig jobs=${jobs + 1} done=${tally.done} partial=${tally.partial} chaosActions=${chaos.actions} " +
            "virtual=${(h.now() - BASE) / 1000}s speeds=$speeds partialReasons=${tally.partialReasons}")
      } finally { harness?.close(); harness = null }   // before runTest's final advanceUntilIdle: the relay pinger and beacons never go idle
    }

    /** Runs [j] on [a] while the chaos driver ticks; fails with the story if it does not end inside the budget. */
    private suspend fun TestScope.runDisturbed(a: SwarmHarness.Node, j: SplitJob, chaos: Chaos, rnd: Random, pDuring: Double, where: String): SplitProgress {
        val seen = ArrayList<SplitProgress>()
        val d = async { a.swarm.runJob(j).onEach { seen += it }.toList() }
        val t0 = chaos.h.now()
        runCurrent()
        while (!d.isCompleted) {
            if (chaos.h.now() - t0 > JOB_BUDGET_MS) {
                val lastSeen = seen.lastOrNull()
                d.cancel()
                fail("$where hung: no end after ${JOB_BUDGET_MS / 60_000} virtual minutes; last view: done=${lastSeen?.done} " +
                    "chunks=${lastSeen?.chunks?.map { it.status }} nodes=${lastSeen?.nodes} notes=${lastSeen?.notes}; story ${chaos.log}")
            }
            advanceTimeBy(rnd.nextLong(50, 1_500)); runCurrent()
            chaos.tick()
            chaos.maybeAct(pDuring)
            runCurrent()
        }
        val out = d.await()
        runCurrent()
        assertTrue("$where emitted nothing", out.isNotEmpty())
        return out.last()
    }

    /** The exit gate for one job: done or partial-with-reason, the hash of the undisturbed run, and a consistent journal. */
    private fun gate(p: SplitProgress, j: SplitJob, ref: String, a: SwarmHarness.Node, where: String, chaos: Chaos, tally: Tally, kind: Kind) {
        val story = "notes ${p.notes}; story ${chaos.log}"
        assertTrue("$where did not end done: error=${p.error}; $story", p.done)
        if (p.partial) {
            assertTrue("$where ended partial without a reason; $story", !p.partialReason.isNullOrBlank())
            tally.partial++; tally.partialReasons += "${j.jobId}: ${p.partialReason}"
        } else {
            assertEquals("$where: result hash differs from the undisturbed one-phone run; $story", ref, p.resultHash)
            tally.done++; tally.doneKinds += kind
        }
        // The orchestrator's journal says the same as the progress it reported.
        val snap = a.journal.load(j.jobId) ?: throw AssertionError("$where: the journal has no row for the job; $story")
        assertEquals("$where: journal state; $story", if (p.partial) JobState.PARTIAL else JobState.DONE, snap.job.state)
        assertTrue("$where: the finished job is still offered for resume; $story", a.journal.resumable().none { it.jobId == j.jobId })
        val outputs = snap.outputs.map { it.chunkId }.toSet()
        val doneRows = snap.chunks.filter { it.state == ChunkStates.DONE }
        assertTrue("$where: a done chunk row has no journaled output: ${doneRows.map { it.chunkId }.filter { it !in outputs }}; $story", doneRows.all { it.chunkId in outputs })
        if (!p.partial) {
            assertTrue("$where: chunk rows not all done after a done job: ${snap.chunks.map { it.chunkId.take(8) to it.state }}; $story", snap.chunks.all { it.state == ChunkStates.DONE })
            assertEquals("$where: journaled done chunks vs the view's; $story", p.chunks.count { it.status == ChunkStatus.DONE }, doneRows.size)
        }
        // The Jobs screen's list is filled by the JobRunner entry points (run / resume), not by the raw split flow this
        // test drives; when it holds the job it must agree with the progress.
        a.swarm.jobs.value.firstOrNull { it.jobId == j.jobId }?.let { view ->
            assertTrue("$where: the job list says not done; $story", view.done)
            assertEquals("$where: the job list disagrees on partial; $story", p.partial, view.partial)
        }
    }
}
