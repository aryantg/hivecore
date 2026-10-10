package com.hivecore.runtime.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TensorIoTest {
    private fun px(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    private fun prep(json: String): ImagePrep = ImagePrep.from(Json.parseToJsonElement(json).jsonObject)!!
    private fun floats(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { f -> FloatArray(f.remaining()).also { f.get(it) } }

    @Test fun uint8QuantizedInputWithAiHubScaleIsThePixelValue() {
        // AI Hub w8a8 image input: uint8, scale 1/255 (as float32), zero point 0, range [0,1]
        val img = RgbImage(2, 1, intArrayOf(px(0, 128, 255), px(1, 2, 254)))
        val p = prep("""{"input":"image","width":2,"height":1,"layout":"NHWC","dtype":"uint8","range":[0,1],"scale":0.003921568859368563,"zeroPoint":0}""")
        val (bytes, _) = TensorIo.imageToInput(img, p)
        assertArrayEquals(intArrayOf(0, 128, 255, 1, 2, 254), bytes.map { it.toInt() and 0xff }.toIntArray())
    }

    @Test fun float32NormalisationAndNchwLayout() {
        val img = RgbImage(2, 1, intArrayOf(px(255, 0, 51), px(0, 255, 102)))
        val p = prep("""{"input":"x","width":2,"height":1,"layout":"NCHW","dtype":"float32","range":[0,1],"mean":[0.5,0.5,0.5],"std":[0.5,0.25,1.0]}""")
        val f = floats(TensorIo.imageToInput(img, p).first)
        // NCHW: R plane, G plane, B plane; (v/255 - mean)/std
        assertArrayEquals(floatArrayOf(1f, -1f, -2f, 2f, -0.3f, -0.1f), f, 1e-6f)
    }

    @Test fun nhwcFloatRange0to255() {
        val img = RgbImage(1, 1, intArrayOf(px(10, 20, 30)))
        val p = prep("""{"width":1,"height":1,"dtype":"float32","range":[0,255]}""")
        assertArrayEquals(floatArrayOf(10f, 20f, 30f), floats(TensorIo.imageToInput(img, p).first), 1e-4f)
    }

    @Test fun quantizeClampsAndRounds() {
        assertEquals(255, TensorIo.quantize(2.0, 1.0 / 255, 0, "uint8"))
        assertEquals(0, TensorIo.quantize(-0.1, 1.0 / 255, 0, "uint8"))
        assertEquals(-128, TensorIo.quantize(-5.0, 0.01, 0, "int8"))
        assertEquals(30063 + 11, TensorIo.quantize(0.0102, 0.0009303585393354297, 30063, "uint16"))
    }

    @Test fun bilinearResizeHalfPixelCentres() {
        // 2x1 → 4x1: centres at -0.25, 0.25, 0.75, 1.25 in source space (clamped)
        val src = RgbImage(2, 1, intArrayOf(px(0, 0, 0), px(100, 200, 40)))
        val out = TensorIo.resize(src, 4, 1)
        assertArrayEquals(intArrayOf(0, 25, 75, 100), IntArray(4) { out.r(it) })
        assertArrayEquals(intArrayOf(0, 50, 150, 200), IntArray(4) { out.g(it) })
        // same size → same object
        assertEquals(src, TensorIo.resize(src, 2, 1))
    }

    @Test fun letterboxKeepsAspectAndPads() {
        val src = RgbImage(4, 2, IntArray(8) { px(200, 200, 200) })
        val (img, lb) = TensorIo.letterbox(src, 4, 4, 114)
        assertEquals(1.0, lb.scale, 1e-9); assertEquals(0, lb.padX); assertEquals(1, lb.padY)
        assertEquals(114, img.r(0)); assertEquals(200, img.r(4)); assertEquals(200, img.r(11)); assertEquals(114, img.r(12))
    }

    @Test fun dequantizeTypes() {
        assertArrayEquals(floatArrayOf((0 - 51) * 4.4f, (255 - 51) * 4.4f), TensorIo.dequantize(byteArrayOf(0, -1), "uint8", 4.4, 51), 1e-3f)
        val u16 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(40000.toShort()).putShort(1).array()
        assertArrayEquals(floatArrayOf(40000f, 1f), TensorIo.dequantize(u16, "uint16"), 0f)
        val f32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).array()
        assertArrayEquals(floatArrayOf(1.5f), TensorIo.dequantize(f32, "float32"), 0f)
    }

    @Test fun halfFloatRoundTrip() {
        for (v in floatArrayOf(0f, 1f, -2f, 0.5f, 65504f, 6.1035156e-5f, 5.9604645e-8f, 0.1f, 1000.25f)) {
            val back = TensorIo.halfToFloat(TensorIo.floatToHalf(v))
            assertEquals("v=$v", v.toDouble(), back.toDouble(), Math.abs(v) * 1e-3 + 1e-7)
        }
        assertEquals(0x3800.toShort(), TensorIo.floatToHalf(0.5f))
        assertEquals(0x3c00.toShort(), TensorIo.floatToHalf(1f))
        assertEquals(Float.POSITIVE_INFINITY, TensorIo.halfToFloat(TensorIo.floatToHalf(1e6f)))
    }

    @Test fun topKIsDeterministicOnTies() {
        assertEquals(listOf(1 to 3f, 3 to 3f, 0 to 1f), TensorIo.topK(floatArrayOf(1f, 3f, 0f, 3f), 3))
        assertEquals(1, TensorIo.argmax(floatArrayOf(1f, 3f, 0f, 3f)))
    }

    @Test fun toBytesEncodesPerDtype() {
        val ints = TensorIo.toBytes(Json.parseToJsonElement("[[1,2],[3]]"), "int32")
        assertEquals(12, ints.size)
        assertEquals(3, ByteBuffer.wrap(ints).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        assertArrayEquals(byteArrayOf(1, 2), TensorIo.toBytes(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), "uint8"))
        assertEquals(8, TensorIo.toBytes(floatArrayOf(1f, 2f), "float32").size)
        assertEquals(4, TensorIo.toBytes(floatArrayOf(1f, 2f), "float16").size)
    }

    @Test fun seededMatchesTheSpikeGenerator() {
        val ids = TensorDesc("input_ids", "int32", listOf(1, 4))
        val mask = TensorDesc("attention_mask", "int32", listOf(1, 4))
        val a = TensorIo.seeded(ids, 0, ort = false)
        assertArrayEquals(a, TensorIo.seeded(ids, 0, ort = false))
        val r = kotlin.random.Random(7)
        val expect = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putInt(r.nextInt(1, 1000)) } }.array()
        assertArrayEquals(expect, a)
        assertArrayEquals(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putInt(1) } }.array(), TensorIo.seeded(mask, 1, ort = false))
        val f16 = TensorIo.seeded(TensorDesc("input_features", "float16", listOf(2)), 0, ort = true)
        assertArrayEquals(byteArrayOf(0x00, 0x38, 0x00, 0x38), f16)
    }

    @Test fun manifestSpecsParseGraphKeys() {
        val spec = Json.parseToJsonElement("""{"encoder.input_features":{"shape":[1,80,3000],"dtype":"float16"},
            "image":{"name":"image","shape":[1,640,640,3],"dtype":"uint8","scale":0.0039,"zeroPoint":0}}""").jsonObject
        val d = ManifestSpecs.parse(spec)
        assertEquals("encoder", d[0].graph); assertEquals("input_features", d[0].name); assertEquals(480000L, d[0].numBytes)
        assertEquals(0.0039, d[1].scale!!, 0.0); assertEquals(1228800L, d[1].numBytes)
        assertNull(ImagePrep.from(JsonObject(emptyMap())))
    }

    @Test fun closedAnswerMatching() {
        val opts = listOf("yes", "no", "unclear")
        assertEquals("yes", ClosedAnswer.match("Yes.", opts))
        assertEquals("no", ClosedAnswer.match("no, the helmet is missing", opts))
        assertEquals("unclear", ClosedAnswer.match("It is unclear", opts))
        assertNull(ClosedAnswer.match("yes and no", opts))
        assertNull(ClosedAnswer.match("", opts))
        assertEquals("Q? Answer with exactly one of: yes, no, unclear.", ClosedAnswer.prompt("Q?", opts))
        assertEquals(listOf("a", "b"), ClosedAnswer.options("a, b"))
    }
}
