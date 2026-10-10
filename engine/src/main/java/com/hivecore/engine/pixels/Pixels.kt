package com.hivecore.engine.pixels

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

// Swarm plan §4A.3 (IMG_PREPROC, SUPERRES, model input packing). Pure JVM image math on ARGB IntArrays. Every
// function is integer arithmetic (or IEEE-exact float ops) so the same input gives the same bytes on every phone:
// IMG_PREPROC's comparison rule is "byte-exact".

/** An ARGB_8888 image, row-major, [px] has w*h entries. */
class Img(val w: Int, val h: Int, val px: IntArray) {
    init { require(w > 0 && h > 0 && px.size == w * h) { "bad image ${w}x$h/${px.size}" } }
    operator fun get(x: Int, y: Int): Int = px[y * w + x]
    fun copy(): Img = Img(w, h, px.copyOf())
    override fun equals(other: Any?): Boolean = other is Img && other.w == w && other.h == h && other.px.contentEquals(px)
    override fun hashCode(): Int = 31 * (31 * w + h) + px.contentHashCode()
    override fun toString(): String = "Img(${w}x$h)"

    companion object {
        fun filled(w: Int, h: Int, argb: Int): Img = Img(w, h, IntArray(w * h) { argb })
        fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
        fun gray(v: Int): Int = rgb(v, v, v)
    }
}

/** Decode/encode seam: [ImageIo] on Android, a map-backed fake in tests. Decoding applies EXIF orientation. */
interface ImageCodec {
    fun decode(file: File): Img
    /** "png" | "jpeg"; [quality] only for JPEG. */
    fun encode(img: Img, format: String, quality: Int = 90): ByteArray
}

/** Where a letterboxed model input sits in the original image: model = orig * scale + pad. */
data class Letterbox(val img: Img, val scale: Double, val padX: Int, val padY: Int, val srcW: Int, val srcH: Int) {
    fun toSrcX(x: Double): Double = ((x - padX) / scale).coerceIn(0.0, srcW.toDouble())
    fun toSrcY(y: Double): Double = ((y - padY) / scale).coerceIn(0.0, srcH.toDouble())
}

/** One tile of a tiled pass: source region [x, x+size) × [y, y+size) (edge-replicated past the border). */
data class Tile(val index: Int, val x: Int, val y: Int, val size: Int)

data class TilePlan(val w: Int, val h: Int, val size: Int, val overlap: Int, val tiles: List<Tile>,
    /** per tile column/row: the [start, end) band of the source this tile owns in the stitched result */
    val xCuts: IntArray, val yCuts: IntArray, val cols: Int, val rows: Int)

object Pixels {
    fun r(c: Int) = (c shr 16) and 255
    fun g(c: Int) = (c shr 8) and 255
    fun b(c: Int) = c and 255
    /** BT.601 luma, integer: (77R + 150G + 29B) >> 8. */
    fun luma(c: Int) = (77 * r(c) + 150 * g(c) + 29 * b(c)) shr 8

    // ---------------------------------------------------------------------------------------------------------
    // Geometry

    fun crop(src: Img, x: Int, y: Int, w: Int, h: Int): Img {
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= src.w && y + h <= src.h) { "crop out of bounds" }
        val out = IntArray(w * h)
        for (j in 0 until h) System.arraycopy(src.px, (y + j) * src.w + x, out, j * w, w)
        return Img(w, h, out)
    }

    /** Crop that may extend past the border; outside pixels replicate the nearest edge pixel. */
    fun cropClamped(src: Img, x: Int, y: Int, w: Int, h: Int): Img {
        val out = IntArray(w * h)
        for (j in 0 until h) {
            val sy = (y + j).coerceIn(0, src.h - 1)
            for (i in 0 until w) out[j * w + i] = src.px[sy * src.w + (x + i).coerceIn(0, src.w - 1)]
        }
        return Img(w, h, out)
    }

    /** Rotate clockwise by [quarterTurns] × 90°. */
    fun rotate90(src: Img, quarterTurns: Int): Img {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return src.copy()
        val w = src.w; val h = src.h
        return when (q) {
            1 -> Img(h, w, IntArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = src.px[y * w + x] })
            2 -> Img(w, h, IntArray(w * h).also { o -> for (i in 0 until w * h) o[w * h - 1 - i] = src.px[i] })
            else -> Img(h, w, IntArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = src.px[y * w + x] })
        }
    }

    fun flipH(src: Img): Img = Img(src.w, src.h, IntArray(src.w * src.h) { i -> val y = i / src.w; val x = i % src.w; src.px[y * src.w + src.w - 1 - x] })

    /** EXIF orientation 1..8 → upright image. */
    fun applyExif(src: Img, orientation: Int): Img = when (orientation) {
        2 -> flipH(src)
        3 -> rotate90(src, 2)
        4 -> flipH(rotate90(src, 2))
        5 -> flipH(rotate90(src, 1))
        6 -> rotate90(src, 1)
        7 -> flipH(rotate90(src, 3))
        8 -> rotate90(src, 3)
        else -> src
    }

    // ---------------------------------------------------------------------------------------------------------
    // Resampling

    /**
     * Deterministic resize: integer box-averaging while the shrink factor is ≥ 2 (anti-aliasing), then bilinear with
     * half-pixel centres in 8-bit fixed point. Same size returns an exact copy.
     */
    fun resize(src: Img, dw: Int, dh: Int): Img {
        require(dw > 0 && dh > 0)
        var s = src
        val kx = s.w / dw; val ky = s.h / dh
        if (kx >= 2 || ky >= 2) s = boxShrink(s, maxOf(1, kx), maxOf(1, ky))
        if (s.w == dw && s.h == dh) return if (s === src) src.copy() else s
        return bilinear(s, dw, dh)
    }

    /** Average k×k blocks (the trailing partial block averages what it has). */
    fun boxShrink(src: Img, kx: Int, ky: Int): Img {
        val dw = (src.w + kx - 1) / kx; val dh = (src.h + ky - 1) / ky
        val out = IntArray(dw * dh)
        for (oy in 0 until dh) for (ox in 0 until dw) {
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            val y1 = minOf(src.h, (oy + 1) * ky); val x1 = minOf(src.w, (ox + 1) * kx)
            for (y in oy * ky until y1) for (x in ox * kx until x1) {
                val c = src.px[y * src.w + x]; sr += r(c); sg += g(c); sb += b(c); n++
            }
            out[oy * dw + ox] = Img.rgb((sr + n / 2) / n, (sg + n / 2) / n, (sb + n / 2) / n)
        }
        return Img(dw, dh, out)
    }

    private fun axis(sn: Int, dn: Int): Pair<IntArray, IntArray> {
        val i0 = IntArray(dn); val f = IntArray(dn)
        for (d in 0 until dn) {
            // source position in 1/256 px: ((2d+1)*sn/(2dn) - 0.5) * 256
            var p = (((2L * d + 1) * sn * 256L) / (2L * dn) - 128L).toInt()
            if (p < 0) p = 0
            var a = p shr 8; var fr = p and 255
            if (a >= sn - 1) { a = sn - 1; fr = 0 }
            i0[d] = a; f[d] = fr
        }
        return i0 to f
    }

    private fun bilinear(src: Img, dw: Int, dh: Int): Img {
        val (x0, fx) = axis(src.w, dw); val (y0, fy) = axis(src.h, dh)
        val out = IntArray(dw * dh)
        val sw = src.w
        for (y in 0 until dh) {
            val ya = y0[y]; val yb = minOf(ya + 1, src.h - 1); val wy = fy[y]
            for (x in 0 until dw) {
                val xa = x0[x]; val xb = minOf(xa + 1, sw - 1); val wx = fx[x]
                val c00 = src.px[ya * sw + xa]; val c01 = src.px[ya * sw + xb]
                val c10 = src.px[yb * sw + xa]; val c11 = src.px[yb * sw + xb]
                fun ch(sh: Int): Int {
                    val top = ((c00 shr sh) and 255) * (256 - wx) + ((c01 shr sh) and 255) * wx
                    val bot = ((c10 shr sh) and 255) * (256 - wx) + ((c11 shr sh) and 255) * wx
                    return (top * (256 - wy) + bot * wy + 32768) shr 16
                }
                out[y * dw + x] = Img.rgb(ch(16), ch(8), ch(0))
            }
        }
        return Img(dw, dh, out)
    }

    /** Scale so the longer side is at most [maxSide] (never upscales). */
    fun fitMax(src: Img, maxSide: Int): Img {
        val m = maxOf(src.w, src.h)
        if (m <= maxSide) return src
        val w = maxOf(1, ((src.w.toLong() * maxSide + m / 2) / m).toInt()); val h = maxOf(1, ((src.h.toLong() * maxSide + m / 2) / m).toInt())
        return resize(src, w, h)
    }

    /** Aspect-preserving resize into a [tw]×[th] canvas, centred, padded with [fill]. */
    fun letterbox(src: Img, tw: Int, th: Int, fill: Int = Img.gray(114)): Letterbox {
        val scale = minOf(tw.toDouble() / src.w, th.toDouble() / src.h)
        val nw = maxOf(1, minOf(tw, Math.round(src.w * scale).toInt())); val nh = maxOf(1, minOf(th, Math.round(src.h * scale).toInt()))
        val scaled = resize(src, nw, nh)
        val px = (tw - nw) / 2; val py = (th - nh) / 2
        val out = IntArray(tw * th) { fill }
        for (y in 0 until nh) System.arraycopy(scaled.px, y * nw, out, (y + py) * tw + px, nw)
        return Letterbox(Img(tw, th, out), scale, px, py, src.w, src.h)
    }

    /** Resize the shorter side to fit, then centre-crop to [tw]×[th] (classification/CLIP style). */
    fun centerCrop(src: Img, tw: Int, th: Int): Img {
        val scale = maxOf(tw.toDouble() / src.w, th.toDouble() / src.h)
        val nw = maxOf(tw, Math.round(src.w * scale).toInt()); val nh = maxOf(th, Math.round(src.h * scale).toInt())
        val s = resize(src, nw, nh)
        return crop(s, (nw - tw) / 2, (nh - th) / 2, tw, th)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tone

    fun grayscale(src: Img): Img = Img(src.w, src.h, IntArray(src.px.size) { Img.gray(luma(src.px[it])) })

    fun lumaPlane(src: Img): IntArray = IntArray(src.px.size) { luma(src.px[it]) }

    /**
     * CLAHE-lite on luma, grey output: [grid]×[grid] tiles, histogram clipped at [clip] × the mean bin count with
     * the excess spread evenly, per-tile CDF maps blended bilinearly (integer weights).
     */
    fun clahe(src: Img, grid: Int = 8, clip: Double = 2.0): Img {
        val w = src.w; val h = src.h
        val l = lumaPlane(src)
        val gx = grid.coerceIn(1, w); val gy = grid.coerceIn(1, h)
        val maps = Array(gy * gx) { IntArray(256) }
        for (ty in 0 until gy) for (tx in 0 until gx) {
            val x0 = tx * w / gx; val x1 = (tx + 1) * w / gx; val y0 = ty * h / gy; val y1 = (ty + 1) * h / gy
            val hist = IntArray(256); var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { hist[l[y * w + x]]++; n++ }
            if (n == 0) { for (v in 0 until 256) maps[ty * gx + tx][v] = v; continue }
            val limit = maxOf(1, (clip * n / 256.0).toInt())
            var excess = 0
            for (v in 0 until 256) if (hist[v] > limit) { excess += hist[v] - limit; hist[v] = limit }
            val add = excess / 256; val rem = excess % 256
            for (v in 0 until 256) hist[v] += add + if (v < rem) 1 else 0
            var cdf = 0
            val m = maps[ty * gx + tx]
            for (v in 0 until 256) { cdf += hist[v]; m[v] = ((cdf.toLong() * 255 + n / 2) / n).toInt().coerceIn(0, 255) }
        }
        val out = IntArray(w * h)
        // tile centres in 1/256 px; a pixel blends the four surrounding tile maps
        fun centre(i: Int, n: Int, g: Int) = ((i * n / g + (i + 1) * n / g) * 128)
        for (y in 0 until h) {
            val py = y * 256 + 128
            var ty0 = 0
            while (ty0 + 1 < gy && centre(ty0 + 1, h, gy) <= py) ty0++
            val ty1 = minOf(ty0 + 1, gy - 1)
            val cy0 = centre(ty0, h, gy); val cy1 = centre(ty1, h, gy)
            val wy = if (ty1 == ty0 || py <= cy0) 0 else minOf(256, ((py - cy0).toLong() * 256 / (cy1 - cy0)).toInt())
            for (x in 0 until w) {
                val px = x * 256 + 128
                var tx0 = 0
                while (tx0 + 1 < gx && centre(tx0 + 1, w, gx) <= px) tx0++
                val tx1 = minOf(tx0 + 1, gx - 1)
                val cx0 = centre(tx0, w, gx); val cx1 = centre(tx1, w, gx)
                val wx = if (tx1 == tx0 || px <= cx0) 0 else minOf(256, ((px - cx0).toLong() * 256 / (cx1 - cx0)).toInt())
                val v = l[y * w + x]
                val a = maps[ty0 * gx + tx0][v]; val b = maps[ty0 * gx + tx1][v]
                val c = maps[ty1 * gx + tx0][v]; val d = maps[ty1 * gx + tx1][v]
                val top = a * (256 - wx) + b * wx; val bot = c * (256 - wx) + d * wx
                out[y * w + x] = Img.gray(((top * (256 - wy) + bot * wy + 32768) shr 16).coerceIn(0, 255))
            }
        }
        return Img(w, h, out)
    }

    /** Pixelate rectangles in place-copy (face redaction): each [block]² cell becomes its average. */
    fun pixelate(src: Img, boxes: List<IntArray>, block: Int = 12): Img {
        val out = src.px.copyOf()
        for (bx in boxes) {
            val x0 = bx[0].coerceIn(0, src.w); val y0 = bx[1].coerceIn(0, src.h)
            val x1 = bx[2].coerceIn(0, src.w); val y1 = bx[3].coerceIn(0, src.h)
            var cy = y0
            while (cy < y1) {
                var cx = x0
                val ey = minOf(y1, cy + block)
                while (cx < x1) {
                    val ex = minOf(x1, cx + block)
                    var sr = 0; var sg = 0; var sb = 0; var n = 0
                    for (y in cy until ey) for (x in cx until ex) { val c = out[y * src.w + x]; sr += r(c); sg += g(c); sb += b(c); n++ }
                    val avg = Img.rgb(sr / n, sg / n, sb / n)
                    for (y in cy until ey) for (x in cx until ex) out[y * src.w + x] = avg
                    cx = ex
                }
                cy = ey
            }
        }
        return Img(src.w, src.h, out)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tensor packing (NHWC, RGB order)

    /** uint8 NHWC: q = round((p/255)/scale) + zp, clamped to 0..255. scale = 1/255, zp = 0 gives the raw bytes. */
    fun toNhwcU8(img: Img, scale: Double = 1.0 / 255.0, zeroPoint: Int = 0): ByteArray {
        val lut = IntArray(256) { v -> (Math.round(v / 255.0 / scale) + zeroPoint).toInt().coerceIn(0, 255) }
        val out = ByteArray(img.px.size * 3)
        for (i in img.px.indices) {
            val c = img.px[i]
            out[3 * i] = lut[r(c)].toByte(); out[3 * i + 1] = lut[g(c)].toByte(); out[3 * i + 2] = lut[b(c)].toByte()
        }
        return out
    }

    /** float NHWC: (p/255 - mean[c]) / std[c]. */
    fun toNhwcFloat(img: Img, mean: FloatArray = floatArrayOf(0f, 0f, 0f), std: FloatArray = floatArrayOf(1f, 1f, 1f)): FloatArray {
        val out = FloatArray(img.px.size * 3)
        for (i in img.px.indices) {
            val c = img.px[i]
            out[3 * i] = (r(c) / 255f - mean[0]) / std[0]
            out[3 * i + 1] = (g(c) / 255f - mean[1]) / std[1]
            out[3 * i + 2] = (b(c) / 255f - mean[2]) / std[2]
        }
        return out
    }

    /** Inverse of [toNhwcU8] for model image outputs: p = round(((q - zp) * scale) * 255). */
    fun fromNhwcU8(data: ByteArray, w: Int, h: Int, scale: Double = 1.0 / 255.0, zeroPoint: Int = 0): Img {
        require(data.size >= w * h * 3) { "tensor too small for ${w}x$h" }
        val lut = IntArray(256) { q -> Math.round((q - zeroPoint) * scale * 255.0).toInt().coerceIn(0, 255) }
        return Img(w, h, IntArray(w * h) { i -> Img.rgb(lut[data[3 * i].toInt() and 255], lut[data[3 * i + 1].toInt() and 255], lut[data[3 * i + 2].toInt() and 255]) })
    }

    fun fromNhwcFloat(data: FloatArray, w: Int, h: Int): Img =
        Img(w, h, IntArray(w * h) { i -> Img.rgb(f2b(data[3 * i]), f2b(data[3 * i + 1]), f2b(data[3 * i + 2])) })

    private fun f2b(v: Float): Int = Math.round(v * 255f).coerceIn(0, 255)

    // ---------------------------------------------------------------------------------------------------------
    // Tiling (SUPERRES): fixed-size tiles with overlap; the stitched result takes each pixel from the tile that
    // owns it (the overlap is split at its midpoint), so stitching identity tiles reproduces the source exactly.

    private fun positions(n: Int, size: Int, overlap: Int): IntArray {
        if (n <= size) return intArrayOf(0)
        val step = size - overlap
        require(step > 0) { "overlap must be < tile size" }
        val ps = ArrayList<Int>()
        var p = 0
        while (true) {
            if (p + size >= n) { ps += n - size; break }
            ps += p; p += step
        }
        return ps.distinct().toIntArray()
    }

    private fun cuts(pos: IntArray, n: Int, size: Int): IntArray {
        val c = IntArray(pos.size + 1)
        c[0] = 0; c[pos.size] = n
        for (i in 1 until pos.size) c[i] = (pos[i] + minOf(n, pos[i - 1] + size)) / 2
        return c
    }

    fun tilePlan(w: Int, h: Int, size: Int, overlap: Int): TilePlan {
        val xs = positions(w, size, overlap); val ys = positions(h, size, overlap)
        val tiles = ArrayList<Tile>()
        for (j in ys.indices) for (i in xs.indices) tiles += Tile(tiles.size, xs[i], ys[j], size)
        return TilePlan(w, h, size, overlap, tiles, cuts(xs, w, size), cuts(ys, h, size), xs.size, ys.size)
    }

    fun tileImage(src: Img, t: Tile): Img = cropClamped(src, t.x, t.y, t.size, t.size)

    /** Stitch per-tile outputs (each [plan.size]*[factor] square, in tile index order) into the upscaled image. */
    fun stitch(plan: TilePlan, outputs: List<Img>, factor: Int): Img {
        require(outputs.size == plan.tiles.size) { "need ${plan.tiles.size} tiles, got ${outputs.size}" }
        val ow = plan.w * factor; val oh = plan.h * factor
        val out = IntArray(ow * oh)
        for (t in plan.tiles) {
            val col = t.index % plan.cols; val row = t.index / plan.cols
            val o = outputs[t.index]
            require(o.w == plan.size * factor && o.h == plan.size * factor) { "tile ${t.index} is ${o.w}x${o.h}" }
            val x0 = plan.xCuts[col] * factor; val x1 = plan.xCuts[col + 1] * factor
            val y0 = plan.yCuts[row] * factor; val y1 = plan.yCuts[row + 1] * factor
            for (y in y0 until y1) {
                val ty = y - t.y * factor
                System.arraycopy(o.px, ty * o.w + (x0 - t.x * factor), out, y * ow + x0, x1 - x0)
            }
        }
        return Img(ow, oh, out)
    }
}

/** Minimal deterministic PNG writer (8-bit RGB, or grey when every pixel is grey or [gray] is set); filter 0. */
object Png {
    fun encode(img: Img, gray: Boolean = img.px.all { Pixels.r(it) == Pixels.g(it) && Pixels.g(it) == Pixels.b(it) }): ByteArray {
        val ch = if (gray) 1 else 3
        val raw = ByteArray(img.h * (1 + img.w * ch))
        var k = 0
        for (y in 0 until img.h) {
            raw[k++] = 0
            for (x in 0 until img.w) {
                val c = img.px[y * img.w + x]
                if (gray) raw[k++] = Pixels.r(c).toByte()
                else { raw[k++] = Pixels.r(c).toByte(); raw[k++] = Pixels.g(c).toByte(); raw[k++] = Pixels.b(c).toByte() }
            }
        }
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { s ->
            DataOutputStream(s).apply { writeInt(img.w); writeInt(img.h); writeByte(8); writeByte(if (gray) 0 else 2); writeByte(0); writeByte(0); writeByte(0) }
        }.toByteArray()
        chunk(out, "IHDR", ihdr); chunk(out, "IDAT", z.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }
}
