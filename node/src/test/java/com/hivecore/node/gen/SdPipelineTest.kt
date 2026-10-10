package com.hivecore.node.gen

import com.hivecore.runtime.models.OrtQnnRuntime
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.runtime.models.ManifestSpecs
import com.hivecore.runtime.models.TensorDesc
import com.hivecore.runtime.models.TensorIo
import com.hivecore.runtime.models.TensorSession
import com.hivecore.node.vision.ClipBpe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SdPipelineTest {

    companion object {
        /** The real sd15 manifest's specs (models/aihub/sd15/manifest.json, from AI Hub metadata.json). */
        val inSpec = """{
          "text_encoder.tokens": {"name":"tokens","graph":"text_encoder","shape":[1,77],"dtype":"int32"},
          "unet.timestep": {"name":"timestep","graph":"unet","shape":[1,1],"dtype":"uint16","scale":0.014770733192563057,"zeroPoint":0},
          "unet.latent": {"name":"latent","graph":"unet","shape":[1,64,64,4],"dtype":"uint16","scale":0.00024176308943424374,"zeroPoint":33983},
          "unet.text_emb": {"name":"text_emb","graph":"unet","shape":[1,77,768],"dtype":"uint16","scale":0.0009331560577265918,"zeroPoint":30103},
          "vae.latent": {"name":"latent","graph":"vae","shape":[1,64,64,4],"dtype":"uint16","scale":0.00034003707696683705,"zeroPoint":34382}}"""
        val outSpec = """{
          "text_encoder.text_embedding": {"name":"text_embedding","graph":"text_encoder","shape":[1,77,768],"dtype":"uint16","scale":0.0009303585393354297,"zeroPoint":30063},
          "unet.output_latent": {"name":"output_latent","graph":"unet","shape":[1,64,64,4],"dtype":"uint16","scale":0.0001881735515780747,"zeroPoint":32340},
          "vae.image": {"name":"image","graph":"vae","shape":[1,512,512,3],"dtype":"uint16","scale":1.5259021893143654e-05,"zeroPoint":0}}"""
        val manifest = ModelManifest("sd15", "gen_image", "ort_qnn", files = listOf("text_encoder.onnx", "unet.onnx", "vae.onnx"),
            inputSpec = Json.parseToJsonElement(inSpec) as JsonObject, outputSpec = Json.parseToJsonElement(outSpec) as JsonObject,
            sha256 = "abc", licence = "creativeml-openrail-m")

        /** A tiny BPE: a handful of merges is enough for ids to depend on the text. */
        val bpe = ClipBpe(listOf("#version: 0.2", "a </w>", "r e", "re d</w>", "m u", "mu g</w>"))

        fun u16(v: FloatArray, d: TensorDesc): ByteArray {
            val b = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { b.putShort(TensorIo.quantize(it.toDouble(), d.scale!!, d.zeroPoint!!, "uint16").toShort()) }
            return b.array()
        }
        /** What the ORT adapter does to a real-valued uint16 input before the EPContext graph sees it. */
        fun adapt(v: FloatArray, d: TensorDesc): FloatArray = TensorIo.dequantize(u16(v, d), "uint16", d.scale, d.zeroPoint!!)
    }

    /**
     * Scripted SD: text encoder → embedding value = 0.01·(sum of ids mod 97); UNet → ε = 0.1·latent + 0.02·emb[0]
     * (so cond/uncond differ) + 0.001·t; VAE → pixel = 0.5 + 0.1·latent[c]. Inputs pass through the adapter
     * quantization, outputs are uint16 per the outputSpec, exactly like OrtQnnRuntime.
     */
    class FakeSd : ModelRuntime {
        val calls = ArrayList<String>()
        val unetEmb0 = ArrayList<Float>()
        var vaeInput: FloatArray? = null
        private val ins = ManifestSpecs.parse(manifest.inputSpec)
        private val outs = ManifestSpecs.parse(manifest.outputSpec)
        private fun i(g: String, n: String) = ins.first { it.graph == g && it.name == n }
        private fun o(g: String) = outs.first { it.graph == g }
        override val id = "ort_qnn"
        override fun supports(m: ModelManifest) = true
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> =
            Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = runCatching {
            val g = inputs[TensorSession.GRAPH] as String
            calls += g
            when (g) {
                "text_encoder" -> {
                    val ids = ByteBuffer.wrap(inputs["tokens"] as ByteArray).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { b -> IntArray(77) { b.get(it) } }
                    val v = (ids.sum() % 97) * 0.01f
                    mapOf("text_embedding" to u16(FloatArray(77 * 768) { v }, o(g)))
                }
                "unet" -> {
                    // uint16 inputs arrive as real float32 values (the adapter quantizes them)
                    val lat = adapt(inputs["latent"] as FloatArray, i(g, "latent"))
                    val emb = adapt(inputs["text_emb"] as FloatArray, i(g, "text_emb"))
                    val t = adapt(inputs["timestep"] as FloatArray, i(g, "timestep"))[0]
                    unetEmb0 += emb[0]
                    mapOf("output_latent" to u16(FloatArray(lat.size) { 0.1f * lat[it] + 0.02f * emb[0] + 0.001f * t }, o(g)))
                }
                "vae" -> {
                    val lat = adapt(inputs["latent"] as FloatArray, i(g, "latent")).also { vaeInput = inputs["latent"] as FloatArray }
                    val px = FloatArray(512 * 512 * 3) { k -> val p = k / 3; val y = p / 512 / 8; val x = p % 512 / 8; 0.5f + 0.1f * lat[(y * 64 + x) * 4 + k % 3] }
                    mapOf("image" to u16(px, o(g)))
                }
                else -> error("graph $g")
            }
        }
    }

    private fun pipeline(rt: FakeSd) = SdPipeline(FakeModelPool(FakeModelRegistry(listOf(manifest)), rt), rt, { bpe })

    @Test fun callCountsAndOrder() = runTest {
        val rt = FakeSd()
        val img = pipeline(rt).generate(GenParams("a red mug", seed = 7, steps = 5))
        assertEquals(listOf("text_encoder", "text_encoder") + List(10) { "unet" } + "vae", rt.calls)
        assertEquals(512, img.width); assertEquals(512 * 512 * 3, img.rgb.size)
        assertTrue(img.timings.keys.containsAll(listOf("tokenize", "textEncoder", "unet", "vae", "png", "total")))
    }

    @Test fun twentyStepsDefault() = runTest {
        val rt = FakeSd()
        pipeline(rt).generate(GenParams("a red mug", seed = 1))
        assertEquals(2, rt.calls.count { it == "text_encoder" })
        assertEquals(40, rt.calls.count { it == "unet" })
        assertEquals(1, rt.calls.count { it == "vae" })
    }

    @Test fun guidanceOneSkipsUncond() = runTest {
        val rt = FakeSd()
        pipeline(rt).generate(GenParams("a red mug", steps = 3, guidance = 1.0))
        assertEquals(listOf("text_encoder") + List(3) { "unet" } + "vae", rt.calls)
    }

    @Test fun cfgMathAndQuantizedPath() = runTest {
        val rt = FakeSd()
        val p = GenParams("a red mug", seed = 3, steps = 4, guidance = 7.5)
        pipeline(rt).generate(p)
        // replay independently: same noise, same scheduler, ε = εu + g(εc − εu) on the dequantized UNet outputs
        val specIn = ManifestSpecs.parse(manifest.inputSpec); val specOut = ManifestSpecs.parse(manifest.outputSpec)
        val latD = specIn.first { it.graph == "unet" && it.name == "latent" }
        val tD = specIn.first { it.graph == "unet" && it.name == "timestep" }
        val epsD = specOut.first { it.graph == "unet" }
        val condE0 = rt.unetEmb0[0]; val uncondE0 = rt.unetEmb0[1]
        assertTrue("cond and uncond embeddings differ", condE0 != uncondE0)
        val s = DpmSolverPP2M(4)
        var x = Noise.gaussian(3, 16384)
        for (i in 0 until 4) {
            val xf = FloatArray(x.size) { x[it].toFloat() }
            val lat = adapt(xf, latD)
            val t = adapt(floatArrayOf(s.timesteps[i].toFloat()), tD)[0]
            fun eps(e0: Float) = TensorIo.dequantize(u16(FloatArray(lat.size) { 0.1f * lat[it] + 0.02f * e0 + 0.001f * t }, epsD), "uint16", epsD.scale, epsD.zeroPoint!!)
            x = s.step(SdPipeline.cfg(eps(uncondE0), eps(condE0), 7.5), x)
        }
        assertArrayEquals(FloatArray(x.size) { x[it].toFloat() }, rt.vaeInput!!, 0f)
    }

    @Test fun cfgFormula() {
        val e = SdPipeline.cfg(floatArrayOf(1f, -2f), floatArrayOf(3f, 0f), 7.5)
        assertArrayEquals(doubleArrayOf(1 + 7.5 * 2, -2 + 7.5 * 2), e, 1e-12)
    }

    @Test fun deterministicAndSeedSensitive() = runTest {
        val a = pipeline(FakeSd()).generate(GenParams("a red mug", seed = 7, steps = 3))
        val b = pipeline(FakeSd()).generate(GenParams("a red mug", seed = 7, steps = 3))
        val c = pipeline(FakeSd()).generate(GenParams("a red mug", seed = 8, steps = 3))
        assertEquals(a.pngSha256, b.pngSha256); assertEquals(a.rgbSha256, b.rgbSha256)
        assertTrue(a.rgbSha256 != c.rgbSha256)
    }

    @Test fun metadataAndPngText() = runTest {
        val img = pipeline(FakeSd()).generate(GenParams("a red mug", seed = 7, steps = 2))
        val m = img.metadata
        assertEquals("true", m["aiGenerated"]!!.jsonPrimitive.content)
        assertEquals("7", m["seed"]!!.jsonPrimitive.content)
        assertEquals("sd15", m["model"]!!.jsonPrimitive.content)
        assertEquals(SdPipeline.sha256("a red mug".toByteArray()), m["promptSha256"]!!.jsonPrimitive.content)
        assertTrue("prompt text is not stored", "a red mug" !in m.toString())
        val s = String(img.png, Charsets.ISO_8859_1)
        assertTrue(s.contains("tEXtAI-Generated\u0000true"))
        assertTrue(s.endsWith("IEND®B`\u0082"))
    }

    @Test fun tokensPadWithEot() {
        val ids = SdPipeline.tokens(bpe, "a red mug")
        assertEquals(77, ids.size)
        assertEquals(bpe.sot, ids[0])
        val body = bpe.encode("a red mug")
        for (i in body.indices) assertEquals(body[i], ids[i + 1])
        assertTrue((body.size + 1 until 77).all { ids[it] == bpe.eot })
        val long = SdPipeline.tokens(bpe, List(200) { "a" }.joinToString(" "))
        assertEquals(bpe.eot, long[76]); assertEquals(bpe.sot, long[0])
        val empty = SdPipeline.tokens(bpe, "")
        assertTrue((1 until 77).all { empty[it] == bpe.eot })
    }

    @Test fun vaeOutputToRgb() {
        val d = ManifestSpecs.parse(manifest.outputSpec).first { it.graph == "vae" }
        val raw = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(-1).putShort(32768.toShort()).array()
        assertArrayEquals(byteArrayOf(0, 255.toByte(), 128.toByte()), SdPipeline.toRgb8(raw, d, 3))
    }
}
