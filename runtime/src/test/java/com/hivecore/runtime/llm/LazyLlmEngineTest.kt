package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.LlmBackend
import com.hivecore.core.fakes.FakeLlmEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyLlmEngineTest {
    @Test fun theFirstGenerateLoadsTheBestAvailableBackendOnce() = runTest {
        val inner = FakeLlmEngine(null).apply { availableBackends = setOf(LlmBackend.CPU, LlmBackend.NPU); fallback = "hi" }
        val lazy = LazyLlmEngine(inner)
        assertNull(lazy.backend.value)
        val r = lazy.generate(listOf(ChatMsg("user", "x")), GenParams())
        assertEquals("hi", r.getOrThrow().text)
        assertEquals(LlmBackend.NPU, lazy.backend.value)
        lazy.generate(listOf(ChatMsg("user", "y")), GenParams())
        assertEquals(2, inner.calls.size)
    }

    @Test fun theMemoryGuardKeepsTheModelUnloaded() = runTest {
        val inner = FakeLlmEngine(null).apply { availableBackends = setOf(LlmBackend.NPU) }
        LazyLlmEngine(inner, mayLoad = { false }).generate(listOf(ChatMsg("user", "x")), GenParams())
        assertNull(inner.backend.value)
    }

    @Test fun withoutABundleTheEnginesOwnOutcomeIsReturned() = runTest {
        val inner = FakeLlmEngine(null).apply { availableBackends = emptySet(); fallback = "still answers" }
        val r = LazyLlmEngine(inner).generate(listOf(ChatMsg("user", "x")), GenParams())
        assertTrue(r.isSuccess)
        assertNull(inner.backend.value)
    }
}
