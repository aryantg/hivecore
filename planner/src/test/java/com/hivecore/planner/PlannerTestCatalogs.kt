package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.CatalogColumn
import com.hivecore.core.CatalogTable
import com.hivecore.core.ColType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset

/** The three seed phones' public catalogs, built from assets/seed/seed_manifest.json (real names and aliases). */
object PlannerTestCatalogs {
    const val HR = "node-hr"
    const val SALES = "node-sales"
    const val ACCOUNTS = "node-accounts"
    val TODAY: LocalDate = LocalDate.of(2026, 10, 10)
    val CLOCK: () -> Long = { TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }

    private val manifest: JsonObject by lazy { Json.parseToJsonElement(TestAssets.read("seed/seed_manifest.json")).jsonObject }

    private fun table(name: String): CatalogTable {
        val t = manifest.getValue("tables").jsonObject.getValue(name).jsonObject
        fun strs(o: JsonObject, k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return CatalogTable(name, strs(t, "aliases"), t.getValue("columns").jsonArray.map { c ->
            val o = c.jsonObject
            CatalogColumn(o.getValue("name").jsonPrimitive.content, ColType.valueOf(o.getValue("type").jsonPrimitive.content),
                groupable = o["groupable"]?.jsonPrimitive?.boolean ?: false,
                confidential = o["confidential"]?.jsonPrimitive?.boolean ?: false,
                identifier = o["identifier"]?.jsonPrimitive?.boolean ?: false,
                aliases = strs(o, "aliases"))
        })
    }

    fun hr() = Catalog(HR, "HR", listOf(table("employees")), listOf("onboarding_checklist.docx"))
    fun sales() = Catalog(SALES, "Sales", listOf(table("sales")), listOf("q3_review.pptx"))
    fun accounts() = Catalog(ACCOUNTS, "Accounts", listOf(table("invoices")))
    fun all() = listOf(hr(), sales(), accounts())

    fun patterns(): Patterns = Patterns.fromJson(TestAssets.read(Patterns.ASSET_PATH))
}
