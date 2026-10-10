package com.hivecore.node.devai

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.runtime.models.ManifestSpecs
import com.hivecore.runtime.models.OrtQnnRuntime
import com.hivecore.runtime.models.TensorDesc
import com.hivecore.runtime.models.TensorIo
import com.hivecore.runtime.models.TensorSession
import com.hivecore.node.textai.TextExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.math.ceil

/** Latency statistics over timed runs (ms). Percentiles are nearest-rank on the sorted times. */
data class BenchStats(val runs: Int, val p50: Double, val p95: Double, val mean: Double, val min: Double, val max: Double) {
    companion object {
        fun of(times: List<Double>): BenchStats {
            require(times.isNotEmpty()) { "no timed runs" }
            val s = times.sorted()
            return BenchStats(s.size, rank(s, 0.50), rank(s, 0.95), r3(s.sum() / s.size), r3(s.first()), r3(s.last()))
        }

        /** Nearest rank: the smallest value with at least [p] of the samples at or below it. */
        fun rank(sorted: List<Double>, p: Double): Double = r3(sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)])

        fun r3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    }
}

/**
 * BENCH (swarm plan §4A.3, §6.1): for each model of the shard, acquire it through the pool (load or warm hit), run
 * it once on seeded synthetic inputs (the §4A.5 spike convention, [TensorIo.seeded]: Random(7 + input index)), run it
 * again to check run-to-run bit-exactness, then [runs] timed runs. Records load, first-run (warm-up) and p50/p95
 * latency, the output hash (sha256 of the outputs' little-endian bytes in sorted name order, comparable across
 * phones) and the battery temperature before/after from [batteryTempC].
 *
 * Output: `{op:"BENCH", items:[{item, runtime, wasWarm, loadMs, warmMs, p50Ms, p95Ms, meanMs, runs, outSha, bitExact,
 * tempBeforeC, tempAfterC} | {item, error}], totals:{models, ok, failed, runs, ms}}`; totals are sums. Successful
 * items are merged into [store] (files/bench.json), whose [BenchStore.p50] feeds Profile.p50.
 *
 * Only tensor runtimes are benchmarked here; the GenieX LLM/VLM report tok/s through their own stats.
 */
class Bench(
    private val registry: ModelRegistry,
    private val pool: ModelPool,
    private val runtimeFor: (ModelManifest) -> ModelRuntime?,
    private val batteryTempC: () -> Float? = { null },
    private val nanoTime: () -> Long = System::nanoTime,
    private val store: BenchStore? = null,
) {
    suspend fun run(models: List<String>, runs: Int = DEFAULT_RUNS, graph: String? = null): JsonObject {
        require(runs >= 1) { "runs must be ≥ 1" }
        val items = models.map { id ->
            try { one(id, runs, graph) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                buildJsonObject { put("item", id); put("error", e.message ?: e.javaClass.simpleName) }
            }
        }
        val ok = items.filter { "error" !in it }
        store?.record(ok)
        return buildJsonObject {
            put("op", "BENCH")
            put("items", JsonArray(items))
            putJsonObject("totals") {
                put("models", items.size.toLong()); put("ok", ok.size.toLong()); put("failed", (items.size - ok.size).toLong())
                put("runs", ok.size.toLong() * runs)
                put("ms", ok.sumOf { (it["totalMs"] as JsonPrimitive).doubleOrNull ?: 0.0 }.let { Math.round(it) })
            }
        }
    }

    private suspend fun one(id: String, runs: Int, graph: String?): JsonObject {
        val m = registry.manifest(id) ?: throw IllegalArgumentException("model $id is not installed")
        val runtime = runtimeFor(m) ?: throw IllegalArgumentException("no tensor runtime for ${m.runtime}")
        val ort = runtime.id == OrtQnnRuntime.ID
        val wasWarm = id in pool.warm.value
        val tempBefore = batteryTempC()
        val t0 = nanoTime()
        val r = pool.with(id) { s ->
            val loadMs = ms(nanoTime() - t0)
            val ts = s as? TensorSession
            val descs = ts?.inputs(graph) ?: ManifestSpecs.parse(m.inputSpec).filter { graph == null || it.graph.isEmpty() || it.graph == graph }
            val feeds = LinkedHashMap<String, Any>()
            if (ts != null) feeds[TensorSession.GRAPH] = graph ?: ts.graphs.first()
            descs.forEachIndexed { i, d -> feeds[d.name] = seeded(d, i, ort) }
            val tw = nanoTime()
            val first = runtime.run(s, feeds).getOrThrow()
            val warmMs = ms(nanoTime() - tw)
            val second = runtime.run(s, feeds).getOrThrow()
            val times = (1..runs).map { val t = nanoTime(); runtime.run(s, feeds).getOrThrow(); ms(nanoTime() - t) }
            Measured(loadMs, warmMs, times, outputHash(first), outputHash(second))
        }.getOrThrow()
        val totalMs = ms(nanoTime() - t0)
        val tempAfter = batteryTempC()
        val st = BenchStats.of(r.times)
        return buildJsonObject {
            put("item", id); put("runtime", m.runtime); put("wasWarm", wasWarm)
            put("loadMs", r.loadMs); put("warmMs", r.warmMs)
            put("p50Ms", st.p50); put("p95Ms", st.p95); put("meanMs", st.mean); put("runs", st.runs)
            put("outSha", r.sha); put("bitExact", r.sha == r.sha2)
            put("tempBeforeC", tempBefore?.let { JsonPrimitive(BenchStats.r3(it.toDouble())) } ?: JsonNull)
            put("tempAfterC", tempAfter?.let { JsonPrimitive(BenchStats.r3(it.toDouble())) } ?: JsonNull)
            put("totalMs", totalMs)
        }
    }

    private class Measured(val loadMs: Double, val warmMs: Double, val times: List<Double>, val sha: String, val sha2: String)

    private fun ms(nanos: Long): Double = BenchStats.r3(nanos / 1_000_000.0)

    companion object {
        const val DEFAULT_RUNS = 10

        /** uint16 inputs go through the ORT adapter as real (float32) values, as the INFER hook does. */
        fun seeded(d: TensorDesc, idx: Int, ort: Boolean): ByteArray =
            TensorIo.seeded(if (ort && d.dtype == "uint16") d.copy(dtype = "float32") else d, idx, ort)

        fun outputHash(out: Map<String, Any>): String =
            TensorIo.sha256Hex(out.keys.sorted().map { k -> TextExecutor.toBytes(out.getValue(k), null).first })

        /** modelId → p50 ms from a BENCH output (the Profile.p50 shape). */
        fun p50Of(out: JsonElement): Map<String, Double> = ((out as? JsonObject)?.get("items") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o -> val id = (o["item"] as? JsonPrimitive)?.content; val p = (o["p50Ms"] as? JsonPrimitive)?.doubleOrNull
                if (id != null && p != null) id to p else null }
            .toMap()
    }
}

/**
 * `files/bench.json`: `{"models": {modelId: <BENCH item>}}`, newest record per model, keys sorted. Reads and writes
 * go through the injected functions so tests need no files; [file] binds it to a real path.
 */
class BenchStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { prettyPrint = true }

    @Synchronized fun records(): Map<String, JsonObject> = runCatching {
        read()?.let { Json.parseToJsonElement(it).jsonObject["models"]?.jsonObject?.mapValues { e -> e.value.jsonObject } }
    }.getOrNull() ?: emptyMap()

    @Synchronized fun record(items: List<JsonObject>) {
        if (items.isEmpty()) return
        val all = java.util.TreeMap(records())
        for (o in items) (o["item"] as? JsonPrimitive)?.content?.let { all[it] = o }
        write(json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("models" to JsonObject(all)))))
    }

    /** modelId → measured p50 ms, for Profile.p50 (§6.1). */
    fun p50(): Map<String, Double> = records().mapNotNull { (k, v) -> (v["p50Ms"] as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()

    companion object {
        const val FILE_NAME = "bench.json"
        fun file(f: File): BenchStore = BenchStore({ f.takeIf { it.isFile }?.readText() }, { f.parentFile?.mkdirs(); f.writeText(it) })
    }
}
