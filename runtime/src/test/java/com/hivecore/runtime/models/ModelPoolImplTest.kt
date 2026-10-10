package com.hivecore.runtime.models

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelPoolImplTest {

    private class Registry(val ms: List<ModelManifest>) : ModelRegistry {
        override fun installed() = ms
        override fun manifest(id: String) = ms.firstOrNull { it.id == id }
        override fun dir(id: String) = File("/models/$id")
    }

    private class FakeRuntime(override val id: String, val log: MutableList<String>, val failLoad: Boolean = false) : ModelRuntime {
        val open = mutableSetOf<String>()
        override fun supports(m: ModelManifest) = m.runtime == id
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> {
            log += "load ${m.id}"
            if (failLoad) return Result.failure(IllegalStateException("map error 8003"))
            open += m.id
            return Result.success(object : ModelSession {
                override val manifest = m
                override fun close() { log += "close ${m.id}"; open -= m.id }
            })
        }
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> =
            Result.success(mapOf("echo" to session.manifest.id))
    }

    /** FakeLlmEngine that logs loads (the pool's unload callback logs unloads). */
    private class LoggingLlm(val inner: FakeLlmEngine, val log: MutableList<String>) : LlmEngine by inner {
        override suspend fun load(backend: LlmBackend): Result<Unit> { log += "llm load $backend"; return inner.load(backend) }
        override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> =
            inner.generate(messages, params, onToken)
    }

    private fun small(id: String, mb: Int) = ModelManifest(id = id, task = "t", runtime = "litert_qnn", files = listOf("$id.tflite"),
        sha256 = "x", licence = "mit", sizeMb = mb)
    private val vlmManifest = ModelManifest(id = "qwen3vl", task = "vlm", runtime = GenieXVlmRuntime.ID, files = listOf("part1_of_4.bin"),
        sha256 = "y", licence = "apache-2.0", sizeMb = 3000)

    private val mb = ModelPoolImpl.MB

    @Test fun lruEvictionWithinBudget() = runTest {
        val log = mutableListOf<String>()
        val rt = FakeRuntime("litert_qnn", log)
        val pool = ModelPoolImpl(Registry(listOf(small("a", 40), small("b", 40), small("c", 40))), listOf(rt), budgetBytes = 100 * mb)
        assertEquals("a", pool.run("a", emptyMap()).getOrThrow()["echo"])
        pool.run("b", emptyMap()).getOrThrow()
        pool.run("a", emptyMap()).getOrThrow()          // touch a → b is now least recently used
        assertEquals(listOf("b", "a"), pool.warm.value)
        pool.run("c", emptyMap()).getOrThrow()          // 120 MB > 100 → evict b
        assertEquals(listOf("load a", "load b", "close b", "load c"), log)
        assertEquals(setOf("a", "c"), rt.open)
        assertEquals(listOf("a", "c"), pool.warm.value)
        assertEquals(80 * mb, pool.usedBytes())
    }

    @Test fun refusesModelLargerThanBudgetAndUnknownIds() = runTest {
        val pool = ModelPoolImpl(Registry(listOf(small("huge", 7000))), listOf(FakeRuntime("litert_qnn", mutableListOf())))
        assertTrue(pool.run("huge", emptyMap()).exceptionOrNull()!!.message!!.contains("budget"))
        assertTrue(pool.run("nope", emptyMap()).isFailure)
    }

    @Test fun modelInUseIsNeverEvicted() = runTest {
        val log = mutableListOf<String>()
        val rt = FakeRuntime("litert_qnn", log)
        val pool = ModelPoolImpl(Registry(listOf(small("a", 60), small("b", 60))), listOf(rt), budgetBytes = 100 * mb)
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = async { pool.with("a") { inside.complete(Unit); release.await() } }
        inside.await()
        pool.run("b", emptyMap()).getOrThrow()          // a is busy: over budget temporarily, a stays
        assertTrue("a" in rt.open && "b" in rt.open)
        release.complete(Unit); holder.await().getOrThrow()
        assertFalse(log.contains("close a"))
        assertTrue(pool.evict("a"))
        assertEquals(listOf("b"), pool.warm.value)
    }

    @Test fun claimingVlmUnloadsLlmFirstAndReleaseReloadsIt() = runTest {
        val log = mutableListOf<String>()
        val llm = LoggingLlm(FakeLlmEngine(LlmBackend.NPU), log)
        val vlmRt = FakeRuntime(GenieXVlmRuntime.ID, log)
        val pool = ModelPoolImpl(Registry(listOf(vlmManifest, small("a", 10))), listOf(vlmRt, FakeRuntime("litert_qnn", log)),
            llm = llm, unloadLlm = { log += "llm unload" })
        assertEquals("llm", pool.big.value)
        pool.claimBig("vlm").getOrThrow()
        assertEquals(listOf("llm unload", "load qwen3vl"), log)
        assertEquals("vlm", pool.big.value)
        pool.claimBig("vlm").getOrThrow()               // idempotent
        assertEquals(2, log.size)
        pool.run("a", emptyMap()).getOrThrow()          // small slots co-reside with the VLM
        pool.releaseBig().getOrThrow()
        assertEquals(listOf("llm unload", "load qwen3vl", "load a", "close qwen3vl", "llm load NPU"), log)
        assertEquals("llm", pool.big.value)
    }

    @Test fun withVlmClaimsTheSlotAndClaimLlmSwapsBack() = runTest {
        val log = mutableListOf<String>()
        val llm = LoggingLlm(FakeLlmEngine(LlmBackend.NPU), log)
        val pool = ModelPoolImpl(Registry(listOf(vlmManifest)), listOf(FakeRuntime(GenieXVlmRuntime.ID, log)),
            llm = llm, unloadLlm = { log += "llm unload" })
        assertEquals("qwen3vl", pool.run("qwen3vl", emptyMap()).getOrThrow()["echo"])
        assertEquals("vlm", pool.big.value)
        pool.claimBig("llm").getOrThrow()
        assertEquals(listOf("llm unload", "load qwen3vl", "close qwen3vl", "llm load NPU"), log)
        assertEquals("llm", pool.big.value)
    }

    @Test fun failedVlmLoadRestoresTheLlm() = runTest {
        val log = mutableListOf<String>()
        val llm = LoggingLlm(FakeLlmEngine(LlmBackend.NPU), log)
        val pool = ModelPoolImpl(Registry(listOf(vlmManifest)), listOf(FakeRuntime(GenieXVlmRuntime.ID, log, failLoad = true)),
            llm = llm, unloadLlm = { log += "llm unload" })
        val r = pool.claimBig("vlm")
        assertTrue(r.isFailure)
        assertEquals(listOf("llm unload", "load qwen3vl", "llm load NPU"), log)
        assertEquals("llm", pool.big.value)
    }

    @Test fun noLlmLoadedMeansNoReloadAndNoneUnloads() = runTest {
        val log = mutableListOf<String>()
        val pool = ModelPoolImpl(Registry(listOf(vlmManifest)), listOf(FakeRuntime(GenieXVlmRuntime.ID, log)), llm = null)
        assertEquals("none", pool.big.value)
        pool.claimBig("vlm").getOrThrow()
        pool.releaseBig().getOrThrow()
        assertEquals(listOf("load qwen3vl", "close qwen3vl"), log)
        assertEquals("none", pool.big.value)
        assertTrue(pool.claimBig("gpu").isFailure)
    }
}
