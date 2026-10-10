package com.hivecore.node

import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.core.NodeRole
import com.hivecore.node.data.TextAnswerRenderer
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.TaskSignalBody
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class CommanderTest {
    private var harness: SwarmHarness? = null
    private fun TestScope.mesh(debug: Boolean = false) = SwarmHarness(this, debug).also { harness = it }
    @After fun tearDown() { harness?.close() }

    private val q = "Revenue per rep by region this year"

    /** revenue per region this year (Sales seed) / headcount per region (HR seed), straight from SQL. */
    private fun expectedRatios(h: SwarmHarness, sales: SwarmHarness.Node, hr: SwarmHarness.Node): Map<String, BigDecimal> {
        val rev = sales.db.query("SELECT region, SUM(revenue) AS s FROM t_sales WHERE month >= '2026-01' AND month <= '2026-12' GROUP BY region")
            .associate { it["region"] as String to BigDecimal((it["s"] as Number).toString()) }
        val reps = hr.db.query("SELECT region, COUNT(*) AS n FROM t_employees GROUP BY region")
            .associate { it["region"] as String to BigDecimal((it["n"] as Number).toString()) }
        return rev.filterKeys { it in reps }.mapValues { (k, v) -> v.divide(reps.getValue(k), 4, RoundingMode.HALF_UP) }
    }

    @Test fun perUnitComputesTheRatioFromBothOwnersWithOneApproval() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val asked = h.ask(sales, q, h.perUnit(sales, hr))
        val a = asked.answer
        val expected = expectedRatios(h, sales, hr)
        assertEquals(4, expected.size)
        assertNotNull(a.joined)
        val table = a.joined!!
        assertEquals(listOf("region", "sum_revenue", "n", "ratio"), table.columns)
        for (row in table.rows) {
            val region = row[0].jsonPrimitive.content
            assertEquals(region, 0, expected.getValue(region).compareTo(BigDecimal(row[3].jsonPrimitive.content).setScale(4, RoundingMode.HALF_UP)))
        }
        val east = expected.getValue("East")
        assertTrue(a.text, a.text.startsWith("Revenue per rep by region: East ${TextAnswerRenderer.indian(east.toDouble())}"))
        assertEquals(0L, a.rowsMoved)
        assertEquals(0, a.approvals)                       // headcount touches no confidential column
        assertTrue(hr.gate.asked.isEmpty())
        assertEquals(setOf("Sales", "HR"), a.tables.map { it.node }.toSet())
        assertEquals(listOf("Sales", "HR"), a.provenance.map { it.node })
        assertTrue(a.provenance.toString(), a.provenance[1].line.startsWith("HR: 61 rows → 4 aggregates"))
        val plan = a.plan!!
        assertEquals("per_unit", plan.patternId)
        assertEquals(3, plan.tasks)
        assertEquals(3, plan.verified)
        assertTrue(plan.lines.toString(), plan.lines[1].startsWith("AGG · HR ·") && plan.lines[2].startsWith("RATIO · here"))
        assertTrue(asked.statuses.toString(), asked.statuses.any { it.startsWith("Asking HR") })
        // Ledger: a plan entry with the assignment and profiles, and one task entry per task.
        val entries = sales.ledger.entries().reversed()
        assertEquals(listOf("plan", "task", "task", "task"), entries.map { it.kind })
        val planEntry = MeshJson.json.parseToJsonElement(entries.first().entry).jsonObject
        assertEquals(plan.planHash, planEntry["planHash"]!!.jsonPrimitive.content)
        assertEquals(3, (planEntry["profiles"] as JsonArray).size)
        // Accounts was not a participant.
        assertTrue(h.nodes["accounts"]!!.got(MsgType.PLAN).isEmpty())
        // HR remembered the answered intent for the differencing window.
        assertEquals(1, (hr.guard as com.hivecore.node.data.SqlDifferencingGuard).answered(sales.nodeId, h.now()).size)
    }

    @Test fun ownerThatNeverRepliesEndsWithNoReplyFromHr() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start(hr)                                        // HR's router is up but its swarm side never answers
        val r = h.ask(sales, q, h.perUnit(sales, hr)).refusal
        assertEquals(listOf("HR" to "no reply from HR"), r.reasons)
        assertTrue(r.timing.totalMs!! in 4_000L..20_000L)
    }

    @Test fun memoHitSkipsReExecution() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val first = h.ask(sales, q, h.perUnit(sales, hr)).answer
        val hrCalls = hr.intentPlanner.calls.size
        val salesCalls = sales.intentPlanner.calls.size
        val second = h.ask(sales, q, h.perUnit(sales, hr)).answer
        assertEquals(first.text, second.text)
        assertEquals(hrCalls, hr.intentPlanner.calls.size)
        assertEquals(salesCalls, sales.intentPlanner.calls.size)
        assertEquals(1, hr.got(MsgType.PLAN).size)
        assertTrue(second.plan!!.lines.toString(), second.plan!!.lines[1].contains("memo"))
    }

    @Test fun workerYieldIsReassigned() = runTest {
        val h = mesh()
        val hr = h.node("hr", "HR", NodeRole.HR)
        val sales = h.node("sales", "Sales", NodeRole.SALES, eligible = false)   // the commander is busy: not a candidate
        val accounts = h.node("accounts", "Accounts", NodeRole.ACCOUNTS)
        h.start()
        hr.runner.yieldAll()                                                       // HR's owner picked the phone up
        val a = h.ask(sales, "Key points from the onboarding checklist", h.extractOnly()).answer
        assertEquals(0, hr.extract.runs)
        assertEquals(1, accounts.extract.runs)
        assertEquals(1, sales.extract.runs)                                        // the reassignment fell back to the commander
        assertEquals(1, sales.got(MsgType.TASK_YIELD).size)
        assertEquals(TaskRunnerImpl.YIELDED, MeshJson.decode<TaskSignalBody>(sales.got(MsgType.TASK_YIELD).single().body).reason)
        assertTrue(a.notes.toString(), a.notes.any { "moved from HR" in it })
        assertEquals(1, a.plan!!.verified)
        assertTrue(a.text, a.text.contains("s1"))
    }

    @Test fun replicasThatAgreeAreVerified() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        h.start()
        val a = h.ask(sales, "Key points from the onboarding checklist", h.extractOnly()).answer
        val ran = listOf(hr, sales, accounts).sumOf { it.extract.runs }
        assertEquals(2, ran)
        assertEquals(1, a.plan!!.verified)
        assertTrue(a.plan!!.lines.single().endsWith("✓"))
    }

    @Test fun replicaMismatchWithoutATieBreakRefuses() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        hr.extract.output = JsonArray(listOf(JsonPrimitive("s1")))
        sales.extract.output = JsonArray(listOf(JsonPrimitive("s2")))
        accounts.extract.output = JsonArray(listOf(JsonPrimitive("s3")))
        h.start()
        val r = h.ask(sales, "Key points from the onboarding checklist", h.extractOnly()).refusal
        assertEquals(3, listOf(hr, sales, accounts).sumOf { it.extract.runs })   // two replicas, then the tie-break
        assertTrue(r.reasons.toString(), r.reasons.single().second.contains("disagree"))
    }

    @Test fun replicaMismatchBrokenByAThirdPhone() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val odd = JsonArray(listOf(JsonPrimitive("s9")))
        // Whichever two phones run first, at most one of them returns the odd answer and the third agrees with the other.
        hr.extract.output = odd
        h.start()
        val a = h.ask(sales, "Key points from the onboarding checklist", h.extractOnly()).answer
        assertTrue(a.text, a.text.contains("s1") && !a.text.contains("s9"))
        assertEquals(1, a.plan!!.verified)
        assertTrue(listOf(hr, sales, accounts).sumOf { it.extract.runs } in 2..3)
    }

    @Test fun lowConfidencePlanAsksForClarification() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val asked = h.ask(sales, q, h.perUnit(sales, hr), confidence = 0.3)
        assertTrue(asked.refusal.reasons.single().second.startsWith("did you mean") || asked.refusal.reasons.single().second.startsWith("not sure"))
        assertTrue(hr.got(MsgType.PLAN).isEmpty())
    }

    @Test fun invalidPlanIsRefusedWithTheCompilerErrors() = runTest {
        val h = mesh()
        val (hr, sales, _) = h.hrSalesAccounts()
        h.start()
        val bad = h.perUnit(sales, hr).let { it.copy(tasks = it.tasks.map { t -> if (t.id == "r") t.copy(inputs = listOf("a")) else t }) }
        val r = h.ask(sales, q, bad).refusal
        assertTrue(r.reasons.toString(), r.reasons.any { "RATIO" in it.second })
        assertTrue(hr.got(MsgType.PLAN).isEmpty())
    }
}
