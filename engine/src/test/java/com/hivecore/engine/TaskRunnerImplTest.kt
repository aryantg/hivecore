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
        val scheduled = async { r.run(t("sched"), emptyList(), emptyList(), TaskRunnerImpl.FROM_SCHEDULED) }
        val close = async { r.run(t("close"), emptyList(), emptyList(), TaskRunnerImpl.FROM_CLOSE) }
        val worker = async { r.run(t("w1"), emptyList(), emptyList(), "peer") }
        val owner = async { r.run(t("own", Op.AGG), emptyList(), emptyList(), "peer") }
        val mine = async { r.run(t("mine"), emptyList(), emptyList(), "me") }
        runCurrent()
        assertEquals(6, r.queueDepth.value)
        repeat(6) { ex.gate.complete(Unit); runCurrent(); ex.gate = CompletableDeferred() }
        listOf(first, scheduled, close, worker, owner, mine).forEach { assertTrue(it.await().isSuccess) }
        // own request, then owner-class work for a peer, then worker work, then scheduled (FIFO within a class)
        assertEquals(listOf("w0", "mine", "own", "w1", "sched", "close"), ex.started)
        assertEquals(0, r.queueDepth.value)
        assertEquals(TaskRunnerImpl.Priority.SCHEDULED, r.priorityOf(t("x"), TaskRunnerImpl.FROM_SCHEDULED))
        assertEquals(TaskRunnerImpl.Priority.OWN, r.priorityOf(t("x"), "me"))
        assertEquals(TaskRunnerImpl.Priority.OWNER, r.priorityOf(t("x", Op.AGG), "peer"))
        assertEquals(TaskRunnerImpl.Priority.WORKER, r.priorityOf(t("x"), "peer"))
    }

    @Test fun yieldAllFailsQueuedWorkerTasksAndRefusesNewOnes() = runTest {
        val r = TaskRunnerImpl("me")
        val ex = GatedExecutor()
        r.register(ex)
        val running = async { r.run(t("w0"), emptyList(), emptyList(), "peer") }
        runCurrent()
        val queued = async { r.run(t("w1"), emptyList(), emptyList(), "peer") }
        val scheduled = async { r.run(t("s1"), emptyList(), emptyList(), TaskRunnerImpl.FROM_SCHEDULED) }
        val owner = async { r.run(t("own", Op.AGG), emptyList(), emptyList(), "peer") }
        runCurrent()
        r.yieldAll()
        runCurrent()
        // queued worker and scheduled tasks fail with class "yielded" (spec section 11.3)
        val e = queued.await().exceptionOrNull()!!
        assertEquals(TaskRunnerImpl.YIELDED, e.message)
        assertEquals(TaskFailure.YIELDED, (e as TaskFailure).errorClass)
        assertEquals(TaskFailure.YIELDED, TaskFailure.classOf(scheduled.await().exceptionOrNull()!!))
        assertEquals(TaskRunnerImpl.YIELDED, r.run(t("w2"), emptyList(), emptyList(), "peer").exceptionOrNull()?.message)
        ex.gate.complete(Unit)
        assertTrue(running.await().isSuccess)                 // the current generation finishes
        assertTrue(owner.await().isSuccess)                   // owner work is never yielded
        r.resumeWorkers()
        assertTrue(r.run(t("w3"), emptyList(), emptyList(), "peer").isSuccess)
    }

    @Test fun unknownOpFails() = runTest {
        val r = TaskRunnerImpl("me")
        val e = r.run(t("x", Op.OCR), emptyList(), emptyList(), "peer").exceptionOrNull()!!
        assertEquals(TaskFailure.RUNTIME, TaskFailure.classOf(e))
        assertEquals("oom: out of memory", TaskFailure(TaskFailure.OOM, "out of memory").message)
    }
}
