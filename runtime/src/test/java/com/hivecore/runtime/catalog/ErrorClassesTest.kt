package com.hivecore.runtime.catalog

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorClassesTest {
    @Test fun messagesMapToTheSpecClasses() {
        assertEquals("oom", ErrorClasses.of("NPU map error 8003"))
        assertEquals("oom", ErrorClasses.of("java.lang.OutOfMemoryError: boom"))
        assertEquals("thermal", ErrorClasses.of("thermal shutdown at 41 C"))
        assertEquals("timeout", ErrorClasses.of("load timed out"))
        assertEquals("unsupported", ErrorClasses.of("model x is not installed"))
        assertEquals("unsupported", ErrorClasses.of("no runtime for y"))
        assertEquals("model_load", ErrorClasses.of("sha256 mismatch for z"))
        assertEquals("model_load", ErrorClasses.of("something odd"))
        assertEquals("yielded", ErrorClasses.of("yielded: owner picked the phone up"))
        assertEquals("schema", ErrorClasses.of("schema: missing field"))
    }

    @Test fun throwablesCarryTheirClass() = runTest {
        assertEquals("thermal", ErrorClasses.of(ModelLoadException("thermal", "too hot")))
        assertEquals("oom", ErrorClasses.of(OutOfMemoryError("x")))
        val t = runCatching { withTimeout(1) { kotlinx.coroutines.delay(10) } }.exceptionOrNull()!!
        assertEquals("timeout", ErrorClasses.of(t))
        assertTrue(runCatching { ModelLoadException("schema", "not a load class") }.isFailure)
        assertEquals(setOf("oom", "model_load", "thermal", "unsupported", "timeout"), ErrorClasses.MODEL_FAIL)
        assertEquals(setOf("oom", "model_load", "thermal"), ErrorClasses.COOLDOWN)
    }
}
