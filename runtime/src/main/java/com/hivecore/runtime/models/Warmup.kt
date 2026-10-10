package com.hivecore.runtime.models

import android.content.Context
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.SLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Compiles every installed small model once (swarm plan §4A.2: the QNN delegate compiles a `.tflite` graph on the
 * phone the first time — 30 ms to 1.5 s for most, 16 s for CLIP, 2 min for D-FINE — and caches it in
 * `filesDir/qnn_cache`). Meant to run while the phone is charging; the caller checks that ([shouldContinue] is
 * polled between models so unplugging stops it).
 *
 * Per model: load (= first compile, recorded as `firstCompileMs`), close, load again (= cached init, `warmMs`),
 * close. Results go to [cacheFile] keyed by model id with the model's sha256, so a changed model is warmed again;
 * [ModelRegistryImpl] merges them into the manifests it returns. GenieX models (big slot) are skipped: loading
 * one would evict the LLM.
 */
class Warmup(
    private val registry: ModelRegistry,
    private val runtimes: List<ModelRuntime>,
    private val cacheFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable data class Record(val sha256: String, val firstCompileMs: Long, val warmMs: Long, val ts: Long, val error: String? = null)

    fun records(): Map<String, Record> = readCache(cacheFile)

    /** The models that still need a warmup (never warmed, changed, or failed last time). */
    fun pending(): List<ModelManifest> {
        val done = records()
        return registry.installed().filter { m -> !m.runtime.startsWith("geniex") && done[m.id]?.let { it.sha256 != m.sha256 || it.error != null } ?: true }
    }

    suspend fun run(force: Boolean = false, shouldContinue: () -> Boolean = { true }): Map<String, Record> {
        val records = records().toMutableMap()
        val todo = if (force) registry.installed().filter { !it.runtime.startsWith("geniex") } else pending()
        for (m in todo) {
            if (!shouldContinue()) { SLog.d(TAG, "stopped before ${m.id}"); break }
            val runtime = runtimes.firstOrNull { it.supports(m) }
            val dir = registry.dir(m.id)
            records[m.id] = if (runtime == null || dir == null) Record(m.sha256, 0, 0, clock(), "no runtime/folder for ${m.runtime}")
            else warmOne(m, runtime, dir)
            SLog.d(TAG, "${m.id}: ${records[m.id]}")
            writeCache(records)
        }
        return records
    }

    private suspend fun warmOne(m: ModelManifest, runtime: ModelRuntime, dir: File): Record {
        val t0 = clock()
        val first = runtime.load(m, dir)
        val firstMs = clock() - t0
        first.exceptionOrNull()?.let { return Record(m.sha256, firstMs, 0, clock(), it.message ?: it.javaClass.simpleName) }
        runCatching { first.getOrThrow().close() }
        val t1 = clock()
        val second = runtime.load(m, dir)
        val warmMs = clock() - t1
        second.getOrNull()?.let { s -> runCatching { s.close() } }
        return Record(m.sha256, firstMs, warmMs, clock(), second.exceptionOrNull()?.message)
    }

    private fun writeCache(records: Map<String, Record>) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer, records))
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        }.onFailure { SLog.w(TAG, "cannot write ${cacheFile.path}: ${it.message}") }
    }

    companion object {
        private const val TAG = "Warmup"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        private val serializer = MapSerializer(String.serializer(), Record.serializer())

        fun defaultCacheFile(context: Context) = File(context.filesDir, "model_warmup.json")

        fun readCache(f: File): Map<String, Record> =
            if (!f.isFile) emptyMap() else runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyMap())
    }
}
