package com.hivecore.node.vision

import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels
import com.hivecore.engine.pixels.Png
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PixelsTest {
    companion object {
        fun noise(w: Int, h: Int, seed: Long = 7, lo: Int = 0, hi: Int = 255): Img {
            val r = Random(seed)
            return Img(w, h, IntArray(w * h) { Img.rgb(lo + r.nextInt(hi - lo + 1), lo + r.nextInt(hi - lo + 1), lo + r.nextInt(hi - lo + 1)) })
        }

        /** Nearest-neighbour ×f (the reference "model" for tiling tests). */
        fun nearest(src: Img, f: Int): Img = Img(src.w * f, src.h * f, IntArray(src.w * f * src.h * f) { i ->
            val x = (i % (src.w * f)) / f; val y = (i / (src.w * f)) / f; src.px[y * src.w + x]
        })
    }

    @Test fun identityResizeIsExactCopy() {
        val a = noise(37, 23)
        val b = Pixels.resize(a, 37, 23)
        assertEquals(a, b)
        assertTrue(a.px !== b.px)
    }

    @Test fun bilinearKnownValuesAndDeterminism() {
        val src = Img(2, 1, intArrayOf(Img.gray(0), Img.gray(255)))
        val up = Pixels.resize(src, 4, 1)
        // half-pixel centres: positions -0.25(clamped 0), 0.25, 0.75, 1.25(clamped) → 0, 64, 191, 255
        assertArrayEquals(intArrayOf(0, 64, 191, 255), up.px.map { Pixels.r(it) }.toIntArray())
        val big = noise(640, 480)
        assertEquals(Pixels.resize(big, 224, 168), Pixels.resize(big, 224, 168))
        assertEquals(Pixels.resize(big, 1000, 700).hashCode(), Pixels.resize(big, 1000, 700).hashCode())
    }

    @Test fun boxShrinkAverages() {
        val src = Img(2, 2, intArrayOf(Img.gray(0), Img.gray(100), Img.gray(200), Img.gray(101)))
        assertEquals(Img.gray(100), Pixels.resize(src, 1, 1).px[0])   // (0+100+200+101+2)/4 = 100
    }

    @Test fun rotationAndExif() {
        val a = noise(5, 3)
        assertEquals(a, Pixels.rotate90(Pixels.rotate90(Pixels.rotate90(Pixels.rotate90(a, 1), 1), 1), 1))
        assertEquals(a, Pixels.rotate90(Pixels.rotate90(a, 1), 3))
        val r = Pixels.rotate90(a, 1)
        assertEquals(3, r.w); assertEquals(5, r.h)
        assertEquals(a[0, 2], r[0, 0])   // clockwise: bottom-left → top-left
        assertEquals(a[0, 0], r[2, 0])
        val t = Pixels.applyExif(a, 5)   // transpose
        for (y in 0 until 3) for (x in 0 until 5) assertEquals(a[x, y], t[y, x])
        assertEquals(a, Pixels.applyExif(a, 1))
    }

    @Test fun cropAndLetterbox() {
        val a = noise(10, 8)
        val c = Pixels.crop(a, 2, 3, 4, 2)
        assertEquals(a[2, 3], c[0, 0]); assertEquals(a[5, 4], c[3, 1])
        val lb = Pixels.letterbox(noise(200, 100), 64, 64)
        assertEquals(64, lb.img.w); assertEquals(0, lb.padX); assertEquals(16, lb.padY)
        assertEquals(0.32, lb.scale, 1e-12)
        assertEquals(100.0, lb.toSrcX(32.0), 1e-9); assertEquals(50.0, lb.toSrcY(32.0), 1e-9)
    }

    @Test fun tilingRoundTripIsByteExact() {
        for ((w, h) in listOf(300 to 200, 128 to 128, 100 to 60, 241 to 129)) {
            val a = noise(w, h, seed = w.toLong())
            val plan = Pixels.tilePlan(w, h, 128, 16)
            val tiles = plan.tiles.map { Pixels.tileImage(a, it) }
            assertEquals("identity $w x $h", a, Pixels.stitch(plan, tiles, 1))
            assertEquals("x4 $w x $h", nearest(a, 4), Pixels.stitch(plan, tiles.map { nearest(it, 4) }, 4))
        }
        val p = Pixels.tilePlan(300, 200, 128, 16)
        assertEquals(listOf(0, 112, 172), p.tiles.filter { it.y == 0 }.map { it.x })
        assertEquals(listOf(0, 72), p.tiles.filter { it.x == 0 }.map { it.y })
    }

    @Test fun tensorPackingRoundTrip() {
        val a = noise(16, 9)
        val q = Pixels.toNhwcU8(a)
        assertEquals(16 * 9 * 3, q.size)
        assertEquals(Pixels.r(a.px[0]), q[0].toInt() and 255)
        assertEquals(a, Pixels.fromNhwcU8(q, 16, 9))
        val f = Pixels.toNhwcFloat(a)
        assertEquals(a, Pixels.fromNhwcFloat(f, 16, 9))
        // MobileNet's non-1/255 input scale saturates instead of wrapping
        val m = Pixels.toNhwcU8(Img.filled(1, 1, Img.gray(255)), 0.003918204456567764, 0)
        assertEquals(255, m[0].toInt() and 255)
    }

    @Test fun grayscaleAndClahe() {
        val g = Pixels.grayscale(noise(20, 20))
        assertTrue(g.px.all { Pixels.r(it) == Pixels.g(it) && Pixels.g(it) == Pixels.b(it) })
        // low-contrast ramp gets stretched; output deterministic and grey
        val ramp = Img(64, 64, IntArray(64 * 64) { Img.gray(100 + (it % 64) / 4) })
        val c1 = Pixels.clahe(ramp, 4, 2.0); val c2 = Pixels.clahe(ramp, 4, 2.0)
        assertEquals(c1, c2)
        val lum = c1.px.map { Pixels.r(it) }
        assertTrue((lum.maxOrNull()!! - lum.minOrNull()!!) > 15)
    }

    @Test fun pngIsDeterministicAndWellFormed() {
        val a = noise(33, 17)
        val p1 = Png.encode(a); val p2 = Png.encode(a)
        assertArrayEquals(p1, p2)
        assertEquals(0x89, p1[0].toInt() and 255)
        assertEquals("PNG", String(p1, 1, 3))
        assertNotEquals(Png.encode(a).size, Png.encode(Pixels.grayscale(a)).size)
    }
}
