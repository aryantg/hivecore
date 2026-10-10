package com.hivecore.runtime.models

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelRegistryImplTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun model(root: File, id: String, files: Map<String, ByteArray>, licence: String? = "apache-2.0",
                      sha: String? = null, listed: List<String> = files.keys.toList(), runtime: String = "litert_qnn"): File {
        val dir = File(root, id).apply { mkdirs() }
        files.forEach { (n, b) -> File(dir, n).writeBytes(b) }
        val digest = sha ?: ModelHash.combine(listed.map { sha(files[it] ?: ByteArray(0)) })
        val lic = if (licence == null) "" else "\"licence\": \"$licence\","
        File(dir, "manifest.json").writeText("""
            {"id": "$id", "task": "detect", "runtime": "$runtime", "files": [${listed.joinToString { "\"$it\"" }}],
             $lic "sha256": "$digest", "sizeMb": 9, "someFutureField": {"x": 1}}
        """.trimIndent())
        return dir
    }

    @Test fun parsesValidManifestsAndIgnoresFoldersWithout() {
        val root = tmp.newFolder("models")
        model(root, "yolox_w8a8", mapOf("yolox.tflite" to byteArrayOf(1, 2, 3), "labels.txt" to "person\n".toByteArray()))
        File(root, "qwen3-4b-npu").mkdirs()     // the LLM bundle: no manifest → not a model, not rejected
        val reg = ModelRegistryImpl(listOf(root))
        assertEquals(listOf("yolox_w8a8"), reg.available())
        val m = reg.manifest("yolox_w8a8")!!
        assertEquals("litert_qnn", m.runtime)
        assertEquals(9, m.sizeMb)
        assertEquals(File(root, "yolox_w8a8"), reg.dir("yolox_w8a8"))
        assertTrue(reg.rejected().isEmpty())
    }

    @Test fun refusesManifestWithoutLicence() {
        val root = tmp.newFolder("models")
        model(root, "nolicence", mapOf("a.tflite" to byteArrayOf(1)), licence = null)
        model(root, "blank", mapOf("a.tflite" to byteArrayOf(1)), licence = "  ")
        val reg = ModelRegistryImpl(listOf(root))
        assertTrue(reg.installed().isEmpty())
        assertNull(reg.manifest("nolicence"))
        val reasons = reg.rejected().values.joinToString()
        assertTrue(reasons, reasons.contains("no licence field"))
        assertTrue(reasons, reasons.contains("blank licence"))
    }

    @Test fun refusesMissingFiles() {
        val root = tmp.newFolder("models")
        model(root, "sd15", mapOf("unet.onnx" to byteArrayOf(1)), listed = listOf("unet.onnx", "unet.bin"))
        val reg = ModelRegistryImpl(listOf(root))
        assertTrue(reg.installed().isEmpty())
        assertTrue(reg.rejected().values.single().contains("missing files: unet.bin"))
    }

    @Test fun firstRootWinsForDuplicateIds() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        model(a, "m", mapOf("x.tflite" to byteArrayOf(1)))
        model(b, "m", mapOf("x.tflite" to byteArrayOf(2)))
        val reg = ModelRegistryImpl(listOf(a, b))
        assertEquals(File(a, "m"), reg.dir("m"))
        assertEquals(1, reg.rejected().size)
    }

    @Test fun verifiesSha256LazilyAndCachesIt() = runTest {
        val root = tmp.newFolder("models")
        val cache = File(tmp.root, "hashes.json")
        model(root, "ok", mapOf("e.onnx" to byteArrayOf(1, 2), "e.bin" to byteArrayOf(3, 4)))
        model(root, "bad", mapOf("e.onnx" to byteArrayOf(1)), sha = "00".repeat(32))
        val reg = ModelRegistryImpl(listOf(root), hashCache = cache)
        assertEquals(2, reg.installed().size)                   // not hashed at scan time
        assertTrue(reg.verify("ok").isSuccess)
        assertTrue(cache.isFile)
        val bad = reg.verify("bad")
        assertTrue(bad.isFailure)
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("sha256 mismatch"))
        // a second registry reuses the on-disk cache (same digest)
        assertTrue(ModelRegistryImpl(listOf(root), hashCache = cache).verify("ok").isSuccess)
    }

    @Test fun combinedHashIsDigestOfDigests() {
        val d1 = sha(byteArrayOf(1)); val d2 = sha(byteArrayOf(2))
        assertEquals(d1, ModelHash.combine(listOf(d1)))
        assertEquals(sha("$d1\n$d2".toByteArray()), ModelHash.combine(listOf(d1, d2)))
        val f1 = tmp.newFile("f1").apply { writeBytes(byteArrayOf(1)) }
        val f2 = tmp.newFile("f2").apply { writeBytes(byteArrayOf(2)) }
        assertEquals(ModelHash.combine(listOf(d1, d2)), ModelHash.of(listOf(f1, f2)))
    }

    @Test fun mergesWarmupRecordsForTheSameSha() {
        val root = tmp.newFolder("models")
        model(root, "m", mapOf("x.tflite" to byteArrayOf(1)))
        val warm = File(tmp.root, "warm.json")
        val sha = ModelRegistryImpl(listOf(root)).manifest("m")!!.sha256
        warm.writeText("""{"m": {"sha256": "$sha", "firstCompileMs": 1234, "warmMs": 27, "ts": 1}}""")
        val m = ModelRegistryImpl(listOf(root), warmCache = warm).manifest("m")
        assertNotNull(m)
        assertEquals(1234L, m!!.firstCompileMs)
        assertEquals(27L, m.warmMs)
    }
}
