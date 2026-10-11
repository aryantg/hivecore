package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.CatalogColumn
import com.hivecore.core.CatalogTable
import com.hivecore.core.ColType
import com.hivecore.core.Op
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.runtime.llm.ClosedChooserImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The chat Home's prompts over the Kavira Pharmaceuticals seed: three phones, each holding its role's tables. */
class KaviraPlanningTest {
    private val manifest: JsonObject = Json.parseToJsonElement(TestAssets.read("seed/seed_manifest.json")).jsonObject

    private fun catalog(node: String, name: String, role: String): Catalog {
        val tables = manifest.getValue("roles").jsonObject.getValue(role).jsonObject.getValue("tables").jsonArray.map { it.jsonPrimitive.content }
        fun strs(o: JsonObject, k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return Catalog(node, name, tables.map { tn ->
            val t = manifest.getValue("tables").jsonObject.getValue(tn).jsonObject
            CatalogTable(tn, strs(t, "aliases"), t.getValue("columns").jsonArray.map { c ->
                val o = c.jsonObject
                CatalogColumn(o.getValue("name").jsonPrimitive.content, ColType.valueOf(o.getValue("type").jsonPrimitive.content),
                    groupable = o["groupable"]?.jsonPrimitive?.boolean ?: false, confidential = o["confidential"]?.jsonPrimitive?.boolean ?: false,
                    identifier = o["identifier"]?.jsonPrimitive?.boolean ?: false, aliases = strs(o, "aliases"))
            })
        })
    }

    private val sales = catalog("node-sales", "Sales", "SALES")
    private val hr = catalog("node-hr", "HR", "HR")
    private val accounts = catalog("node-accounts", "Accounts", "ACCOUNTS")
    private val catalogs = listOf(hr, sales, accounts)

    private fun planner(pattern: String) = PlannerImpl(ClosedChooserImpl(FakeLlmEngine().apply { replies["which plan fits"] = pattern }),
        PlannerTestCatalogs.patterns(), PlannerTestCatalogs.CLOCK, ZoneOffset.UTC, availableOps = { setOf(Op.AGG, Op.LLM_MAP, Op.GEN_IMAGE) })

    private suspend fun plan(q: String, pattern: String) = SwarmRouterImpl().route(q, "node-sales", catalogs).let { route ->
        assertTrue("$q: ${route.reason}", route.composite)
        planner(pattern).plan(q, route, catalogs).getOrThrow()
    }

    @Test fun theDeckReadsNetSalesOnTheSalesPhone() = runTest {
        val pq = plan("Generate a pitch deck showing the increase in sales FY2024-26", "deck_growth")
        assertEquals("deck_growth", pq.dag.patternId)
        val a = pq.dag.tasks.first { it.op == Op.AGG }
        assertEquals("node-sales", a.owner)
        // the owner's cached intent (tour_templates K1) is keyed on this exact sub-question
        assertEquals("sales by month FY2024 26", a.params["q"]!!.jsonPrimitive.content)
        val expect = a.params["expect"]!!.jsonObject
        assertEquals("sales_monthly", expect["table"]!!.jsonPrimitive.content)
        assertEquals("net_sales", expect["measure"]!!.jsonPrimitive.content)
        val c = PlanCompilerImpl().compile(pq.dag, catalogs, "", setOf("llm.json", "image.gen"), setOf(Op.AGG, Op.LLM_MAP, Op.GEN_IMAGE), "node-sales")
        assertEquals(emptyList<String>(), c.check.errors)
    }

    @Test fun salesPerRepAsksTheSalesAndHrPhones() = runTest {
        val pq = plan("Sales per rep by branch in FY2026", "per_unit")
        assertEquals("per_unit", pq.dag.patternId)
        val aggs = pq.dag.tasks.filter { it.op == Op.AGG }
        assertEquals(setOf("node-sales", "node-hr"), aggs.map { it.owner }.toSet())
        val c = PlanCompilerImpl().compile(pq.dag, catalogs, "", setOf("llm.json"), setOf(Op.AGG), "node-sales")
        assertEquals(emptyList<String>(), c.check.errors)
    }

    @Test fun monthOnMonthSalesByBranch() = runTest {
        val pq = plan("How did sales change month on month in FY2026 by branch?", "growth_over_period")
        assertEquals("growth_over_period", pq.dag.patternId)
        val a = pq.dag.tasks.first { it.op == Op.AGG }
        assertEquals("node-sales", a.owner)
        assertEquals("2025-04", a.params["expect"]!!.jsonObject["period"]!!.jsonObject["gte"]!!.jsonPrimitive.content)
    }
}
