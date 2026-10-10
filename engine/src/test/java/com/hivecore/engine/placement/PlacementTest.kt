package com.hivecore.engine.placement

import com.hivecore.core.CapabilityState
import com.hivecore.core.Canon
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.Profile
import com.hivecore.engine.placement.ProfileFixtures.T0
import com.hivecore.engine.placement.ProfileFixtures.a1
import com.hivecore.engine.placement.ProfileFixtures.all
import com.hivecore.engine.placement.ProfileFixtures.b2
import com.hivecore.engine.placement.ProfileFixtures.c3
import com.hivecore.engine.placement.ProfileFixtures.d4
import com.hivecore.engine.placement.ProfileFixtures.e5
import com.hivecore.engine.placement.ProfileFixtures.entries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The placement solver (spec 8.2) over the recorded profiles of [ProfileFixtures]. */
class PlacementTest {
    private fun solve(demand: Map<String, Int>, profiles: List<Profile> = all, self: String = "a1", cooldowns: CooldownTable? = null, now: Long = T0) =
        Placement.plan(demand, profiles, entries, self, cooldowns = cooldowns, nowMs = now)

    private fun nodes(o: Placement.Outcome, cap: String) = o.assignments.filter { it.capability == cap }.map { it.nodeId }

    @Test fun deterministicWhateverTheProfileOrder() {
        val demand = mapOf("llm.json" to 30, "vlm" to 24, "detect" to 120, "ocr" to 40)
        val a = solve(demand)
        val b = solve(demand, all.reversed())
        val c = solve(demand, all.shuffled(java.util.Random(7)) + listOf(b2.copy(ts = T0 - 60_000)))   // a stale duplicate loses
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, solve(demand))
        assertTrue(a.assignments.isNotEmpty())
    }

    @Test fun warmPhonesAreChosenAndNoSwapIsPaid() {
        val o = solve(mapOf("llm.json" to 4, "vlm" to 4))
        assertEquals(listOf("a1", "c3"), nodes(o, "llm.json").sorted())           // both warm; a 6000 ms swap on b2 or d4 would not finish a unit sooner
        assertEquals(listOf("b2"), nodes(o, "vlm"))                              // warm on b2 only; a1 is busy with the LLM
        assertTrue(o.assignments.none { it.swap })
        assertEquals(0L, o.swapCostMs)
        assertTrue(o.gaps.isEmpty())
        assertEquals(4, o.assignments.filter { it.capability == "llm.json" }.sumOf { it.units })
        // a big demand makes the swap worth it: b2 and d4 join llm.json after a 6000 ms load
        val big = solve(mapOf("llm.json" to 40))
        assertEquals(listOf("a1", "b2", "c3", "d4"), nodes(big, "llm.json").sorted())
        assertEquals(12_000L, big.swapCostMs)
        assertTrue(big.makespanMs < solve(mapOf("llm.json" to 40), listOf(a1, c3)).makespanMs)
    }

    @Test fun oneBigModelPerPhonePerJob() {
        val lone = listOf(a1, e5)                                                 // only the orchestrator can work
        val o = solve(mapOf("llm.json" to 50, "vlm" to 50), lone)
        assertEquals(listOf("a1"), nodes(o, "llm.json"))                          // the larger demand (50 × 4000) is placed first
        assertTrue(nodes(o, "vlm").isEmpty())
        assertEquals(listOf("vlm"), o.gaps)
        // llm.text and llm.json share one build, so one phone may serve both
        val both = solve(mapOf("llm.json" to 5, "llm.text" to 5), lone)
        assertEquals(listOf("a1"), nodes(both, "llm.json")); assertEquals(listOf("a1"), nodes(both, "llm.text"))
    }

    @Test fun swapIsReportedWithItsCost() {
        val o = solve(mapOf("vlm" to 10), listOf(a1, d4))
        val a = o.assignments.single()
        assertEquals("a1", a.nodeId); assertTrue(a.swap); assertEquals(ProfileFixtures.VLM.sha256, a.sha256)
        assertEquals(8000L, o.swapCostMs)
        assertEquals(8000L + 10 * 1500L, a.expectedMs)
    }

    @Test fun heavyOnlyWhereHeavyOkAndNeverOverBluetoothOrRelay() {
        val o = solve(mapOf("gen.image" to 4))
        assertEquals(listOf("b2"), nodes(o, "gen.image"))                         // c3 has sd15 but is not heavyOk
        val cold = b2.copy(heavyOk = false)
        assertEquals(listOf("gen.image"), solve(mapOf("gen.image" to 4), listOf(a1, cold, c3, d4)).gaps)
        val relayB2 = b2.copy(link = Link(LinkKind.WAN_RELAY, 500, 300))
        assertEquals(listOf("gen.image"), solve(mapOf("gen.image" to 4), listOf(a1, relayB2)).gaps)
        assertFalse("d4" in nodes(solve(mapOf("detect" to 200)), "detect"))      // blobs never over Bluetooth
        assertFalse("d4" in nodes(solve(mapOf("ocr" to 200)), "ocr"))            // OCR reads image blobs too
        assertTrue("d4" in nodes(solve(mapOf("llm.json" to 60)), "llm.json"))     // prompt-in/JSON-out may go over Bluetooth
    }

    @Test fun cooldownsSkipAPhoneFromTheTableOrFromItsProfile() {
        val table = CooldownTable({ T0 })
        table.mark("b2", "vlm")
        val o = solve(mapOf("vlm" to 1), cooldowns = table)
        assertEquals(listOf("a1"), nodes(o, "vlm")); assertTrue(o.assignments.single().swap)
        val published = all.map { if (it.nodeId == "b2") ProfileFixtures.cooled(it, "vlm") else it }
        assertEquals(listOf("a1"), nodes(solve(mapOf("vlm" to 1), published), "vlm"))
        val expired = all.map { if (it.nodeId == "b2") ProfileFixtures.cooled(it, "vlm", untilTs = T0 - 1) else it }
        assertEquals(listOf("b2"), nodes(solve(mapOf("vlm" to 1), expired), "vlm"))
        table.clear("b2", "vlm")
        assertEquals(listOf("b2"), nodes(solve(mapOf("vlm" to 1), cooldowns = table), "vlm"))
    }

    @Test fun queueDepthCostsThreeSecondsPerTask() {
        val busy = all.map { if (it.nodeId == "a1") it.copy(queueDepth = 4) else it }
        val o = solve(mapOf("llm.json" to 2), busy)
        val a1Share = o.assignments.firstOrNull { it.nodeId == "a1" && it.capability == "llm.json" }
        assertEquals(listOf("c3"), nodes(o, "llm.json"))                         // 2 × 4200 < 12 000 + 2 × 3900
        assertEquals(null, a1Share)
    }

    @Test fun lanFirstForBlobsWanWinsTiesForText() {
        fun phone(id: String, kind: LinkKind) = Profile(id, T0, eligible = true, big = "llm", warm = listOf("qwen3-4b-npu"),
            link = Link.defaultFor(kind), p50 = mapOf("qwen3-4b-npu" to 4000.0, "yolox_w8a8" to 40.0),
            capabilities = listOf(CapabilityState("llm.json", "qwen3-4b-npu", ProfileFixtures.SHA_LLM, true),
                CapabilityState("detect", "yolox_w8a8", ProfileFixtures.SHA_DET, true)))
        val self = Profile("self", T0, eligible = false, capabilities = emptyList())
        val lan = phone("lan", LinkKind.LAN); val wan = phone("wan", LinkKind.WAN_DIRECT)
        val blobs = solve(mapOf("detect" to 1), listOf(self, wan, lan), self = "self")
        assertEquals("lan", blobs.assignments.single().nodeId)
        val text = solve(mapOf("llm.json" to 1), listOf(self, lan, wan), self = "self")
        assertEquals("wan", text.assignments.single().nodeId)
    }

    @Test fun tiesBreakOnSha256OfNodeIdAndCapability() {
        fun phone(id: String) = Profile(id, T0, eligible = true, link = Link.defaultFor(LinkKind.LAN),
            capabilities = listOf(CapabilityState("detect", "yolox_w8a8", ProfileFixtures.SHA_DET, true)))
        val ids = listOf("p-one", "p-two", "p-three", "p-four")
        val self = Profile("self", T0, eligible = false)
        val o = solve(mapOf("detect" to 1), ids.map(::phone) + self, self = "self")
        val expected = ids.minByOrNull { Canon.sha256Hex(it + "detect") }!!
        assertEquals(expected, o.assignments.single().nodeId)
        assertEquals(o, solve(mapOf("detect" to 1), (ids.map(::phone) + self).reversed(), self = "self"))
    }

    @Test fun unitsAreSharedByMeasuredSpeedAndSumToTheDemand() {
        val o = solve(mapOf("detect" to 100), listOf(a1, b2, e5))
        val shares = o.assignments.filter { it.capability == "detect" }.associate { it.nodeId to it.units }
        assertEquals(100, shares.values.sum())
        assertTrue(shares.getValue("a1") >= shares.getValue("b2"))                // a1 is slightly faster (38 vs 41 ms)
        assertTrue(o.makespanMs in 1_900L..2_200L)
        val self = solve(mapOf("llm.json" to 1), listOf(a1.copy(eligible = false, workerOptIn = false)))
        assertEquals(listOf("a1"), nodes(self, "llm.json"))                      // the orchestrator is always a candidate
    }

    @Test fun defaultsComeFromTheOpRegistry() {
        assertEquals(setOf("asr", "gen.image", "superres", "upscale"), Placement.DEFAULT_HEAVY)
        assertTrue(Placement.DEFAULT_BLOB.containsAll(setOf("ocr", "detect", "segment", "classify", "iqa", "vlm", "asr", "superres", "upscale")))
        assertFalse("llm.json" in Placement.DEFAULT_BLOB); assertFalse("llm.text" in Placement.DEFAULT_BLOB)
        assertFalse("gen.image" in Placement.DEFAULT_BLOB)
    }
}
