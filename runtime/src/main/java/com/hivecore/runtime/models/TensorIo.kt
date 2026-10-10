package com.hivecore.runtime.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Tensor helpers shared by the runtimes and the op executors (swarm plan §4A.2). Pure Kotlin except
 * [decodeImageFile], so the preprocessing math is JVM-testable and byte-exact across phones.
 *
 * All raw tensor bytes are little-endian (the phones' native order and the order of every buffer the runtimes
 * hand back).
 */

/** One tensor of a graph: name, element type ("uint8", "int8", "uint16", "int32", "int64", "float16", "float32"), shape. */
data class TensorDesc(val name: String, val dtype: String, val shape: List<Long>, val graph: String = "",
                      val scale: Double? = null, val zeroPoint: Int? = null) {
    val elements: Long get() = shape.fold(1L) { a, b -> a * (if (b < 0) 1 else b) }
    val numBytes: Long get() = elements * TensorIo.elementSize(dtype)
}

/** A decoded image as packed ARGB pixels (row-major), independent of android.graphics so tests can build one. */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size == width * height) { "argb has ${argb.size} pixels, expected ${width * height}" } }
    fun r(i: Int) = (argb[i] shr 16) and 0xff
    fun g(i: Int) = (argb[i] shr 8) and 0xff
    fun b(i: Int) = argb[i] and 0xff
}

/**
 * How an image becomes a model input, from the manifest's `preprocess` object (written by tools/fetch_aihub_models.py):
 * `{"kind":"image","input":"image","width":640,"height":640,"layout":"NHWC","dtype":"uint8","range":[0,1],
 *   "mean":[..3],"std":[..3],"scale":0.0039,"zeroPoint":0,"resize":"stretch"|"letterbox","pad":114}`.
 * Pixel v in 0..255 → real = lo + v/255·(hi−lo) → (real − mean[c]) / std[c] → float, fp16, or quantized
 * round(x/scale)+zeroPoint clamped to the integer type's range.
 */
data class ImagePrep(
    val input: String = "image",
    val width: Int,
    val height: Int,
    val layout: String = "NHWC",
    val dtype: String = "uint8",
    val lo: Double = 0.0,
    val hi: Double = 1.0,
    val mean: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    val std: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
    val scale: Double? = null,
    val zeroPoint: Int = 0,
    val resize: String = "stretch",
    val pad: Int = 0,
) {
    companion object {
        fun from(preprocess: JsonObject): ImagePrep? {
            val w = preprocess["width"]?.jsonPrimitive?.intOrNull ?: return null
            val h = preprocess["height"]?.jsonPrimitive?.intOrNull ?: return null
            val range = preprocess["range"]?.let { r -> r.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } } ?: listOf(0.0, 1.0)
            fun triple(k: String, d: Double): DoubleArray = preprocess[k]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: d }
                ?.let { l -> DoubleArray(3) { l[min(it, l.size - 1)] } } ?: DoubleArray(3) { d }
            return ImagePrep(
                input = preprocess.str("input") ?: "image",
                width = w, height = h,
                layout = (preprocess.str("layout") ?: "NHWC").uppercase(),
                dtype = (preprocess.str("dtype") ?: "uint8").lowercase(),
                lo = range.getOrElse(0) { 0.0 }, hi = range.getOrElse(1) { 1.0 },
                mean = triple("mean", 0.0), std = triple("std", 1.0),
                scale = preprocess["scale"]?.jsonPrimitive?.doubleOrNull,
                zeroPoint = preprocess["zeroPoint"]?.jsonPrimitive?.intOrNull ?: 0,
                resize = preprocess.str("resize") ?: "stretch",
                pad = preprocess["pad"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}

/** Where the source image landed inside a letterboxed input: input = source·scale + pad. */
data class Letterbox(val scale: Double, val padX: Int, val padY: Int)

object TensorIo {

    fun elementSize(dtype: String): Int = when (dtype.lowercase()) {
        "uint8", "int8", "bool" -> 1
        "uint16", "int16", "float16" -> 2
        "int32", "uint32", "float32", "float" -> 4
        "int64", "float64", "double" -> 8
        else -> 1
    }

    // ---- images ------------------------------------------------------------------------------------------------

    /** Bilinear resize with half-pixel centres (align_corners = false), channels rounded to the nearest integer. */
    fun resize(src: RgbImage, outW: Int, outH: Int): RgbImage {
        if (src.width == outW && src.height == outH) return src
        val out = IntArray(outW * outH)
        val sx = src.width.toDouble() / outW
        val sy = src.height.toDouble() / outH
        for (y in 0 until outH) {
            val fy = max(0.0, (y + 0.5) * sy - 0.5)
            val y0 = min(floor(fy).toInt(), src.height - 1); val y1 = min(y0 + 1, src.height - 1); val wy = fy - y0
            for (x in 0 until outW) {
                val fx = max(0.0, (x + 0.5) * sx - 0.5)
                val x0 = min(floor(fx).toInt(), src.width - 1); val x1 = min(x0 + 1, src.width - 1); val wx = fx - x0
                val p00 = src.argb[y0 * src.width + x0]; val p01 = src.argb[y0 * src.width + x1]
                val p10 = src.argb[y1 * src.width + x0]; val p11 = src.argb[y1 * src.width + x1]
                fun ch(shift: Int): Int {
                    val a = (p00 shr shift) and 0xff; val b = (p01 shr shift) and 0xff
                    val c = (p10 shr shift) and 0xff; val d = (p11 shr shift) and 0xff
                    val top = a + (b - a) * wx; val bot = c + (d - c) * wx
                    return (top + (bot - top) * wy).roundToInt().coerceIn(0, 255)
                }
                out[y * outW + x] = (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return RgbImage(outW, outH, out)
    }

    /** Keeps the aspect ratio, centres the resized image and fills the rest with grey [pad]. */
    fun letterbox(src: RgbImage, outW: Int, outH: Int, pad: Int): Pair<RgbImage, Letterbox> {
        val s = min(outW.toDouble() / src.width, outH.toDouble() / src.height)
        val w = max(1, (src.width * s).roundToInt()); val h = max(1, (src.height * s).roundToInt())
        val inner = resize(src, w, h)
        val px = (outW - w) / 2; val py = (outH - h) / 2
        val fill = (0xff shl 24) or (pad shl 16) or (pad shl 8) or pad
        val out = IntArray(outW * outH) { fill }
        for (y in 0 until h) System.arraycopy(inner.argb, y * w, out, (y + py) * outW + px, w)
        return RgbImage(outW, outH, out) to Letterbox(s, px, py)
    }

    /** One channel value after range mapping and normalisation (before quantization). */
    fun normalize(v: Int, c: Int, p: ImagePrep): Double = ((p.lo + v / 255.0 * (p.hi - p.lo)) - p.mean[c]) / p.std[c]

    /** Quantize a real value: round(x / scale) + zeroPoint, clamped to the integer type. */
    fun quantize(x: Double, scale: Double, zeroPoint: Int, dtype: String): Int {
        val q = Math.round(x / scale).toInt() + zeroPoint
        return when (dtype) {
            "int8" -> q.coerceIn(-128, 127)
            "uint16" -> q.coerceIn(0, 65535)
            "int16" -> q.coerceIn(-32768, 32767)
            else -> q.coerceIn(0, 255)
        }
    }

    /** Image → input tensor bytes per [p] (resize, layout, dtype, normalisation, quantization). */
    fun imageToInput(img: RgbImage, p: ImagePrep): Pair<ByteArray, Letterbox> {
        val (sized, lb) = if (p.resize == "letterbox") letterbox(img, p.width, p.height, p.pad)
            else resize(img, p.width, p.height) to Letterbox(p.width.toDouble() / img.width, 0, 0)
        val n = p.width * p.height
        val es = elementSize(p.dtype)
        val buf = ByteBuffer.allocate(n * 3 * es).order(ByteOrder.LITTLE_ENDIAN)
        fun put(v: Int, c: Int) {
            val x = normalize(v, c, p)
            when (p.dtype) {
                "float32" -> buf.putFloat(x.toFloat())
                "float16" -> buf.putShort(floatToHalf(x.toFloat()))
                "uint8", "int8" -> buf.put((if (p.scale != null) quantize(x, p.scale, p.zeroPoint, p.dtype) else x.roundToInt().coerceIn(if (p.dtype == "int8") -128 else 0, if (p.dtype == "int8") 127 else 255)).toByte())
                "uint16", "int16" -> buf.putShort((if (p.scale != null) quantize(x, p.scale, p.zeroPoint, p.dtype) else x.roundToInt()).toShort())
                "int32" -> buf.putInt(x.roundToInt())
                else -> throw IllegalArgumentException("unsupported image dtype ${p.dtype}")
            }
        }
        if (p.layout == "NCHW") {
            for (c in 0 until 3) for (i in 0 until n) put(channel(sized, i, c), c)
        } else {
            for (i in 0 until n) for (c in 0 until 3) put(channel(sized, i, c), c)
        }
        return buf.array() to lb
    }

    private fun channel(img: RgbImage, i: Int, c: Int) = when (c) { 0 -> img.r(i); 1 -> img.g(i); else -> img.b(i) }

    /** Decodes a JPEG/PNG with BitmapFactory (Android only). */
    fun decodeImageFile(file: File): RgbImage {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalArgumentException("cannot decode image ${file.absolutePath}")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return RgbImage(bmp.width, bmp.height, px).also { bmp.recycle() }
    }

    // ---- values → bytes ----------------------------------------------------------------------------------------

    /**
     * Any accepted input value → little-endian bytes of [dtype]: ByteBuffer/ByteArray (raw, taken as is),
     * FloatArray, IntArray, LongArray, ShortArray, DoubleArray, a base64 String (raw), a JsonArray or List of
     * numbers (encoded as [dtype], nested arrays flattened).
     */
    fun toBytes(value: Any, dtype: String): ByteArray = when (value) {
        is ByteArray -> value
        is ByteBuffer -> value.duplicate().let { b -> b.rewind(); ByteArray(b.remaining()).also { b.get(it) } }
        is String -> Base64.getDecoder().decode(value)
        is FloatArray -> encode(value.size, dtype) { value[it].toDouble() }
        is DoubleArray -> encode(value.size, dtype) { value[it] }
        is IntArray -> encode(value.size, dtype) { value[it].toDouble() }
        is LongArray -> encode(value.size, dtype) { value[it].toDouble() }
        is ShortArray -> ByteBuffer.allocate(value.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> value.forEach { b.putShort(it) } }.array()
        is JsonElement -> flatten(value).let { l -> encode(l.size, dtype) { l[it] } }
        is List<*> -> value.map { (it as Number).toDouble() }.let { l -> encode(l.size, dtype) { l[it] } }
        else -> throw IllegalArgumentException("unsupported input value ${value.javaClass.simpleName}")
    }

    private fun flatten(e: JsonElement): List<Double> = when (e) {
        is JsonArray -> e.flatMap { flatten(it) }
        is JsonPrimitive -> listOf(e.doubleOrNull ?: throw IllegalArgumentException("not a number: $e"))
        else -> throw IllegalArgumentException("expected numbers, got $e")
    }

    private inline fun encode(n: Int, dtype: String, v: (Int) -> Double): ByteArray {
        val b = ByteBuffer.allocate(n * elementSize(dtype)).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val x = v(i)
            when (dtype.lowercase()) {
                "float32", "float" -> b.putFloat(x.toFloat())
                "float64", "double" -> b.putDouble(x)
                "float16" -> b.putShort(floatToHalf(x.toFloat()))
                "int32", "uint32" -> b.putInt(x.toLong().toInt())
                "int64" -> b.putLong(x.toLong())
                "int16", "uint16" -> b.putShort(x.toInt().toShort())
                "uint8", "int8", "bool" -> b.put(x.toInt().toByte())
                else -> throw IllegalArgumentException("unsupported dtype $dtype")
            }
        }
        return b.array()
    }

    /** A direct, native-order buffer holding [bytes] (what LiteRT and ORT want). */
    fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }

    // ---- bytes → values ----------------------------------------------------------------------------------------

    /** Real values of a raw tensor: quantized types use (q − zeroPoint)·scale when [scale] is given. */
    fun dequantize(bytes: ByteArray, dtype: String, scale: Double? = null, zeroPoint: Int = 0): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bytes.size / elementSize(dtype)
        fun q(raw: Int): Float = if (scale != null) ((raw - zeroPoint) * scale).toFloat() else raw.toFloat()
        return FloatArray(n) { i ->
            when (dtype.lowercase()) {
                "uint8" -> q(bytes[i].toInt() and 0xff)
                "int8" -> q(bytes[i].toInt())
                "uint16" -> q(b.getShort(i * 2).toInt() and 0xffff)
                "int16" -> q(b.getShort(i * 2).toInt())
                "int32" -> q(b.getInt(i * 4))
                "int64" -> b.getLong(i * 8).toFloat()
                "float16" -> halfToFloat(b.getShort(i * 2))
                "float32", "float" -> b.getFloat(i * 4)
                else -> throw IllegalArgumentException("unsupported dtype $dtype")
            }
        }
    }

    /** Indices of the [k] largest values, ties broken by the lower index (deterministic). */
    fun topK(values: FloatArray, k: Int): List<Pair<Int, Float>> =
        values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).take(k).map { it to values[it] }

    fun argmax(values: FloatArray): Int = topK(values, 1).firstOrNull()?.first ?: -1

    fun softmax(values: FloatArray): FloatArray {
        val m = values.maxOrNull() ?: return values
        val e = DoubleArray(values.size) { Math.exp((values[it] - m).toDouble()) }
        val s = e.sum()
        return FloatArray(values.size) { (e[it] / s).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12)
    }

    /** sha256 over the outputs' bytes in order, hex (replicas exchange this, §4A.3). */
    fun sha256Hex(parts: Iterable<ByteArray>): String = MessageDigest.getInstance("SHA-256").let { md ->
        parts.forEach { md.update(it) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- fp16 --------------------------------------------------------------------------------------------------

    fun halfToFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = bits and 0x8000
        var exp = (bits ushr 10) and 0x1f
        var mant = bits and 0x3ff
        val out: Int = when {
            exp == 0 && mant == 0 -> sign shl 16
            exp == 0 -> { // subnormal: normalise
                exp = 1
                while (mant and 0x400 == 0) { mant = mant shl 1; exp-- }
                mant = mant and 0x3ff
                (sign shl 16) or ((exp + 112) shl 23) or (mant shl 13)
            }
            exp == 31 -> (sign shl 16) or 0x7f800000 or (mant shl 13)
            else -> (sign shl 16) or ((exp + 112) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }

    /** Round-to-nearest-even float → IEEE half. */
    fun floatToHalf(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xff
        val mant = bits and 0x7fffff
        if (exp == 0xff) return (sign or 0x7c00 or (if (mant != 0) 0x200 else 0)).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1f) return (sign or 0x7c00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            val m = mant or 0x800000
            val shift = 14 - e
            var h = m ushr shift
            val rem = m and ((1 shl shift) - 1); val half = 1 shl (shift - 1)
            if (rem > half || (rem == half && (h and 1) == 1)) h++
            return (sign or h).toShort()
        }
        var h = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h++
        return (sign or h).toShort()
    }

    // ---- seeded test inputs (the §4A.5 spike's generator, so INFER reproduces its hashes) ----------------------

    /**
     * Seeded bytes for input #[idx]: Random(7 + idx). float32 → nextFloat; int32/int64 → 1 for "mask" inputs,
     * 0 for "position" inputs on ORT ([ort] = true), else nextInt(1, 1000); float16 → 0.5 on ORT;
     * anything else → random bytes.
     */
    fun seeded(d: TensorDesc, idx: Int, ort: Boolean): ByteArray {
        val rnd = Random(7 + idx)
        val n = d.elements.toInt()
        val lname = d.name.lowercase()
        val mask = "mask" in lname
        val pos = ort && "position" in lname
        val b = ByteBuffer.allocate(d.numBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        when (d.dtype) {
            "float32" -> repeat(n) { b.putFloat(rnd.nextFloat()) }
            "int32" -> repeat(n) { b.putInt(if (mask) 1 else if (pos) 0 else rnd.nextInt(1, 1000)) }
            "int64" -> repeat(n) { b.putLong(if (mask) 1L else rnd.nextLong(1, 1000)) }
            "float16" -> if (ort) repeat(n) { b.putShort(0x3800) } else repeat(n * 2) { b.put(rnd.nextInt(0, 256).toByte()) }
            else -> repeat(d.numBytes.toInt()) { b.put(rnd.nextInt(0, 256).toByte()) }
        }
        return b.array()
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The manifest's `inputSpec` / `outputSpec` (written by tools/fetch_aihub_models.py from AI Hub's metadata.json):
 * `{"<name>" or "<graph>.<name>": {"name", "graph", "shape": [..], "dtype", "scale"?, "zeroPoint"?, "ioType"?}}`.
 */
object ManifestSpecs {
    fun parse(spec: JsonObject): List<TensorDesc> = spec.entries.mapNotNull { (key, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        val graph = o.str("graph") ?: (if ('.' in key) key.substringBefore('.') else "")
        TensorDesc(
            name = o.str("name") ?: key.substringAfter('.'),
            dtype = o.str("dtype") ?: "float32",
            shape = (o["shape"] as? JsonArray)?.map { it.jsonPrimitive.content.toLong() } ?: emptyList(),
            graph = graph,
            scale = (o["scale"] as? JsonPrimitive)?.doubleOrNull,
            zeroPoint = (o["zeroPoint"] as? JsonPrimitive)?.intOrNull,
        )
    }
}
