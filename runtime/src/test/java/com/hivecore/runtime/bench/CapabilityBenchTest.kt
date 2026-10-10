package com.hivecore.runtime.bench

import com.hivecore.core.ModelEntry
import com.hivecore.core.Profile
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.fakes.FakeCapabilityPool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BENCH over fakes: p50 per model lands in the table, the table decorates the profile (spec 4.6). */
class CapabilityBenchTest {
    private fun e(id: String, cap: String, runtime: String = "litert_qnn", big: Boolean = false) = ModelEntry(id, cap, runtime, "unpinned", 1, 300, 40, 0, big)
    private val llm = e("qwen-npu", "llm.json", "qairt", true)
    private val vlm = e("qwen3vl", "vlm", "qairt", true)
    private val det = e("yolo", "detect")
    private val emb = e("minilm", "embed")
    private val ocr = e("mlkit", "ocr", "mlkit")
    private val catalog = ModelCatalog(listOf(llm, vlm, det, emb, ocr))

    /** A fake clock: every unit of [id] costs the scripted ms; the LLM's units vary so p50 is a real median. */
    private class Clock {
        var nowNs = 0L
        val times = HashMap<String, ArrayDeque<Long>>()
        fun unit(id: String, vararg ms: Long) = times.getOrPut(id) { ArrayDeque() }.addAll(ms.toList())
        fun workloads() = UnitWorkloads { entry -> if (entry.modelId in times) ({ nowNs += times.getValue(entry.modelId).removeFirst() * 1_000_000 }) else null }
    }

    @Test fun recordsP50PerModelAndDecoratesTheProfile() = runTest {
        val clock = Clock()
        clock.unit("yolo", 90, 40, 41, 39, 42, 40)             // warm-up 90, then five timed runs
        clock.unit("qwen-npu", 9000, 4100, 3900, 4000, 4300, 3800)
        val pool = FakeCapabilityPool(catalog)
        pool.loadMs["qwen-npu"] = 6000
        val table = BenchTable.memory()
        val bench = CapabilityBench(catalog, pool, clock.workloads(), table, installed = { setOf("yolo", "qwen-npu", "qwen3vl") },
            clock = { 1_000L }, nanoTime = { clock.nowNs })
        val entries = bench.installedEntries()
        assertEquals(listOf("yolo", "mlkit", "qwen-npu", "qwen3vl"), entries.map { it.modelId })   // small first, big last

        val out = bench.run(entries, runs = 5)
        val byId = out.associateBy { it.modelId }
        assertEquals(40.0, byId.getValue("yolo").p50Ms!!, 1e-9)
        assertEquals(42.0, byId.getValue("yolo").p95Ms!!, 1e-9)
        assertEquals(5, byId.getValue("yolo").runs)
        assertEquals(4000.0, byId.getValue("qwen-npu").p50Ms!!, 1e-9)
        assertEquals(6000L, byId.getValue("qwen-npu").loadMs)
        assertNull(byId.getValue("qwen3vl").p50Ms)                 // no unit workload: load only
        assertNull(byId.getValue("qwen3vl").error)
        assertTrue(byId.getValue("mlkit").warm)
        assertEquals(mapOf("qwen-npu" to 4000.0, "yolo" to 40.0), table.p50(catalog))
        val p = table.decorate(Profile("n", 1), catalog)
        assertEquals(4000.0, p.p50["qwen-npu"]!!, 1e-9)
        assertEquals(listOf("detect/yolo/true", "llm.json/qwen-npu/false", "ocr/mlkit/true", "vlm/qwen3vl/true"),
            CapabilityBench.capabilityStates(catalog, setOf("yolo", "qwen-npu", "qwen3vl"), pool).map { "${it.capability}/${it.modelId}/${it.warm}" })
    }

    @Test fun failuresAreRecordedWithTheirClassAndStaleShaIsIgnored() = runTest {
        val pool = FakeCapabilityPool(catalog)
        pool.fail["yolo"] = "oom"
        val table = BenchTable.memory()
        val bench = CapabilityBench(catalog, pool, { null }, table, clock = { 5L })
        val out = bench.run(listOf(det, emb), runs = 1)
        assertTrue(out[0].error!!.startsWith("oom"))
        assertNull(out[1].error)
        table.record(listOf(BenchRecord("embed", "minilm", "f".repeat(64), "litert_qnn", 1, false, p50Ms = 9.0, runs = 1, ts = 1)))
        assertTrue(table.p50(catalog).isEmpty())                   // sha no longer in the catalog: not advertised
        assertEquals(mapOf("minilm" to 9.0), table.p50())
    }

    @Test fun stopsBetweenModelsWhenAskedAndRejectsZeroRuns() = runTest {
        val pool = FakeCapabilityPool(catalog)
        var budget = 1
        val out = CapabilityBench(catalog, pool, { null }).run(listOf(det, emb), runs = 1, shouldContinue = { budget-- > 0 })
        assertEquals(listOf("yolo"), out.map { it.modelId })
        assertTrue(runCatching { CapabilityBench(catalog, pool, { null }).run(listOf(det), runs = 0) }.isFailure)
        assertEquals(40.0, CapabilityBench.rank(listOf(39.0, 40.0, 41.0), 0.5), 1e-9)
        assertEquals(41.0, CapabilityBench.rank(listOf(39.0, 40.0, 41.0), 0.95), 1e-9)
    }
}
