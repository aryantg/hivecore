package com.hivecore.engine

import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskRunnerImplTest {
    /** Blocks each run until [gate] completes; records the order tasks started in. */
    private class GatedExecutor : OpExecutor {
        override val ops = setOf(Op.EXTRACT, Op.AGG)
        val started = mutableListOf<String>()
        var gate = CompletableDeferred<Unit>()
        override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> {
            started += task.id
            gate.await()
            return Result.success(JsonPrimitive(task.id))
        }
    }

    private fun t(id: String, op: Op = Op.EXTRACT) = TaskSpec(id, op)

    @Test fun runsOneAtATimeByPriority() = runTest {
        val r = TaskRunnerImpl("me")
        val ex = GatedExecutor()
        r.register(ex)
        val first = async { r.run(t("w0"), emptyList(), emptyList(), "peer") }
        runCurrent()
        assertEquals(listOf("w0"), ex.started)
        val close = async { r.run(t("close"), emptyList(), emptyList(), TaskRunnerImpl.FROM_CLOSE) }
        val worker = async { r.run(t("w1"), emptyList(), emptyList(), "peer") }
        val owner = async { r.run(t("own", Op.AGG), emptyList(), emptyList(), "peer") }
        val mine = async { r.run(t("mine"), emptyList(), emptyList(), "me") }
        runCurrent()
        assertEquals(5, r.queueDepth.value)
        repeat(5) { ex.gate.complete(Unit); runCurrent(); ex.gate = CompletableDeferred() }
        listOf(first, close, worker, owner, mine).forEach { assertTrue(it.await().isSuccess) }
        assertEquals(listOf("w0", "mine", "own", "w1", "close"), ex.started)
        assertEquals(0, r.queueDepth.value)
    }

    @Test fun yieldAllFailsQueuedWorkerTasksAndRefusesNewOnes() = runTest {
        val r = TaskRunnerImpl("me")
        val ex = GatedExecutor()
        r.register(ex)
        val running = async { r.run(t("w0"), emptyList(), emptyList(), "peer") }
        runCurrent()
        val queued = async { r.run(t("w1"), emptyList(), emptyList(), "peer") }
        val owner = async { r.run(t("own", Op.AGG), emptyList(), emptyList(), "peer") }
        runCurrent()
        r.yieldAll()
        runCurrent()
        assertEquals(TaskRunnerImpl.YIELDED, queued.await().exceptionOrNull()?.message)
        assertEquals(TaskRunnerImpl.YIELDED, r.run(t("w2"), emptyList(), emptyList(), "peer").exceptionOrNull()?.message)
        ex.gate.complete(Unit)
        assertTrue(running.await().isSuccess)                 // the current generation finishes
        assertTrue(owner.await().isSuccess)                   // owner work is never yielded
        r.resumeWorkers()
        assertTrue(r.run(t("w3"), emptyList(), emptyList(), "peer").isSuccess)
    }

    @Test fun unknownOpFails() = runTest {
        val r = TaskRunnerImpl("me")
        assertTrue(r.run(t("x", Op.OCR), emptyList(), emptyList(), "peer").isFailure)
    }
}
