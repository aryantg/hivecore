package com.hivecore.engine.split

import com.hivecore.core.Canon
import com.hivecore.core.MergeKind
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.engine.ComparatorImpl
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.JsonSchemaValidator
import com.hivecore.engine.TaskFailure
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.engine.journal.AttemptRow
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.ChunkRow
import com.hivecore.engine.journal.EscalationRow
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.JobRow
import com.hivecore.engine.journal.JobSnapshot
import com.hivecore.engine.journal.MemoHit
import com.hivecore.engine.journal.OutputRow
import com.hivecore.engine.journal.PlacementRow
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskBody
import com.hivecore.mesh.TaskResultBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The commander over a one-phone runner: journal write-ahead order (13.1), memo (13.2), cooldowns, schema checks and the gate seam. */
class SplitCommanderTest {
    /** Records every row in call order; the test interleaves the TASK sends into the same log. */
    private class RecordingJournal(val log: MutableList<String>) : JobJournal {
        override fun begin(job: JobRow) { log += "job:${job.state}" }
        override fun job(row: JobRow) { log += "job:${row.state}" }
        override fun chunk(row: ChunkRow) { log += "chunk:${row.index}:${row.state}" }
        override fun attempt(row: AttemptRow) { log += "attempt:${row.chunkId.take(8)}:${row.attempt}:${row.outcome}" }
        override fun output(row: OutputRow) { log += "output:${row.chunkId.take(8)}:${row.source}" }
        override fun placement(row: PlacementRow) {}
        override fun escalation(row: EscalationRow) { log += "escalation:${row.trigger}:${row.outcome}" }
        override fun resumable(): List<JobRow> = emptyList()
        override fun load(jobId: String): JobSnapshot? = null
    }

    private class MemoryMemo : ChunkMemo {
        val map = LinkedHashMap<String, MemoHit>()
        override fun get(chunkId: String): MemoHit? = map[chunkId]
        override fun put(chunkId: String, hash: String, json: JsonElement) { map[chunkId] = MemoHit(chunkId, hash, json, 0) }
    }

    private class FakeGate(val outcome: GateOutcome) : Escalator {
        val requests = ArrayList<EscalationRequest>()
        override suspend fun tryEscalate(req: EscalationRequest): GateOutcome { requests += req; return outcome }
    }

    /** Echoes the items of its shard (or the variant index for a fan-out); [fail] makes every run fail with that class. */
    private class Echo(override val ops: Set<Op> = setOf(Op.IQA)) : OpExecutor {
        var fail: TaskFailure? = null
        var runs = 0
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            runs++
            fail?.let { return Result.failure(it) }
            val texts = task.params["texts"] as? JsonArray ?: return Result.success(buildJsonObject { put("value", "v${task.params["variant"]!!.jsonPrimitive.content}") })
            return Result.success(JsonArray(texts.map { JsonPrimitive("r" + it.jsonPrimitive.content) }))
        }
    }

    private class Rig(scope: TestScope, journal: JobJournal = JobJournal.None, memo: ChunkMemo = ChunkMemo.None, escalator: Escalator = Escalator.Off,
                      schema: com.hivecore.engine.SchemaValidator = com.hivecore.engine.SchemaValidator.NONE, val log: MutableList<String> = ArrayList()) {
        val now = { 1_000_000L + scope.testScheduler.currentTime }
        val runner = TaskRunnerImpl("me")
        val echo = Echo().also { runner.register(it) }
        val cooldowns = ArrayList<Triple<String, String, Long>>()
        val bg = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob())
        var idOf: Map<Int, String> = emptyMap()
        lateinit var sc: SplitCommander
        init {
            sc = SplitCommander("me", ComparatorImpl(), profiles = { emptyList() }, clock = now, journal = journal, memo = memo, escalator = escalator, schema = schema,
                onCooldown = { n, c, u -> cooldowns += Triple(n, c, u) },
                transmit = { _, type, body ->
                    if (type == MsgType.TASK) {
                        val b = MeshJson.decode<TaskBody>(body)
                        val (i, n) = WorkQueue.parseTaskId(b.task.id)!!
                        log += "TASK:${idOf[i]}:$n"
                        bg.launch {
                            val r = runner.run(b.task, emptyList(), emptyList(), "me")
                            val res = r.fold({ TaskResultBody(b.planId, b.task.id, "me", it, Canon.hash(it), ms = 5) },
                                { TaskResultBody(b.planId, b.task.id, "me", error = it.message, errorClass = TaskFailure.classOf(it)) })
                            sc.onMessage(MsgType.TASK_RESULT, "me", MeshJson.encode(res))
                        }
                    }
                    Result.success(Unit)
                })
        }
        fun close() = bg.cancel()
    }

    private fun items(n: Int) = SplitInput.Items((0 until n).map { JsonPrimitive(it) })
    private fun job(n: Int = 6, per: Int = 2, params: JsonObject = JsonObject(emptyMap())) =
        SplitJob("j", Op.IQA, params, input = items(n), unitsPerChunk = per, unitMs = 10.0)

    @Test fun journalRowsAreWrittenBeforeEveryTaskAndBeforeTheMerge() = runTest {
        val memo = MemoryMemo()
        val shared = ArrayList<String>()                      // the journal rows and the TASK sends in one order
        val rig2 = Rig(this, RecordingJournal(shared), memo, log = shared)
        val j = job()
        val plan = ChunkPlanner().plan(j, emptyList(), "me").getOrThrow()
        rig2.idOf = plan.chunks.associate { it.index to it.chunkId.take(8) }
        val progress = rig2.sc.run(j).toList()
        testScheduler.runCurrent()
        val l = rig2.log
        assertEquals("job:running", l.first())
        val firstTask = l.indexOfFirst { it.startsWith("TASK:") }
        assertTrue(l.subList(0, firstTask).containsAll(plan.chunks.map { "chunk:${it.index}:queued" }))
        for ((k, e) in l.withIndex()) if (e.startsWith("TASK:")) {
            val (_, id, n) = e.split(":")
            assertTrue("attempt row before $e", l.subList(0, k).contains("attempt:$id:$n:sent"))
        }
        assertTrue(plan.chunks.all { c -> "output:${c.chunkId.take(8)}:node:me" in l })
        val merging = l.indexOf("job:merging")
        assertTrue(merging > l.indexOfLast { it.startsWith("output:") })
        assertEquals("job:done", l.last())
        val last = progress.last()
        assertTrue(last.error, last.done && !last.partial)
        assertTrue(progress.first().resumable)
        assertFalse(last.resumable)
        assertEquals(plan.chunks.size, memo.map.size)
        // 13.2: a second run of the same job is answered from the memo without any TASK
        rig2.log.clear()
        val again = rig2.sc.runToEnd(j)
        assertTrue(again.done)
        assertEquals(last.resultHash, again.resultHash)
        assertTrue(rig2.log.none { it.startsWith("TASK:") })
        assertTrue(again.chunks.all { it.memo })
        assertTrue(rig2.log.any { it.endsWith(":memo") })
        rig2.close()
    }

    @Test fun oomResultStartsTheCooldownAndTheJobEndsPartialNotHung() = runTest {
        val rig = Rig(this)
        rig.echo.fail = TaskFailure(TaskFailure.OOM, "out of memory")
        val p = rig.sc.runToEnd(job())
        testScheduler.runCurrent()
        assertTrue(p.done && p.partial)
        assertTrue(p.partialReason!!, p.partialReason!!.contains("no phone can take it"))
        assertEquals(listOf("me" to "iqa"), rig.cooldowns.map { it.first to it.second })
        assertEquals(1_000_000L + SplitConstants.CAPABILITY_COOLDOWN_MS, rig.cooldowns.single().third)
        assertTrue(rig.sc.cooldowns().containsKey("me" to "iqa"))
        assertTrue(p.notes.any { it.contains("oom") })
        assertNull(p.result)
        rig.close()
    }

    @Test fun errorClassComesFromTheMessageTextWhenTheNodeOnlySendsText() {
        assertEquals("oom", SplitConstants.classify("oom: out of memory"))
        assertEquals("thermal", SplitConstants.classify("thermal"))
        assertEquals("blobs_missing", SplitConstants.classify("released items are missing on this phone"))
        assertEquals("runtime", SplitConstants.classify("IllegalStateException"))
        assertEquals("yielded", SplitConstants.classify("yielded"))
    }

    @Test fun schemaFailuresOnOnePhoneNeverEscalateAndTheChunkFailsAfterSixAttempts() = runTest {
        val gate = FakeGate(GateOutcome.Ran(JsonPrimitive("cloud"), Canon.hash(JsonPrimitive("cloud")), "fake/model"))
        val schema = buildJsonObject { put("type", "array"); put("maxItems", 1) }
        val rig = Rig(this, escalator = gate, schema = JsonSchemaValidator)
        val p = rig.sc.runToEnd(job(n = 2, per = 2, params = buildJsonObject { put("schema", schema) }))
        testScheduler.runCurrent()
        assertTrue(p.done && p.partial)
        assertTrue(p.partialReason!!, p.partialReason!!.contains("failed 6 times (last: schema"))
        assertTrue(gate.requests.isEmpty())                 // 3 failures on 1 phone is not the 2-phone trigger
        assertEquals(6, rig.echo.runs)
        rig.close()
    }

    @Test fun lowVoteConfidenceReachesTheGateAndACloudAnswerIsBadged() = runTest {
        val cloud = buildJsonObject { put("value", "cloud") }
        val gate = FakeGate(GateOutcome.Ran(cloud, Canon.hash(cloud), "fake/model"))
        val memo = MemoryMemo()
        val rig = Rig(this, memo = memo, escalator = gate)
        val variants = (0 until 5).map { JsonObject(emptyMap()) }
        val j = SplitJob("v", Op.IQA, JsonObject(emptyMap()), input = SplitInput.FanOut(variants), merge = MergeKind.VOTE, unitMs = 10.0)
        val p = rig.sc.runToEnd(j)
        testScheduler.runCurrent()
        assertTrue(p.error, p.done)
        val req = gate.requests.single()
        assertEquals(EscalationTrigger.LowConfidence(0.2), req.trigger)
        assertEquals("public", req.privacy)
        assertEquals(Op.IQA, req.node.op)
        assertEquals(cloud, p.result)
        assertEquals(listOf("fake/model"), p.cloud)
        assertTrue(p.notes.any { it.contains("fake/model answered the step") })
        // the chunk outputs were memoized under their ids; nothing is memoized under a bare id for the cloud
        assertTrue(memo.map.keys.none { it.startsWith("cloud:") })
        // private lineage never reaches the gate
        val priv = rig.sc.runToEnd(j.copy(jobId = "v2", input = SplitInput.FanOut(variants, private = true)))
        testScheduler.runCurrent()
        assertEquals(1, gate.requests.size)
        assertTrue(priv.notes.any { it.contains("private") })
        assertEquals("v0", priv.result!!.jsonObject["value"]!!.jsonPrimitive.content)
        rig.close()
    }
}
