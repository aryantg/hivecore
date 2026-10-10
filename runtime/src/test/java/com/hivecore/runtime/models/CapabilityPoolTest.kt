package com.hivecore.runtime.models

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.catalog.ModelLoadException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [ModelPoolImpl.ensure] (spec 8.3): qairt first, llama_cpp once, classed failures; one big slot; warm after. */
class CapabilityPoolTest {
    private class Registry(val ms: List<ModelManifest>) : ModelRegistry {
        override fun installed() = ms
        override fun manifest(id: String) = ms.firstOrNull { it.id == id }
        override fun dir(id: String) = File("/models/$id")
    }

    private class FakeRuntime(override val id: String, val log: MutableList<String>, val failWith: String? = null) : ModelRuntime {
        override fun supports(m: ModelManifest) = m.runtime == id
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> {
            log += "load ${m.id}"
            if (failWith != null) return Result.failure(IllegalStateException(failWith))
            return Result.success(object : ModelSession {
                override val manifest = m
                override fun close() { log += "close ${m.id}" }
            })
        }
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = Result.success(emptyMap())
    }

    /** Logs loads; [failBackends] makes those backends fail (a qairt bundle that will not map). */
    private class Llm(val log: MutableList<String>, val failBackends: Set<LlmBackend> = emptySet()) : LlmEngine {
        val inner = FakeLlmEngine(null)
        override val backend get() = inner.backend
        override val lastStats get() = inner.lastStats
        override val status get() = inner.status
        override fun available() = inner.available()
        override suspend fun load(backend: LlmBackend): Result<Unit> {
            log += "llm load $backend"
            if (backend in failBackends) return Result.failure(IllegalStateException("qairt could not load the bundle"))
            return inner.load(backend)
        }
        override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> = inner.generate(messages, params, onToken)
        override suspend fun cancel() = inner.cancel()
    }

    private val sha = "a".repeat(64)
    private fun entry(id: String, cap: String, runtime: String, big: Boolean, sha256: String = "unpinned") =
        ModelEntry(id, cap, runtime, sha256, 10, 300, 40, 0, big)
    private val llmQ = entry(ModelPoolImpl.LLM_QAIRT_ID, "llm.json", "qairt", true)
    private val llmG = entry(ModelPoolImpl.LLM_GGUF_ID, "llm.json", "llama_cpp", true)
    private val vlmE = entry("qwen3vl", "vlm", "qairt", true)
    private val detE = entry("yolo", "detect", "litert_qnn", false, sha)
    private val sdE = entry("sd15", "gen.image", "ort_qnn", true)
    private val ocrE = entry("mlkit", "ocr", "mlkit", false)
    private val catalog = ModelCatalog(listOf(llmQ, llmG, vlmE, detE, sdE, ocrE))

    private fun small(id: String, runtime: String = "litert_qnn", sha256: String = sha) =
        ModelManifest(id = id, task = "t", runtime = runtime, files = listOf("$id.bin"), sha256 = sha256, licence = "mit", sizeMb = 10)
    private val vlmManifest = small("qwen3vl", GenieXVlmRuntime.ID, "unpinned")

    private fun pool(log: MutableList<String>, llm: LlmEngine?, vararg runtimes: ModelRuntime, manifests: List<ModelManifest> = listOf(small("yolo"), vlmManifest, small("sd15", "ort_qnn"))) =
        ModelPoolImpl(Registry(manifests), runtimes.toList(), llm = llm, unloadLlm = { log += "llm unload" }, catalog = { catalog })

    @Test fun smallModelsLoadOnceAndStayWarm() = runTest {
        val log = mutableListOf<String>()
        val p = pool(log, null, FakeRuntime("litert_qnn", log))
        val first = p.ensure(detE).getOrThrow()
        assertFalse(first.warm); assertEquals("yolo", first.modelId); assertEquals("litert_qnn", first.runtime)
        val again = p.ensure(detE).getOrThrow()
        assertTrue(again.warm); assertEquals(0L, again.loadMs)
        assertEquals(listOf("load yolo"), log)
        assertEquals(setOf("yolo"), p.warm()); assertNull(p.big()); assertNull(p.bigModel)
        assertTrue(p.ensure(ocrE).getOrThrow().warm)                 // built-in runtime: nothing to load
    }

    @Test fun llmThenVlmThenLlmSwapTheOneBigSlot() = runTest {
        val log = mutableListOf<String>()
        val llm = Llm(log)
        val p = pool(log, llm, FakeRuntime(GenieXVlmRuntime.ID, log), FakeRuntime("litert_qnn", log))
        assertNull(p.big())
        val a = p.ensure(llmQ).getOrThrow()
        assertFalse(a.warm); assertEquals("llm", p.big()); assertEquals(ModelPoolImpl.LLM_QAIRT_ID, p.bigModel)
        assertTrue(p.ensure(llmQ).getOrThrow().warm)
        assertTrue(p.ensure(entry(ModelPoolImpl.LLM_QAIRT_ID, "llm.text", "qairt", true)).getOrThrow().warm)   // same build, other capability
        p.ensure(detE).getOrThrow()                                   // a small model beside the big one
        val v = p.ensure(vlmE).getOrThrow()
        assertFalse(v.warm); assertEquals("vlm", p.big()); assertEquals("qwen3vl", p.bigModel)
        assertEquals(setOf("yolo", "qwen3vl"), p.warm())
        assertTrue(p.ensure(vlmE).getOrThrow().warm)
        assertFalse(p.ensure(llmQ).getOrThrow().warm)
        assertEquals("llm", p.big())
        assertEquals(listOf("llm load NPU", "load yolo", "llm unload", "load qwen3vl", "close qwen3vl", "llm load NPU"), log)
    }

    @Test fun qairtFailureFallsBackToTheLlamaBuildOnce() = runTest {
        val log = mutableListOf<String>()
        val p = pool(log, Llm(log, failBackends = setOf(LlmBackend.NPU)))
        val li = p.ensure(llmQ).getOrThrow()
        assertEquals(ModelPoolImpl.LLM_GGUF_ID, li.modelId); assertEquals("llama_cpp", li.runtime); assertFalse(li.warm)
        assertEquals("llm", p.big()); assertEquals(ModelPoolImpl.LLM_GGUF_ID, p.bigModel)
        assertEquals(listOf("llm load NPU", "llm load GPU"), log)
        assertTrue(p.ensure(llmG).getOrThrow().warm)
    }

    @Test fun failuresCarryASpecClass() = runTest {
        val log = mutableListOf<String>()
        val noCatalog = ModelPoolImpl(Registry(listOf(small("yolo"), vlmManifest)), listOf(FakeRuntime(GenieXVlmRuntime.ID, log, failWith = "NPU map error 8003")),
            llm = Llm(log, failBackends = setOf(LlmBackend.NPU, LlmBackend.GPU)), unloadLlm = {})
        fun cls(r: Result<LoadInfo>) = (r.exceptionOrNull() as ModelLoadException).errorClass
        assertEquals("model_load", cls(noCatalog.ensure(llmQ)))                           // no fallback without a catalog
        assertEquals("oom", cls(noCatalog.ensure(vlmE)))
        assertEquals("unsupported", cls(noCatalog.ensure(entry("missing", "detect", "litert_qnn", false))))
        assertEquals("unsupported", cls(noCatalog.ensure(entry("yolo", "detect", "litert_qnn", false))))   // no litert runtime here
        assertEquals("model_load", cls(noCatalog.ensure(entry("yolo", "detect", "litert_qnn", false, "b".repeat(64)))))   // sha differs (P12)
        assertEquals("unsupported", cls(ModelPoolImpl(Registry(emptyList()), emptyList(), llm = null).ensure(llmQ)))
    }

    @Test fun generationBundleTakesTheBigSlotAndIsEvictedByTheLlm() = runTest {
        val log = mutableListOf<String>()
        val llm = Llm(log)
        val p = pool(log, llm, FakeRuntime("ort_qnn", log), FakeRuntime("litert_qnn", log))
        p.ensure(llmQ).getOrThrow()
        val g = p.ensure(sdE).getOrThrow()
        assertFalse(g.warm); assertEquals("gen", p.big()); assertEquals("sd15", p.bigModel)
        assertTrue(p.ensure(sdE).getOrThrow().warm)
        assertTrue("sd15" in p.warm())
        assertFalse(p.ensure(llmQ).getOrThrow().warm)
        assertEquals("llm", p.big()); assertFalse("sd15" in p.warm())
        assertEquals(listOf("llm load NPU", "llm unload", "load sd15", "close sd15", "llm load NPU"), log)
    }
}
