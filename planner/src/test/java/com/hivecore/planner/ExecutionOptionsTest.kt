package com.hivecore.planner

import com.hivecore.core.CapabilityState
import com.hivecore.core.Dag
import com.hivecore.core.ModelEntry
import com.hivecore.core.Op
import com.hivecore.core.PlanCheck
import com.hivecore.core.Profile
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionOptionsTest {
    private val prices = CloudPrices(StepPrice("anthropic/claude-sonnet-5", 2.0, 10.0), StepPrice("google/gemini-3.1-pro", 2.0, 12.0), 88.0)
    private val catalog = listOf(
        ModelEntry("llm-3b", "llm.json", "qairt", "aa", 2_000_000_000, 4000, 4000, 8192, big = true),
        ModelEntry("ocr-small", "ocr", "litert", "bb", 20_000_000, 500, 200, 0, big = false),
    )
    private fun phone(id: String, eligible: Boolean = true, big: String = "none", thermal: Int = 0, battery: Int = 80, charging: Boolean = false, caps: List<String> = listOf("ocr", "llm.json")) =
        Profile(id, ts = 1, tokPerSec = 20.0, eligible = eligible, big = big, thermal = thermal, batteryPct = battery, charging = charging,
            capabilities = caps.map { CapabilityState(it, "$it-model", "h", warm = it == big || it == "ocr") })

    private fun compiled(vararg tasks: TaskSpec) = CompiledPlan(Dag("t2", "v", tasks.toList(), tasks.last().id), PlanCheck(true, emptyList(), "h", emptyMap()))

    private val schema = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("vendor") { put("type", "string") } } }

    /** OCR of 20 released photos, FIELDS per photo, RENDER. */
    private fun invoicePlan(privacy: String) = compiled(
        TaskSpec("ocr", Op.OCR, buildJsonObject { put("cap", "ocr"); put("input", "items"); put("axis", "shard"); put("privacy", privacy); put("items", 20) }),
        TaskSpec("fields", Op.FIELDS, buildJsonObject { put("cap", "llm.json"); put("axis", "shard"); put("schema", schema); put("items", 20); put("maxTokens", 64) }, listOf("ocr")),
        TaskSpec("out", Op.RENDER, inputs = listOf("fields")),
    )

    @Test fun releasedItemsOnThreePhonesDefaultToLocalAndEstimateBothOptions() {
        val profiles = listOf(phone("self", big = "llm"), phone("b", big = "llm"), phone("c"))
        val r = ExecutionOptions.compute(invoicePlan("released"), profiles, catalog, prices, CloudPolicy.ASK_EVERY_TIME, online = true, selfId = "self")
        assertTrue(r.local.enabled); assertTrue(r.cloud.enabled)
        val local = r.local.local!!
        assertTrue("N phones faster than one: ${local.ms} vs ${local.singlePhoneMs}", local.ms < local.singlePhoneMs)
        assertTrue(local.phones.size >= 2)
        assertTrue(local.loads.any { it.capability == "llm.json" && it.modelId == "llm-3b" })
        val cloud = r.cloud.cloud!!
        assertEquals(listOf("fields"), cloud.steps)
        assertTrue(cloud.costInr > 0.0)
        assertEquals(20L * 4000, cloud.bytesOut)
        assertEquals(ExecutionMode.LOCAL, r.default)
        assertTrue(r.askUser)
        assertTrue(r.signals.toString(), r.signals.any { it.contains("eligible phones → LOCAL") })
        val fields = r.steps.first { it.id == "fields" }
        assertTrue(fields.cloudEligible); assertEquals("released", fields.lineage)
        assertFalse(r.steps.first { it.id == "out" }.cloudEligible)
    }

    @Test fun privateLineageDisablesCloudWithTheReason() {
        val r = ExecutionOptions.compute(invoicePlan("private"), listOf(phone("self", big = "llm"), phone("b")), catalog, prices, CloudPolicy.FASTEST, true, "self")
        assertFalse(r.cloud.enabled)
        assertTrue(r.cloud.reason!!, r.cloud.reason!!.contains("private"))
        assertEquals(ExecutionMode.LOCAL, r.default)
        assertFalse(r.askUser)
        assertFalse(r.steps.first { it.id == "fields" }.cloudEligible)
        assertEquals("private", r.steps.first { it.id == "fields" }.lineage)
        assertTrue(r.steps.first { it.id == "fields" }.reason!!.contains("private lineage"))
    }

    @Test fun ownerStepsAreNeverCloudEligible() {
        val plan = compiled(
            TaskSpec("a", Op.AGG, buildJsonObject { put("q", "revenue by region") }, owner = "sales"),
            TaskSpec("r", Op.RANK, buildJsonObject { put("measure", "sum_revenue") }, listOf("a")),
        )
        val r = ExecutionOptions.compute(plan, listOf(phone("self")), catalog, prices, CloudPolicy.ASK_EVERY_TIME, true, "self")
        assertFalse(r.cloud.enabled)
        assertTrue(r.cloud.reason!!.contains("no step of this plan uses a model"))
        assertTrue(r.steps.none { it.cloudEligible })
        assertEquals("private", r.steps.first { it.id == "a" }.lineage)
    }

    @Test fun offlineForcesLocal() {
        val r = ExecutionOptions.compute(invoicePlan("public"), listOf(phone("self", big = "llm")), catalog, prices, CloudPolicy.FASTEST, online = false, selfId = "self")
        assertFalse(r.cloud.enabled)
        assertEquals("no internet on this phone", r.cloud.reason)
        assertNotNull(r.cloud.cloud)   // the estimate is still shown, greyed out
        assertEquals(ExecutionMode.LOCAL, r.default)
    }

    @Test fun capabilityGapPushesCloudForThatStep() {
        val plan = compiled(
            TaskSpec("s", Op.SUMMARIZE, buildJsonObject { put("cap", "llm.text"); put("privacy", "public"); put("maxTokens", 400) }),
            TaskSpec("out", Op.RENDER, inputs = listOf("s")),
        )
        val noText = listOf(ModelEntry("llm-3b", "llm.json", "qairt", "aa", 1, 1, 1, 1, true))
        val r = ExecutionOptions.compute(plan, listOf(phone("self", caps = listOf("llm.json"))), noText, prices, CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE, true, "self")
        assertEquals(setOf("llm.text"), r.capabilityGaps)
        assertEquals(ExecutionMode.CLOUD, r.default)
        assertTrue(r.signals.any { it.contains("no local model for s → CLOUD") })
        // the same plan with the model present: local unless impossible stays local
        val ok = ExecutionOptions.compute(plan, listOf(phone("self", caps = listOf("llm.text"))), catalog + ModelEntry("t", "llm.text", "qairt", "c", 1, 1, 1, 1, true),
            prices, CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE, true, "self")
        assertTrue(ok.capabilityGaps.isEmpty())
        assertEquals(ExecutionMode.LOCAL, ok.default)
        assertTrue(ok.signals.any { it.contains("single-shot") })
    }

    @Test fun fastestPicksTheQuickerOptionAndHotPhonesPushCloud() {
        val plan = compiled(
            TaskSpec("s", Op.SUMMARIZE, buildJsonObject { put("cap", "llm.text"); put("privacy", "public"); put("maxTokens", 512) }),
            TaskSpec("out", Op.RENDER, inputs = listOf("s")),
        )
        val slowSelf = Profile("self", ts = 1, tokPerSec = 2.0, eligible = true, big = "llm", thermal = 2, batteryPct = 20,
            capabilities = listOf(CapabilityState("llm.text", "t", "h", true)))
        val r = ExecutionOptions.compute(plan, listOf(slowSelf), catalog, prices, CloudPolicy.FASTEST, true, "self")
        assertEquals(ExecutionMode.CLOUD, r.default)
        assertTrue(r.cloud.cloud!!.ms < r.local.local!!.ms)
        assertTrue(r.signals.any { it.contains("hot or low on battery") })
        // ask-every-time with the same signals also lands on CLOUD but asks
        val ask = ExecutionOptions.compute(plan, listOf(slowSelf), catalog, prices, CloudPolicy.ASK_EVERY_TIME, true, "self")
        assertEquals(ExecutionMode.CLOUD, ask.default)
        assertTrue(ask.askUser)
    }

    @Test fun computeIsPure() {
        val profiles = listOf(phone("self", big = "llm"), phone("b"))
        val a = ExecutionOptions.compute(invoicePlan("released"), profiles, catalog, prices, CloudPolicy.ASK_EVERY_TIME, true, "self")
        val b = ExecutionOptions.compute(invoicePlan("released"), profiles.reversed(), catalog, prices, CloudPolicy.ASK_EVERY_TIME, true, "self")
        assertEquals(a, b)
    }
}
