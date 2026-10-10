package com.hivecore.node.vision

import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels
import com.hivecore.node.vision.PixelsTest.Companion.noise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IqaTest {
    private fun boxBlur(src: Img, r: Int): Img {
        var cur = src
        repeat(3) {
            val l = cur
            cur = Img(l.w, l.h, IntArray(l.w * l.h) { i ->
                val x = i % l.w; val y = i / l.w
                var s = 0; var n = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val xx = (x + dx).coerceIn(0, l.w - 1); val yy = (y + dy).coerceIn(0, l.h - 1)
                    s += Pixels.r(l.px[yy * l.w + xx]); n++
                }
                Img.gray(s / n)
            })
        }
        return cur
    }

    private val sharp = Pixels.grayscale(noise(640, 480, lo = 68, hi = 188))

    @Test fun sharpIsOk() {
        val r = Iqa.assess(sharp)
        assertEquals("ok", r.verdict)
        assertTrue(r.blur > 1000)
    }

    @Test fun blurredIsBlurry() {
        val r = Iqa.assess(boxBlur(sharp, 4))
        assertEquals("blurry", r.verdict)
        assertTrue("blur ${r.blur}", r.blur < Iqa.BLUR_MIN)
    }

    @Test fun darkAndOverexposedAndSmall() {
        val dark = Img(sharp.w, sharp.h, IntArray(sharp.px.size) { Img.gray(Pixels.r(sharp.px[it]) / 8) })
        assertEquals("dark", Iqa.assess(dark).verdict)
        val bright = Img(sharp.w, sharp.h, IntArray(sharp.px.size) { Img.gray(255 - Pixels.r(dark.px[it])) })
        assertEquals("overexposed", Iqa.assess(bright).verdict)
        assertEquals("small", Iqa.assess(Pixels.grayscale(noise(300, 200))).verdict)
    }

    @Test fun exact() {
        assertEquals(Iqa.assess(sharp), Iqa.assess(sharp.copy()))
    }
}

class DetectionTest {
    private val yoloxOut = Json.parseToJsonElement("""{
        "boxes": {"shape": [1,4,4], "dtype": "uint8", "quantization_parameters": {"scale": 4.416494846343994, "zero_point": 51}},
        "scores": {"shape": [1,4], "dtype": "uint8", "quantization_parameters": {"scale": 0.0038126425351947546, "zero_point": 0}},
        "class_idx": {"shape": [1,4], "dtype": "uint8"}}""") as JsonObject

    @Test fun yoloxDecodeFromQuantizedTensorsAndNms() {
        val specs = TensorIo.outputs(yoloxOut)
        val boxesQ = byteArrayOf(74, 74, 96, 96, 75, 75, 97, 97, 75, 75, 97, 97, 10, 10, 20, 20)
        val scoresQ = byteArrayOf(230.toByte(), 200.toByte(), 150.toByte(), 50)
        val cls = byteArrayOf(0, 0, 2, 0)
        val boxes = TensorIo.floats(boxesQ, specs[0]); val scores = TensorIo.floats(scoresQ, specs[1]); val ci = TensorIo.ints(cls, specs[2])
        assertEquals(23 * 4.416494846343994, boxes[0].toDouble(), 1e-4)
        val raw = Detection.decodeYolox(boxes, scores, ci, listOf("person", "bicycle", "car"))
        assertEquals(3, raw.size)   // index 3 below 0.35
        val kept = Detection.nms(raw)
        assertEquals(listOf("person", "car"), kept.map { it.label })   // second person suppressed, car kept (class-wise)
        assertEquals(listOf("person"), Detection.nms(raw, classAgnostic = true).map { it.label })
        assertEquals(mapOf("car" to 1, "person" to 1), Detection.counts(kept))
    }

    @Test fun deterministicTieOrder() {
        val a = Det(1, "b", 0.0, 0.0, 10.0, 10.0, 0.5, 7)
        val b = Det(0, "a", 100.0, 0.0, 110.0, 10.0, 0.5, 9)
        val c = Det(0, "a", 200.0, 0.0, 210.0, 10.0, 0.5, 3)
        assertEquals(listOf(c, b, a), Detection.nms(listOf(a, b, c)))
        assertEquals(Detection.nms(listOf(a, b, c)), Detection.nms(listOf(c, a, b)))
    }

    @Test fun iouAndDfine() {
        val a = Det(0, "x", 0.0, 0.0, 10.0, 10.0, 1.0)
        assertEquals(1.0 / 3.0, Detection.iou(a, a.copy(x1 = 5.0, x2 = 15.0)), 1e-12)
        val d = Detection.decodeDfine(floatArrayOf(0.5f, 0.5f, 0.2f, 0.4f), floatArrayOf(0.9f), intArrayOf(2), listOf("a", "b", "c"),
            boxFormat = "cxcywh", normalizedW = 640, normalizedH = 640)
        assertEquals("c", d[0].label)
        assertEquals(256.0, d[0].x1, 1e-3); assertEquals(192.0, d[0].y1, 1e-3); assertEquals(384.0, d[0].x2, 1e-3)
    }

    @Test fun unletterboxMapsToSource() {
        val lb = Pixels.letterbox(Img.filled(1280, 640, 0), 640, 640)
        val d = Detection.unletterbox(listOf(Det(0, "p", 0.0, 160.0, 320.0, 480.0, 1.0)), lb)[0]
        assertEquals(0.0, d.x1, 1e-9); assertEquals(0.0, d.y1, 1e-9); assertEquals(640.0, d.x2, 1e-9); assertEquals(640.0, d.y2, 1e-9)
    }
}

class SegmentationClassificationTest {
    @Test fun sharesMath() {
        val m = Segmentation.fromIndexMask(intArrayOf(0, 0, 0, 1, 1, 2, 0, 0), 2, 4)
        val s = Segmentation.shares(m, listOf("bg", "shelf", "gap"))
        assertEquals(listOf("bg", "shelf", "gap"), s.map { it.label })
        assertEquals(listOf(5, 2, 1), s.map { it.pixels })
        assertEquals(0.625, s[0].share, 1e-12); assertEquals(0.125, s[2].share, 1e-12)
        assertEquals(1.0, s.sumOf { it.share }, 1e-12)
    }

    @Test fun argmaxTiesGoLow() {
        val m = Segmentation.argmax(floatArrayOf(1f, 3f, 3f, 5f, 0f, 0f), 1, 2, 3)
        assertArrayEquals(intArrayOf(1, 0), m.cls)
    }

    @Test fun topK() {
        val t = Classification.topK(floatArrayOf(0f, 8.665f, 1.733f, 8.665f), listOf("a", "b", "c", "d"), 3)
        assertEquals(listOf("b", "d", "c"), t.map { it.label })
        assertEquals(t[0].score, t[1].score, 0.0)
        assertTrue(t.sumOf { it.score } < 1.0)
    }
}

class ClipAndFacesTest {
    private val merges = listOf("#version: 0.2", "h e", "l l", "he ll", "hell o</w>")

    @Test fun bpeTokenizer() {
        val bpe = ClipBpe(merges)
        assertEquals(516, bpe.sot); assertEquals(517, bpe.eot)
        assertArrayEquals(intArrayOf(516, 515, 517, 0, 0, 0, 0, 0), bpe.tokenize("  Hello ", 8))
        // 'h' is byte-encoder index 71, 'i</w>' = 256 + 72
        assertEquals(listOf(71, 328), bpe.encode("hi"))
        assertEquals(listOf(515, 515), bpe.encode("hello hello"))
        val long = bpe.tokenize("hello ".repeat(20), 8)
        assertEquals(517, long[7])
    }

    @Test fun tagScoring() {
        val tags = ClipTagger.score(floatArrayOf(1f, 0f), listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f)), listOf("a", "b", "c"))
        assertEquals(listOf("a"), tags.map { it.tag })
        val cos = ClipTagger.score(floatArrayOf(1f, 0f), listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f)), listOf("a", "b", "c"), "cosine", 0.5)
        assertEquals(listOf("a", "c"), cos.map { it.tag })
        assertEquals(Math.sqrt(0.5), cos[1].score, 1e-9)
    }

    @Test fun faceAnchorsAndDecode() {
        val a = Faces.anchors()
        assertEquals(896, a.size)
        assertEquals(0.5 / 16, a[0][0], 1e-12); assertEquals(0.5 / 16, a[1][0], 1e-12)
        assertEquals(0.5 / 8, a[512][0], 1e-12); assertEquals(1.5 / 8, a[518][0], 1e-12)
        val coords = FloatArray(896 * 16); val logits = FloatArray(896) { -10f }
        coords[2] = 32f; coords[3] = 32f; logits[0] = 5f
        coords[16 + 2] = 32f; coords[16 + 3] = 32f; logits[1] = 4f      // same place, lower score → suppressed
        val f = Faces.decode(coords, logits)
        assertEquals(1, f.size)
        assertEquals(-8.0, f[0].x1, 1e-9); assertEquals(24.0, f[0].x2, 1e-9)
    }
}
