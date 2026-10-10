package com.hivecore.cloud

import com.hivecore.core.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ModelsConfigAndCapsTest {
    private val config = CloudTestSupport.config()

    @Test fun shippedModelsJsonHasEveryRoleWithPricesAndCaps() {
        assertEquals("2026-10-10", config.version)
        for (r in CloudRole.entries) {
            val role = config.role(r)
            assertTrue(role.models.isNotEmpty())
            assertTrue(role.maxTokens > 0); assertTrue(role.jobCapInr > 0); assertTrue(role.monthCapInr >= role.jobCapInr)
            for (slug in role.models) assertNotNull("price for $slug", config.price(slug))
        }
        assertEquals("openai/gpt-5.6-luna", config.primary(CloudRole.PLANNER))
        assertEquals("anthropic/claude-sonnet-5", config.primary(CloudRole.ESCALATE_TEXT))
        assertEquals("google/gemini-3.1-pro", config.primary(CloudRole.ESCALATE_MULTIMODAL))
        assertEquals(ModelsConfig.INR_PER_USD, config.inrPerUsd, 0.0)
    }

    @Test fun estimateUsesListPriceAndTheDocumentedRate() {
        // 1M input tokens at $2 + 1M output at $10 = $12 → ₹12 × rate
        assertEquals(12.0 * ModelsConfig.INR_PER_USD, config.estimateInr("anthropic/claude-sonnet-5", 1_000_000, 1_000_000), 1e-9)
        assertEquals(0.0, config.estimateInr("nobody/unknown", 1000, 1000), 0.0)
        val p = config.cloudPrices()
        assertEquals("anthropic/claude-sonnet-5", p.text.slug); assertEquals(2.0, p.text.usdInPerMillion, 0.0); assertEquals(10.0, p.text.usdOutPerMillion, 0.0)
        assertEquals("google/gemini-3.1-pro", p.multimodal.slug)
    }

    @Test fun parseRejectsAMissingRole() {
        val r = runCatching { ModelsConfig.parse("""{"version":"x","roles":{"PLANNER":{"models":["a"],"maxTokens":1,"jobCapInr":1,"monthCapInr":1}}}""") }
        assertTrue(r.exceptionOrNull()!!.message!!.contains("no role ESCALATE_TEXT"))
    }

    @Test fun capsStopSpendPerJobAndPerMonth() {
        val oct = LocalDate.of(2026, 10, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        var now = oct
        val caps = SpendCaps(MemorySpendStore(), { RoleCaps(jobCapInr = 10.0, monthCapInr = 25.0) }, { now })
        assertEquals("2026-10", caps.month())
        assertNull(caps.check(CloudRole.ESCALATE_TEXT, "j1", 9.0))
        caps.record("j1", 9.0)
        assertEquals(9.0, caps.jobSpent("j1"), 0.0); assertEquals(9.0, caps.monthSpent(), 0.0)
        assertTrue(caps.check(CloudRole.ESCALATE_TEXT, "j1", 2.0)!!.contains("job's cloud cap"))
        assertNull(caps.check(CloudRole.ESCALATE_TEXT, "j2", 9.0))
        caps.record("j2", 9.0)
        caps.record("j3", 6.0)
        assertTrue(caps.check(CloudRole.ESCALATE_TEXT, "j4", 2.0)!!.contains("month's cloud cap"))
        // a new month starts clean; the job scope does not
        now = LocalDate.of(2026, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2026-11", caps.month())
        assertEquals(0.0, caps.monthSpent(), 0.0)
        assertTrue(caps.check(CloudRole.ESCALATE_TEXT, "j1", 2.0)!!.contains("job's cloud cap"))
        assertNull(caps.check(CloudRole.ESCALATE_TEXT, "j4", 2.0))
    }

    @Test fun prefsStorePersistsThroughKeyValuePrefs() {
        val prefs = MemoryPrefs()
        val store = PrefsSpendStore(prefs)
        store.add("month:2026-10", 1.5); store.add("month:2026-10", 2.0)
        assertEquals(3.5, store.spent("month:2026-10"), 1e-9)
        assertEquals("3.5", prefs.getString(PrefsSpendStore.KEY + "month:2026-10", null))
        val caps = SpendCaps(store, config)
        assertEquals(config.role(CloudRole.PLANNER).jobCapInr, 5.0, 0.0)
        assertTrue(caps.check(CloudRole.PLANNER, "p", 6.0)!!.contains("₹5"))
    }
}
