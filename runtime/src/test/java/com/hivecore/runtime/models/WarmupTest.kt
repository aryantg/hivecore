package com.hivecore.runtime.models

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WarmupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun recordsFirstCompileOncePerShaAndSkipsBigSlotModels() = runTest {
        val ms = listOf(
            ModelManifest("a", "t", "litert_qnn", files = listOf("a.tflite"), sha256 = "s1", licence = "mit"),
            ModelManifest("vlm", "t", "geniex_vlm", files = listOf("p.bin"), sha256 = "s2", licence = "mit"),
        )
        val reg = object : ModelRegistry {
            override fun installed() = ms
            override fun manifest(id: String) = ms.firstOrNull { it.id == id }
            override fun dir(id: String) = File("/m/$id")
        }
        var now = 0L
        var loads = 0
        val rt = object : ModelRuntime {
            override val id = "litert_qnn"
            override fun supports(m: ModelManifest) = m.runtime == id
            override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> {
                now += if (loads++ == 0) 1500 else 30           // first load compiles, the second hits the cache
                return Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
            }
            override suspend fun run(session: ModelSession, inputs: Map<String, Any>) = Result.success(emptyMap<String, Any>())
        }
        val cache = File(tmp.root, "warm.json")
        val w = Warmup(reg, listOf(rt), cache, clock = { now })
        assertEquals(listOf("a"), w.pending().map { it.id })
        val recs = w.run()
        assertEquals(1500L, recs["a"]!!.firstCompileMs)
        assertEquals(30L, recs["a"]!!.warmMs)
        assertTrue(w.pending().isEmpty())
        assertEquals(1500L, Warmup.readCache(cache)["a"]!!.firstCompileMs)
        assertTrue(w.run(shouldContinue = { false }).containsKey("a"))   // nothing pending, cache kept
        assertEquals(2, loads)
    }
}
