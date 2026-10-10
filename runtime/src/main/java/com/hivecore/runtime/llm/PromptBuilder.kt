package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg
import com.hivecore.core.ColumnDef
import com.hivecore.core.Schema
import com.hivecore.core.TableDef

/**
 * Builds the intent prompt (plan 8): system rules + schema lines + few-shots as user/assistant turns + the
 * question. Pure Kotlin so the JVM snapshot test pins it.
 */
object PromptBuilder {
    const val COVERAGE_RULE = "Answer only the parts of the question your tables cover; use not_relevant only when no part of the question is about these tables."

    /**
     * Wave 4 (HR, Q1 "Revenue per region this year, and how many reps per region?"): without a place to put the
     * measure it does not hold, Qwen3-4B mapped "revenue" to a second intent (sum with no column, or sum salary) and
     * copied a few-shot filter. Naming the foreign measures first in "skip" gives it that place; the parser ignores
     * the key. Measured on the NPU: HR Q1 25 tokens, 2.2 s, correct in every run.
     */
    const val SKIP_RULE = "- Start the object with \"skip\": the measures the question asks for that are not columns here (another department's data), e.g. \"skip\":[\"revenue\"]; then \"intents\" for the rest only. Use \"skip\":[] when every part is yours."

    fun intent(schema: Schema, question: String, fewShots: List<FewShot>, todayIso: String): List<ChatMsg> {
        val out = ArrayList<ChatMsg>(2 + fewShots.size * 2)
        out.add(ChatMsg("system", system(schema, todayIso)))
        for (s in fewShots) {
            out.add(ChatMsg("user", s.question))
            out.add(ChatMsg("assistant", s.answerJson))
        }
        out.add(ChatMsg("user", question.trim()))
        return out
    }

    /** System prompt: rules + schema lines. */
    fun system(schema: Schema, todayIso: String): String = buildString {
        append(rules(todayIso))
        append("\nTables on this node:\n")
        for (t in schema.tables) append(schemaLine(t)).append('\n')
        if (schema.docs.isNotEmpty()) {
            append("Documents on this node (not tables; a question only about them is not_relevant here): ")
            append(schema.docs.joinToString(", ") { it.name }).append('\n')
        }
    }.trimEnd()

    fun rules(todayIso: String): String {
        val d = Dates.of(todayIso)
        return """
            |You turn a question about company tables into a JSON query intent.
            |Output JSON only: exactly one object, either {"intents":[...]} with 1 to 3 intents, or {"not_relevant":true}. No prose, no code fences, nothing after the object.
            |An intent is {"table":"<table>","metric":"<count|sum|avg|min|max>","column":"<numeric column; omit for count>","group_by":["<groupable column>"],"filters":[{"column":"<column>","op":"<eq|neq|gt|gte|lt|lte|in>","value":<text, number, or array for in>}]}. Omit group_by and filters when empty.
            |Rules:
            |- Use only the tables and columns listed below, spelled exactly. Table aliases tell you which words refer to a table.
            |- $COVERAGE_RULE
            |- One intent per measure asked that your tables hold; at most 3. A single intent is the correct answer when only one part of the question is about your tables. Keep the question's grouping ("per region", "by vendor") as group_by.
            |- sum, avg, min and max may only use a column whose name or alias (aka) appears in the question. A measure the question names that is not a column here is simply left out.
            |- Add a filter only for a value the question states (a region name, a month, a status, a person's name); never copy a filter from an example.
            |- group_by may only use columns marked groupable.
            |- Columns marked confidential allow only avg and count as the metric.
            |- Columns marked identifier name a person or an id; when the question names one, filter on that column with op eq and the name as written.
            |- count has no column. sum, avg, min and max need a numeric (INTEGER or REAL) column.
            |- Filter values are exact: text as written in the question (e.g. "West", "UNPAID"), numbers as numbers.
            |- Month values look like ${d.year}-03, dates like ${d.year}-03-14; use the month column for time filters. Today is $todayIso.
            |- "this year" = month gte "${d.year}-01" and month lte "${d.year}-12". "last quarter" = month gte "${d.lastQuarterStart}" and month lte "${d.lastQuarterEnd}". "last month" = month eq "${d.lastMonth}". A month name alone means that month of this year, e.g. "in October" = month eq "${d.year}-10".
            |$SKIP_RULE
        """.trimMargin()
    }

    /** `employees(region TEXT groupable, rep_id TEXT identifier, …) rows=61 aliases: reps, staff` */
    fun schemaLine(t: TableDef): String = buildString {
        append(t.name).append('(')
        append(t.columns.joinToString(", ") { columnSpec(it) })
        append(") rows=").append(t.rowCount)
        if (t.confidential) append(" confidential")
        val aliases = t.aliases.filter { it != t.name }
        if (aliases.isNotEmpty()) append(" aliases: ").append(aliases.joinToString(", "))
    }

    private fun columnSpec(c: ColumnDef): String = buildString {
        append(c.name).append(' ').append(c.type.name)
        if (c.groupable) append(" groupable")
        if (c.identifier) append(" identifier")
        if (c.confidential) append(" confidential")
        val aliases = c.aliases.filter { it != c.name }
        if (aliases.isNotEmpty()) append(" aka ").append(aliases.joinToString("/"))
    }

    /** Date arithmetic for the rules; `todayIso` = YYYY-MM-DD. */
    class Dates private constructor(val year: Int, val month: Int) {
        val lastMonth: String get() = ym(if (month == 1) year - 1 else year, if (month == 1) 12 else month - 1)
        val lastQuarterStart: String get() { val (y, q) = prevQuarter(); return ym(y, q * 3 + 1) }
        val lastQuarterEnd: String get() { val (y, q) = prevQuarter(); return ym(y, q * 3 + 3) }
        private fun prevQuarter(): Pair<Int, Int> { val q = (month - 1) / 3; return if (q == 0) (year - 1) to 3 else year to q - 1 }
        private fun ym(y: Int, m: Int) = "%04d-%02d".format(y, m)
        companion object {
            fun of(todayIso: String): Dates {
                val p = todayIso.trim().split('-')
                val y = p.getOrNull(0)?.toIntOrNull() ?: 2026
                val m = p.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12) ?: 1
                return Dates(y, m)
            }
        }
    }
}
