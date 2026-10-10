package com.hivecore.runtime.models

import android.content.Context
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.SLog
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Model registry (swarm plan §4A.2): every `<root>/<id>/manifest.json` under the model roots, first root wins
 * for a duplicate id. Folders without a manifest (the LLM bundle, gguf, stt) are not models and are ignored.
 *
 * A manifest is refused (listed in [rejected] with the reason) when it does not parse, has no or a blank
 * `licence`, has a blank `sha256`, lists no files, or a listed file is missing. The sha256 check itself is lazy
 * ([verify]): hashing a 3 GB bundle takes seconds, so it runs when a model is first loaded and its result is
 * cached by (path, length, mtime) in [hashCache].
 *
 * `sha256` is the hex sha256 of the only file, or for several files the sha256 of their hex digests joined with
 * "\n" in `files` order ([ModelHash]); tools/fetch_aihub_models.py writes the same.
 */
class ModelRegistryImpl(
    private val roots: List<File>,
    private val hashCache: File? = null,
    /** Warmup's cache (firstCompileMs/warmMs per model sha), merged into the manifests returned. */
    private val warmCache: File? = null,
) : ModelRegistry {

    private class Found(val manifest: ModelManifest, val dir: File)

    @Volatile private var found: Map<String, Found> = emptyMap()
    @Volatile private var rejectedMap: Map<String, String> = emptyMap()
    @Volatile private var scanned = false
    private val verified = HashMap<String, Result<Unit>>()
    private val verifyLock = Mutex()

    /** Rescans the roots; returns the installed models. */
    @Synchronized
    fun refresh(): List<ModelManifest> {
        val ok = LinkedHashMap<String, Found>()
        val bad = LinkedHashMap<String, String>()
        for (root in roots) {
            val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: continue
            for (dir in dirs) {
                val mf = File(dir, MANIFEST)
                if (!mf.isFile) continue
                val r = parse(mf.readText(), dir)
                r.onSuccess { m ->
                    if (m.id in ok) bad[mf.path] = "duplicate id ${m.id} (kept ${ok[m.id]!!.dir.path})"
                    else ok[m.id] = Found(m, dir)
                }.onFailure { bad[mf.path] = it.message ?: it.javaClass.simpleName }
            }
        }
        bad.forEach { (p, why) -> SLog.w(TAG, "refused $p: $why") }
        found = ok; rejectedMap = bad; scanned = true
        synchronized(verified) { verified.keys.retainAll(ok.keys) }
        return installed()
    }

    private fun ensure() { if (!scanned) refresh() }

    override fun installed(): List<ModelManifest> { ensure(); val w = warm(); return found.values.map { merge(it.manifest, w) } }
    override fun manifest(id: String): ModelManifest? { ensure(); return found[id]?.manifest?.let { merge(it, warm()) } }
    override fun dir(id: String): File? { ensure(); return found[id]?.dir }

    /** Same as [installed]: the models this phone can advertise in HELLO/PROFILE (§6.1). */
    fun available(): List<String> = installed().map { it.id }

    /** manifest path → why it was refused. */
    fun rejected(): Map<String, String> { ensure(); return rejectedMap }

    /** Lazily checks the model's sha256 against its files (cached per process and in [hashCache]). */
    suspend fun verify(id: String): Result<Unit> = verifyLock.withLock {
        ensure()
        synchronized(verified) { verified[id] }?.let { return@withLock it }
        val f = found[id] ?: return@withLock Result.failure(IllegalArgumentException("model $id is not installed"))
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val files = f.manifest.files.map { File(f.dir, it) }
                val got = ModelHash.of(files, cache)
                saveCache()
                if (!got.equals(f.manifest.sha256, ignoreCase = true))
                    throw IllegalStateException("sha256 mismatch for ${f.manifest.id}: manifest ${f.manifest.sha256.take(16)}…, files $got")
            }
        }
        synchronized(verified) { verified[id] = r }
        r
    }

    // ---- hash cache --------------------------------------------------------------------------------------------

    private val cache: MutableMap<String, HashEntry> by lazy {
        hashCache?.takeIf { it.isFile }?.let { f ->
            runCatching { json.decodeFromString(HashCacheFile.serializer(), f.readText()).entries.toMutableMap() }.getOrNull()
        } ?: mutableMapOf()
    }

    private fun saveCache() {
        val f = hashCache ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(json.encodeToString(HashCacheFile.serializer(), HashCacheFile(cache.toMap()))) }
            .onFailure { SLog.w(TAG, "cannot write ${f.path}: ${it.message}") }
    }

    private fun warm(): Map<String, Warmup.Record> = warmCache?.let { Warmup.readCache(it) } ?: emptyMap()

    private fun merge(m: ModelManifest, w: Map<String, Warmup.Record>): ModelManifest {
        val r = w[m.id]?.takeIf { it.sha256 == m.sha256 && it.error == null } ?: return m
        return m.copy(firstCompileMs = if (r.firstCompileMs > 0) r.firstCompileMs else m.firstCompileMs,
            warmMs = if (r.warmMs > 0) r.warmMs else m.warmMs)
    }

    companion object {
        private const val TAG = "ModelRegistry"
        const val MANIFEST = "manifest.json"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }

        /** Parses and checks one manifest against its folder (no hashing). */
        fun parse(text: String, dir: File): Result<ModelManifest> = runCatching {
            val m = try { json.decodeFromString(ModelManifest.serializer(), text) } catch (e: Exception) {
                val msg = e.message ?: ""
                throw IllegalArgumentException(if ("licence" in msg) "no licence field (refused, §4A.2)" else "bad manifest: ${msg.lineSequence().first()}")
            }
            require(m.licence.isNotBlank()) { "blank licence (refused, §4A.2)" }
            require(m.id.isNotBlank()) { "blank id" }
            require(m.sha256.isNotBlank()) { "no sha256" }
            require(m.files.isNotEmpty()) { "no files listed" }
            val missing = m.files.filter { !File(dir, it).isFile }
            require(missing.isEmpty()) { "missing files: ${missing.joinToString()}" }
            m
        }

        /** The default roots, same as the voice/LLM models: Download/HiveCoreModels, then app-specific storage. */
        fun defaultRoots(context: Context): List<File> = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HiveCoreModels"),
            context.getExternalFilesDir(null)?.let { File(it, "models") },
        )

        fun create(context: Context): ModelRegistryImpl = ModelRegistryImpl(
            defaultRoots(context),
            hashCache = File(context.filesDir, "model_hashes.json"),
            warmCache = Warmup.defaultCacheFile(context),
        )
    }
}

@Serializable internal data class HashEntry(val length: Long, val mtime: Long, val sha256: String)
@Serializable internal data class HashCacheFile(val entries: Map<String, HashEntry> = emptyMap())

/** The manifest sha256 rule shared with tools/fetch_aihub_models.py. */
object ModelHash {
    fun file(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { s -> val buf = ByteArray(1 shl 20); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    fun combine(digests: List<String>): String = if (digests.size == 1) digests[0] else
        MessageDigest.getInstance("SHA-256").digest(digests.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun of(files: List<File>, cache: MutableMap<String, HashEntry>?): String = combine(files.map { f ->
        val key = f.absolutePath
        val hit = cache?.get(key)?.takeIf { it.length == f.length() && it.mtime == f.lastModified() }
        hit?.sha256 ?: file(f).also { cache?.put(key, HashEntry(f.length(), f.lastModified(), it)) }
    })

    fun of(files: List<File>): String = of(files, null)

    /**
     * The LLM bundle's identity for HELLO/PROFILE `modelHash` (§8.1): sha256 of genie_config.json's bytes plus every
     * other file's name and size (hashing the GB-sized parts would take seconds; their sizes pin the build).
     */
    fun llmBundle(dir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        File(dir, "genie_config.json").takeIf { it.isFile }?.let { md.update(it.readBytes()) }
        dir.listFiles { f -> f.isFile && f.name != "genie_config.json" }?.sortedBy { it.name }?.forEach { f ->
            md.update("\n${f.name}:${f.length()}".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
