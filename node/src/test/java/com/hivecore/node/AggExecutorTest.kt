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

    @Test fun aPatternTasksExpectCompilesWithoutTheModel() = runTest {
        // the per_unit pattern's HR step: count employees by region, with the planner's expect block and no model call
        val t = TaskSpec("b", Op.AGG, buildJsonObject {
            put("q", "number of rep by region")
            put("expect", buildJsonObject { put("table", "employees"); put("metric", "count"); put("measure", kotlinx.serialization.json.JsonNull)
                put("dims", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("region")))) })
        }, owner = "hr")
        val q = agg.intentOf(t)!!
        assertEquals("employees", q.intent.table); assertEquals("count", q.intent.metric); assertEquals(listOf("region"), q.intent.groupBy)
        prepared["b"] = PreparedTask.Agg(q)
        val out = Canon.decodeTable(agg.execute(t, emptyList(), emptyList()).getOrThrow())
        assertEquals(listOf("region", "n"), out.columns)
        // a confidential measure keeps the compiler's rule: a sum of salary is refused, so the model path is tried instead
        val bad = TaskSpec("c", Op.AGG, buildJsonObject { put("q", "total salary by region")
            put("expect", buildJsonObject { put("table", "employees"); put("metric", "sum"); put("measure", "salary")
                put("dims", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("region")))) }) }, owner = "hr")
        assertEquals(null, agg.intentOf(bad))
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
