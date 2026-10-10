package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeSchemaSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanCompilerImplTest {
    private val pc = PlanCompilerImpl()
    private val catalogs = listOf(
        Catalog.of("hr", "HR", FakeSchemaSource.seed(NodeRole.HR)),
        Catalog.of("sales", "Sales", FakeSchemaSource.seed(NodeRole.SALES)),
    )

    private fun agg(id: String, owner: String?, q: String, dims: List<String>? = null) = TaskSpec(id, Op.AGG, buildJsonObject {
        put("q", q)
        if (dims != null) putJsonObject("expect") { putJsonArray("dims") { dims.forEach { add(it) } } }
    }, owner = owner)

    private fun ratio(id: String, inputs: List<String>, key: String = "region") =
        TaskSpec(id, Op.RATIO, buildJsonObject { put("key", key); put("num", "sum_revenue"); put("den", "n") }, inputs)

    private val perUnit = Dag("per_unit", "patterns-1", listOf(
        agg("a", "sales", "revenue by region", listOf("region")),
        agg("b", "hr", "reps by region", listOf("region")),
        ratio("r", listOf("a", "b")),
    ), "r")

    @Test fun validPlanPasses() {
        val c = pc.validate(perUnit, catalogs, "m1")
        assertTrue(c.errors.toString(), c.ok)
        assertEquals(mapOf("sales" to listOf("revenue by region"), "hr" to listOf("reps by region")), c.perOwner)
        assertEquals(64, c.planHash.length)
    }

    @Test fun planHashIsStableAndCoversModelAndVersion() {
        val h = pc.validate(perUnit, catalogs, "m1").planHash
        assertEquals(h, pc.validate(perUnit.copy(), catalogs, "m1").planHash)
        assertEquals(Canon.sha256Hex(Canon.canonical(perUnit) + "patterns-1" + "m1"), h)
        assertNotEquals(h, pc.validate(perUnit, catalogs, "m2").planHash)
        assertNotEquals(h, pc.validate(perUnit.copy(patternsVersion = "patterns-2"), catalogs, "m1").planHash)
    }

    @Test fun cycleIsRejected() {
        val dag = Dag("x", "p", listOf(
            TaskSpec("r1", Op.RANK, buildJsonObject { put("measure", "v") }, listOf("r2")),
            TaskSpec("r2", Op.RANK, buildJsonObject { put("measure", "v") }, listOf("r1")),
        ), "r2")
        val c = pc.validate(dag, catalogs, "m")
        assertFalse(c.ok)
        assertTrue(c.errors.toString(), c.errors.any { it.contains("cycle") })
        val self = Dag("x", "p", listOf(TaskSpec("r", Op.RANK, buildJsonObject { put("measure", "v") }, listOf("r"))), "r")
        assertTrue(pc.validate(self, catalogs, "m").errors.any { it.contains("cycle") })
    }

    @Test fun missingOrLaterInputIsRejected() {
        val missing = perUnit.copy(tasks = listOf(perUnit.tasks[0], ratio("r", listOf("a", "zz"))))
        val c = pc.validate(missing, catalogs, "m")
        assertFalse(c.ok)
        assertTrue(c.errors.toString(), c.errors.any { it.contains("missing input 'zz'") })
        val later = perUnit.copy(tasks = listOf(perUnit.tasks[0], ratio("r", listOf("a", "b")), perUnit.tasks[1]))
        assertTrue(pc.validate(later, catalogs, "m").errors.any { it.contains("comes later") })
    }

    @Test fun unknownOrMissingOwnerIsRejected() {
        val unknown = perUnit.copy(tasks = listOf(agg("a", "accounts", "spend by month"), perUnit.tasks[1], ratio("r", listOf("a", "b"))))
        val c = pc.validate(unknown, catalogs, "m")
        assertFalse(c.ok)
        assertTrue(c.errors.toString(), c.errors.any { it.contains("'accounts' is not a known node") })
        val none = perUnit.copy(tasks = listOf(agg("a", null, "x"), perUnit.tasks[1], ratio("r", listOf("a", "b"))))
        assertTrue(pc.validate(none, catalogs, "m").errors.any { it.contains("has no owner") })
    }

    @Test fun tooManyTasksIsRejected() {
        val tasks = (1..13).map { agg("a$it", "sales", "q$it") }
        val c = pc.validate(Dag("big", "p", tasks, "a1"), catalogs, "m")
        assertFalse(c.ok)
        assertTrue(c.errors.toString(), c.errors.any { it.contains("13 tasks") })
        // 7 AGGs on one owner breaks the per-phone LLM budget even under 12 tasks
        val seven = (1..7).map { agg("a$it", "sales", "q$it") }
        assertTrue(pc.validate(Dag("llm", "p", seven, "a1"), catalogs, "m").errors.any { it.contains("7 LLM calls on 'sales'") })
        val sixOk = (1..6).map { agg("a$it", "sales", "q$it") } + (1..6).map { agg("b$it", "hr", "q$it") }
        assertTrue(pc.validate(Dag("ok", "p", sixOk, "a1"), catalogs, "m").ok)
    }

    @Test fun arityTypesAndKeysAreChecked() {
        val oneInput = perUnit.copy(tasks = listOf(perUnit.tasks[0], ratio("r", listOf("a"))))
        assertTrue(pc.validate(oneInput, catalogs, "m").errors.any { it.contains("has 1 inputs") })
        val docIn = Dag("x", "p", listOf(
            TaskSpec("d", Op.DOC, buildJsonObject { put("q", "leave days") }, owner = "hr"),
            TaskSpec("k", Op.RANK, buildJsonObject { put("measure", "v") }, listOf("d")),
        ), "k")
        assertTrue(pc.validate(docIn, catalogs, "m").errors.any { it.contains("needs tables") })
        val badKey = perUnit.copy(tasks = perUnit.tasks.dropLast(1) + ratio("r", listOf("a", "b"), key = "month"))
        assertTrue(pc.validate(badKey, catalogs, "m").errors.any { it.contains("key month is not a dimension") })
        val noQ = Dag("x", "p", listOf(TaskSpec("a", Op.AGG, JsonObject(emptyMap()), owner = "sales")), "a")
        assertTrue(pc.validate(noQ, catalogs, "m").errors.any { it.contains("no sub-question") })
    }

    @Test fun outputAndIdsAreChecked() {
        assertTrue(pc.validate(perUnit.copy(output = "nope"), catalogs, "m").errors.any { it.contains("output 'nope'") })
        val dup = perUnit.copy(tasks = perUnit.tasks + agg("a", "sales", "again"))
        assertTrue(pc.validate(dup, catalogs, "m").errors.any { it.contains("duplicate task id 'a'") })
    }
}
