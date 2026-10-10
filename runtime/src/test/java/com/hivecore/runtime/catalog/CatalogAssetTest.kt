package com.hivecore.runtime.catalog

import com.hivecore.core.OpRegistry
import com.hivecore.runtime.llm.GenieXEngine
import com.hivecore.runtime.models.ModelPoolImpl
import com.hivecore.runtime.models.ModelRegistryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The shipped `models/catalog.json` (spec 4.3): valid, complete, and naming builds the runtime knows. */
class CatalogAssetTest {
    private val text = listOf(File("src/main/assets"), File("../runtime/src/main/assets"))
        .map { File(it, ModelCatalog.ASSET) }.first { it.isFile }.readText()
    private val catalog = ModelCatalog.parse(text)

    @Test fun coversEveryCapabilityOfTheSpecAndTheOpRegistry() {
        assertEquals(ModelCatalog.REQUIRED_CAPABILITIES, catalog.capabilities)
        assertTrue((OpRegistry.capabilities - catalog.capabilities).toString(), catalog.capabilities.containsAll(OpRegistry.capabilities))
        assertTrue(catalog.version.isNotBlank())
    }

    @Test fun llmBuildsMatchTheEngineAndThePool() {
        assertEquals(GenieXEngine.NPU_DIR, catalog.preferred("llm.text")!!.modelId)
        assertEquals(ModelPoolImpl.LLM_QAIRT_ID, catalog.preferred("llm.json")!!.modelId)
        assertEquals(ModelPoolImpl.LLM_GGUF_ID, catalog.fallback("llm.json", catalog.preferred("llm.json")!!)!!.modelId)
        for (cap in listOf("llm.text", "llm.json", "vlm", "gen.image")) assertTrue(cap, catalog.preferred(cap)!!.big)
        for (cap in listOf("asr", "ocr", "embed", "detect", "segment", "classify", "iqa")) assertTrue(cap, !catalog.preferred(cap)!!.big)
    }

    @Test fun fileBackedBuildsArePinned() {
        for (e in catalog.entries) {
            if (ModelCatalog.isBuiltIn(e.runtime)) assertEquals(e.modelId, 0L, e.sizeBytes)
            else { assertTrue(e.modelId, ModelCatalog.isHex64(e.sha256)); assertTrue(e.modelId, e.sizeBytes > 0) }
        }
    }

    /** Where the fetched AI Hub manifests exist, the catalog's sha256 and ids agree with them (skipped elsewhere). */
    @Test fun agreesWithTheFetchedManifests() {
        val root = File(System.getenv("HIVECORE_AIHUB_DIR") ?: "D:/dedata/models/aihub")
        assumeTrue("no $root", File(root, "yolox_w8a8/manifest.json").isFile)
        val reg = ModelRegistryImpl(listOf(root))
        for (e in catalog.entries) {
            val m = reg.manifest(e.modelId) ?: continue
            assertEquals(e.modelId, m.sha256, e.sha256)
            assertEquals(e.modelId, m.sizeMb * ModelPoolImpl.MB, e.sizeBytes)
        }
        assertTrue(catalog.entries.count { reg.manifest(it.modelId) != null } >= 7)
    }
}
