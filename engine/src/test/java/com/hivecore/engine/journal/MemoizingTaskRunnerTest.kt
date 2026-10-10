package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.engine.TaskRunnerImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoizingTaskRunnerTest {
    private class Counting(var output: JsonElement = JsonPrimitive("ran")) : OpExecutor {
        override val ops = setOf(Op.OCR)
        var runs = 0
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> { runs++; return Result.success(output) }
    }

    private val memo = SqliteChunkMemo(JdbcSqlDb.inMemory())
    private val exec = Counting()
    private val runner = MemoizingTaskRunner(TaskRunnerImpl("self").also { it.register(exec) }, memo)

    private fun task(id: String, chunkId: String?, memoOnly: Boolean = false) = TaskSpec(id, Op.OCR, buildJsonObject {
        if (chunkId != null) put(MemoizingTaskRunner.PARAM_CHUNK_ID, chunkId)
        if (memoOnly) put(MemoizingTaskRunner.PARAM_MEMO_ONLY, true)
    })

    @Test fun answersFromTheMemoWithoutRunning() = runTest {
        assertEquals(JsonPrimitive("ran"), runner.run(task("t1", "c1"), emptyList(), emptyList(), "self").getOrThrow())
        assertEquals(1, exec.runs)
        assertEquals(Canon.hash(JsonPrimitive("ran")), memo.get("c1")!!.outputHash)
        exec.output = JsonPrimitive("changed")
        assertEquals(JsonPrimitive("ran"), runner.run(task("t2", "c1"), emptyList(), emptyList(), "peer").getOrThrow())
        assertEquals(1, exec.runs)
        assertEquals(1, runner.hits)
        // No chunkId: never memoized.
        assertEquals(JsonPrimitive("changed"), runner.run(task("t3", null), emptyList(), emptyList(), "self").getOrThrow())
        assertEquals(2, exec.runs)
        assertEquals(JsonPrimitive("changed"), runner.run(task("t4", null), emptyList(), emptyList(), "self").getOrThrow())
        assertEquals(3, exec.runs)
    }

    @Test fun memoOnlyTasksNeverRun() = runTest {
        val r = runner.run(task("t1", "c9", memoOnly = true), emptyList(), emptyList(), "orch")
        assertTrue(r.isFailure)
        assertEquals(MemoizingTaskRunner.NOT_MEMOIZED, r.exceptionOrNull()!!.message)
        assertEquals(0, exec.runs)
        memo.put("c9", Canon.hash(JsonPrimitive("held")), JsonPrimitive("held"))
        assertEquals(JsonPrimitive("held"), runner.run(task("t2", "c9", memoOnly = true), emptyList(), emptyList(), "orch").getOrThrow())
        assertEquals(0, exec.runs)
    }

    @Test fun corruptedEntriesAreNotServedAndTheFallbackIsConsulted() = runTest {
        memo.put("c1", "not-the-hash", JsonPrimitive("x"))
        assertEquals(JsonPrimitive("ran"), runner.run(task("t1", "c1"), emptyList(), emptyList(), "self").getOrThrow())
        assertEquals(1, exec.runs)                       // ran, and the memo now holds the right hash
        assertEquals(Canon.hash(JsonPrimitive("ran")), memo.get("c1")!!.outputHash)
        runner.fallback = { id -> if (id == "c7") MemoHit("c7", Canon.hash(JsonPrimitive("deputy")), JsonPrimitive("deputy"), 0) else null }
        assertEquals(JsonPrimitive("deputy"), runner.run(task("t2", "c7", memoOnly = true), emptyList(), emptyList(), "orch").getOrThrow())
        assertEquals(1, exec.runs)
    }
}
