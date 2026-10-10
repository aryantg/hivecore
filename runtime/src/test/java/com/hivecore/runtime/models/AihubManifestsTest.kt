package com.hivecore.runtime.models

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks tools/fetch_aihub_models.py with the app: the manifests it wrote under D:/dedata/models/aihub parse,
 * pass the registry checks, and their sha256 (same rule as [ModelHash]) verifies. Skipped where the folder is absent.
 */
class AihubManifestsTest {
    private val root = File(System.getenv("HIVECORE_AIHUB_DIR") ?: "D:/dedata/models/aihub")

    @Test fun generatedManifestsLoadAndVerify() = runTest {
        assumeTrue("no $root", File(root, "yolox_w8a8/manifest.json").isFile)
        val reg = ModelRegistryImpl(listOf(root))
        assertTrue(reg.rejected().toString(), reg.rejected().isEmpty())
        val ids = reg.available().toSet()
        assertTrue(ids.toString(), ids.containsAll(listOf("yolox_w8a8", "minilm_w8a8", "whisper_tiny")))
        for (m in reg.installed()) {
            assertTrue(m.id, m.licence.isNotBlank())
            if (m.sizeMb <= 120) assertTrue("${m.id}: ${reg.verify(m.id).exceptionOrNull()?.message}", reg.verify(m.id).isSuccess)
        }
        val yolox = reg.manifest("yolox_w8a8")!!
        val prep = ImagePrep.from(yolox.preprocess)!!
        assertEquals(640, prep.width); assertEquals("uint8", prep.dtype); assertEquals("NHWC", prep.layout)
        assertEquals(80, yolox.labels.size)
        val whisper = ManifestSpecs.parse(reg.manifest("whisper_tiny")!!.inputSpec)
        assertTrue(whisper.any { it.graph == "encoder" && it.name == "input_features" && it.dtype == "float16" })
    }
}
