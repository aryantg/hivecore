package com.hivecore.engine.device

import com.hivecore.core.LlmBackend
import com.hivecore.core.MemoryPrefs
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.fakes.FakeDeviceState
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.engine.placement.CooldownTable
import com.hivecore.engine.placement.ProfileFixtures
import com.hivecore.runtime.fakes.FakeCapabilityPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spec 4.6: the profile carries capabilities[], cooldowns[], p50 and the pool's big/warm state. */
class WorkerPolicyDecorationTest {
    private class Registry(val ids: List<String>) : ModelRegistry {
        override fun installed() = ids.map { ModelManifest(id = it, task = "t", runtime = "litert_qnn", files = listOf("x"), sha256 = "s", licence = "mit") }
        override fun manifest(id: String) = installed().firstOrNull { it.id == id }
        override fun dir(id: String) = File(id)
    }

    @Test fun profileIsDecoratedFromCatalogPoolCooldownsAndBench() = runTest {
        val now = { 9_000_000L + testScheduler.currentTime }
        val pool = FakeCapabilityPool(ProfileFixtures.catalog)
        pool.preload(ProfileFixtures.DET)
        val cooldowns = CooldownTable(now)
        val bench = mutableMapOf("yolox_w8a8" to 38.0)
        val policy = WorkerPolicyImpl("b2", FakeDeviceState(), MutableStateFlow(Presence(screenOn = true, batteryPct = 80)), FakeLlmEngine(LlmBackend.NPU),
            MemoryPrefs(), MutableStateFlow(0), backgroundScope, pool = pool, registry = Registry(listOf("yolox_w8a8", "qwen3vl", "whisper_tiny")),
            clock = now, catalog = { ProfileFixtures.catalog }, cooldowns = cooldowns, bench = { bench }, extraInstalled = { setOf("qwen3-4b-npu") })
        runCurrent()
        var p = policy.profile.value
        assertEquals("none", p.big)
        assertEquals(listOf("yolox_w8a8"), p.warm)
        assertEquals(listOf("asr/whisper_tiny/false", "detect/yolox_w8a8/true", "llm.json/qwen3-4b-npu/false", "llm.text/qwen3-4b-npu/false",
            "ocr/mlkit_text_latin/true", "vlm/qwen3vl/false"), p.capabilities.map { "${it.capability}/${it.modelId}/${it.warm}" })
        assertEquals(ProfileFixtures.SHA_DET, p.capabilities.first { it.capability == "detect" }.sha256)
        assertEquals(mapOf("yolox_w8a8" to 38.0), p.p50)
        assertTrue(p.cooldowns.isEmpty())

        pool.ensure(ProfileFixtures.VLM).getOrThrow()
        cooldowns.mark("b2", "gen.image")
        cooldowns.mark("a1", "vlm")                                              // another phone's: not ours to publish
        bench["qwen3vl"] = 1400.0
        policy.recompute()
        p = policy.profile.value
        assertEquals("vlm", p.big)
        assertEquals(listOf("qwen3vl", "yolox_w8a8"), p.warm)
        assertTrue(p.capabilities.first { it.capability == "vlm" }.warm)
        assertEquals(listOf("gen.image"), p.cooldowns.map { it.capability })
        assertEquals(now() + 600_000, p.cooldowns.single().untilTs)
        assertEquals(1400.0, p.p50["qwen3vl"]!!, 1e-9)
    }

    @Test fun withoutACatalogTheProfileStaysAsBefore() = runTest {
        val policy = WorkerPolicyImpl("n", FakeDeviceState(), MutableStateFlow(Presence()), FakeLlmEngine(LlmBackend.CPU), MemoryPrefs(), MutableStateFlow(0), backgroundScope)
        runCurrent()
        val p = policy.profile.value
        assertTrue(p.capabilities.isEmpty()); assertTrue(p.cooldowns.isEmpty()); assertTrue(p.p50.isEmpty())
        assertEquals("llm", p.big); assertNull(p.link)
    }
}
