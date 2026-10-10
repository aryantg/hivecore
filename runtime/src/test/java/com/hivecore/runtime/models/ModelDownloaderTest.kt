package com.hivecore.runtime.models

import com.hivecore.core.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private val files = HashMap<String, ByteArray>()
    /** Every `Range` header the server saw per path (null = the request carried none). */
    private val ranges = CopyOnWriteArrayList<Pair<String, String?>>()
    private val slow = AtomicBoolean(false)
    /** A server that answers 200 with the whole file whatever the Range header says. */
    private val ignoreRange = AtomicBoolean(false)
    private val port get() = server.localPort

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "test-http") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }
    }

    @After fun stop() = server.close()

    /** A minimal HTTP/1.1 file server: GET with optional `Range: bytes=N-`, `Connection: close` after each response. */
    private fun serve(client: Socket) {
        val input = client.getInputStream().buffered()
        val lines = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return
            if (c == '\n'.code) { val l = line.toString().trimEnd('\r'); line.clear(); if (l.isEmpty()) break; lines += l } else line.append(c.toChar())
        }
        val path = lines.first().split(' ')[1].substringBefore('?')
        val range = lines.drop(1).firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        ranges += path to range
        val out = client.getOutputStream()
        fun head(code: Int, len: Long, extra: String = "") =
            out.write("HTTP/1.1 $code X\r\nContent-Length: $len\r\nConnection: close\r\n$extra\r\n".toByteArray())
        val body = files[path]
        if (body == null) { head(404, 0); out.flush(); return }
        var from = 0
        var code = 200
        var extra = ""
        if (range != null && !ignoreRange.get()) {
            from = Regex("bytes=(\\d+)-").find(range)!!.groupValues[1].toInt()
            if (from >= body.size) { head(416, 0); out.flush(); return }
            code = 206
            extra = "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n"
        }
        head(code, (body.size - from).toLong(), extra)
        try {
            if (!slow.get()) out.write(body, from, body.size - from)
            else { var p = from; while (p < body.size) { val n = minOf(1024, body.size - p); out.write(body, p, n); out.flush(); p += n; Thread.sleep(5) } }
            out.flush()
        } catch (_: Exception) { /* the client went away: fine */ }
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun bytes(n: Int, seed: Int = 1) = Random(seed).nextBytes(n)

    private fun entry(id: String, runtime: String = "litert_qnn", cap: String = "detect", sha: String = "unpinned", url: String? = null,
                      dl: String? = null) = ModelEntry(id, cap, runtime, sha, 10, 300, 40, 0, big = false, url = url, downloadSha256 = dl)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun registryModelDirs(root: File) = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }!!.map { it.name }.sorted()

    @Test fun downloadsVerifiesAndInstallsARegistryFileWithAMinimalManifest() = runTest {
        val root = tmp.newFolder("models")
        val body = bytes(200_000)
        files["/yolo.tflite"] = body
        val installed = ArrayList<String>()
        val d = ModelDownloader(listOf(root), onInstalled = { installed += "refresh" })
        val progress = ArrayList<Pair<Long, Long>>()
        val r = d.fetch(entry("yolox_w8a8", sha = sha(body), url = url("/yolo.tflite"))) { done, total -> progress += done to total }
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(File(root, "yolox_w8a8"), r.getOrThrow())
        assertTrue(File(root, "yolox_w8a8/yolo.tflite").readBytes().contentEquals(body))
        assertEquals(listOf("refresh"), installed)
        assertEquals(body.size.toLong(), progress.last().first); assertEquals(body.size.toLong(), progress.last().second)
        assertNull(d.partial("yolox_w8a8"))
        assertEquals(listOf("yolox_w8a8"), registryModelDirs(root))
        // The registry accepts what we wrote: the rule of ModelRegistryImpl.parse plus ModelHash.
        val reg = ModelRegistryImpl(listOf(root))
        assertEquals(listOf("yolox_w8a8"), reg.available())
        val m = reg.manifest("yolox_w8a8")!!
        assertEquals("litert_qnn", m.runtime); assertEquals("detect", m.task); assertEquals(listOf("yolo.tflite"), m.files)
        assertTrue(reg.verify("yolox_w8a8").isSuccess)
        assertTrue(reg.rejected().toString(), reg.rejected().isEmpty())
    }

    @Test fun ggufGoesUnderTheGgufFolderWhereGenieXLooks() = runTest {
        val root = tmp.newFolder("models")
        val body = bytes(50_000, seed = 2)
        files["/q/qwen2.5-coder-1.5b-instruct-q4_0.gguf"] = body
        val r = ModelDownloader(listOf(root)).fetch(entry("qwen2.5-coder-1.5b-instruct-q4_0", "llama_cpp", "llm.text", sha(body),
            url("/q/qwen2.5-coder-1.5b-instruct-q4_0.gguf")))
        assertEquals(File(root, "gguf/qwen2.5-coder-1.5b-instruct-q4_0.gguf"), r.getOrThrow())
        assertTrue(r.getOrThrow().readBytes().contentEquals(body))
        files["/blob/abc123"] = body                                      // no .gguf name in the URL: named after the model id
        val r2 = ModelDownloader(listOf(root)).fetch(entry("other-llm", "llama_cpp", "llm.text", sha(body), url("/blob/abc123")))
        assertEquals(File(root, "gguf/other-llm.gguf"), r2.getOrThrow())
        assertTrue(ModelRegistryImpl(listOf(root)).installed().isEmpty())    // gguf is not a registry model
    }

    @Test fun resumesAPartialFileWithARangeRequest() = runTest {
        val root = tmp.newFolder("models")
        val body = bytes(300_000, seed = 3)
        files["/whisper.onnx"] = body
        val d = ModelDownloader(listOf(root))
        val part = ModelDownloader.partFile(root, "whisper_tiny").also { it.parentFile!!.mkdirs() }
        part.writeBytes(body.copyOfRange(0, 120_000))                       // what an interrupted run left behind
        assertEquals(part, d.partial("whisper_tiny"))
        val progress = ArrayList<Long>()
        val r = d.fetch(entry("whisper_tiny", "ort_qnn", "asr", sha(body), url("/whisper.onnx"))) { done, _ -> progress += done }
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(listOf("/whisper.onnx" to "bytes=120000-"), ranges.toList())
        assertEquals(120_000L, progress.first())
        assertTrue(File(root, "whisper_tiny/whisper.onnx").readBytes().contentEquals(body))
        assertFalse(part.exists())
    }

    @Test fun cancellationKeepsThePartialAndTheNextFetchResumesIt() {
        val root = tmp.newFolder("models")
        val body = bytes(400_000, seed = 4)
        files["/big.bin"] = body
        slow.set(true)
        val d = ModelDownloader(listOf(root))
        val e = entry("sd15", "ort_qnn", "gen.image", sha(body), url("/big.bin"))
        val part = ModelDownloader.partFile(root, "sd15")
        runBlocking {
            val job = async(Dispatchers.IO) { d.fetch(e) }
            val deadline = System.currentTimeMillis() + 20_000
            while ((!part.isFile || part.length() < 10_000) && System.currentTimeMillis() < deadline) Thread.sleep(10)
            job.cancel()
            assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
        slow.set(false)
        val have = part.length()
        assertTrue("partial $have", have in 1 until body.size)
        assertFalse(File(root, "sd15").exists())
        val r = runBlocking { d.fetch(e) }
        assertTrue(r.toString(), r.isSuccess)
        assertEquals("bytes=$have-", ranges.last().second)
        assertTrue(File(root, "sd15/big.bin").readBytes().contentEquals(body))
    }

    @Test fun aServerThatIgnoresRangeRestartsTheFile() = runTest {
        val root = tmp.newFolder("models")
        val body = bytes(100_000, seed = 5)
        files["/plain.bin"] = body
        ignoreRange.set(true)
        val part = ModelDownloader.partFile(root, "m").also { it.parentFile!!.mkdirs() }
        part.writeBytes(byteArrayOf(9, 9, 9, 9))                            // garbage prefix the server will not honour
        val r = ModelDownloader(listOf(root)).fetch(entry("m", sha = sha(body), url = url("/plain.bin")))
        assertTrue(r.toString(), r.isSuccess)
        assertEquals("bytes=4-", ranges.single().second)
        assertTrue(File(root, "m/plain.bin").readBytes().contentEquals(body))
    }

    @Test fun digestMismatchDeletesTheFileAndFails() = runTest {
        val root = tmp.newFolder("models")
        val body = bytes(10_000, seed = 6)
        files["/bad.tflite"] = body
        val d = ModelDownloader(listOf(root), onInstalled = { throw AssertionError("must not install") })
        val r = d.fetch(entry("bad", sha = "ab".repeat(32), url = url("/bad.tflite")))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!, "sha256 mismatch" in r.exceptionOrNull()!!.message!!)
        assertNull(d.partial("bad"))
        assertFalse(File(root, "bad").exists())
        assertTrue(File(root, ModelDownloader.DOWNLOADS).listFiles()!!.isEmpty())
    }

    @Test fun downloadSha256PinsTheArchiveWhenSha256IsTheInstalledRule() = runTest {
        val root = tmp.newFolder("models")
        val cfg = """{"ctx-bins":["part1_of_2.bin"]}""".toByteArray()
        val archive = zip("genie_config.json" to cfg, "part1_of_2.bin" to bytes(5000, 7), "part2_of_2.bin" to bytes(6000, 8), "tokenizer.json" to "{}".toByteArray())
        files["/qwen3-4b-npu.zip"] = archive
        val e = entry("qwen3-4b-npu", "qairt", "llm.text", sha = "11".repeat(32), url = url("/qwen3-4b-npu.zip"), dl = sha(archive))
        val r = ModelDownloader(listOf(root)).fetch(e)
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(File(root, "qwen3-4b-npu"), r.getOrThrow())
        assertTrue(File(root, "qwen3-4b-npu/genie_config.json").readBytes().contentEquals(cfg))       // what GenieXEngine.npuBundle wants
        assertEquals(listOf("genie_config.json", "part1_of_2.bin", "part2_of_2.bin", "tokenizer.json"), File(root, "qwen3-4b-npu").list()!!.sorted())
        assertFalse(ModelDownloader.partFile(root, "qwen3-4b-npu").exists())
        // Without downloadSha256 the bundle rule cannot match the archive: refused, nothing installed.
        files["/again.zip"] = archive
        val r2 = ModelDownloader(listOf(root)).fetch(entry("another", "qairt", "vlm", sha = "22".repeat(32), url = url("/again.zip")))
        assertTrue(r2.isFailure); assertFalse(File(root, "another").exists())
    }

    @Test fun zipWithOneTopLevelFolderIsFlattenedAndReplacesAnOldInstall() = runTest {
        val root = tmp.newFolder("models")
        val mf = """{"id":"yolox_w8a8","task":"detect","runtime":"litert_qnn","files":["yolo.tflite"],"sha256":"${sha(byteArrayOf(1, 2, 3))}","licence":"apache-2.0"}""".toByteArray()
        val archive = zip("yolox_w8a8-v2/" to ByteArray(0), "yolox_w8a8-v2/manifest.json" to mf, "yolox_w8a8-v2/yolo.tflite" to byteArrayOf(1, 2, 3))
        files["/yolo.zip"] = archive
        File(root, "yolox_w8a8").mkdirs(); File(root, "yolox_w8a8/stale.bin").writeBytes(byteArrayOf(0))
        val r = ModelDownloader(listOf(root)).fetch(entry("yolox_w8a8", sha = "33".repeat(32), url = url("/yolo.zip"), dl = sha(archive)))
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(listOf("manifest.json", "yolo.tflite"), File(root, "yolox_w8a8").list()!!.sorted())
        val reg = ModelRegistryImpl(listOf(root))
        assertEquals(listOf("yolox_w8a8"), reg.available())
        assertEquals("apache-2.0", reg.manifest("yolox_w8a8")!!.licence)
        assertTrue(reg.verify("yolox_w8a8").isSuccess)
    }

    @Test fun zipSlipIsRefusedBeforeAnythingIsWritten() = runTest {
        val root = tmp.newFolder("models")
        val archive = zip("ok.bin" to byteArrayOf(1), "../../escape.bin" to byteArrayOf(2))
        files["/evil.zip"] = archive
        val r = ModelDownloader(listOf(root), onInstalled = { throw AssertionError("must not install") })
            .fetch(entry("evil", sha = sha(archive), url = url("/evil.zip")))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!, "escapes" in r.exceptionOrNull()!!.message!!)
        assertFalse(File(root, "evil").exists())
        assertFalse(File(root.parentFile, "escape.bin").exists()); assertFalse(File(root, "escape.bin").exists())
        assertFalse(File(root, "${ModelDownloader.DOWNLOADS}/evil.unpack").exists())
    }

    @Test fun refusesWithoutUrlPinOrFetchableRuntimeBeforeAnyRequest() = runTest {
        val root = tmp.newFolder("models")
        val d = ModelDownloader(listOf(root))
        val noUrl = d.fetch(entry("x", sha = "ab".repeat(32)))
        assertTrue(noUrl.isFailure); assertEquals("x: no download source in the catalog", noUrl.exceptionOrNull()!!.message)
        val unpinned = d.fetch(entry("y", url = url("/y.bin")))
        assertTrue(unpinned.isFailure); assertTrue(unpinned.exceptionOrNull()!!.message!!, "unpinned" in unpinned.exceptionOrNull()!!.message!!)
        val builtIn = d.fetch(entry("mlkit_text_latin", "mlkit", "ocr", url = url("/z.bin"), dl = "ab".repeat(32)))
        assertTrue(builtIn.isFailure); assertTrue(builtIn.exceptionOrNull()!!.message!!, "built-in" in builtIn.exceptionOrNull()!!.message!!)
        val notHttp = d.fetch(entry("w", sha = "ab".repeat(32), url = "ftp://example.com/w.bin"))
        assertTrue(notHttp.isFailure)
        assertTrue(ranges.isEmpty())
        val missing = d.fetch(entry("v", sha = "ab".repeat(32), url = url("/nope.bin")))
        assertTrue(missing.isFailure); assertTrue(missing.exceptionOrNull()!!.message!!, "HTTP 404" in missing.exceptionOrNull()!!.message!!)
        assertNull(d.partial("v"))
    }

    @Test fun rootIsTheFirstWritableDirectory() {
        val base = tmp.newFolder("base")
        val unmakeable = File(tmp.newFile("afile"), "sub")                 // a file in the way: cannot be made
        val fresh = File(base, "fresh/models")
        val d = ModelDownloader(listOf(unmakeable, fresh, base))
        assertEquals(fresh, d.root()); assertTrue(fresh.isDirectory)
        assertNull(ModelDownloader(listOf(unmakeable)).root())
        assertNotNull(ModelDownloader.fileName("https://h/x/y/model%20v2.gguf?token=1"))
        assertEquals("model v2.gguf", ModelDownloader.fileName("https://h/x/y/model%20v2.gguf?token=1"))
        assertEquals("", ModelDownloader.fileName("https://h/"))
    }

    @Test fun manifestRuntimeFollowsTheCatalogRuntimeAndCapability() {
        assertEquals("litert_qnn", ModelDownloader.manifestRuntime(entry("a", "litert_qnn")))
        assertEquals("ort_qnn", ModelDownloader.manifestRuntime(entry("a", "ort_qnn")))
        assertEquals("geniex_vlm", ModelDownloader.manifestRuntime(entry("a", "qairt", "vlm")))
        assertEquals("geniex_llm", ModelDownloader.manifestRuntime(entry("a", "qairt", "llm.text")))
    }
}
