package com.hivecore.node.devai

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.Op
import com.hivecore.core.Profile
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.core.Canon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BenchTest {

    @Test fun statsMath() {
        val s = BenchStats.of(listOf(5.0, 1.0, 9.0, 3.0, 7.0, 2.0, 8.0, 4.0, 6.0, 10.0))
        assertEquals(10, s.runs)
        assertEquals(5.0, s.p50, 0.0)       // nearest rank: ceil(0.5 * 10) = 5th
        assertEquals(10.0, s.p95, 0.0)      // ceil(0.95 * 10) = 10th
        assertEquals(5.5, s.mean, 0.0)
        assertEquals(1.0, s.min, 0.0); assertEquals(10.0, s.max, 0.0)
        val t = BenchStats.of((1..100).map { it.toDouble() })
        assertEquals(50.0, t.p50, 0.0); assertEquals(95.0, t.p95, 0.0)
        val one = BenchStats.of(listOf(3.25))
        assertEquals(3.25, one.p50, 0.0); assertEquals(3.25, one.p95, 0.0)
    }

    private val manifest = ModelManifest(
        id = "tiny", task = "classify", runtime = "litert_qnn", files = listOf("tiny.tflite"), sha256 = "00", licence = "test",
        inputSpec = Json.parseToJsonElement("""{"image": {"shape": [1, 4], "dtype": "float32"}, "ids": {"shape": [1, 3], "dtype": "int32"}}""").jsonObject,
    )

    /** Virtual time: a load costs [loadMs], run k costs durations[k] ms; outputs are a digest of the inputs. */
    private class TimedRuntime(val durations: List<Long>, val loadMs: Long) : ModelRuntime {
        var nowNs = 0L
        var runs = 0
        val seen = mutableListOf<Map<String, Any>>()
        override val id = "litert_qnn"
        override fun supports(m: ModelManifest) = true
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> {
            nowNs += loadMs * 1_000_000
            return Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
        }
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> {
            nowNs += durations[runs++ % durations.size] * 1_000_000
            seen += inputs
            val sum = inputs.values.sumOf { (it as ByteArray).sumOf { b -> b.toInt() and 0xff } }
            return Result.success(mapOf("logits" to FloatArray(2) { sum.toFloat() + it }, "argmax" to IntArray(1) { sum % 7 }))
        }
    }

    private fun setup(): Triple<TimedRuntime, Bench, StringBuilder> {
        // first run 40 ms (warm-up), second 6 ms (bit-exact check), then ten timed runs
        val rt = TimedRuntime(listOf(40L, 6L, 5, 1, 9, 3, 7, 2, 8, 4, 6, 10), loadMs = 120)
        val reg = FakeModelRegistry(listOf(manifest))
        val temps = ArrayDeque(listOf(31.5f, 33.0f))
        val file = StringBuilder()
        val store = BenchStore({ file.toString().ifEmpty { null } }, { file.setLength(0); file.append(it) })
        val bench = Bench(reg, FakeModelPool(reg, rt), { rt }, { temps.removeFirstOrNull() }, { rt.nowNs }, store)
        return Triple(rt, bench, file)
    }

    @Test fun benchMeasuresWithFakeRuntime() = runBlocking {
        val (rt, bench, file) = setup()
        val out = bench.run(listOf("tiny", "missing"), runs = 10)
        val item = out["items"]!!.jsonArray[0].jsonObject
        assertEquals("tiny", item["item"]!!.jsonPrimitive.content)
        assertEquals(false, item["wasWarm"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(120.0, item["loadMs"]!!.jsonPrimitive.double, 0.0)
        assertEquals(40.0, item["warmMs"]!!.jsonPrimitive.double, 0.0)
        assertEquals(5.0, item["p50Ms"]!!.jsonPrimitive.double, 0.0)
        assertEquals(10.0, item["p95Ms"]!!.jsonPrimitive.double, 0.0)
        assertEquals(5.5, item["meanMs"]!!.jsonPrimitive.double, 0.0)
        assertEquals(10L, item["runs"]!!.jsonPrimitive.long)
        assertEquals(true, item["bitExact"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(31.5, item["tempBeforeC"]!!.jsonPrimitive.double, 0.0)
        assertEquals(33.0, item["tempAfterC"]!!.jsonPrimitive.double, 0.0)
        assertEquals(120.0 + 40 + 6 + 55, item["totalMs"]!!.jsonPrimitive.double, 0.0)
        assertEquals(12, rt.runs)
        // seeded inputs per the manifest spec: float32 1x4 = 16 bytes, int32 1x3 = 12 bytes, identical every run
        assertEquals(16, (rt.seen[0]["image"] as ByteArray).size)
        assertEquals(12, (rt.seen[0]["ids"] as ByteArray).size)
        assertTrue(rt.seen.all { (it["image"] as ByteArray).contentEquals(rt.seen[0]["image"] as ByteArray) })

        val missing = out["items"]!!.jsonArray[1].jsonObject
        assertTrue(missing["error"]!!.jsonPrimitive.content.contains("not installed"))
        val totals = out["totals"]!!.jsonObject
        assertEquals(2L, totals["models"]!!.jsonPrimitive.long)
        assertEquals(1L, totals["ok"]!!.jsonPrimitive.long)
        assertEquals(1L, totals["failed"]!!.jsonPrimitive.long)
        assertEquals(10L, totals["runs"]!!.jsonPrimitive.long)
        assertEquals(221L, totals["ms"]!!.jsonPrimitive.long)

        // persisted to bench.json; p50 feeds the profile
        val store = BenchStore({ file.toString() }, { })
        assertEquals(mapOf("tiny" to 5.0), store.p50())
        assertEquals(mapOf("tiny" to 5.0), Bench.p50Of(out))
        val profile = Profile(nodeId = "n1", ts = 0, p50 = store.p50())
        assertEquals(5.0, profile.p50["tiny"]!!, 0.0)
    }

    @Test fun outputHashIsDeterministic() = runBlocking {
        val a = setup().second.run(listOf("tiny")).let { it["items"]!!.jsonArray[0].jsonObject["outSha"]!!.jsonPrimitive.content }
        val b = setup().second.run(listOf("tiny")).let { it["items"]!!.jsonArray[0].jsonObject["outSha"]!!.jsonPrimitive.content }
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertNotEquals(Bench.outputHash(mapOf("x" to byteArrayOf(1))), Bench.outputHash(mapOf("x" to byteArrayOf(2))))
        // key order does not matter
        assertEquals(Bench.outputHash(linkedMapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2))),
            Bench.outputHash(linkedMapOf("b" to byteArrayOf(2), "a" to byteArrayOf(1))))
    }

    @Test fun executorTakesModelsParam() = runBlocking {
        val (_, bench, _) = setup()
        val ex = DevAiExecutor(bench = bench)
        assertEquals(setOf(Op.BENCH), ex.ops)
        val out = ex.execute(TaskSpec("b1", Op.BENCH, buildJsonObject { put("models", JsonArray(listOf(JsonPrimitive("tiny")))); put("runs", 3) }),
            emptyList(), emptyList()).getOrThrow() as JsonObject
        assertEquals(3L, out["totals"]!!.jsonObject["runs"]!!.jsonPrimitive.long)
        val viaModelId = DevAiExecutor(bench = setup().second).execute(TaskSpec("b2", Op.BENCH, modelId = "tiny"), emptyList(), emptyList()).getOrThrow()
        assertEquals(10L, viaModelId.jsonObject["totals"]!!.jsonObject["runs"]!!.jsonPrimitive.long)
        assertTrue(Canon.canonical(out).contains("\"op\":\"BENCH\""))
    }
}
