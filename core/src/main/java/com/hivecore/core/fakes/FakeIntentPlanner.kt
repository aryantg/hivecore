package com.hivecore.core.fakes

import com.hivecore.core.AnswerChip
import com.hivecore.core.Filter
import com.hivecore.core.Intent
import com.hivecore.core.IntentPlanner
import com.hivecore.core.IntentSet
import com.hivecore.core.PlanResult
import com.hivecore.core.Schema
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keyword → [IntentSet], mirroring tour_templates.json, restricted to the tables the given [Schema] holds
 * (so the same planner behaves like the HR, Sales or Accounts node). Unknown questions → not_relevant.
 * [canned] overrides by exact question text.
 */
class FakeIntentPlanner(var chip: AnswerChip = AnswerChip.LLM, var ms: Long = 5) : IntentPlanner {
    val canned = HashMap<String, IntentSet>()
    val calls = mutableListOf<String>()
    var failNext: Throwable? = null

    override suspend fun plan(question: String, schema: Schema): Result<PlanResult> {
        calls.add(question)
        failNext?.let { failNext = null; return Result.failure(it) }
        canned[question]?.let { return Result.success(PlanResult(it, chip, "canned", ms)) }
        val set = plan(question.lowercase(), schema.tables.map { it.name }.toSet())
        return Result.success(PlanResult(set, chip, "fake", ms))
    }

    private fun plan(q: String, tables: Set<String>): IntentSet {
        val out = ArrayList<Intent>()
        if ("sales" in tables && ("revenue" in q || "turnover" in q || "sold" in q || "units" in q)) {
            val filters = when {
                "this year" in q -> listOf(Filter("month", "gte", JsonPrimitive("2026-01")), Filter("month", "lte", JsonPrimitive("2026-12")))
                "march" in q -> listOf(Filter("month", "eq", JsonPrimitive("2026-03")))
                else -> emptyList()
            } + if ("west" in q) listOf(Filter("region", "eq", JsonPrimitive("West"))) else emptyList()
            val groups = if ("region" in q && "west" !in q) listOf("region") else emptyList()
            out.add(Intent("sales", "sum", if ("units" in q && "revenue" !in q) "units" else "revenue", groups, filters))
        }
        if ("employees" in tables) {
            when {
                "commission" in q -> out.add(Intent("employees", "avg", "commission",
                    filters = if ("west" in q) listOf(Filter("region", "eq", JsonPrimitive("West"))) else emptyList()))
                "salary" in q && "rahul" in q -> out.add(Intent("employees", if ("maximum" in q || "highest" in q) "max" else "avg", "salary",
                    filters = listOf(Filter("name", "eq", JsonPrimitive("Rahul")))))
                "salary" in q -> out.add(Intent("employees", "avg", "salary",
                    filters = if ("south" in q) listOf(Filter("region", "eq", JsonPrimitive("South"))) else emptyList()))
                "reps" in q || "employees" in q || "headcount" in q || "staff" in q || "people" in q ->
                    out.add(Intent("employees", "count", groupBy = if ("region" in q) listOf("region") else emptyList()))
            }
        }
        if ("invoices" in tables && ("invoice" in q || "billed" in q || "spend" in q || "payable" in q)) {
            when {
                "unpaid" in q -> out.add(Intent("invoices", "count", filters = listOf(Filter("status", "eq", JsonPrimitive("UNPAID")))))
                else -> out.add(Intent("invoices", "sum", "total",
                    filters = if ("october" in q) listOf(Filter("month", "eq", JsonPrimitive("2026-10"))) else emptyList()))
            }
        }
        return if (out.isEmpty()) IntentSet(notRelevant = true) else IntentSet(out.take(3))
    }
}
