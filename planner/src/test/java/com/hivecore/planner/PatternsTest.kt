package com.hivecore.planner

import com.hivecore.core.Op
import com.hivecore.planner.PlannerTestCatalogs.ACCOUNTS
import com.hivecore.planner.PlannerTestCatalogs.HR
import com.hivecore.planner.PlannerTestCatalogs.SALES
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternsTest {
    private val patterns = PlannerTestCatalogs.patterns()
    private val catalogs = PlannerTestCatalogs.all()
    private fun p(id: String) = patterns.byId(id)!!

    private val revenue = MeasureSlot("revenue", "sales", "revenue", SALES, timed = true)
    private val reps = CountSlot("rep", "employees", HR)
    private val region = DimensionSlot("region", "region")
    private val thisYear = PeriodSlot("this year", "2026-01", "2026-12")

    @Test fun loadsTheTwelvePatterns() {
        assertEquals("3", patterns.version)
        assertEquals(listOf("per_unit", "compare_two", "growth_over_period", "top_k_by", "flag_vs_average", "reconcile_counts",
            "ratio_then_rank", "multi_doc_extract", "deck_growth", "make_image", "bulk_ocr_close", "eval_sweep"), patterns.all.map { it.id })
        assertEquals(setOf("bulk_ocr_close", "eval_sweep"), patterns.all.filter { it.bulk }.map { it.id }.toSet())
    }

    @Test fun perUnitBindsOwnersQuestionsAndExpect() {
        val dag = patterns.bind(p("per_unit"), mapOf("numerator" to revenue, "denominator" to reps, "by" to region, "period" to thisYear), catalogs).getOrThrow()
        assertEquals("per_unit", dag.patternId); assertEquals("3", dag.patternsVersion); assertEquals("r", dag.output)
        assertEquals(listOf("a", "b", "r"), dag.tasks.map { it.id })
        val (a, b, r) = dag.tasks
        assertEquals(SALES, a.owner); assertEquals(HR, b.owner); assertEquals(null, r.owner)
        assertEquals("revenue by region this year", a.params["q"]!!.jsonPrimitive.content)
        // employees has no month column: the period is not put on HR's sub-question
        assertEquals("number of rep by region", b.params["q"]!!.jsonPrimitive.content)
        val ea = a.params["expect"]!!.jsonObject
        assertEquals("sales", ea["table"]!!.jsonPrimitive.content)
        assertEquals("sum", ea["metric"]!!.jsonPrimitive.content)
        assertEquals("revenue", ea["measure"]!!.jsonPrimitive.content)
        assertEquals(listOf("region"), ea["dims"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2026-01", ea["period"]!!.jsonObject["gte"]!!.jsonPrimitive.content)
        assertEquals("count", b.params["expect"]!!.jsonObject["metric"]!!.jsonPrimitive.content)
        assertFalse("period" in b.params["expect"]!!.jsonObject)
        assertEquals(Op.RATIO, r.op); assertEquals(listOf("a", "b"), r.inputs)
        assertEquals(JsonPrimitive("region"), r.params["key"])
    }

    @Test fun optionalSlotsDropFragmentsAndParams() {
        val dag = patterns.bind(p("growth_over_period"), mapOf("measure" to revenue), catalogs).getOrThrow()
        assertEquals("revenue by month", dag.tasks[0].params["q"]!!.jsonPrimitive.content)
        assertEquals(listOf("month"), dag.tasks[0].params["expect"]!!.jsonObject["dims"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("key" in dag.tasks[1].params)
        val top = patterns.bind(p("top_k_by"), mapOf("measure" to revenue, "by" to region, "k" to IntSlot("2", 2), "order" to WordSlot("", "desc")), catalogs).getOrThrow()
        assertEquals(2, top.tasks[1].params["k"]!!.jsonPrimitive.int)
        assertEquals("desc", top.tasks[1].params["order"]!!.jsonPrimitive.content)
    }

    @Test fun confidentialSumAndUngroupableDimAreRejected() {
        val salary = MeasureSlot("salary", "employees", "salary", HR, confidential = true)
        val r = patterns.bind(p("per_unit"), mapOf("numerator" to salary, "denominator" to reps, "by" to region), catalogs)
        assertTrue(r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message!!.contains("confidential"))
        // flag_vs_average uses avg: allowed on a confidential column
        val commission = MeasureSlot("commission", "employees", "commission", HR, confidential = true)
        assertTrue(patterns.bind(p("flag_vs_average"), mapOf("measure" to commission, "by" to region, "cmp" to WordSlot("above", "gt")), catalogs).isSuccess)
        val vendor = DimensionSlot("vendor", "vendor")
        val bad = patterns.bind(p("top_k_by"), mapOf("measure" to revenue, "by" to vendor, "k" to IntSlot("", 1), "order" to WordSlot("", "desc")), catalogs)
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("cannot be grouped by vendor"))
        assertTrue(patterns.bind(p("per_unit"), mapOf("numerator" to revenue), catalogs).isFailure)
    }

    @Test fun multiDocWithOneDocDropsTheSecondBranch() {
        val doc = DocSlot("onboarding checklist", "onboarding_checklist.docx", HR)
        val dag = patterns.bind(p("multi_doc_extract"), mapOf("doc1" to doc), catalogs).getOrThrow()
        assertEquals(listOf("d1", "e1", "r"), dag.tasks.map { it.id })
        assertEquals(HR, dag.tasks[0].owner)
        assertEquals("onboarding_checklist.docx", dag.tasks[0].params["doc"]!!.jsonPrimitive.content)
        assertEquals(listOf("e1"), dag.tasks[2].inputs)
    }

    @Test fun bulkPatternsExpandPerItem() {
        val items = ItemsSlot(listOf("h1", "h2", "h3"), ACCOUNTS)
        val dag = patterns.bind(p("bulk_ocr_close"), mapOf("items" to items), catalogs).getOrThrow()
        assertEquals(listOf("o1", "o2", "o3", "f1", "f2", "f3", "a", "c"), dag.tasks.map { it.id })
        assertEquals(listOf("h2"), dag.tasks[1].blobs)
        assertEquals("h2", dag.tasks[1].params["item"]!!.jsonPrimitive.content)
        assertEquals(listOf("o3"), dag.tasks[5].inputs)
        assertEquals(ACCOUNTS, dag.tasks[6].owner)
        assertEquals(listOf("f1", "f2", "f3", "a"), dag.tasks[7].inputs)
        val ev = patterns.bind(p("eval_sweep"), mapOf("items" to ItemsSlot(listOf("q1", "q2"), null)), catalogs).getOrThrow()
        assertEquals(listOf("e1", "e2", "s"), ev.tasks.map { it.id }); assertEquals("s", ev.output)
    }

    @Test fun loaderRejectsBadReferences() {
        val bad = """{"version":"1","patterns":[{"id":"x","title":"x","slots":[],"dag":[{"id":"a","op":"RATIO","in":["b"]}],"output":"a"}]}"""
        assertThrows(IllegalArgumentException::class.java) { Patterns.fromJson(bad) }
    }
}
