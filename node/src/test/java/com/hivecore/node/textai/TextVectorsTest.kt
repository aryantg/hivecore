package com.hivecore.node.textai

import com.hivecore.core.LlmBackend
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeClosedChooser
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.node.vision.TensorIo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class TextVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    // HuggingFace's BertTokenizer unit-test vocabulary
    private val testVocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "want", "##want", "##ed", "wa", "un", "runn", "##ing", ",", "low", "lowest", "!", "?")

    @Test fun wordPieceMatchesBertExamples() {
        val wp = WordPiece(testVocab)
        assertEquals(listOf("un", "##want", "##ed", ",", "runn", "##ing"), wp.tokenize("UNwantéd,running"))
        assertEquals(listOf("[UNK]", "runn", "##ing"), wp.tokenize("unwantedX running"))
        assertEquals(listOf("hello", "!", "how", "are", "you", "?"), WordPiece.basic(" \tHeLLo!how  \n Are yoU?  "))
        assertEquals(listOf("hallo", "!", "how", "are", "you", "?"), WordPiece.basic(" \tHäLLo!how  \n Are yoU?  "))
        val e = wp.encode("unwanted running", 6)
        assertArrayEquals(intArrayOf(2, 8, 5, 6, 9, 3), e.ids)   // [CLS] un ##want ##ed runn [SEP] (truncated)
        assertTrue(e.truncated)
        val p = wp.encode("lowest", 5)
        assertArrayEquals(intArrayOf(2, 13, 3, 0, 0), p.ids)
        assertArrayEquals(intArrayOf(1, 1, 1, 0, 0), p.mask)
    }

    @Test fun poolingMath() {
        // 3 tokens × 2 dims, the last one masked out → mean of the first two
        val pooled = Embedder.meanPool(floatArrayOf(1f, 2f, 3f, 4f, 100f, 100f), 3, 2, intArrayOf(1, 1, 0))
        assertArrayEquals(floatArrayOf(2f, 3f), pooled, 1e-6f)
        val unit = Embedder.l2(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), unit, 1e-6f)
        assertArrayEquals(byteArrayOf(76, 102), Embedder.quantize(unit))
        val rows = Embedder.pooled(floatArrayOf(1f, 0f, 0f, 1f), listOf(2, 2), 2, 4, IntArray(8) { 1 })
        assertArrayEquals(floatArrayOf(0f, 1f), rows[1], 0f)
    }

    /** MiniLM-style runtime: uint8 [1,4] output computed from the token ids (scale/zp from the spec). */
    private class MiniRuntime : ModelRuntime {
        val calls = mutableListOf<Map<String, Any>>()
        override val id = "scripted"
        override fun supports(m: ModelManifest) = true
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> =
            Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> {
            calls += inputs
            val ids = ByteBuffer.wrap(inputs["input_ids"] as ByteArray).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val a = ids.get(1)   // first real token
            // dequantized value = (q - 134) * 0.01
            return Result.success(mapOf("embeddings" to byteArrayOf((134 + a).toByte(), (134 + 10).toByte(), 134.toByte(), 134.toByte())))
        }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun setup(): Triple<TextExecutor, MiniRuntime, FakeModelRegistry> {
        val m = ModelManifest("minilm_v2_w8a8", "text_embedding", "litert_qnn", files = listOf("minilm_v2.tflite"),
            inputSpec = obj("""{"input_ids":{"shape":[1,8],"dtype":"int32"},"attention_mask":{"shape":[1,8],"dtype":"int32"}}"""),
            outputSpec = obj("""{"embeddings":{"shape":[1,4],"dtype":"uint8","quantization_parameters":{"scale":0.01,"zero_point":134}}}"""),
            sha256 = "0", licence = "MIT")
        val root = tmp.newFolder("models")
        File(root, m.id).mkdirs()
        File(File(root, m.id), "vocab.txt").writeText(testVocab.joinToString("\n") + "\n")
        val reg = FakeModelRegistry(listOf(m), root)
        val rt = MiniRuntime()
        val llm = FakeLlmEngine(LlmBackend.NPU).apply { replies["summarise"] = "3, 2" }
        return Triple(TextExecutor(llm, FakeClosedChooser(), FakeModelPool(reg, rt), rt, reg), rt, reg)
    }

    @Test fun embedderFeedsInt32AndNormalises() = runTest {
        val (_, rt, reg) = setup()
        val e = Embedder(FakeModelPool(reg, rt), rt, reg).embed("minilm_v2_w8a8", listOf("a" to "want"))
        val ids = ByteBuffer.wrap(rt.calls.single()["input_ids"] as ByteArray).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        assertEquals(listOf(2, 4, 3, 0, 0, 0, 0, 0), (0 until 8).map { ids.get(it) })
        val mask = ByteBuffer.wrap(rt.calls.single()["attention_mask"] as ByteArray).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0, 0), (0 until 8).map { mask.get(it) })
        // raw (0.04, 0.10, 0, 0) → unit vector
        val n = sqrt(0.04 * 0.04 + 0.1 * 0.1)
        assertEquals(0.04 / n, e.single().vec[0].toDouble(), 1e-5)
        assertEquals(0.10 / n, e.single().vec[1].toDouble(), 1e-5)
        assertTrue(abs(e.single().vec.sumOf { it.toDouble() * it } - 1.0) < 1e-5)
    }

    @Test fun knnIsDeterministicWithIdTieBreak() {
        val items = listOf("c" to byteArrayOf(100, 0), "a" to byteArrayOf(100, 0), "b" to byteArrayOf(0, 100), "d" to byteArrayOf(70, 70))
        val hits = Knn.topK(byteArrayOf(127, 0), items, 3)
        assertEquals(listOf("a", "c", "d"), hits.map { it.id })
        assertEquals(1.0, hits[0].score, 0.0)
        assertEquals(hits, Knn.topK(byteArrayOf(127, 0), items.reversed(), 3))
    }

    @Test fun kmeansIsDeterministicAndOrderFree() {
        val pts = listOf("p1" to floatArrayOf(1f, 0f), "p2" to floatArrayOf(0.95f, 0.05f), "p3" to floatArrayOf(0f, 1f),
            "p4" to floatArrayOf(0.05f, 0.95f), "p5" to floatArrayOf(0.9f, 0.1f))
        val seed = Cluster.seed("job-42")
        val a = Cluster.kmeans(pts, 2, seed)
        val b = Cluster.kmeans(pts.reversed(), 2, seed)
        assertEquals(a.assignments, b.assignments)
        assertEquals(mapOf("p1" to 0, "p2" to 0, "p3" to 1, "p4" to 1, "p5" to 0), a.assignments)
        assertEquals(seed, Cluster.seed("job-42"))
        assertEquals(listOf(listOf("a", "b")), Cluster.dedupe(listOf("a" to byteArrayOf(100, 1), "b" to byteArrayOf(100, 2), "c" to byteArrayOf(0, 100)), 0.99))
    }

    @Test fun nearestCentroid() {
        val nc = NearestCentroid(mapOf("x" to listOf(floatArrayOf(1f, 0f), floatArrayOf(0.9f, 0.1f)), "y" to listOf(floatArrayOf(0f, 1f))), minMargin = 0.1)
        assertEquals("x", nc.predict(floatArrayOf(1f, 0.1f)).label)
        assertEquals(null, nc.predict(floatArrayOf(1f, 1f)).label)   // margin too small → refuse
    }

    // ------------------------------------------------------------------------------------------------ executors

    private fun task(op: Op, params: String, modelId: String? = null) = TaskSpec("t1", op, obj(params), modelId = modelId)

    @Test fun executorJsonIsStable() = runTest {
        val (ex, _, _) = setup()
        val text = """{"text":"We reviewed the budget. The budget rises to 12 lakh. Priya will send the revised budget by Friday."}"""
        val sumA = ex.execute(task(Op.SUMMARIZE, text.dropLast(1) + ""","sentences":2}"""), emptyList(), emptyList()).getOrThrow()
        val sumB = ex.execute(task(Op.SUMMARIZE, text.dropLast(1) + ""","sentences":2}"""), emptyList(), emptyList()).getOrThrow()
        assertEquals(sumA.toString(), sumB.toString())
        assertEquals("""{"op":"SUMMARIZE","mode":"extractive","method":"llm","candidates":3,"picks":[""" +
            """{"id":"text|0|1","doc":"text","unit":"0","text":"The budget rises to 12 lakh."},""" +
            """{"id":"text|0|2","doc":"text","unit":"0","text":"Priya will send the revised budget by Friday."}],"citations":["text · 0"]}""", sumA.toString())

        val emb = ex.execute(task(Op.EMBED, """{"texts":[{"id":"b","text":"want"},{"id":"a","text":"lowest"}],"includeText":true}""", "minilm_v2_w8a8"), emptyList(), emptyList()).getOrThrow()
        val emb2 = ex.execute(task(Op.EMBED, """{"texts":[{"id":"b","text":"want"},{"id":"a","text":"lowest"}],"includeText":true}""", "minilm_v2_w8a8"), emptyList(), emptyList()).getOrThrow()
        assertEquals(emb.toString(), emb2.toString())
        assertEquals(listOf("b", "a"), emb.jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val knn = ex.execute(task(Op.KNN, """{"query":[1,0,0,0],"k":2}"""), listOf(emb), emptyList()).getOrThrow()
        assertEquals("""{"op":"KNN","k":2,"hits":[{"id":"a","score":0.795251},{"id":"b","score":0.370033}]}""", knn.toString())

        val cl = ex.execute(task(Op.CLUSTER, """{"k":2,"jobId":"job-1"}"""), listOf(emb), emptyList()).getOrThrow()
        assertEquals(cl.toString(), ex.execute(task(Op.CLUSTER, """{"k":2,"jobId":"job-1"}"""), listOf(emb), emptyList()).getOrThrow().toString())
        assertEquals(2, cl.jsonObject["clusters"]!!.jsonArray.size)
    }

    @Test fun classifyAndGenTextExecutors() = runTest {
        val ch = FakeClosedChooser().apply { answers["send"] = 0 }
        val llm = FakeLlmEngine(LlmBackend.NPU).apply { fallback = "{\"title\": \"Reel\"" }
        val ex = TextExecutor(llm, ch, null, null)
        val out = ex.execute(task(Op.TEXT_CLASSIFY, """{"labels":["action","decision","info"],"granularity":"sentence",
            "text":"Priya will send the deck. We chose vendor B."}"""), emptyList(), emptyList()).getOrThrow()
        assertEquals("""{"op":"TEXT_CLASSIFY","mode":"llm","labels":["action","decision","info"],"items":[""" +
            """{"id":"text|0|0","label":"action","confidence":1.0,"agreeing":3,"runs":3,"votes":{"action":0,"decision":0,"info":0}},""" +
            """{"id":"text|0|1","label":"action","confidence":1.0,"agreeing":3,"runs":3,"votes":{"action":0,"decision":0,"info":0}}]}""", out.toString())
        val bad = ex.execute(task(Op.GEN_TEXT, """{"instruction":"title","schema":{"type":"object","properties":{"title":{"type":"string"}}}}"""), emptyList(), emptyList())
        assertTrue(bad.exceptionOrNull()!!.message!!.startsWith("rejected"))
        llm.fallback = "{\"title\": \"Reel\"}"
        val ok = ex.execute(task(Op.GEN_TEXT, """{"instruction":"title","schema":{"type":"object","properties":{"title":{"type":"string"}}}}"""), emptyList(), emptyList())
        assertEquals("""{"op":"GEN_TEXT","value":{"title":"Reel"}}""", ok.getOrThrow().toString())
    }

    @Test fun inferPassesTensorsAndReturnsBase64() = runTest {
        val (ex, rt, _) = setup()
        val r = ex.execute(task(Op.INFER, """{"inputs":{"input_ids":{"data":[2,4,3,0,0,0,0,0]},"attention_mask":{"data":[1,1,1,0,0,0,0,0]}}}""", "minilm_v2_w8a8"),
            emptyList(), emptyList()).getOrThrow()
        val o = r.jsonObject["outputs"]!!.jsonArray.single().jsonObject
        assertEquals("embeddings", o["name"]!!.jsonPrimitive.content)
        assertEquals("uint8", o["dtype"]!!.jsonPrimitive.content)
        assertEquals("[1,4]", o["shape"].toString())
        assertArrayEquals(byteArrayOf((134 + 4).toByte(), (144).toByte(), 134.toByte(), 134.toByte()),
            java.util.Base64.getDecoder().decode(o["b64"]!!.jsonPrimitive.content))
        assertEquals(TensorIo.int32Bytes(intArrayOf(2, 4, 3, 0, 0, 0, 0, 0)).toList(), (rt.calls.single()["input_ids"] as ByteArray).toList())
        val missing = ex.execute(task(Op.INFER, """{"inputs":{"input_ids":{"data":[2,4,3,0,0,0,0,0]}}}""", "minilm_v2_w8a8"), emptyList(), emptyList())
        assertTrue(missing.isFailure)
    }

}
