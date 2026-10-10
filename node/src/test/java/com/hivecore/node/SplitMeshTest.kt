package com.hivecore.node

import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.SplitPlan
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.split.Splitters
import com.hivecore.engine.split.ChunkPlanner
import com.hivecore.engine.split.ChunkStatus
import com.hivecore.engine.split.SplitConstants
import com.hivecore.engine.split.WorkQueue
import com.hivecore.engine.ComparatorImpl
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.TaskFailure
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.MergeKind
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.Recipe
import com.hivecore.core.TaskSpec
import com.hivecore.core.WorkloadSpec
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import com.hivecore.core.Canon
import com.hivecore.node.SwarmHarness
import com.hivecore.node.textai.TextExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The splitting engine on a 3-phone fake mesh (SwarmHarness: real routers, SwarmNodes, TaskRunners, profiles). */
class SplitMeshTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    /**
     * Deterministic EMBED stand-in: one hash per text (an item shard under `texts`, or sentence units under `units`),
     * [msPerItem] of (virtual) work per item. [failAbove] fails shards bigger than that (the re-cut scenario);
     * [failWith] fails every run with that class (the cooldown scenario).
     */
    class HashEmbed(var msPerItem: Long) : OpExecutor {
        override val ops = setOf(Op.EMBED)
        var chunks = 0
        var items = 0
        var failAbove = Int.MAX_VALUE
        var failWith: TaskFailure? = null
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            failWith?.let { return Result.failure(it) }
            val texts = ((task.params["texts"] ?: task.params["units"]) as JsonArray).map { it.jsonObject }
            if (texts.size > failAbove) return Result.failure(TaskFailure(TaskFailure.RUNTIME, "shard of ${texts.size} is too big"))
            delay(msPerItem * texts.size)
            chunks++; items += texts.size
            return Result.success(buildJsonObject {
                put("op", "EMBED"); put("model", "hash")
                putJsonArray("items") { texts.forEach { t -> add(buildJsonObject {
                    put("id", t["id"] ?: t["unit"]!!); put("sha256", Canon.sha256Hex(t["text"]!!.jsonPrimitive.content))
                }) } }
            })
        }
    }

    /** MONOID stand-in: a partial aggregate over the rows [rowFrom, rowTo) of a synthetic table (value = row × 7 mod 11). */
    class RowAgg : OpExecutor {
        override val ops = setOf(Op.IQA)
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            val from = task.params["rowFrom"]!!.jsonPrimitive.content.toInt(); val to = task.params["rowTo"]!!.jsonPrimitive.content.toInt()
            val vs = (from until to).map { (it * 7) % 11 }
            delay(1)
            return Result.success(buildJsonObject { put("op", "AGG"); put("count", vs.size); put("sum", vs.sum()); put("min", vs.min()); put("max", vs.max()) })
        }
    }

    /** TOPK stand-in: the k best of a corpus shard by a score derived from the id. */
    class Knn : OpExecutor {
        override val ops = setOf(Op.KNN)
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            val k = task.params["k"]!!.jsonPrimitive.content.toInt()
            val hits = (task.params["corpus"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
                .map { id -> id to (Canon.sha256Hex(id).take(4).toInt(16) % 97) / 100.0 }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(k)
            delay(1)
            return Result.success(buildJsonObject { put("op", "KNN"); put("k", k)
                putJsonArray("hits") { hits.forEach { (id, s) -> add(buildJsonObject { put("id", id); put("score", s) }) } } })
        }
    }

    /** An LLM-class classifier whose label is fixed per phone, so replicas on different phones disagree. */
    class Labeller(val label: String) : OpExecutor {
        override val ops = setOf(Op.TEXT_CLASSIFY)
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            delay(5)
            return Result.success(buildJsonObject { putJsonArray("items") {
                (task.params["texts"] as JsonArray).forEach { t -> add(buildJsonObject { put("id", t.jsonObject["id"]!!); put("label", label) }) } } })
        }
    }

    class FakeGate(val outcome: (EscalationRequest) -> GateOutcome) : Escalator {
        val requests = ArrayList<EscalationRequest>()
        override suspend fun tryEscalate(req: EscalationRequest): GateOutcome { requests += req; return outcome(req) }
    }

    private class Mesh(val h: SwarmHarness, val nodes: List<SwarmHarness.Node>, val exec: Map<String, HashEmbed>) {
        val a get() = nodes[0]; val b get() = nodes[1]; val c get() = nodes[2]
    }

    private suspend fun TestScope.mesh(vararg msPerItem: Long): Mesh {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val nodes = listOf(hr, sales, accounts)
        val ex = nodes.mapIndexed { i, n -> n.id to HashEmbed(msPerItem[i]).also { n.runner.register(it) } }.toMap()
        h.start()
        return Mesh(h, nodes, ex)
    }

    private fun texts(n: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", "item number $i") } }
    private fun job(n: Int, chunks: Int? = null, private: Boolean = false, owner: String? = null) =
        SplitJob("emb", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Items(texts(n), private = private), chunks = chunks, owner = owner)

    private suspend fun TestScope.run(m: Mesh, j: SplitJob): List<SplitProgress> {
        val out = m.a.swarm.runJob(j, useMemo = false).toList()
        testScheduler.runCurrent()
        return out
    }

    /** The unsplit reference: one task over every item on one executor. */
    private suspend fun reference(j: SplitJob, ex: OpExecutor = HashEmbed(0)): String {
        val whole = Splitters.whole(j.opKey, j.params, j.input)
        val out = ex.execute(TaskSpec("w", j.op, JsonObject(j.params + whole.params)), emptyList(), emptyList()).getOrThrow()
        return Canon.hash(out)
    }

    private fun eligible(n: SwarmHarness.Node, on: Boolean) = n.policy.set(n.policy.profile.value.copy(eligible = on, ts = n.policy.profile.value.ts + 1))
    private fun link(n: SwarmHarness.Node, link: Link) = n.policy.set(n.policy.profile.value.copy(link = link, ts = n.policy.profile.value.ts + 1))

    @Test fun fastPhoneTakesMoreChunksAndTheResultEqualsTheUnsplitRun() = runTest {
        val m = mesh(10, 100, 100)
        val j = job(60, chunks = 15)
        val p = run(m, j).last()
        assertNull(p.error)
        assertTrue(p.done)
        assertEquals(reference(j), p.resultHash)
        val fast = m.exec.getValue("hr").chunks; val b = m.exec.getValue("sales").chunks; val c = m.exec.getValue("accounts").chunks
        assertEquals(15, fast + b + c)
        assertTrue("fast $fast vs $b/$c", fast > b && fast > c)
        assertTrue(p.chunks.all { it.status == ChunkStatus.DONE })
        assertEquals(fast, p.nodes.single { it.node == m.a.nodeId }.chunks)
        assertTrue(p.bytesMoved > 0)
        assertTrue("speed-up ${p.speedup}", p.speedup > 1.0)
        // the TASKs went to the other phones as ordinary TASK messages
        assertTrue(m.b.got(MsgType.TASK).isNotEmpty())
        assertFalse(p.partial)
        assertEquals(3, p.nodes.size)
        assertTrue(p.nodes.all { if (it.node == m.a.nodeId) it.link == null else it.link == "LAN" })   // link kind per peer, none for this phone
    }

    @Test fun stragglerIsDuplicatedAndTheFirstResultWins() = runTest {
        val m = mesh(10, 10, 700)
        val j = job(48, chunks = 12)
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue("duplicates ${p.duplicates}", p.duplicates >= 1)
        assertTrue(p.chunks.any { it.duplicated })
        assertTrue("elapsed ${p.elapsedMs}", p.elapsedMs < 2 * 4 * 700)   // did not wait for the slow phone's queue
        assertEquals(0, p.mismatches)
    }

    @Test fun killedPhoneLosesOnlyItsInFlightChunks() = runTest {
        val m = mesh(100, 100, 100)
        val j = job(60, chunks = 15)
        val d = async { m.a.swarm.runJob(j, useMemo = false).toList() }
        testScheduler.advanceTimeBy(450)
        testScheduler.runCurrent()
        val before = m.exec.getValue("accounts").chunks
        m.h.bus.setOffline("accounts", true)
        val p = d.await().last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue(p.notes.toString(), p.reassigned + p.duplicates >= 1)   // re-queued at its deadline, or rescued earlier as a straggler
        assertTrue(p.chunks.any { it.attempts >= 2 })
        assertTrue("accounts ran $before before it died", before >= 1)
        assertEquals(15, p.chunks.count { it.status == ChunkStatus.DONE })
        assertFalse(p.partial)
    }

    @Test fun privateItemsStayOnTheOwner() = runTest {
        val m = mesh(10, 10, 10)
        val p = run(m, job(20, chunks = 6, private = true)).last()
        assertTrue(p.done)
        assertEquals(0, m.exec.getValue("sales").chunks + m.exec.getValue("accounts").chunks)
        assertTrue(m.b.got(MsgType.TASK).isEmpty() && m.c.got(MsgType.TASK).isEmpty())
        // private data owned by Sales: only Sales runs it, even though HR commands the job
        val q = run(m, job(20, chunks = 6, private = true, owner = m.b.nodeId).copy(jobId = "emb2")).last()
        assertTrue(q.error, q.done)
        assertEquals(q.chunks.size, m.exec.getValue("sales").chunks)
        assertEquals(0, m.exec.getValue("accounts").chunks)
        assertEquals(p.resultHash, q.resultHash)
        assertTrue(m.c.got(MsgType.TASK).isEmpty())
    }

    @Test fun sameJobOnOneTwoAndThreePhonesGivesTheSameHash() = runTest {
        val m = mesh(30, 50, 70)
        val j = job(37)
        eligible(m.b, false); eligible(m.c, false); testScheduler.runCurrent()
        val one = run(m, j).last()
        eligible(m.b, true); testScheduler.runCurrent()
        val two = run(m, j).last()
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j).last()
        assertEquals(reference(j), one.resultHash)
        assertEquals(one.resultHash, two.resultHash)
        assertEquals(one.resultHash, three.resultHash)
        assertEquals(setOf(m.a.nodeId), one.nodes.filter { it.chunks > 0 }.map { it.node }.toSet())
        assertEquals(3, three.nodes.count { it.chunks > 0 })
        assertTrue(one.chunks.size < three.chunks.size)                    // ≥ 2 chunks per eligible phone
    }

    /** Runs [j] on 1, 2 and 3 phones and returns the three final views (all must hash like [ref]). */
    private suspend fun TestScope.onOneTwoThree(m: Mesh, j: SplitJob, ref: String): List<SplitProgress> {
        eligible(m.b, false); eligible(m.c, false); testScheduler.runCurrent()
        val one = run(m, j).last()
        eligible(m.b, true); testScheduler.runCurrent()
        val two = run(m, j.copy(jobId = j.jobId + "-2")).last()
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j.copy(jobId = j.jobId + "-3")).last()
        for (p in listOf(one, two, three)) { assertTrue(p.error, p.done); assertFalse(p.partialReason ?: "", p.partial); assertEquals(ref, p.resultHash) }
        assertEquals(3, three.nodes.count { it.chunks > 0 })
        return listOf(one, two, three)
    }

    @Test fun stitchOfSentenceChunksGivesTheSameHashOnOneTwoAndThreePhones() = runTest {
        val m = mesh(5, 5, 5)
        val text = (1..60).joinToString(" ") { "Sentence number $it talks about site ${it % 4}." }
        val j = SplitJob("st", Op.EMBED, JsonObject(emptyMap()), "hash", SplitInput.Text(text, "memo"), unitsPerChunk = 7)
        assertEquals(MergeKind.STITCH, ChunkPlanner().plan(j, emptyList(), "me").getOrThrow().kind)
        val (one, _, three) = onOneTwoThree(m, j, reference(j))
        assertEquals(60, three.result!!.jsonObject["items"]!!.jsonArray.size)
        assertEquals(one.chunks.size, three.chunks.size)                   // the unit size, not the phone count, cuts a text
    }

    @Test fun monoidOfRowRangesGivesTheSameHashOnOneTwoAndThreePhones() = runTest {
        val m = mesh(1, 1, 1)
        m.nodes.forEach { it.runner.register(RowAgg()) }
        val j = SplitJob("agg", Op.IQA, JsonObject(emptyMap()), input = SplitInput.Table("t_sales", 1000), merge = MergeKind.MONOID, unitMs = 1.0)
        val (_, _, three) = onOneTwoThree(m, j, reference(j, RowAgg()))
        assertEquals(1000, three.result!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun topKOfCorpusShardsGivesTheSameHashOnOneTwoAndThreePhones() = runTest {
        val m = mesh(1, 1, 1)
        m.nodes.forEach { it.runner.register(Knn()) }
        val corpus = (0 until 90).map { buildJsonObject { put("id", "d%03d".format(it)) } }
        val j = SplitJob("knn", Op.KNN, buildJsonObject { put("k", 7) }, input = SplitInput.Items(corpus, key = "corpus"), unitMs = 50.0)
        assertEquals(MergeKind.TOPK, ChunkPlanner().plan(j, emptyList(), "me").getOrThrow().kind)
        val (_, _, three) = onOneTwoThree(m, j, reference(j, Knn()))
        assertEquals(7, three.result!!.jsonObject["hits"]!!.jsonArray.size)
    }

    @Test fun treeSummaryIsIdenticalOnTwoAndThreePhones() = runTest {
        val m = mesh(1, 1, 1)
        m.nodes.forEach { it.runner.register(TextExecutor(null, null, null, null)) }
        val text = (1..120).joinToString(" ") { "Clause $it fixes the fee at ${it * 7} rupees for site ${it % 5}." }
        val j = SplitJob("sum", Op.SUMMARIZE, buildJsonObject { put("sentences", 5) }, input = SplitInput.Text(text, "contract"))
        eligible(m.c, false); testScheduler.runCurrent()
        val two = run(m, j).last()
        eligible(m.c, true); testScheduler.runCurrent()
        val three = run(m, j).last()
        assertTrue(two.error, two.done)
        assertEquals(3, two.chunks.size)
        assertEquals(two.resultHash, three.resultHash)
        assertEquals(5, three.result!!.jsonObject["picks"]!!.jsonArray.size)
        // SUMMARIZE is an LLM op: every chunk ran on two phones and the replicas agreed
        assertTrue(three.chunks.all { it.verified == true })
        assertEquals(0, three.unverified)
    }

    @Test fun workloadRunsEachRecipeStageAsASplitJob() = runTest {
        val m = mesh(10, 10, 10)
        // each recipe stage becomes one split job over the workload's items; `release` decides private mode
        val recipe = Recipe("r", "1", "test", listOf(TaskSpec("s1", Op.IQA, JsonObject(emptyMap()))), emptyList())
        m.a.swarm.recipes = { id, v -> recipe.takeIf { id == "r" && v == "1" } }
        val missing = m.a.swarm.runWorkload(WorkloadSpec("w0", "nope", "1", m.a.nodeId, listOf("x"))).toList().last()
        assertTrue(missing.error!!.contains("not installed"))
        val jobs = SplitJob.forWorkload(WorkloadSpec("w1", "r", "1", m.a.nodeId, listOf("a", "b", "c"), release = "items"), recipe)
        assertEquals(listOf("w1/s1"), jobs.map { it.jobId })
        assertEquals(listOf(false, false, false), (jobs.single().input as SplitInput.Blobs).private)
        val priv = SplitJob.forWorkload(WorkloadSpec("w2", "r", "1", m.b.nodeId, listOf("a")), recipe).single()
        assertEquals(m.b.nodeId, priv.owner)
        assertEquals(listOf(true), (priv.input as SplitInput.Blobs).private)
    }

    @Test fun swarmNodeIsTheCoreJobRunner() = runTest {
        val m = mesh(10, 100, 100)
        val runner: com.hivecore.core.JobRunner = m.a.swarm
        val input = Canon.json.encodeToJsonElement(SplitInput.serializer(), SplitInput.Items(texts(24)))
        val req = com.hivecore.core.JobRequest("Embed 24 notes", op = Op.EMBED,
            params = buildJsonObject { put("input", input); put("modelId", "hash") })
        val views = runner.run(req).toList()
        testScheduler.runCurrent()
        val last = views.last()
        assertTrue(last.error, last.done)
        assertEquals("shard", last.axis)
        assertEquals("concat", last.merge)
        assertTrue(last.speedup!! > 0)
        assertNull(views.first().speedup)
        assertTrue(last.chunks.all { it.state == com.hivecore.core.ChunkState.DONE && it.label.startsWith("items ") })
        assertTrue(last.nodes.map { it.name }.containsAll(listOf("HR", "Sales")))
        assertEquals(last, runner.jobs.value.first())
        assertTrue(last.resultSummary!!.contains("concat merge"))
        assertFalse(last.partial)
        assertNull(last.partialReason)
        assertTrue(last.nodes.all { if (it.nodeId == m.a.nodeId) it.link == null else it.link == "LAN" })
        // a private job never leaves this phone
        val salesBefore = m.exec.getValue("sales").chunks
        val priv = runner.run(req.copy(private = true)).toList().last()
        assertTrue(priv.done)
        assertEquals(salesBefore, m.exec.getValue("sales").chunks)
        assertEquals(last.resultRef, priv.resultRef)
    }

    @Test fun taskBodiesUseTheChunkTaskIds() = runTest {
        val m = mesh(10, 10, 10)
        run(m, job(8, chunks = 4))
        val ids = (m.b.got(MsgType.TASK) + m.c.got(MsgType.TASK)).map { MeshJson.decode<TaskBody>(it.body).task.id }
        assertTrue(ids.toString(), ids.all { WorkQueue.parseTaskId(it) != null })
        assertTrue(ids.isNotEmpty())
        assertEquals(JsonPrimitive("EMBED"), JsonPrimitive(MeshJson.decode<TaskBody>(m.b.got(MsgType.TASK).first().body).task.op.name))
    }

    // ------------------------------------------------------------------ phase 3 spec scenarios (sections 11, 12, 14)

    @Test fun chunkThatFailsOnTwoPhonesIsRecutAndTheHashIsUnchanged() = runTest {
        val m = mesh(10, 10, 10)
        m.exec.values.forEach { it.failAbove = 3 }                          // 4-item shards fail everywhere, 2-item halves run
        val j = job(16, chunks = 4)
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertFalse(p.partialReason ?: "", p.partial)
        assertEquals(reference(j), p.resultHash)
        assertEquals(4, p.recuts)
        assertEquals(12, p.chunks.size)                                      // 4 parents + 8 halves
        assertTrue(p.chunks.take(4).all { it.recut && it.status == ChunkStatus.DONE })
        assertTrue(p.chunks.drop(4).all { it.recutOf != null && it.units == 2 })
        assertTrue(p.notes.any { it.contains("re-cut into") })
        assertEquals(8, m.exec.values.sumOf { it.chunks })
    }

    @Test fun wanRelayPhoneTakesTextChunksButNeverBlobChunks() = runTest {
        val m = mesh(10, 10, 10)
        link(m.c, Link(LinkKind.WAN_RELAY, 500, 300)); testScheduler.runCurrent()
        val j = job(30, chunks = 10)
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue(m.c.got(MsgType.TASK).isNotEmpty())                      // inline text travels over the relay
        assertEquals("WAN_RELAY", p.nodes.single { it.node == m.c.nodeId }.link)
        assertTrue(p.nodes.single { it.node == m.c.nodeId }.chunks > 0)
        // a blob-carrying chunk is never offered to it (the queue rule, with the same profile the mesh carries)
        val plan = ChunkPlanner().plan(SplitJob("b", Op.IQA, JsonObject(emptyMap()), input = SplitInput.Blobs(listOf("x", "y"), sizes = listOf(10L, 10L)), unitsPerChunk = 1),
            m.a.swarm.beacon!!.snapshot(), m.a.nodeId).getOrThrow()
        val q = WorkQueue(plan, plan.eligible, m.h.now, ComparatorImpl(), linkOf = { n -> WorkQueue.linkFrom(m.a.swarm.beacon!!.lastOf(n)) })
        val first = q.fill()
        assertTrue(first.isNotEmpty())
        assertTrue(first.none { it.node == m.c.nodeId })                     // the LAN peer may, the relay peer never
        assertTrue(m.c.nodeId in q.nodes)
    }

    @Test fun oomResultTriggersTheCapabilityCooldownAndThePhoneIsSkipped() = runTest {
        val m = mesh(10, 10, 10)
        m.exec.getValue("sales").failWith = TaskFailure(TaskFailure.OOM, "out of memory")
        val j = job(30, chunks = 10)
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertFalse(p.partialReason ?: "", p.partial)
        assertEquals(reference(j), p.resultHash)
        assertTrue(p.nodes.none { it.node == m.b.nodeId && it.chunks > 0 })  // removed from the job after the oom
        assertEquals(0, m.exec.getValue("sales").chunks)
        val cool = m.a.swarm.split!!.cooldowns()
        assertEquals(setOf(m.b.nodeId to "embed"), cool.keys)
        val until = cool.values.single()
        assertTrue(until > m.h.now() && until <= m.h.now() + SplitConstants.CAPABILITY_COOLDOWN_MS)
        assertTrue(p.notes.any { it.contains("oom") && it.contains("cools down") })
        // the next job of that capability does not even offer the phone a chunk
        val before = m.b.got(MsgType.TASK).size
        val again = run(m, j.copy(jobId = "emb2")).last()
        assertTrue(again.done)
        assertEquals(before, m.b.got(MsgType.TASK).size)
    }

    private fun TestScope.commanderWithGate(m: Mesh, gate: Escalator): SplitCommander = SplitCommander(
        m.a.nodeId, ComparatorImpl(), profiles = { m.a.swarm.beacon!!.snapshot() }, transmit = { to, t, b -> m.a.swarm.sendTo(to, t, b) },
        clock = m.h.now, escalator = gate).also { m.a.swarm.split = it }

    @Test fun replicaDisagreementReachesTheEscalatorWithTheVerificationTrigger() = runTest {
        val m = mesh(1, 1, 1)
        m.nodes.forEach { it.runner.register(Labeller(it.name)) }          // HR, Sales and Accounts never agree
        val cloud = buildJsonObject { putJsonArray("items") { add(buildJsonObject { put("id", "cloud"); put("label", "cloud") }) } }
        val gate = FakeGate { GateOutcome.Ran(cloud, Canon.hash(cloud), "fake/model") }
        commanderWithGate(m, gate)
        val j = SplitJob("cls", Op.TEXT_CLASSIFY, JsonObject(emptyMap()), input = SplitInput.Items(texts(6)), chunks = 2)
        val plan = ChunkPlanner().plan(j, emptyList(), m.a.nodeId).getOrThrow()
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertFalse(p.partialReason ?: "", p.partial)
        assertEquals(2, gate.requests.size)
        for (r in gate.requests) {
            assertTrue(r.trigger.toString(), r.trigger is EscalationTrigger.VerificationFailed)
            assertTrue((r.trigger as EscalationTrigger.VerificationFailed).reason.contains("3 phones disagreed"))
            assertEquals("public", r.privacy)
            assertEquals(m.a.nodeId, r.commander)
            assertEquals(Op.TEXT_CLASSIFY, r.node.op)
            assertTrue(r.chunkId in plan.chunks.map { it.chunkId })
            assertEquals(3, r.localAttempts)
        }
        assertEquals(listOf("fake/model"), p.cloud)
        assertTrue(p.chunks.all { it.cloud == "fake/model" && it.status == ChunkStatus.DONE })
        assertEquals(2, p.result!!.jsonObject["items"]!!.jsonArray.size)
        assertTrue(p.mismatches >= 2)
        // with no gate the job ends partial, in plain words; with private data the gate is never asked
        val off = FakeGate { GateOutcome.Refused("cloud escalation is off") }
        commanderWithGate(m, off)
        val q = run(m, j.copy(jobId = "cls2")).last()
        assertTrue(q.done && q.partial)
        assertTrue(q.partialReason!!, q.partialReason!!.contains("disagreed") && q.partialReason!!.contains("cloud escalation is off"))
        assertEquals(2, off.requests.size)
        val priv = run(m, j.copy(jobId = "cls3", input = SplitInput.Items(texts(6), private = true))).last()
        assertTrue(priv.done)
        assertEquals(2, off.requests.size)
        assertEquals(2, priv.unverified)                                     // one phone only: given, marked not verified
    }

    @Test fun yieldIsAReassignmentNotAFault() = runTest {
        val m = mesh(10, 10, 10)
        m.b.runner.yieldAll()                                                // Sales is in its owner's hands
        val j = job(24, chunks = 6)
        val p = run(m, j).last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        assertTrue(m.b.got(MsgType.TASK).isNotEmpty())
        assertTrue(m.a.got(MsgType.TASK_YIELD).isNotEmpty())
        assertEquals(0, m.exec.getValue("sales").chunks)
        assertTrue("reassigned ${p.reassigned}", p.reassigned >= 1)
        assertEquals(0, p.recuts)
        assertTrue(m.a.swarm.split!!.cooldowns().isEmpty())
        assertTrue(p.nodes.any { it.node == m.b.nodeId })                   // still a member of the job
        assertTrue(p.notes.none { it.contains("failed") })
        m.b.runner.resumeWorkers()
        val again = run(m, j.copy(jobId = "emb2")).last()
        assertTrue(again.done)
        assertTrue(m.exec.getValue("sales").chunks > 0)
    }
}
