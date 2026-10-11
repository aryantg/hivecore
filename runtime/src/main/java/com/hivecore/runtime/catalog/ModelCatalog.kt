package com.hivecore.runtime.catalog

import com.hivecore.core.CapabilityState
import com.hivecore.core.ModelEntry
import com.hivecore.core.SLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The big-slot kind a capability needs (spec 4.3): `llm` for `llm.*`, `vlm` for `vlm`, `gen` for the HEAVY
 * diffusion / super-resolution bundles, `none` for the small models that stay warm beside the big one. A build with
 * `big = false` never claims the slot whatever its capability (a 2 MB super-resolution network is a warm model).
 */
object BigKind {
    const val LLM = "llm"
    const val VLM = "vlm"
    const val GEN = "gen"
    const val NONE = "none"

    fun of(capability: String): String = when {
        capability == "llm" || capability.startsWith("llm.") -> LLM
        capability == VLM -> VLM
        capability == "gen.image" || capability == "superres" || capability == "upscale" -> GEN
        else -> NONE
    }

    /** The slot kind [entry] takes when loaded: its capability's kind when `big`, else `none`. */
    fun of(entry: ModelEntry): String = if (entry.big) of(entry.capability) else NONE
}

/**
 * The capability catalog (spec 4.3, `models/catalog.json`): one [ModelEntry] per (model, capability). A plan names
 * a capability; placement resolves it to a model id; replicas compare only outputs from the same sha256 (P12).
 * Entries of one capability are ordered `qairt` first, then `llama_cpp`, then the tensor runtimes, then by model id,
 * so "the preferred build" is the same on every phone.
 *
 * [validate] is what [parse] runs: a catalog with a problem is refused as a whole, so a phone never runs with half
 * a catalog. [ModelCatalogLoader] picks between the shipped asset and a newer file in the app's files dir.
 */
class ModelCatalog(entries: List<ModelEntry>, val version: String = "") {
    val entries: List<ModelEntry> = entries.sortedWith(
        compareBy<ModelEntry> { it.capability }.thenBy { runtimeRank(it.runtime) }.thenBy { it.modelId })

    val capabilities: Set<String> get() = entries.map { it.capability }.toSortedSet()

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Entries for [capability], preferred first (qairt, then llama_cpp, then the rest). */
    fun forCapability(capability: String): List<ModelEntry> = entries.filter { it.capability == capability }

    /** The preferred build for [capability] (null = not in the catalog). */
    fun preferred(capability: String): ModelEntry? = forCapability(capability).firstOrNull()

    /** The entry of [modelId] for [capability] (or any capability when null). */
    fun entry(modelId: String, capability: String? = null): ModelEntry? =
        entries.firstOrNull { it.modelId == modelId && (capability == null || it.capability == capability) }

    /** Every entry naming [modelId] (a model may serve several capabilities, e.g. `llm.text` and `llm.json`). */
    fun entriesOf(modelId: String): List<ModelEntry> = entries.filter { it.modelId == modelId }

    /** The `llama_cpp` build to try once after a `qairt` failure of [after] (spec 8.3), if the catalog has one. */
    fun fallback(capability: String, after: ModelEntry): ModelEntry? =
        if (after.runtime != RUNTIME_QAIRT) null
        else forCapability(capability).firstOrNull { it.runtime == RUNTIME_LLAMA && it.modelId != after.modelId }

    /** Catalog `unitMsP50` of [modelId]; null when unknown. */
    fun unitMsP50(modelId: String?): Double? = modelId?.let { id -> entries.firstOrNull { it.modelId == id }?.unitMsP50?.toDouble() }

    /**
     * `Profile.capabilities` (spec 4.6): the entries whose model is [installed] here (the registry's ids plus the
     * GenieX LLM ids, which live outside the registry; built-in runtimes count as installed), each flagged warm when
     * the model is resident: the big slot holds it ([bigModel]) or it is in the pool's [warm] set.
     */
    fun capabilitiesOf(installed: Set<String>, warm: Set<String>, bigModel: String?): List<CapabilityState> =
        entries.filter { e -> isBuiltIn(e.runtime) || e.modelId in installed }.map { e ->
            val resident = e.modelId == bigModel || e.modelId in warm || isBuiltIn(e.runtime)
            CapabilityState(e.capability, e.modelId, e.sha256, resident)
        }

    @Serializable private data class CatalogFile(val version: String = "", val models: List<ModelEntry> = emptyList())

    companion object {
        const val RUNTIME_QAIRT = "qairt"
        const val RUNTIME_LLAMA = "llama_cpp"
        const val RUNTIME_LITERT = "litert_qnn"
        const val RUNTIME_ORT = "ort_qnn"
        /** ML Kit and pure-Kotlin ops carry a catalog entry so plans can name them, but there is nothing to load. */
        const val RUNTIME_MLKIT = "mlkit"
        const val RUNTIME_KOTLIN = "kotlin"
        val RUNTIMES = setOf(RUNTIME_QAIRT, RUNTIME_LLAMA, RUNTIME_LITERT, RUNTIME_ORT, RUNTIME_MLKIT, RUNTIME_KOTLIN)
        /** A sha256 the BENCH sweep on real phones has not pinned yet; placement still works, replica checks warn. */
        const val UNPINNED = "unpinned"
        const val ASSET = "models/catalog.json"
        /** The thirteen capabilities of spec 4.3 that every shipped catalog must cover. */
        val REQUIRED_CAPABILITIES = setOf("llm.text", "llm.json", "vlm", "asr", "ocr", "embed", "detect", "segment", "classify",
            "iqa", "gen.image", "superres", "upscale")
        val EMPTY = ModelCatalog(emptyList())
        private const val TAG = "ModelCatalog"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun isBuiltIn(runtime: String): Boolean = runtime == RUNTIME_MLKIT || runtime == RUNTIME_KOTLIN

        private fun runtimeRank(r: String): Int = when (r) {
            RUNTIME_QAIRT -> 0; RUNTIME_LLAMA -> 1; RUNTIME_LITERT, RUNTIME_ORT -> 2; else -> 3
        }

        fun isHex64(s: String): Boolean = s.length == 64 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        /** An absolute `http://` or `https://` URL with a host (what [ModelEntry.url] may hold). */
        fun isHttpUrl(s: String): Boolean = runCatching {
            val u = java.net.URI(s)
            (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
        }.getOrDefault(false)

        /**
         * The problems with [entries] (empty = valid): blank ids, an unknown runtime, a sha256 that is neither 64 hex
         * digits nor [UNPINNED], negative sizes, non-positive load or unit times, a duplicate (model, capability), a
         * `big` build for a capability with no big-slot kind, a model listed under two runtimes, a `url` that is not
         * http(s), and a `downloadSha256` that is not 64 hex digits.
         */
        fun validate(entries: List<ModelEntry>): List<String> {
            val problems = ArrayList<String>()
            val seen = HashSet<Pair<String, String>>()
            val runtimeOf = HashMap<String, String>()
            for (e in entries) {
                val at = "${e.modelId.ifBlank { "?" }}/${e.capability.ifBlank { "?" }}"
                if (e.modelId.isBlank()) problems += "$at: blank modelId"
                if (e.capability.isBlank()) problems += "$at: blank capability"
                if (e.runtime !in RUNTIMES) problems += "$at: unknown runtime '${e.runtime}'"
                if (e.sha256 != UNPINNED && !isHex64(e.sha256)) problems += "$at: sha256 must be 64 hex digits or '$UNPINNED'"
                if (e.sizeBytes < 0) problems += "$at: negative sizeBytes"
                if (e.loadMsP50 <= 0) problems += "$at: loadMsP50 must be positive"
                if (e.unitMsP50 <= 0) problems += "$at: unitMsP50 must be positive"
                if (e.maxContext < 0) problems += "$at: negative maxContext"
                if (e.big && BigKind.of(e.capability) == BigKind.NONE) problems += "$at: big build for a capability with no big-slot kind"
                e.url?.let { if (!isHttpUrl(it)) problems += "$at: url must be http(s)" }
                e.downloadSha256?.let { if (!isHex64(it)) problems += "$at: downloadSha256 must be 64 hex digits" }
                e.calibration?.problems()?.forEach { problems += "$at: $it" }
                if (!seen.add(e.modelId to e.capability)) problems += "$at: duplicate (modelId, capability)"
                val prev = runtimeOf.put(e.modelId, e.runtime)
                if (prev != null && prev != e.runtime) problems += "$at: model listed under runtimes '$prev' and '${e.runtime}'"
            }
            return problems
        }

        /** Parses `{version, models: [ModelEntry…]}` or a bare array; throws [IllegalArgumentException] when invalid. */
        fun parse(text: String): ModelCatalog {
            val t = text.trim()
            val (version, models) = if (t.startsWith("[")) "" to json.decodeFromString<List<ModelEntry>>(t)
            else json.decodeFromString<CatalogFile>(t).let { it.version to it.models }
            val problems = validate(models)
            require(problems.isEmpty()) { "invalid catalog: " + problems.joinToString("; ") }
            return ModelCatalog(models, version)
        }

        /** [parse] as a [Result] (a bad file is a reason to fall back, not to crash). */
        fun tryParse(text: String): Result<ModelCatalog> = runCatching { parse(text) }

        /** Loads [ASSET] through [open] (e.g. `{ context.assets.open(it).bufferedReader().readText() }`); empty on any error. */
        fun load(open: (String) -> String): ModelCatalog =
            runCatching { parse(open(ASSET)) }.getOrElse { SLog.w(TAG, "catalog asset refused: ${it.message}"); EMPTY }
    }
}

/**
 * Picks the catalog a phone runs with: the asset shipped in the APK unless a valid file at [override] carries a
 * version at least as new (spec 4.3 "shipped with the app and updatable"). Versions compare by their numeric
 * fields (`catalog-3` > `catalog-2`, `2026.10.1` > `2026.9.12`); an unparsable or malformed override is ignored with
 * a log line, never trusted over the asset. On Android: `ModelCatalogLoader.android(context).load()`.
 */
class ModelCatalogLoader(
    private val asset: () -> String?,
    private val override: File? = null,
) {
    /** The asset catalog, the override, and which one won. */
    data class Loaded(val catalog: ModelCatalog, val source: String)

    fun load(): ModelCatalog = loadDetailed().catalog

    fun loadDetailed(): Loaded {
        val shipped = asset()?.let { ModelCatalog.tryParse(it).onFailure { e -> SLog.w(TAG, "catalog asset refused: ${e.message}") }.getOrNull() }
        val file = override?.takeIf { it.isFile }?.let { f ->
            runCatching { ModelCatalog.parse(f.readText()) }.onFailure { e -> SLog.w(TAG, "catalog override ${f.path} refused: ${e.message}") }.getOrNull()
        }
        return when {
            file != null && (shipped == null || compareVersions(file.version, shipped.version) >= 0) -> Loaded(file, SOURCE_FILE)
            shipped != null -> Loaded(shipped, SOURCE_ASSET)
            else -> Loaded(ModelCatalog.EMPTY, SOURCE_NONE)
        }
    }

    /** Writes a downloaded catalog as the override after validating it; returns the problems when refused. */
    fun install(text: String): Result<ModelCatalog> = ModelCatalog.tryParse(text).onSuccess {
        val f = override ?: return@onSuccess
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    companion object {
        const val SOURCE_ASSET = "asset"
        const val SOURCE_FILE = "file"
        const val SOURCE_NONE = "none"
        const val OVERRIDE_PATH = "models/catalog.json"
        private const val TAG = "ModelCatalog"

        /** Numeric-field comparison: equal-length prefixes of digits compare as numbers, the rest as text. */
        fun compareVersions(a: String, b: String): Int {
            val na = Regex("\\d+").findAll(a).map { it.value.toLong() }.toList()
            val nb = Regex("\\d+").findAll(b).map { it.value.toLong() }.toList()
            for (i in 0 until maxOf(na.size, nb.size)) {
                val x = na.getOrNull(i) ?: return -1
                val y = nb.getOrNull(i) ?: return 1
                if (x != y) return x.compareTo(y)
            }
            return a.compareTo(b)
        }

        fun android(context: android.content.Context): ModelCatalogLoader = ModelCatalogLoader(
            asset = { runCatching { context.assets.open(ModelCatalog.ASSET).bufferedReader().readText() }.getOrNull() },
            override = File(context.filesDir, OVERRIDE_PATH),
        )
    }
}
