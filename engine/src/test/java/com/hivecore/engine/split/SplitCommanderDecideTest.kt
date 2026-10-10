package com.hivecore.engine.split

import com.hivecore.core.Canon
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeDecisionModel
import com.hivecore.engine.ComparatorImpl
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.engine.decide.ChunkDecisions
import com.hivecore.engine.decide.DecisionBudget
import com.hivecore.engine.decide.Templates
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.SqliteJobJournal
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The commander with a decision model: decisions before the first TASK, journaled; fallback; resume without the model; verdict annotation. */
class SplitCommanderDecideTest {
    /** EMBED-like: one item per sentence unit with a hash of it (a STITCH merge of it equals the unsplit run). */
    private class Units : OpExecutor {
        override val ops = setOf(Op.EMBED, Op.IQA)
        val ran = ArrayList<String>()
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            ran += task.id
            val units = task.params["units"] as? JsonArray ?: return Result.success(buildJsonObject { put("items", JsonArray(emptyList())) })
            return Result.success(buildJsonObject { put("items", JsonArray(units.map { u -> buildJsonObject { put("id", u.jsonObject["unit"]!!); put("v", Canon.hash(u)) } })) })
        }
    }

    /** The rig's jobs take milliseconds, so the budget's share rule is relaxed here (DecideTest covers it). */
    private class Rig(scope: TestScope, model: FakeDecisionModel?, journal: JobJournal = JobJournal.None, budget: DecisionBudget = DecisionBudget(callMsEstimate = 1.0)) {
        val now = { 1_000_000L + scope.testScheduler.currentTime }
        val runner = TaskRunnerImpl("me")
        val exec = Units().also { runner.register(it) }
        val bg = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob())
        val tasks = ArrayList<String>()
        lateinit var sc: SplitCommander
        init {
            sc = SplitCommander("me", ComparatorImpl(), profiles = { emptyList() }, clock = now, journal = journal,
                decisions = model?.let { ChunkDecisions(it, "me", budget, now) },
                transmit = { _, type, body ->
                    if (type == MsgType.TASK) {
                        val b = MeshJson.decode<TaskBody>(body)
                        tasks += b.task.id
                        bg.launch {
                            val r = runner.run(b.task, emptyList(), emptyList(), "me")
                            val res = r.fold({ TaskResultBody(b.planId, b.task.id, "me", it, Canon.hash(it), ms = 5) },
                                { TaskResultBody(b.planId, b.task.id, "me", error = it.message) })
                            sc.onMessage(MsgType.TASK_RESULT, "me", MeshJson.encode(res))
                        }
                    }
                    Result.success(Unit)
                })
        }
        fun close() = bg.cancel()
    }

    private val text = (0 until 120).joinToString(" ") { i -> if (i % 30 == 0) "Section ${i / 30} begins here." else "Sentence $i stays on topic ${i / 30}." }
    private fun model() = FakeDecisionModel().apply {
        boundaryRule = { q, state -> val i = q.id.removePrefix("b").toInt(); if (state.contains("s${i + 1}: Section")) 0.95 else 0.05 }
        score("d.*", 0.8)
        noul("v.*", 0.35)
    }
    private fun job(id: String = "t") = SplitJob(id, Op.EMBED, JsonObject(emptyMap()), "m", SplitInput.Text(text, "doc"), unitMs = 10.0)

    private fun reference(j: SplitJob): String {
        val whole = Splitters.whole(j.opKey, j.params, j.input)
        val units = whole.params["units"]!!.jsonArray
        return Canon.hash(buildJsonObject { put("items", JsonArray(units.map { u -> buildJsonObject { put("id", u.jsonObject["unit"]!!); put("v", Canon.hash(u)) } })) })
    }

    @Test fun decisionsAreJournaledBeforeTheFirstTaskAndTheMergeEqualsTheUnsplitRun() = runTest {
        val log = ArrayList<String>()
        val journal = object : JobJournal by SqliteJobJournal(JdbcSqlDb.inMemory()) {
            override fun begin(job: com.hivecore.engine.journal.JobRow) { log += "begin" }
            override fun decisions(rows: List<com.hivecore.engine.journal.DecisionRow>) { log += "decisions:${rows.size}:${rows.map { it.point }.distinct()}" }
            override fun chunk(row: com.hivecore.engine.journal.ChunkRow) { if (log.none { it.startsWith("chunk") }) log += "chunk" }
            override fun attempt(row: com.hivecore.engine.journal.AttemptRow) { if (log.none { it.startsWith("attempt") }) log += "attempt" }
        }
        val rig = Rig(this, model(), journal)
        val j = job()
        val out = rig.sc.run(j, useMemo = false).toList()
        testScheduler.runCurrent()
        val p = out.last()
        assertTrue(p.error, p.done)
        assertEquals(reference(j), p.resultHash)
        // the first emission says the model is deciding; then journal order: job, decisions (A and B), chunks, attempts
        assertTrue(out.first().notes.any { it.contains("deciding") })
        assertEquals("begin", log[0]); assertTrue(log[1], log[1].startsWith("decisions:") && log[1].contains(Templates.BOUNDARY) && log[1].contains(Templates.DIFFICULTY))
        assertEquals(listOf("chunk", "attempt"), log.drop(2).take(2))
        // the cut followed the model: 30-sentence topics; difficulty 0.8 → cost factor 1.15 on every chunk
        assertEquals(listOf(30, 30, 30, 30), p.chunks.map { it.units })
        assertTrue(p.notes.any { it.contains("decided boundaries") })
        // the annotation (decision point E) reached the cells: a single-run op's outputs are judged off the loop
        assertTrue(p.chunks.all { it.verdict == 0.35 })
        assertTrue(p.notes.any { it.contains("low-confidence") })
        rig.close()
    }

    @Test fun withoutAModelOrWithItUnavailableTheIdsAreTodays() = runTest {
        val j = job()
        val plain = Rig(this, null)
        val a = plain.sc.run(j, useMemo = false).toList().last()
        val unavailable = Rig(this, FakeDecisionModel(build = null))
        val b = unavailable.sc.run(j, useMemo = false).toList().last()
        val failing = Rig(this, FakeDecisionModel().apply { failNext = IllegalStateException("no") })
        val c = failing.sc.run(j, useMemo = false).toList().last()
        val broke = Rig(this, model(), budget = DecisionBudget(maxLlmCalls = 1, callMsEstimate = 1.0))
        val d = broke.sc.run(j, useMemo = false).toList().last()
        testScheduler.runCurrent()
        for (p in listOf(a, b, c, d)) { assertTrue(p.error, p.done); assertEquals(reference(j), p.resultHash) }
        val fixed = ChunkPlanner().plan(j, emptyList(), "me").getOrThrow().chunks.map { it.chunkId }
        assertEquals(fixed, a.chunks.map { it.chunkId })
        assertEquals(fixed, b.chunks.map { it.chunkId })
        assertEquals(fixed, c.chunks.map { it.chunkId })
        assertEquals(fixed, d.chunks.map { it.chunkId })
        assertTrue(d.notes.any { it.contains("over budget") })
        assertTrue(a.chunks.all { it.verdict == null })
        listOf(plain, unavailable, failing, broke).forEach { it.close() }
    }

    @Test fun aJournaledDecidedJobResumesToTheSameIdsWithoutTheModel() = runTest {
        val db = JdbcSqlDb.inMemory()
        val journal = SqliteJobJournal(db)
        val rig = Rig(this, model(), journal)
        val j = job("r")
        val p = rig.sc.run(j, useMemo = false).toList().last()
        testScheduler.runCurrent()
        assertTrue(p.done)
        val snap = journal.load("r")!!
        val spec = Canon.json.decodeFromJsonElement(SplitJob.serializer(), snap.job.spec!!)
        assertEquals(listOf(30, 60, 90), spec.cuts)
        assertTrue(spec.decider!!.endsWith("+t${Templates.VERSION}"))
        assertTrue(snap.decisions.size > 100)
        assertEquals(p.chunks.map { it.chunkId }, snap.chunks.sortedBy { it.index }.map { it.chunkId })
        // a resume re-runs the journaled spec with no model: same ids, no new decisions, every chunk prefilled
        val resumed = Rig(this, FakeDecisionModel(build = null), journal)
        val prefilled = snap.outputs.associate { it.chunkId to it.outputJson }
        val q = resumed.sc.run(spec, useMemo = false, prefilled = prefilled, planRun = 2).toList().last()
        testScheduler.runCurrent()
        assertTrue(q.done)
        assertEquals(p.chunks.map { it.chunkId }, q.chunks.map { it.chunkId })
        assertEquals(p.resultHash, q.resultHash)
        assertTrue(resumed.tasks.isEmpty())
        assertEquals(snap.decisions.size, journal.load("r")!!.decisions.size)
        // the same spec under a different decider build would be a different cut identity
        val other = ChunkPlanner().plan(spec.copy(decider = "other:cal+t1"), emptyList(), "me").getOrThrow()
        assertNotEquals(p.chunks.map { it.chunkId }, other.chunks.map { it.chunkId })
        assertNull(ChunkDecisions(model(), "me").decideCut(spec, other))
        rig.close(); resumed.close()
    }
}
