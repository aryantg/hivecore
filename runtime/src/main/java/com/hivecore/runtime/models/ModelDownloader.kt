package com.hivecore.runtime.models

import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelManifest
import com.hivecore.core.SLog
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.llm.GenieXEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Fetches a catalog build ([ModelEntry.url]) onto this phone and installs it where the runtime named by
 * [ModelEntry.runtime] looks for it (the Models screen's `download` action).
 *
 * **Download.** The file streams into `<root>/.downloads/<modelId>.part` where `<root>` is the first directory of
 * [roots] that exists (or can be made) and is writable: the same roots the registry and GenieX scan, so a model
 * installed here is found on the next [ModelRegistryImpl.refresh]. A partial file left by an interrupted run is
 * resumed with an HTTP `Range` request (a server that answers 200 instead of 206 restarts the file; 416 discards the
 * partial and restarts). The sha256 is computed while writing (the existing prefix is re-hashed first on resume) and
 * compared with [ModelEntry.downloadSha256], else [ModelEntry.sha256]; a mismatch deletes the file and fails. A build
 * with neither (catalog `unpinned`) or with no `url` is refused before any network call, as are the built-in
 * runtimes (`mlkit`, `kotlin`: nothing to fetch). The copy loop is cancellable; cancelling keeps the `.part` for a
 * later resume.
 *
 * **Install** (the on-disk rule each runtime expects, from [ModelRegistryImpl], [GenieXEngine] and [ModelPoolImpl]):
 *  - A `.zip` (any runtime): extracted into `<root>/<modelId>/`, replacing that folder. Entries escaping the target
 *    are refused before anything is written (zip-slip); a zip whose entries all sit under one top-level folder has
 *    that folder stripped. This covers the `qairt` LLM bundle (`<root>/qwen3-4b-npu/genie_config.json` is what
 *    [GenieXEngine.npuBundle] wants; [ModelPoolImpl.LLM_QAIRT_ID] is that folder name) and any registry model whose
 *    archive carries its `manifest.json` and files.
 *  - `llama_cpp`, not a zip: the GGUF goes to `<root>/gguf/<name>` ([GenieXEngine.ggufFile] loads the alphabetically
 *    first `*.gguf` there); `<name>` is the URL's file name when it ends in `.gguf`, else `<modelId>.gguf`.
 *  - `litert_qnn`, `ort_qnn`, `qairt`, not a zip: the file goes to `<root>/<modelId>/<name>` and, when that folder has
 *    no `manifest.json` yet, a minimal one is written that [ModelRegistryImpl.parse] accepts: `id` = modelId, `task`
 *    = capability, `runtime` = the manifest runtime (`litert_qnn` / `ort_qnn` as-is; `qairt` becomes `geniex_vlm` for
 *    the `vlm` capability, `geniex_llm` otherwise), `files` = [name], `sha256` = the file digest ([ModelHash] rule
 *    for one file), `licence` = "see <url>" (the catalog carries no licence text; the registry only requires it
 *    non-blank).
 *
 * [onInstalled] runs after a successful install (the app passes `registry::refresh`). The returned [File] is the
 * installed folder (zip, registry file) or the GGUF file.
 */
class ModelDownloader(
    private val roots: List<File>,
    private val onInstalled: () -> Unit = {},
) {
    /** Where downloads go: the first root that exists (or can be made) and is writable. */
    fun root(): File? = roots.firstOrNull { r -> (r.isDirectory || r.mkdirs()) && r.canWrite() }

    /** The `.part` file of [modelId] when a resumable download is on disk. */
    fun partial(modelId: String): File? = root()?.let { partFile(it, modelId) }?.takeIf { it.isFile }

    /** The digest a download of [entry] must match; null when the catalog pins nothing. */
    fun expectedSha256(entry: ModelEntry): String? =
        entry.downloadSha256 ?: entry.sha256.takeIf { ModelCatalog.isHex64(it) }

    suspend fun fetch(entry: ModelEntry, onProgress: ((done: Long, total: Long) -> Unit)? = null): Result<File> {
        val url = entry.url ?: return Result.failure(IllegalArgumentException("${entry.modelId}: no download source in the catalog"))
        if (ModelCatalog.isBuiltIn(entry.runtime))
            return Result.failure(IllegalArgumentException("${entry.modelId}: built-in runtime '${entry.runtime}', nothing to download"))
        if (!ModelCatalog.isHttpUrl(url)) return Result.failure(IllegalArgumentException("${entry.modelId}: url is not http(s): $url"))
        val expected = expectedSha256(entry)
            ?: return Result.failure(IllegalArgumentException("${entry.modelId}: no sha256 pinned in the catalog (unpinned), refusing an unverified download"))
        val root = root() ?: return Result.failure(IOException("no writable model root among ${roots.joinToString { it.path }}"))
        return try {
            withContext(Dispatchers.IO) {
                val part = partFile(root, entry.modelId).also { it.parentFile?.mkdirs() }
                val digest = download(url, part, onProgress)
                if (!digest.equals(expected, ignoreCase = true)) {
                    part.delete()
                    throw IOException("${entry.modelId}: sha256 mismatch: expected ${expected.take(16)}, downloaded ${digest.take(16)}")
                }
                val installed = install(entry, url, part, digest, root)
                SLog.d(TAG, "installed ${entry.modelId} at ${installed.path}")
                onInstalled()
                Result.success(installed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SLog.w(TAG, "download of ${entry.modelId} failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ---- download ----------------------------------------------------------------------------------------------

    /** Streams [url] into [part] (resuming its current length) and returns the hex sha256 of the complete file. */
    private suspend fun download(url: String, part: File, onProgress: ((Long, Long) -> Unit)?): String {
        var offset = if (part.isFile) part.length() else 0L
        var conn = open(url, offset)
        var code = conn.responseCode
        if (code == 416 && offset > 0) {           // the server cannot satisfy the range: the partial is stale
            conn.disconnect(); part.delete(); offset = 0
            conn = open(url, 0); code = conn.responseCode
        }
        try {
            when {
                code == HttpURLConnection.HTTP_PARTIAL && offset > 0 -> Unit
                code == HttpURLConnection.HTTP_OK -> { if (offset > 0) part.delete(); offset = 0 }
                else -> throw IOException("HTTP $code from $url")
            }
            val remaining = conn.contentLengthLong
            val total = if (remaining >= 0) offset + remaining else -1L
            val md = MessageDigest.getInstance("SHA-256")
            if (offset > 0) hashPrefix(part, md)
            var done = offset
            onProgress?.invoke(done, total)
            FileOutputStream(part, offset > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            if (total >= 0 && done < total) throw IOException("connection closed after $done of $total bytes (resumable)")
            return md.digest().toHex()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, offset: Long): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
    }

    private suspend fun hashPrefix(part: File, md: MessageDigest) {
        part.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) { currentCoroutineContext().ensureActive(); val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
    }

    // ---- install -----------------------------------------------------------------------------------------------

    private fun install(entry: ModelEntry, url: String, part: File, digest: String, root: File): File {
        val name = fileName(url)
        return when {
            name.endsWith(".zip", ignoreCase = true) || isZip(part) -> {
                val target = File(root, entry.modelId)
                val staging = File(root, "$DOWNLOADS/${safeName(entry.modelId)}.unpack")
                staging.deleteRecursively()
                try {
                    extractZip(part, staging)
                    target.deleteRecursively()
                    if (!staging.renameTo(target)) { staging.copyRecursively(target, overwrite = true); staging.deleteRecursively() }
                } finally { staging.deleteRecursively() }
                part.delete()
                target
            }
            entry.runtime == ModelCatalog.RUNTIME_LLAMA -> {
                val dir = File(root, GenieXEngine.GGUF_DIR).also { it.mkdirs() }
                val dest = File(dir, if (name.endsWith(".gguf", ignoreCase = true)) name else "${safeName(entry.modelId)}.gguf")
                move(part, dest)
                dest
            }
            else -> {
                val dir = File(root, entry.modelId).also { it.mkdirs() }
                val dest = File(dir, name.ifBlank { safeName(entry.modelId) })
                move(part, dest)
                val mf = File(dir, ModelRegistryImpl.MANIFEST)
                if (!mf.isFile) mf.writeText(ModelRegistryImpl.json.encodeToString(ModelManifest.serializer(), ModelManifest(
                    id = entry.modelId, task = entry.capability, runtime = manifestRuntime(entry), files = listOf(dest.name),
                    sha256 = digest, licence = "see $url", sizeMb = (dest.length() / MB).toInt(),
                )))
                dir
            }
        }
    }

    private fun move(src: File, dest: File) {
        dest.delete()
        if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
    }

    private fun extractZip(zip: File, target: File) {
        target.mkdirs()
        val targetPath = target.canonicalPath + File.separator
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            // Safety first: refuse the archive as a whole before writing anything.
            for (e in entries) {
                val out = File(target, e.name).canonicalPath
                if (out != target.canonicalPath && !out.startsWith(targetPath))
                    throw IOException("zip entry escapes the target folder: ${e.name}")
            }
            val names = entries.map { it.name.trimStart('/') }
            val top = names.map { it.substringBefore('/', "") }.toSet()
            val strip = if (top.size == 1 && top.single().isNotEmpty() && names.all { it.contains('/') }) top.single() + "/" else ""
            for (e in entries) {
                val rel = e.name.trimStart('/').removePrefix(strip)
                if (rel.isEmpty()) continue
                val f = File(target, rel)
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                zf.getInputStream(e).use { s -> f.outputStream().use { o -> s.copyTo(o) } }
            }
        }
    }

    private fun isZip(f: File): Boolean = runCatching {
        f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
        }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "ModelDownloader"
        const val DOWNLOADS = ".downloads"
        const val PART = ".part"
        private const val MB = 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        /** The manifest `runtime` for a catalog runtime (see [ModelManifest.runtime]). */
        fun manifestRuntime(entry: ModelEntry): String = when (entry.runtime) {
            ModelCatalog.RUNTIME_QAIRT -> if (entry.capability == "vlm") "geniex_vlm" else "geniex_llm"
            else -> entry.runtime
        }

        /** The last path segment of [url] without its query, percent-decoded, with path separators removed. */
        fun fileName(url: String): String = runCatching {
            URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8")
        }.getOrDefault("").replace('\\', '_').replace('/', '_').trim()

        internal fun partFile(root: File, modelId: String): File = File(root, "$DOWNLOADS/${safeName(modelId)}$PART")

        private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
