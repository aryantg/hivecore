package com.hivecore.node

import com.hivecore.mesh.PlanConsent
import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeDifferencingGuard
import com.hivecore.node.data.SqlDifferencingGuard
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PlanConsentReqBody
import com.hivecore.mesh.PlanConsentResBody
import com.hivecore.mesh.TaskResultBody
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPlanHandlerTest {
    private var harness: SwarmHarness? = null
    private fun TestScope.mesh(debug: Boolean = false) = SwarmHarness(this, debug).also { harness = it }
    @After fun tearDown() { harness?.close() }

    private val q = "Revenue per rep by region this year"

    @Test fun approvalCoversEveryFigureWithOneSignedConsent() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val a = h.ask(sales, q, h.consentDag(sales, hr)).answer
        assertEquals(1, a.approvals)
        val asked = hr.gate.asked.single()
        assertEquals("Sales", asked.requester)
        assertTrue(asked.description, asked.description.startsWith("Sales' phone asks for 1 figure from your employees table:") && "commission" in asked.description)
        val req = MeshJson.decode<PlanConsentReqBody>(sales.got(MsgType.PLAN_CONSENT_REQ).single().body)
        val res = MeshJson.decode<PlanConsentResBody>(sales.got(MsgType.PLAN_CONSENT_RES).single().body)
        assertEquals(req.intentHashes, res.intentHashes)
        assertEquals(asked.planHash, res.planHash)
        assertTrue(res.approved)
        // Sales's own figure is not confidential: no approval on Sales.
        assertTrue(sales.gate.asked.isEmpty())
    }

    @Test fun ownerDeclineRefusesThePlan() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        hr.gate.mode = PlanGate.Mode.DECLINE
        h.start()
        val r = h.ask(sales, q, h.consentDag(sales, hr)).refusal
        assertEquals(listOf("HR" to "declined by HR"), r.reasons)
        assertTrue(hr.got(MsgType.TASK).isNotEmpty())
        // HR never executed anything: no TASK_RESULT carried an output.
        assertTrue(sales.got(MsgType.TASK_RESULT).map { MeshJson.decode<TaskResultBody>(it.body) }.all { it.output == null })
        assertTrue((hr.guard as SqlDifferencingGuard).answered(sales.nodeId, h.now()).isEmpty())
    }

    @Test fun differencingRefusalPropagatesToTheCommander() = runTest {
        val h = mesh()
        val guard = FakeDifferencingGuard().apply { refusal = SqlDifferencingGuard.REFUSAL }
        val hr = h.node("hr", "HR", NodeRole.HR, guard = guard)
        val sales = h.node("sales", "Sales", NodeRole.SALES)
        h.node("accounts", "Accounts", NodeRole.ACCOUNTS)
        h.start()
        val r = h.ask(sales, q, h.consentDag(sales, hr)).refusal
        assertEquals(listOf("HR" to SqlDifferencingGuard.REFUSAL), r.reasons)
        assertTrue(hr.gate.asked.isEmpty())                               // refused before any fingerprint
        val res = MeshJson.decode<PlanConsentResBody>(sales.got(MsgType.PLAN_CONSENT_RES).single().body)
        assertFalse(res.approved)
        assertEquals(SqlDifferencingGuard.REFUSAL, res.refusal)
        assertTrue(guard.remembered.isEmpty())
    }

    @Test fun nodeSignedApprovalIsDebugOnly() = runTest {
        val h = mesh(debug = false)
        val (hr, sales, _) = h.hrSalesAccounts()
        hr.gate.mode = PlanGate.Mode.NODE_SIGNED
        h.start()
        val r = h.ask(sales, q, h.consentDag(sales, hr)).refusal
        // The router dropped the node-signed approval, so HR's figure never counts as consented.
        assertTrue(sales.got(MsgType.PLAN_CONSENT_RES).isEmpty())
        assertEquals(listOf("HR" to "HR's approval could not be verified"), r.reasons)
        assertEquals(1, sales.got(MsgType.TASK_RESULT).size)
    }

    @Test fun nodeSignedApprovalAcceptedByADebugCommander() = runTest {
        val h = mesh(debug = true)
        val (hr, sales, _) = h.hrSalesAccounts()
        hr.gate.mode = PlanGate.Mode.NODE_SIGNED
        h.start()
        val a = h.ask(sales, q, h.consentDag(sales, hr)).answer
        assertEquals(1, a.approvals)
        val res = MeshJson.decode<PlanConsentResBody>(sales.got(MsgType.PLAN_CONSENT_RES).single().body)
        assertEquals("node", res.signedBy)
    }

    @Test fun refusedSubQuestionRefusesThePlan() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val dag = h.perUnit(sales, hr).let { d -> d.copy(tasks = d.tasks.map { if (it.id == "b") h.agg("b", hr, "the weather by region", listOf("region")) else it }) }
        val r = h.ask(sales, q, dag).refusal
        assertEquals("HR", r.reasons.single().first)
        assertTrue(r.reasons.toString(), r.reasons.single().second.contains("holds nothing"))
    }

    @Test fun canonicalBytesBindPlanAndFigures() {
        val a = String(PlanConsent.canonicalBytes("p", "h", listOf("b", "a")))
        assertEquals("p|h|a,b|true", a)
    }
}
