package com.hivecore.runtime.catalog

import com.hivecore.core.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelCatalogTest {
    private fun e(id: String, cap: String, runtime: String = "litert_qnn", big: Boolean = false, sha: String = "unpinned",
                  load: Int = 300, unit: Int = 40) = ModelEntry(id, cap, runtime, sha, 1000, load, unit, 0, big)

    private val llmQ = e("qwen-npu", "llm.json", "qairt", big = true, load = 6000, unit = 4000)
    private val llmG = e("qwen-gguf", "llm.json", "llama_cpp", big = true, load = 3000, unit = 6000)
    private val det = e("yolo", "detect")

    @Test fun entriesAreOrderedQairtThenLlamaThenTensorThenId() {
        val c = ModelCatalog(listOf(det, llmG, llmQ, e("a-emb", "embed", "ort_qnn"), e("b-emb", "embed", "litert_qnn")))
        assertEquals(listOf("yolo", "a-emb", "b-emb", "qwen-npu", "qwen-gguf"), c.entries.map { it.modelId })
        assertEquals("qwen-npu", c.preferred("llm.json")!!.modelId)
        assertEquals(setOf("detect", "embed", "llm.json"), c.capabilities)
    }

    @Test fun fallbackIsTheLlamaBuildAfterAQairtFailureOnly() {
        val c = ModelCatalog(listOf(llmQ, llmG, det))
        assertEquals("qwen-gguf", c.fallback("llm.json", llmQ)!!.modelId)
        assertNull(c.fallback("llm.json", llmG))
        assertNull(c.fallback("detect", det))
        assertNull(ModelCatalog(listOf(llmQ)).fallback("llm.json", llmQ))
    }

    @Test fun validateRefusesBadEntries() {
        val problems = ModelCatalog.validate(listOf(
            e("", "x"), e("m", "", sha = "zz"), e("m", "cap", runtime = "tensorrt"), e("m2", "cap", load = 0, unit = 0),
            e("big-small", "detect", big = true), e("dup", "detect"), e("dup", "detect"), e("two", "a", "litert_qnn"), e("two", "b", "ort_qnn"),
        ))
        assertTrue(problems.toString(), problems.any { "blank modelId" in it })
        assertTrue(problems.toString(), problems.any { "blank capability" in it })
        assertTrue(problems.toString(), problems.any { "sha256" in it })
        assertTrue(problems.toString(), problems.any { "unknown runtime 'tensorrt'" in it })
        assertTrue(problems.toString(), problems.any { "loadMsP50" in it })
        assertTrue(problems.toString(), problems.any { "unitMsP50" in it })
        assertTrue(problems.toString(), problems.any { "no big-slot kind" in it })
        assertTrue(problems.toString(), problems.any { "duplicate" in it })
        assertTrue(problems.toString(), problems.any { "under runtimes 'litert_qnn' and 'ort_qnn'" in it })
        assertTrue(ModelCatalog.validate(listOf(llmQ, llmG, det)).isEmpty())
    }

    @Test fun validateChecksTheOptionalDownloadFields() {
        val hex = "ab".repeat(32)
        assertTrue(ModelCatalog.validate(listOf(det.copy(url = "https://models.example.com/yolo.zip", downloadSha256 = hex))).isEmpty())
        assertTrue(ModelCatalog.validate(listOf(det.copy(url = "http://10.0.0.2:8080/yolo.tflite"))).isEmpty())
        val problems = ModelCatalog.validate(listOf(
            det.copy(url = "ftp://models.example.com/yolo.zip"), e("m2", "detect", sha = hex).copy(url = "not a url"),
            e("m3", "detect").copy(url = "https://"), e("m4", "detect").copy(downloadSha256 = "abc"),
            e("m5", "detect").copy(downloadSha256 = "zz".repeat(32)),
        ))
        assertEquals(problems.toString(), 3, problems.count { "url must be http(s)" in it })
        assertEquals(problems.toString(), 2, problems.count { "downloadSha256 must be 64 hex digits" in it })
        assertTrue(ModelCatalog.isHttpUrl("https://example.com/a?b=c")); assertFalse(ModelCatalog.isHttpUrl("file:///tmp/x"))
    }

    @Test fun parseReadsTheDownloadFieldsAndDefaultsThemToNull() {
        val hex = "cd".repeat(32)
        val one = """{"modelId":"yolo","capability":"detect","runtime":"litert_qnn","sha256":"unpinned","sizeBytes":1,"loadMsP50":1,"unitMsP50":1,"maxContext":0,"big":false"""
        val plain = ModelCatalog.parse("[$one}]").entries.single()
        assertNull(plain.url); assertNull(plain.downloadSha256)
        val withUrl = ModelCatalog.parse("""[$one,"url":"https://models.example.com/yolo.zip","downloadSha256":"$hex"}]""").entries.single()
        assertEquals("https://models.example.com/yolo.zip", withUrl.url); assertEquals(hex, withUrl.downloadSha256)
        assertTrue(ModelCatalog.tryParse("""[$one,"url":"gopher://x"}]""").isFailure)
        assertTrue(ModelCatalog.tryParse("""[$one,"downloadSha256":"short"}]""").isFailure)
    }

    @Test fun parseTakesBothShapesAndRefusesInvalidText() {
        val one = """{"modelId":"yolo","capability":"detect","runtime":"litert_qnn","sha256":"unpinned","sizeBytes":1,"loadMsP50":1,"unitMsP50":1,"maxContext":0,"big":false}"""
        assertEquals("catalog-7", ModelCatalog.parse("""{"version":"catalog-7","models":[$one]}""").version)
        assertEquals(1, ModelCatalog.parse("[$one]").entries.size)
        assertTrue(ModelCatalog.tryParse("{\"models\":[{\"modelId\":\"x\"}]}").isFailure)
        assertTrue(ModelCatalog.tryParse("not json").isFailure)
        assertTrue(ModelCatalog.load { throw java.io.FileNotFoundException(it) }.isEmpty())
    }

    @Test fun bigKindFollowsTheCapabilityAndTheBuildFlag() {
        assertEquals("llm", BigKind.of("llm.text")); assertEquals("llm", BigKind.of("llm.json"))
        assertEquals("vlm", BigKind.of("vlm")); assertEquals("gen", BigKind.of("gen.image"))
        assertEquals("gen", BigKind.of("superres")); assertEquals("gen", BigKind.of("upscale"))
        assertEquals("none", BigKind.of("detect")); assertEquals("none", BigKind.of("asr"))
        assertEquals("none", BigKind.of(e("sr", "superres", big = false)))
        assertEquals("gen", BigKind.of(e("sd", "gen.image", "ort_qnn", big = true)))
    }

    @Test fun capabilitiesOfListsInstalledEntriesWithWarmFlags() {
        val c = ModelCatalog(listOf(llmQ, llmG, det, e("ocr-mlkit", "ocr", "mlkit")))
        val states = c.capabilitiesOf(installed = setOf("qwen-npu", "yolo"), warm = setOf("yolo"), bigModel = null)
        assertEquals(listOf("detect/yolo/true", "llm.json/qwen-npu/false", "ocr/ocr-mlkit/true"), states.map { "${it.capability}/${it.modelId}/${it.warm}" })
        val withBig = c.capabilitiesOf(setOf("qwen-npu", "qwen-gguf"), emptySet(), bigModel = "qwen-gguf")
        assertEquals(listOf(false, true), withBig.filter { it.capability == "llm.json" }.map { it.warm })
    }

    @Test fun loaderPrefersANewerValidOverrideFile() {
        val dir = Files.createTempDirectory("catalog").toFile()
        val override = File(dir, "models/catalog.json")
        fun entry(v: String, id: String) = """{"version":"$v","models":[{"modelId":"$id","capability":"detect","runtime":"litert_qnn","sha256":"unpinned","sizeBytes":1,"loadMsP50":1,"unitMsP50":1,"maxContext":0,"big":false}]}"""
        val asset = entry("catalog-2", "shipped")
        val loader = ModelCatalogLoader({ asset }, override)
        assertEquals(ModelCatalogLoader.SOURCE_ASSET, loader.loadDetailed().source)

        assertTrue(loader.install(entry("catalog-3", "downloaded")).isSuccess)
        val got = loader.loadDetailed()
        assertEquals(ModelCatalogLoader.SOURCE_FILE, got.source)
        assertEquals("downloaded", got.catalog.entries.single().modelId)

        override.writeText(entry("catalog-1", "stale"))            // older: the asset wins
        assertEquals("shipped", loader.load().entries.single().modelId)
        override.writeText("{ broken")                               // malformed: ignored
        assertEquals("shipped", loader.load().entries.single().modelId)
        assertTrue(loader.install("{\"models\":[{\"modelId\":\"\"}]}").isFailure)
        assertEquals("shipped", loader.load().entries.single().modelId)

        override.writeText(entry("catalog-2", "same"))              // equal version: the file wins (it is the update)
        assertEquals("same", loader.load().entries.single().modelId)
        assertEquals(ModelCatalogLoader.SOURCE_NONE, ModelCatalogLoader({ null }, null).loadDetailed().source)
        dir.deleteRecursively()
    }

    @Test fun versionsCompareByNumericFields() {
        assertTrue(ModelCatalogLoader.compareVersions("catalog-10", "catalog-9") > 0)
        assertTrue(ModelCatalogLoader.compareVersions("2026.10.1", "2026.9.12") > 0)
        assertEquals(0, ModelCatalogLoader.compareVersions("catalog-3", "catalog-3"))
        assertTrue(ModelCatalogLoader.compareVersions("catalog-3.1", "catalog-3") > 0)
        assertFalse(ModelCatalogLoader.compareVersions("", "catalog-1") > 0)
    }
}
