package com.hivecore.runtime.bench

import com.hivecore.core.CapabilityState
import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.runtime.catalog.BigKind
import com.hivecore.runtime.catalog.ErrorClasses
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.models.CapabilityPool
import com.hivecore.runtime.models.ManifestSpecs
import com.hivecore.runtime.models.OrtQnnRuntime
import com.hivecore.runtime.models.TensorIo
import com.hivecore.runtime.models.TensorSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.ceil

/** One BENCH result per model: what [Profile.p50] and [Profile.capabilities] are built from. */
@Serializable data class BenchRecord(
    val capability: String,
    val modelId: String,
    val sha256: String,
    val runtime: String,
    /** Wall time of the load through the pool (0 when the model was already warm). */
    val loadMs: Long,
    val warm: Boolean,
    /** Measured ms per unit (one LLM call, one tensor run); null when the model has no unit workload (VLM, built-ins). */
    val p50Ms: Double? = null,
    val p95Ms: Double? = null,
    val runs: Int = 0,
    val ts: Long,
    val error: String? = null,
)

/**
 * `files/catalog_bench.json`: the newest [BenchRecord] per model id, keyed and sorted by model id. Reads and writes
 * go through the injected functions so tests need no files; [file] binds it to a real path. [p50] is what the
 * profile advertises and the placement solver reads back (`Profile.p50[modelId]`, spec 8.1): only successful,
 * measured records whose sha256 still matches the catalog count.
 */
class BenchTable(private val read: () -> String?, private val write: (String) -> Unit) {
    @Serializable private data class FileShape(val models: Map<String, BenchRecord> = emptyMap())

    @Synchronized fun records(): Map<String, BenchRecord> =
        runCatching { read()?.let { json.decodeFromString(FileShape.serializer(), it).models } }.getOrNull() ?: emptyMap()

    @Synchronized fun record(records: List<BenchRecord>) {
        if (records.isEmpty()) return
        val all = java.util.TreeMap(records())
        for (r in records) all[r.modelId] = r
        write(json.encodeToString(FileShape.serializer(), FileShape(all)))
    }

    /** modelId → measured p50 ms (spec 4.6 `capabilities[].p50`, carried in `Profile.p50`). */
    fun p50(catalog: ModelCatalog? = null): Map<String, Double> = records().values
        .filter { it.error == null && it.p50Ms != null && (catalog == null || catalog.entriesOf(it.modelId).any { e -> e.sha256 == it.sha256 }) }
        .associate { it.modelId to it.p50Ms!! }

    /** Adds this table's p50 figures to a profile (a `decorateProfile` step; see WorkerPolicyImpl). */
    fun decorate(profile: Profile, catalog: ModelCatalog? = null): Profile = profile.copy(p50 = profile.p50 + p50(catalog))

    companion object {
        const val FILE_NAME = "catalog_bench.json"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        fun file(f: File): BenchTable = BenchTable({ f.takeIf { it.isFile }?.readText() }, { f.parentFile?.mkdirs(); f.writeText(it) })
        fun memory(): BenchTable { var s: String? = null; return BenchTable({ s }, { s = it }) }
    }
}

/**
 * BENCH (spec 4.3, phase 2 gate): for each catalog entry installed on this phone, load it through the
 * [CapabilityPool] (`ensure`, so the big slot swaps exactly as a placement would), run one warm-up unit, then
 * [runs] timed units, and record the load time and the p50/p95 unit time into the [BenchTable] the profile is
 * decorated from. The unit per entry comes from [workloads] (one LLM call for `llm.*`, one seeded tensor run for
 * the registry models, none for the VLM and the built-ins, whose records carry the load time only), so the bench
 * itself is JVM-testable with fakes. [shouldContinue] is polled between models: the phase 2 gate runs this on the
 * charger and unplugging stops it. p50 is the nearest-rank percentile, as in the node's BENCH op.
 */
class CapabilityBench(
    private val catalog: ModelCatalog,
    private val pool: CapabilityPool,
    private val workloads: UnitWorkloads,
    private val table: BenchTable? = null,
    private val installed: () -> Set<String> = { emptySet() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Benchmarks [entries] (default: every installed entry, one per model id, big models last so small ones stay warm). */
    suspend fun run(entries: List<ModelEntry> = installedEntries(), runs: Int = DEFAULT_RUNS, shouldContinue: () -> Boolean = { true }): List<BenchRecord> {
        require(runs >= 1) { "runs must be at least 1" }
        val out = ArrayList<BenchRecord>()
        for (e in entries) {
            if (!shouldContinue()) { SLog.d(TAG, "stopped before ${e.modelId}"); break }
            val r = one(e, runs)
            SLog.d(TAG, "${e.modelId}: load ${r.loadMs} ms, p50 ${r.p50Ms ?: "-"} ms${r.error?.let { " ($it)" } ?: ""}")
            out += r
        }
        table?.record(out)
        return out
    }

    /** Catalog entries this phone can load: the registry's models, the GenieX LLM ids and the built-ins; small first. */
    fun installedEntries(): List<ModelEntry> {
        val have = installed()
        return catalog.entries.filter { ModelCatalog.isBuiltIn(it.runtime) || it.modelId in have }
            .distinctBy { it.modelId }
            .sortedWith(compareBy<ModelEntry> { it.big }.thenBy { it.capability }.thenBy { it.modelId })
    }

    suspend fun one(e: ModelEntry, runs: Int): BenchRecord {
        val load = pool.ensure(e).getOrElse { t ->
            return BenchRecord(e.capability, e.modelId, e.sha256, e.runtime, 0, false, ts = clock(), error = "${ErrorClasses.of(t)}: ${t.message}")
        }
        val unit = workloads.unitOf(e) ?: return BenchRecord(e.capability, load.modelId, e.sha256, load.runtime, load.loadMs, load.warm, ts = clock())
        return try {
            unit()                                             // warm-up: first run after a load compiles / fills caches
            val times = (1..runs).map { val t = nanoTime(); unit(); (nanoTime() - t) / 1_000_000.0 }
            val sorted = times.sorted()
            BenchRecord(e.capability, load.modelId, e.sha256, load.runtime, load.loadMs, load.warm,
                p50Ms = rank(sorted, 0.50), p95Ms = rank(sorted, 0.95), runs = runs, ts = clock())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            BenchRecord(e.capability, load.modelId, e.sha256, load.runtime, load.loadMs, load.warm, ts = clock(), error = "unit: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_RUNS = 5
        private const val TAG = "Bench"

        /** Nearest rank: the smallest value with at least [p] of the samples at or below it, rounded to 3 decimals. */
        fun rank(sorted: List<Double>, p: Double): Double =
            Math.round(sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)] * 1000.0) / 1000.0

        /** The `capabilities[]` a profile advertises, from the catalog, the installed set and the pool's residency. */
        fun capabilityStates(catalog: ModelCatalog, installed: Set<String>, pool: CapabilityPool): List<CapabilityState> =
            catalog.capabilitiesOf(installed, pool.warm(), pool.bigModel)
    }
}

/** What one "unit" of a catalog entry is on this phone, for timing; null = nothing to time (load only). */
fun interface UnitWorkloads {
    fun unitOf(entry: ModelEntry): (suspend () -> Unit)?

    companion object {
        /**
         * The real workloads: `llm.*` = one short generation on the loaded LLM; registry tensor models = one run on
         * seeded inputs through the pool's session (the node's BENCH convention, `Random(7 + input index)`); the VLM
         * and the built-ins have no unit here.
         */
        fun default(pool: CapabilityPool, registry: ModelRegistry?, runtimeFor: (ModelManifest) -> ModelRuntime?, llm: LlmEngine?): UnitWorkloads =
            UnitWorkloads { e ->
                when {
                    BigKind.of(e.capability) == BigKind.LLM -> llm?.let { engine ->
                        suspend { engine.generate(listOf(ChatMsg("user", BENCH_PROMPT)), GenParams(maxTokens = BENCH_TOKENS)).getOrThrow(); Unit }
                    }
                    ModelCatalog.isBuiltIn(e.runtime) || BigKind.of(e.capability) == BigKind.VLM -> null
                    else -> {
                        val m = registry?.manifest(e.modelId) ?: return@UnitWorkloads null
                        val rt = runtimeFor(m) ?: return@UnitWorkloads null
                        val ort = rt.id == OrtQnnRuntime.ID
                        suspend {
                            pool.with(e.modelId) { s ->
                                val ts = s as? TensorSession
                                val descs = ts?.inputs(null) ?: ManifestSpecs.parse(m.inputSpec).filter { it.graph.isEmpty() }
                                val feeds = LinkedHashMap<String, Any>()
                                if (ts != null) feeds[TensorSession.GRAPH] = ts.graphs.first()
                                descs.forEachIndexed { i, d -> feeds[d.name] = TensorIo.seeded(if (ort && d.dtype == "uint16") d.copy(dtype = "float32") else d, i, ort) }
                                rt.run(s, feeds).getOrThrow()
                            }.getOrThrow()
                            Unit
                        }
                    }
                }
            }

        const val BENCH_PROMPT = "Reply with the single word ready."
        const val BENCH_TOKENS = 32
    }
}
