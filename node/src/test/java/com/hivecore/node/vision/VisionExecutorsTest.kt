package com.hivecore.node.vision

import com.hivecore.engine.pixels.Png
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels
import com.hivecore.core.Decision
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.core.fakes.FakeModelRuntime
import com.hivecore.node.vision.PixelsTest.Companion.nearest
import com.hivecore.node.vision.PixelsTest.Companion.noise
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class VisionExecutorsTest {
    @get:Rule val tmp = TemporaryFolder()

    class MapCodec(val images: Map<String, Img>) : ImageCodec {
        override fun decode(file: File): Img = images[file.name] ?: error("no image ${file.name}")
        override fun encode(img: Img, format: String, quality: Int): ByteArray = Png.encode(img)
    }

    /** Runtime whose outputs are computed from the inputs; records every call. */
    class ScriptedRuntime(val fn: (ModelManifest, Map<String, Any>) -> Map<String, Any>) : ModelRuntime {
        val calls = mutableListOf<Map<String, Any>>()
        override val id = "scripted"
        override fun supports(m: ModelManifest) = true
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> =
            Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> {
            calls += inputs; return Result.success(fn(session.manifest, inputs))
        }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun manifest(id: String, inSpec: String, outSpec: String, labels: List<String> = emptyList(), pre: String = "{}") =
        ModelManifest(id, "vision", "litert_qnn", files = listOf("$id.tflite"), inputSpec = obj(inSpec), outputSpec = obj(outSpec),
            preprocess = obj(pre), labels = labels, sha256 = "0", licence = "Apache-2.0")

    private val yolox = manifest("yolox_w8a8",
        """{"image": {"shape": [1,640,640,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003921568859368563, "zero_point": 0}}}""",
        """{"boxes": {"shape": [1,4,4], "dtype": "uint8", "quantization_parameters": {"scale": 4.416494846343994, "zero_point": 51}},
            "scores": {"shape": [1,4], "dtype": "uint8", "quantization_parameters": {"scale": 0.0038126425351947546, "zero_point": 0}},
            "class_idx": {"shape": [1,4], "dtype": "uint8"}}""", listOf("person", "bicycle", "car"))
    private val mobilenet = manifest("mobilenet_w8a8",
        """{"image_tensor": {"shape": [1,224,224,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003918204456567764, "zero_point": 0}}}""",
        """{"class_logits": {"shape": [1,4], "dtype": "uint8", "quantization_parameters": {"scale": 0.17330844700336456, "zero_point": 102}}}""",
        listOf("a", "b", "c", "d"))
    private val deeplab = manifest("deeplab_w8a8",
        """{"image": {"shape": [1,4,4,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003921568859368563, "zero_point": 0}}}""",
        """{"mask": {"shape": [1,2,4], "dtype": "uint8"}}""", listOf("BACKGROUND", "shelf", "gap"))
    private val clip = manifest("openai_clip",
        """{"image": {"shape": [1,224,224,3], "dtype": "float32"}, "text": {"shape": [1,5,8], "dtype": "int32"}}""",
        """{"image_features": {"shape": [1,2], "dtype": "float32"}, "text_features": {"shape": [5,2], "dtype": "float32"}}""")
    private val esrgan = manifest("esrgan_w8a8",
        """{"image": {"shape": [1,128,128,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003921568859368563, "zero_point": 0}}}""",
        """{"upscaled_image": {"shape": [1,512,512,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003921568859368563, "zero_point": 0}}}""")

    private val photo = noise(640, 640, seed = 3, lo = 60, hi = 190)
    private val codec = MapCodec(mapOf("p1.jpg" to photo, "small.jpg" to noise(300, 200), "wide.jpg" to noise(300, 200, seed = 9)))

    private fun task(op: Op, params: String, modelId: String? = null) = TaskSpec("t1", op, obj(params), modelId = modelId)
    private fun blobs(vararg names: String) = names.map { File(tmp.root, it) }

    private fun fakePool(): Pair<FakeModelPool, FakeModelRuntime> {
        val rt = FakeModelRuntime()
        rt.outputs["yolox_w8a8"] = mapOf(
            "boxes" to byteArrayOf(74, 74, 96, 96, 75, 75, 97, 97, 75, 75, 97, 97, 10, 10, 20, 20),
            "scores" to byteArrayOf(230.toByte(), 200.toByte(), 150.toByte(), 50),
            "class_idx" to byteArrayOf(0, 0, 2, 0))
        rt.outputs["mobilenet_w8a8"] = mapOf("0" to byteArrayOf(102, 152.toByte(), 112, 152.toByte()))   // positional key
        rt.outputs["deeplab_w8a8"] = mapOf("mask" to byteArrayOf(0, 0, 0, 1, 1, 2, 0, 0))
        return FakeModelPool(FakeModelRegistry(listOf(yolox, mobilenet, deeplab)), rt) to rt
    }

    private fun executor(pool: FakeModelPool, rt: ModelRuntime, registry: FakeModelRegistry? = null) =
        VisionExecutor(pool, rt, codec, File(tmp.root, "out"), registry)

    @Test fun detectJsonIsExactAndStable() = runTest {
        val (pool, rt) = fakePool()
        val ex = executor(pool, rt)
        val out1 = ex.execute(task(Op.DETECT, "{}", "yolox_w8a8"), emptyList(), blobs("p1.jpg")).getOrThrow()
        val out2 = ex.execute(task(Op.DETECT, "{}", "yolox_w8a8"), emptyList(), blobs("p1.jpg")).getOrThrow()
        assertEquals(out1, out2)
        assertEquals("""{"op":"DETECT","model":"yolox_w8a8","items":[{"item":"p1.jpg","w":640,"h":640,"detections":[""" +
            """{"label":"person","box":[102,102,199,199],"score":0.8769},{"label":"car","box":[106,106,203,203],"score":0.5719}],""" +
            """"counts":{"car":1,"person":1}}]}""", out1.toString())
        val filtered = ex.execute(task(Op.DETECT, """{"modelId":"yolox_w8a8","classes":["car"],"score":0.6}"""), emptyList(), blobs("p1.jpg")).getOrThrow()
        assertEquals(0, filtered.jsonObject["items"]!!.jsonArray[0].jsonObject["detections"]!!.jsonArray.size)
    }

    @Test fun detectMapsBoxesThroughLetterbox() = runTest {
        val (pool, rt) = fakePool()
        val out = executor(pool, rt).execute(task(Op.DETECT, "{}", "yolox_w8a8"), emptyList(), blobs("wide.jpg")).getOrThrow()
        // 300x200 → scale 640/300, padY = (640-427)/2 = 106
        val box = out.jsonObject["items"]!!.jsonArray[0].jsonObject["detections"]!!.jsonArray[0].jsonObject["box"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(48, 0, 93, 43), box)
    }

    @Test fun classifyAndSegment() = runTest {
        val (pool, rt) = fakePool()
        val ex = executor(pool, rt)
        val c = ex.execute(task(Op.IMG_CLASSIFY, """{"topK":3}""", "mobilenet_w8a8"), emptyList(), blobs("p1.jpg")).getOrThrow()
        val labels = c.jsonObject["items"]!!.jsonArray[0].jsonObject["labels"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertEquals(listOf("b", "d", "c"), labels)
        val s = ex.execute(task(Op.SEGMENT, "{}", "deeplab_w8a8"), emptyList(), blobs("p1.jpg")).getOrThrow()
        val item = s.jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("""[{"label":"BACKGROUND","share":0.625},{"label":"shelf","share":0.25},{"label":"gap","share":0.125}]""", item["shares"].toString())
        val mask = File(File(tmp.root, "out"), item["mask"]!!.jsonPrimitive.content)
        assertTrue(mask.isFile)
        assertEquals(item["maskSha256"]!!.jsonPrimitive.content, VJson.sha256(mask.readBytes()))
    }

    @Test fun iqaAndPreproc() = runTest {
        val (pool, rt) = fakePool()
        val ex = executor(pool, rt)
        val q = ex.execute(task(Op.IQA, "{}"), emptyList(), blobs("p1.jpg", "small.jpg")).getOrThrow()
        val items = q.jsonObject["items"]!!.jsonArray
        assertEquals(listOf("ok", "small"), items.map { it.jsonObject["verdict"]!!.jsonPrimitive.content })
        assertEquals("[640,640]", items[0].jsonObject["res"].toString())
        assertTrue("model" !in q.jsonObject)

        val pre = ex.execute(task(Op.IMG_PREPROC, """{"steps":[{"op":"fit","max":100},{"op":"gray"}],"format":"png"}"""), emptyList(), blobs("p1.jpg")).getOrThrow()
        val it0 = pre.jsonObject["items"]!!.jsonArray[0].jsonObject
        val expected = Png.encode(Pixels.grayscale(Pixels.fitMax(photo, 100)))
        assertEquals(VJson.sha256(expected), it0["sha256"]!!.jsonPrimitive.content)
        assertArrayEquals(expected, File(File(tmp.root, "out"), it0["out"]!!.jsonPrimitive.content).readBytes())
        assertEquals(100, it0["w"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun tagUsesClipTokensInBatchesOfFive() = runTest {
        val dir = tmp.newFolder("models", "openai_clip")
        File(dir, "bpe_simple_vocab_16e6.txt").writeText("#version: 0.2\nh e\nl l\nhe ll\nhell o</w>\n")
        val registry = FakeModelRegistry(listOf(clip), File(tmp.root, "models"))
        // text i's feature: [1,0] for prompts containing "hello", else [0,1]; image [1,0]
        val rt = ScriptedRuntime { _, inputs ->
            val ids = java.nio.ByteBuffer.wrap(inputs["text"] as ByteArray).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val feats = FloatArray(10)
            for (i in 0 until 5) { val hit = (0 until 8).any { ids.get(i * 8 + it) == 515 }; feats[2 * i] = if (hit) 1f else 0f; feats[2 * i + 1] = if (hit) 0f else 1f }
            mapOf("image_features" to floatArrayOf(1f, 0f), "text_features" to feats)
        }
        val pool = FakeModelPool(registry, rt)
        val vocab = listOf("x", "y", "z", "w", "v", "hello")
        val out = VisionExecutor(pool, rt, codec, File(tmp.root, "out"), registry)
            .execute(task(Op.TAG, """{"vocabulary":${JsonArray(vocab.map { kotlinx.serialization.json.JsonPrimitive(it) })},"template":"{}"}""", "openai_clip"), emptyList(), blobs("p1.jpg")).getOrThrow()
        assertEquals(2, rt.calls.size)                       // 6 tags → 2 calls of 5
        assertTrue(rt.calls[0]["image"] is FloatArray)
        assertEquals("""[{"tag":"hello","score":1.0}]""", out.jsonObject["items"]!!.jsonArray[0].jsonObject["tags"].toString())
    }

    @Test fun tagWithoutMergesFileFails() = runTest {
        val registry = FakeModelRegistry(listOf(clip), tmp.newFolder("empty"))
        val pool = FakeModelPool(registry, FakeModelRuntime())
        val r = VisionExecutor(pool, FakeModelRuntime(), codec, tmp.root, registry)
            .execute(task(Op.TAG, """{"vocabulary":["a"]}""", "openai_clip"), emptyList(), blobs("p1.jpg"))
        assertTrue(r.exceptionOrNull()!!.message!!.contains("merges"))
    }

    @Test fun superresTilesAndStitchesSeamlessly() = runTest {
        val rt = ScriptedRuntime { _, inputs ->
            val tile = Pixels.fromNhwcU8(inputs["image"] as ByteArray, 128, 128)
            mapOf("upscaled_image" to Pixels.toNhwcU8(nearest(tile, 4)))
        }
        val registry = FakeModelRegistry(listOf(esrgan))
        val ex = VisionExecutor(FakeModelPool(registry, rt), rt, codec, File(tmp.root, "out"), registry)
        val out = ex.execute(task(Op.SUPERRES, "{}", "esrgan_w8a8"), emptyList(), blobs("wide.jpg")).getOrThrow()
        val item = out.jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals(6, rt.calls.size)
        assertEquals(VJson.sha256(Png.encode(nearest(codec.images.getValue("wide.jpg"), 4))), item["sha256"]!!.jsonPrimitive.content)
        assertEquals("""{"w":300,"h":200,"tile":128,"overlap":16,"factor":4,"cols":3,"rows":2,"tiles":6}""", item["plan"].toString())

        val part = ex.execute(task(Op.SUPERRES, """{"tiles":[4,1]}""", "esrgan_w8a8"), emptyList(), blobs("wide.jpg")).getOrThrow()
        val tiles = part.jsonObject["items"]!!.jsonArray[0].jsonObject["tileOutputs"]!!.jsonArray
        assertEquals(listOf(1, 4), tiles.map { it.jsonObject["index"]!!.jsonPrimitive.content.toInt() })
    }

    @Test fun vlmAsk() = runTest {
        val seen = mutableListOf<Triple<String, String, List<String>>>()
        val ex = VlmAskExecutor { path, q, opts -> seen += Triple(path, q, opts); Decision("yes", 2.0 / 3.0, opts, 2, 3, votes = listOf(2, 1, 0)) }
        val out = ex.execute(TaskSpec("v", Op.VLM_ASK, obj("""{"question":"Is the safety helmet worn?"}""")), emptyList(), blobs("p1.jpg")).getOrThrow()
        assertEquals(listOf("yes", "no", "unclear"), seen[0].third)
        assertEquals("""{"op":"VLM_ASK","question":"Is the safety helmet worn?","options":["yes","no","unclear"],"items":[""" +
            """{"item":"p1.jpg","value":"yes","confidence":0.6667,"agreeing":2,"runs":3,"votes":{"yes":2,"no":1,"unclear":0}}]}""", out.toString())
        val none = VlmAskExecutor { _, _, opts -> Decision<String>(null, 0.0, opts, 0, 3) }
            .execute(TaskSpec("v", Op.VLM_ASK, obj("""{"question":"q","options":["a","b"]}""")), emptyList(), blobs("p1.jpg")).getOrThrow()
        assertEquals("null", none.jsonObject["items"]!!.jsonArray[0].jsonObject["value"].toString())
    }

    @Test fun errorsAreResults() = runTest {
        val (pool, rt) = fakePool()
        val ex = executor(pool, rt)
        assertTrue(ex.execute(task(Op.DETECT, "{}"), emptyList(), blobs("p1.jpg")).isFailure)            // no modelId
        assertTrue(ex.execute(task(Op.IQA, "{}"), emptyList(), emptyList()).isFailure)                   // no blobs
        assertTrue(ex.execute(task(Op.DETECT, "{}", "nope"), emptyList(), blobs("p1.jpg")).isFailure)    // unknown model
        assertTrue(ex.execute(task(Op.OCR, "{}"), emptyList(), blobs("p1.jpg")).isFailure)
    }
}

