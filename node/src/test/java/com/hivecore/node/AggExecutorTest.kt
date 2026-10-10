package com.hivecore.node

import com.hivecore.engine.PreparedTask
import com.hivecore.engine.AggExecutor
import com.hivecore.core.Canon
import com.hivecore.core.AnswerChip
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeIntentPlanner
import com.hivecore.node.data.RuleIntentCompiler
import com.hivecore.node.data.SqlQueryExecutor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hivecore.node.data.Fixtures as DataFixtures

class AggExecutorTest {
    private val db = JdbcSqlDb.inMemory()
    private val store = DataFixtures.store(NodeRole.HR, db)
    private val prepared = HashMap<String, PreparedTask>()
    private val agg = AggExecutor("HR", store, FakeIntentPlanner(), RuleIntentCompiler(), SqlQueryExecutor(db) { AnswerChip.LLM }) { prepared[it.id] }

    private fun task(q: String) = TaskSpec("b", Op.AGG, buildJsonObject { put("q", q) }, owner = "hr")

    @Test fun compilesThroughTheOwnersPathAndRunsOnlyPreparedTasks() = runTest {
        val q = agg.compileAgg("reps by region").getOrThrow()
        assertEquals("employees", q.intent.table)
        assertFalse(q.touchesConfidential)
        val c = agg.compileAgg("average commission").getOrThrow()
        assertTrue(c.touchesConfidential)
        assertTrue(agg.execute(task("reps by region"), emptyList(), emptyList()).isFailure)   // not prepared
        prepared["b"] = PreparedTask.Agg(q)
        val t = Canon.decodeTable(agg.execute(task("reps by region"), emptyList(), emptyList()).getOrThrow())
        assertEquals(listOf("region", "n"), t.columns)
        assertEquals(61L, t.rowsScanned)
        assertEquals("HR", t.node)
    }

    @Test fun unknownSubQuestionIsRefused() = runTest {
        val r = agg.compileAgg("the weather")
        assertFalse(r.isSuccess)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("holds nothing"))
    }

    @Test fun floorRefusalIsAFailure() = runTest {
        val q = agg.compileAgg("What is Rahul's maximum salary?")
        // Either the compiler refuses the identifier filter, or the executor's floor does: never a figure.
        if (q.isSuccess) {
            prepared["b"] = PreparedTask.Agg(q.getOrThrow())
            assertTrue(agg.execute(task("x"), emptyList(), emptyList()).isFailure)
        }
    }
}
