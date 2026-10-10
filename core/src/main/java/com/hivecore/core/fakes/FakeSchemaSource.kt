package com.hivecore.core.fakes

import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.DocDef
import com.hivecore.core.NodeRole
import com.hivecore.core.Schema
import com.hivecore.core.SchemaSource
import com.hivecore.core.TableDef

/** Holds one mutable [Schema]; [seed] builds the plan 7 seed-manifest schema for a role. */
class FakeSchemaSource(@Volatile var current: Schema) : SchemaSource {
    constructor(role: NodeRole) : this(seed(role))

    override fun schema(): Schema = current

    companion object {
        val EMPLOYEES = TableDef(
            name = "employees", rowCount = 61,
            aliases = listOf("employees", "employee", "reps", "rep", "staff", "headcount", "people", "salaries", "commissions"),
            columns = listOf(
                ColumnDef("region", ColType.TEXT, groupable = true),
                ColumnDef("rep_id", ColType.TEXT, identifier = true),
                ColumnDef("name", ColType.TEXT, identifier = true),
                ColumnDef("salary", ColType.INTEGER, confidential = true),
                ColumnDef("commission", ColType.INTEGER, confidential = true),
                ColumnDef("joined", ColType.TEXT)
            )
        )
        val SALES = TableDef(
            name = "sales", rowCount = 1204,
            aliases = listOf("sales", "revenue", "sold", "units", "orders", "turnover"),
            columns = listOf(
                ColumnDef("region", ColType.TEXT, groupable = true),
                ColumnDef("rep_id", ColType.TEXT, identifier = true),
                ColumnDef("month", ColType.TEXT, groupable = true),
                ColumnDef("revenue", ColType.INTEGER),
                ColumnDef("units", ColType.INTEGER)
            )
        )
        val INVOICES = TableDef(
            name = "invoices", rowCount = 40,
            aliases = listOf("invoices", "invoice", "invoiced", "billed", "bills", "payable", "vendors", "spend"),
            columns = listOf(
                ColumnDef("vendor", ColType.TEXT, groupable = true),
                ColumnDef("date", ColType.TEXT),
                ColumnDef("month", ColType.TEXT, groupable = true),
                ColumnDef("total", ColType.INTEGER),
                ColumnDef("status", ColType.TEXT, groupable = true),
                ColumnDef("source", ColType.TEXT)
            )
        )
        val ONBOARDING = DocDef(id = "doc_onboarding", name = "onboarding_checklist.docx", kind = "docx", units = 40, unitLabel = "paragraph", chunkCount = 8)
        val LEAVE_POLICY = DocDef(id = "doc_leave", name = "leave_policy.pdf", kind = "pdf", units = 6, unitLabel = "page", chunkCount = 12)
        val Q3_REVIEW = DocDef(id = "doc_q3", name = "q3_review.pptx", kind = "pptx", units = 14, unitLabel = "slide", chunkCount = 14)

        /** What each role holds right after Setup (HR gets leave_policy.pdf live, in the tour). */
        fun seed(role: NodeRole): Schema = when (role) {
            NodeRole.HR -> Schema(role, listOf(EMPLOYEES), listOf(ONBOARDING))
            NodeRole.SALES -> Schema(role, listOf(SALES), listOf(Q3_REVIEW))
            NodeRole.ACCOUNTS -> Schema(role, listOf(INVOICES), emptyList())
        }
    }
}
