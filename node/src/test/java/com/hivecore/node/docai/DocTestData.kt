package com.hivecore.node.docai

import com.hivecore.core.fakes.FakeOcr
import com.hivecore.node.data.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Shared test data: the shipped class schemas, the printed-invoice fixtures, and synthetic non-invoice texts. */
object DocTestData {
    val schemas: DocSchemas by lazy { DocSchemas.builtin() }

    val invoices: List<JsonObject> by lazy { Fixtures.expected["printed_invoices"]!!.jsonArray.map { it.jsonObject } }

    fun layoutOfText(text: String): LayoutResult = Layout.of(FakeOcr.fromText(text))

    fun rowsText(text: String): String = RowGrouper.rowsText(FakeOcr.fromText(text)).joinToString("\n")

    val RECEIPT = """
        Fresh Mart Supermarket
        MG Road, Bengaluru
        CASH MEMO / RECEIPT
        Date: 04/10/2026
        Milk 1L x 2  Rs 120.00
        Bread  Rs 45.00
        Total: Rs 165.00
        Paid by UPI
        Thank you for shopping with us
    """.trimIndent()

    val CONTRACT = """
        SERVICE AGREEMENT
        This Agreement is made between Meridian Traders Pvt Ltd (the Client) and Bluewave Hosting (the Provider).
        WHEREAS the Client wishes to engage the Provider for hosting services;
        Effective Date: 1 November 2026
        Term: this agreement remains in force for a period of 24 months.
        Contract value: Rs 6,00,000
        Governing law: India
        IN WITNESS WHEREOF the parties have signed below.
    """.trimIndent()

    val RESUME = """
        Priya Sharma
        Curriculum Vitae
        Career Objective: build reliable data systems
        Work Experience: 6 years as a data engineer at Arjun Logistics
        Education: B.Tech, NIT Surathkal
        Skills: Python, SQL, Kotlin, Spark
    """.trimIndent()

    val LETTER = """
        Kavya Textiles
        12 October 2026
        To the Accounts Manager, Meridian Traders Pvt Ltd
        Subject: Revised payment schedule
        Dear Sir,
        We request that the pending amount be settled in two parts.
        Yours sincerely,
        Kavya Iyer
    """.trimIndent()

    val REPORT = """
        Quarterly Operations Report
        Date: 2026-10-01
        Executive Summary
        Findings: delivery delays fell by 12 percent.
        Recommendations: expand the Pune warehouse.
        Conclusion
    """.trimIndent()

    val PURCHASE_ORDER = """
        Meridian Traders Pvt Ltd
        PURCHASE ORDER
        PO Number: PO-2026-118
        Order Date: 06/10/2026
        Vendor: Omkar Packaging
        Ship to: Pune warehouse
        Corrugated boxes x 400  Rs 31,144.00
        Total: Rs 36,750.00
    """.trimIndent()

    val ID_CARD = """
        GOVERNMENT OF INDIA
        Unique Identification Authority of India
        Name: Rahul Verma
        Date of Birth: 14/03/1990
        2345 6789 0124
        Aadhaar - Aam Aadmi ka Adhikar
    """.trimIndent()
}
