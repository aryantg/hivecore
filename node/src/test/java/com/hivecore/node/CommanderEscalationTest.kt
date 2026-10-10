package com.hivecore.node

import com.hivecore.core.Canon
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.mesh.MeshJson
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 15 on the graph engine over the 3-phone fake swarm: a public step whose replicas disagree three ways goes to
 * the gate and the cloud answer is badged; a private step is refused without asking; a refusal ends the plan with
 * both reasons; schema failures across two phones, a low vote and a low judge score escalate too.
 */
class CommanderEscalationTest {
    private var harness: SwarmHarness? = null
    private fun TestScope.mesh() = SwarmHarness(this).also { harness = it }
    @After fun tearDown() { harness?.close() }

    private class FakeGate(val outcome: (EscalationRequest) -> GateOutcome) : Escalator {
        override suspend fun tryEscalate(req: EscalationRequest): GateOutcome = outcome(req)
    }

    private val cloud: JsonElement = JsonArray(listOf(JsonPrimitive("c1"), JsonPrimitive("c2")))
    private fun ran() = FakeGate { GateOutcome.Ran(cloud, Canon.hash(cloud), "fake/model") }
    private val q = "Key points from the onboarding checklist"

    private fun disagree(hr: SwarmHarness.Node, sales: SwarmHarness.Node, accounts: SwarmHarness.Node) {
        hr.extract.output = JsonArray(listOf(JsonPrimitive("s1")))
        sales.extract.output = JsonArray(listOf(JsonPrimitive("s2")))
        accounts.extract.output = JsonArray(listOf(JsonPrimitive("s3")))
    }

    private fun extractDag(params: Map<String, JsonElement> = emptyMap()) = Dag("multi_doc_extract", "p1", listOf(
        TaskSpec("x", Op.EXTRACT, buildJsonObject { put("q", "key points"); put("sentences", JsonArray((1..5).map { JsonPrimitive("s$it") })); params.forEach { (k, v) -> put(k, v) } }),
    ), output = "x")

    @Test fun threeWayDisagreementOnAPublicStepIsAnsweredByTheCloudAndBadged() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        disagree(hr, sales, accounts)
        sales.escalator.delegate = ran()
        h.start()
        val a = h.ask(sales, q, extractDag()).answer
        assertEquals(3, listOf(hr, sales, accounts).sumOf { it.extract.runs })
        assertTrue(a.text, a.text.contains("c1") && !a.text.contains("s2"))
        val req = sales.escalator.requests.single()
        assertTrue(req.trigger.toString(), req.trigger is EscalationTrigger.VerificationFailed)
        assertEquals("public", req.privacy)
        assertEquals(sales.nodeId, req.commander)
        assertEquals(Op.EXTRACT, req.node.op)
        assertEquals(3, req.localAttempts)
        // badged: the plan line and the provenance carry the slug, the step counts as verified, the phones count excludes the cloud
        assertTrue(a.plan!!.lines.toString(), a.plan!!.lines.single().contains("cloud:fake/model"))
        assertEquals(1, a.plan!!.verified)
        assertTrue(a.provenance.toString(), a.provenance.any { it.node == "cloud:fake/model" && it.line.contains("EXTRACT") })
        assertEquals(1, a.phones)
        assertTrue(a.notes.toString(), a.notes.any { it.contains("fake/model answered") })
        val task = sales.ledger.entries().first { it.kind == "task" }
        assertEquals("cloud:fake/model", MeshJson.json.parseToJsonElement(task.entry).jsonObject["source"]!!.jsonPrimitive.content)
    }

    @Test fun privateStepIsRefusedWithoutAskingTheGate() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val verdict = buildJsonObject { put("score", 0.2) }
        listOf(hr, sales, accounts).forEach { it.runner.register(ConstExecutor(verdict, setOf(Op.EVAL))) }
        sales.escalator.delegate = ran()
        h.start()
        // a private extract (spec 7.4: on its owner, one replica) judged low: no cloud re-run may be attempted
        val dag = Dag("judged", "p1", listOf(
            TaskSpec("x", Op.EXTRACT, buildJsonObject { put("q", "key points"); put("privacy", "private"); put("replicas", 1) }, owner = sales.nodeId),
            TaskSpec("j", Op.EVAL, buildJsonObject { put("replicas", 1) }, inputs = listOf("x"), owner = sales.nodeId),
            TaskSpec("r", Op.RENDER, inputs = listOf("x")),
        ), output = "r")
        val r = h.ask(sales, q, dag).refusal
        assertTrue(sales.escalator.requests.toString(), sales.escalator.requests.isEmpty())
        val why = r.reasons.single().second
        assertTrue(why, why.contains("judge") && why.contains("private"))
        assertEquals(1, sales.extract.runs + hr.extract.runs + accounts.extract.runs)         // ran on its owner only
    }

    @Test fun gateRefusalRefusesThePlanWithBothReasons() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        disagree(hr, sales, accounts)
        h.start()                                                                           // the delegate stays Escalator.Off
        val r = h.ask(sales, q, extractDag()).refusal
        assertEquals(1, sales.escalator.requests.size)
        val why = r.reasons.single().second
        assertTrue(why, why.contains("disagree") && why.contains("cloud escalation is off"))
        assertEquals(3, listOf(hr, sales, accounts).sumOf { it.extract.runs })             // nothing was re-dispatched
    }

    @Test fun schemaFailuresAcrossTwoPhonesEscalateTheStep() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        sales.escalator.delegate = ran()
        h.start()
        // every phone answers an array; the step's schema wants an object
        val schema = buildJsonObject { put("type", "object") }
        val a = h.ask(sales, q, extractDag(mapOf("schema" to schema))).answer
        val req = sales.escalator.requests.single()
        val t = req.trigger as EscalationTrigger.VerificationFailed
        assertTrue(t.reason, t.reason.contains("schema") && t.attempts >= 3)
        assertTrue(listOf(hr, sales, accounts).sumOf { it.extract.runs } >= 3)
        assertTrue(a.plan!!.lines.single().contains("cloud:fake/model"))
    }

    @Test fun lowVoteConfidenceEscalatesTheStep() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val vote = buildJsonObject { put("value", "a"); put("agreeing", 1); put("confidence", 0.5); putJsonArray("votes") { add(buildJsonObject { put("value", "a"); put("n", 1) }) } }
        listOf(hr, sales, accounts).forEach { it.runner.register(ConstExecutor(vote, setOf(Op.TEXT_CLASSIFY))) }
        sales.escalator.delegate = ran()
        h.start()
        val dag = Dag("vote", "p1", listOf(TaskSpec("v", Op.TEXT_CLASSIFY, buildJsonObject { put("replicate", "none") })), output = "v")
        val a = h.ask(sales, "classify", dag).answer
        val req = sales.escalator.requests.single()
        assertEquals(EscalationTrigger.LowConfidence(0.5), req.trigger)
        assertEquals("v", req.node.id)
        assertTrue(a.plan!!.lines.single().contains("cloud:fake/model"))
    }

    @Test fun lowJudgeScoreEscalatesTheJudgedStepBeforeTheRender() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val verdict = buildJsonObject { put("score", 0.2); put("reason", "misses the second point") }
        listOf(hr, sales, accounts).forEach { it.runner.register(ConstExecutor(verdict, setOf(Op.EVAL))) }
        sales.escalator.delegate = ran()
        h.start()
        val dag = Dag("judged", "p1", listOf(
            TaskSpec("x", Op.EXTRACT, buildJsonObject { put("q", "key points"); put("replicate", "none") }),
            TaskSpec("j", Op.EVAL, buildJsonObject { put("replicate", "none") }, inputs = listOf("x")),
            TaskSpec("r", Op.RENDER, inputs = listOf("x")),
        ), output = "r")
        val a = h.ask(sales, "key points, judged", dag).answer
        val req = sales.escalator.requests.single()
        assertEquals("x", req.node.id)
        assertTrue(req.trigger.toString(), (req.trigger as EscalationTrigger.VerificationFailed).reason.contains("judge"))
        assertTrue(a.text, a.text.contains("c1"))                                           // the render saw the cloud output
        assertTrue(a.plan!!.lines.toString(), a.plan!!.lines[0].contains("cloud:fake/model"))
        assertFalse(a.plan!!.lines[1].contains("cloud"))                                     // the judge itself ran locally
    }

    // ---- spec 9.1: the CLOUD option sends the chosen steps to the cloud first, from the orchestrator only

    @Test fun cloudOptionRunsTheChosenStepInTheCloudBeforeAnyPhoneIsAsked() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        sales.escalator.delegate = ran()
        h.start()
        val a = h.ask(sales, q, extractDag(), cloudSteps = setOf("x")).answer
        assertEquals(0, listOf(hr, sales, accounts).sumOf { it.extract.runs })             // no phone ran the step
        val req = sales.escalator.requests.single()
        assertEquals(EscalationTrigger.UserChoice, req.trigger)
        assertEquals(0, req.localAttempts)
        assertEquals("public", req.privacy)
        assertTrue(a.text, a.text.contains("c1"))
        assertTrue(a.plan!!.lines.single().contains("cloud:fake/model"))
        assertTrue(a.notes.toString(), a.notes.any { it.contains("CLOUD option") })
        val task = sales.ledger.entries().first { it.kind == "task" }
        assertEquals("cloud:fake/model", MeshJson.json.parseToJsonElement(task.entry).jsonObject["source"]!!.jsonPrimitive.content)
    }

    @Test fun cloudOptionFallsBackToThePhonesWhenTheGateRefuses() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val same = JsonArray(listOf(JsonPrimitive("s1")))
        listOf(hr, sales, accounts).forEach { it.extract.output = same }
        h.start()                                                                           // the delegate stays Escalator.Off
        val a = h.ask(sales, q, extractDag(), cloudSteps = setOf("x")).answer
        assertEquals(EscalationTrigger.UserChoice, sales.escalator.requests.single().trigger)
        assertTrue(listOf(hr, sales, accounts).sumOf { it.extract.runs } >= 1)             // ran on the phones instead
        assertTrue(a.text, a.text.contains("s1"))
        assertFalse(a.plan!!.lines.single().contains("cloud"))
        assertTrue(a.notes.toString(), a.notes.any { it.contains("instead of the cloud") && it.contains("cloud escalation is off") })
    }

    @Test fun cloudOptionNeverSendsAPrivateStep() = runTest {
        val h = mesh()
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val same = JsonArray(listOf(JsonPrimitive("s1")))
        listOf(hr, sales, accounts).forEach { it.extract.output = same }
        sales.escalator.delegate = ran()
        h.start()
        val dag = Dag("private", "p1", listOf(
            TaskSpec("x", Op.EXTRACT, buildJsonObject { put("q", "key points"); put("privacy", "private"); put("replicas", 1) }, owner = sales.nodeId),
        ), output = "x")
        val a = h.ask(sales, q, dag, cloudSteps = setOf("x")).answer
        assertTrue(sales.escalator.requests.toString(), sales.escalator.requests.isEmpty())
        assertTrue(a.text, a.text.contains("s1"))
    }
}
