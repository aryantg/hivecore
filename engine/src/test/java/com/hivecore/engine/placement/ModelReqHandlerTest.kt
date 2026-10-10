package com.hivecore.engine.placement

import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelFailBody
import com.hivecore.mesh.ModelReadyBody
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.MsgType
import com.hivecore.runtime.fakes.FakeCapabilityPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Node side of placement (spec 8.3) over the fake pool. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelReqHandlerTest {
    private val catalog = ProfileFixtures.catalog
    private fun req(cap: String, modelId: String, sha: String, deadline: Long = 20_000, commander: String = "a1") =
        ModelReqBody("plan-1", cap, modelId, sha, deadline, commander, 1_000L, "nonce-$cap")

    private fun ready(r: Pair<MsgType, String>): ModelReadyBody { assertEquals(r.second, MsgType.MODEL_READY, r.first); return MeshJson.decode(r.second) }
    private fun fail(r: Pair<MsgType, String>): ModelFailBody { assertEquals(r.second, MsgType.MODEL_FAIL, r.first); return MeshJson.decode(r.second) }

    @Test fun loadsAndRepliesReadyThenWarm() = runTest {
        val pool = FakeCapabilityPool(catalog)
        pool.loadMs["qwen3vl"] = 7_800
        val h = ModelReqHandler("b2", catalog, pool, backgroundScope)
        val first = ready(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM), "a1"))
        assertEquals(ModelReadyBody("plan-1", "b2", "qwen3vl", 7_800, false), first)
        val again = ready(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM), "a1"))
        assertTrue(again.warm); assertEquals(0L, again.loadMs)
        assertEquals(listOf("qwen3vl"), pool.loads)
        assertEquals("vlm", pool.big())
    }

    @Test fun qairtFailureAnswersReadyWithTheLlamaBuild() = runTest {
        val pool = FakeCapabilityPool(catalog)
        pool.fail["qwen3-4b-npu"] = "oom"
        val cooldowns = CooldownTable({ 0L })
        val h = ModelReqHandler("c3", catalog, pool, backgroundScope, cooldowns)
        val r = ready(h.process(req("llm.json", "qwen3-4b-npu", ProfileFixtures.SHA_LLM), "a1"))
        assertEquals("qwen2.5-coder-1.5b-instruct-q4_0", r.modelId); assertFalse(r.warm)
        assertTrue(pool.log.any { it.startsWith("fallback") })
        assertFalse(cooldowns.isDown("c3", "llm.json"))                      // the capability is served after all
    }

    @Test fun failuresCarryTheirClassAndStartTheOwnCooldown() = runTest {
        val pool = FakeCapabilityPool(catalog)
        pool.fail["qwen3vl"] = "thermal"
        val cooldowns = CooldownTable({ 5_000L })
        val h = ModelReqHandler("b2", catalog, pool, backgroundScope, cooldowns)
        val f = fail(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM), "a1"))
        assertEquals("thermal", f.errorClass); assertEquals("b2", f.node); assertEquals("plan-1", f.planId)
        assertTrue(cooldowns.isDown("b2", "vlm"))
        assertEquals(listOf("vlm"), cooldowns.of("b2").map { it.capability })
    }

    @Test fun refusalsAreUnsupportedOrModelLoadWithoutACooldown() = runTest {
        val pool = FakeCapabilityPool(catalog)
        val cooldowns = CooldownTable({ 0L })
        var accept = false
        val h = ModelReqHandler("b2", catalog, pool, backgroundScope, cooldowns, acceptWork = { accept })
        assertEquals("unsupported", fail(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM), "a1")).errorClass)   // worker mode off
        assertTrue(ready(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM, commander = "b2"), "b2")).modelId == "qwen3vl")   // own job
        accept = true
        assertEquals("unsupported", fail(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM, commander = "zz"), "a1")).errorClass)
        assertEquals("unsupported", fail(h.process(req("tts", "none", "unpinned"), "a1")).errorClass)
        val wrongSha = fail(h.process(req("detect", "yolox_w8a8", "f".repeat(64)), "a1"))
        assertEquals("model_load", wrongSha.errorClass)
        assertTrue(cooldowns.all().isEmpty())                                 // refusals never cool a capability down
        assertTrue(pool.loads.none { it == "yolox_w8a8" })
        ready(h.process(req("detect", "unknown-build", "unpinned"), "a1"))   // unknown build id: the preferred build is loaded
        assertEquals(listOf("qwen3vl", "yolox_w8a8"), pool.loads)
    }

    @Test fun aLoadPastTheDeadlineAnswersTimeout() = runTest {
        val pool = FakeCapabilityPool(catalog)
        pool.gate = { delay(30_000) }
        val cooldowns = CooldownTable({ 0L })
        val h = ModelReqHandler("b2", catalog, pool, backgroundScope, cooldowns)
        val f = fail(h.process(req("vlm", "qwen3vl", ProfileFixtures.SHA_VLM, deadline = 24_000), "a1"))
        assertEquals("timeout", f.errorClass)
        assertFalse(cooldowns.isDown("b2", "vlm"))
    }

    @Test fun handleRepliesThroughTheCallback() = runTest {
        val pool = FakeCapabilityPool(catalog)
        val h = ModelReqHandler("b2", catalog, pool, backgroundScope)
        val sent = ArrayList<Pair<MsgType, String>>()
        h.handle(req("detect", "yolox_w8a8", ProfileFixtures.SHA_DET), "a1") { t, b -> sent += t to b; Result.success(Unit) }
        runCurrent()
        advanceUntilIdle()
        assertEquals(sent.toString(), 1, sent.size)
        assertEquals(MsgType.MODEL_READY, sent.single().first)
        assertEquals("yolox_w8a8", MeshJson.decode<ModelReadyBody>(sent.single().second).modelId)
    }
}
