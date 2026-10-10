package com.hivecore.node.vision

import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The ModelRuntime I/O convention this package assumes (to reconcile with package `models`):
//  - manifest.inputSpec / outputSpec are AI Hub metadata.json style: {"<tensor name>": {"shape": [...], "dtype":
//    "uint8|int8|int32|float32|uint16|float16", "quantization_parameters": {"scale": s, "zero_point": z}}, ...} in
//    model order (an {"inputs": {...}} / {"outputs": {...}} wrapper and flat "scale"/"zero_point" are accepted too).
//  - inputs we send: key = tensor name; value = ByteArray of raw tensor bytes (uint8/int8, or little-endian int32),
//    or FloatArray for float32.
//  - outputs we read: by tensor name, else by position "0","1",…; value ByteArray (raw bytes, little-endian for
//    multi-byte dtypes, decoded per the spec dtype), FloatArray, IntArray, ByteBuffer or a single-element map.

data class TensorSpec(val name: String, val index: Int, val shape: List<Int>, val dtype: String, val scale: Double?, val zeroPoint: Int) {
    val count: Int get() = shape.fold(1) { a, b -> a * b }
    val quantized: Boolean get() = scale != null
}

object TensorIo {
    fun specs(spec: JsonObject, wrapper: String): List<TensorSpec> {
        val body = (spec[wrapper] as? JsonObject) ?: spec
        return body.entries.filter { it.value is JsonObject }.mapIndexed { i, (name, v) ->
            v as JsonObject
            val shape = (v["shape"] as? JsonArray)?.map { (it as JsonPrimitive).intOrNull ?: 0 } ?: emptyList()
            val dtype = (v["dtype"] as? JsonPrimitive)?.content ?: "float32"
            val q = (v["quantization_parameters"] as? JsonObject) ?: v
            val scale = (q["scale"] as? JsonPrimitive)?.doubleOrNull
            val zp = ((q["zero_point"] ?: q["zeroPoint"]) as? JsonPrimitive)?.intOrNull ?: 0
            TensorSpec(name, i, shape, dtype, scale, zp)
        }
    }

    fun inputs(spec: JsonObject) = specs(spec, "inputs")
    fun outputs(spec: JsonObject) = specs(spec, "outputs")

    /** The runtime's value for [spec]: by name, else by position, else the only output. */
    fun find(outputs: Map<String, Any>, spec: TensorSpec): Any =
        outputs[spec.name] ?: outputs[spec.index.toString()] ?: outputs.values.singleOrNull()
            ?: throw IllegalStateException("output ${spec.name} missing (have ${outputs.keys.sorted()})")

    /** Raw (non-dequantized) integer values: uint8 as 0..255, int8 signed, int32/uint16 as is. */
    fun ints(value: Any, spec: TensorSpec): IntArray = when (value) {
        is IntArray -> value
        is LongArray -> IntArray(value.size) { value[it].toInt() }
        is FloatArray -> IntArray(value.size) { Math.round(value[it]) }
        is ByteBuffer -> ints(value.duplicate().let { b -> b.rewind(); ByteArray(b.remaining()).also { b.get(it) } }, spec)
        is ByteArray -> when (spec.dtype) {
            "int8" -> IntArray(value.size) { value[it].toInt() }
            "int32" -> le(value).asIntBuffer().let { b -> IntArray(b.remaining()).also { b.get(it) } }
            "int64" -> le(value).asLongBuffer().let { b -> IntArray(b.remaining()) { b.get(it).toInt() } }
            "uint16" -> le(value).asShortBuffer().let { b -> IntArray(b.remaining()) { b.get(it).toInt() and 0xFFFF } }
            "float32" -> le(value).asFloatBuffer().let { b -> IntArray(b.remaining()) { Math.round(b.get(it)) } }
            else -> IntArray(value.size) { value[it].toInt() and 255 }
        }
        else -> throw IllegalArgumentException("unsupported tensor value ${value::class.java.simpleName}")
    }

    /** Real values: dequantized ((q - zp) * scale) when the spec is quantized, else the float data. */
    fun floats(value: Any, spec: TensorSpec): FloatArray {
        if (value is FloatArray) return value
        if (value is ByteArray && spec.dtype == "float32") return le(value).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        if (value is ByteArray && spec.dtype == "float16") return le(value).asShortBuffer().let { b -> FloatArray(b.remaining()) { half(b.get(it)) } }
        if (value is ByteBuffer) return floats(value.duplicate().let { b -> b.rewind(); ByteArray(b.remaining()).also { b.get(it) } }, spec)
        val q = ints(value, spec)
        val s = spec.scale ?: return FloatArray(q.size) { q[it].toFloat() }
        val sf = s.toFloat()
        return FloatArray(q.size) { (q[it] - spec.zeroPoint) * sf }
    }

    fun read(outputs: Map<String, Any>, spec: TensorSpec): FloatArray = floats(find(outputs, spec), spec)

    /** Packs int32 values little-endian (token ids). */
    fun int32Bytes(v: IntArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()

    fun floatBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun half(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = bits ushr 15; val exp = (bits ushr 10) and 0x1F; val mant = bits and 0x3FF
        val v = when (exp) {
            0 -> mant * StrictMath.pow(2.0, -24.0)
            31 -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> (1 + mant / 1024.0) * StrictMath.pow(2.0, (exp - 15).toDouble())
        }
        return (if (sign == 1) -v else v).toFloat()
    }

    /** NHWC image input [1,H,W,C] → (H, W). */
    fun imageHw(spec: TensorSpec): Pair<Int, Int> {
        require(spec.shape.size == 4) { "image input ${spec.name} must be NHWC, got ${spec.shape}" }
        return spec.shape[1] to spec.shape[2]
    }

    /** Pack [img] for an NHWC image input per its dtype/quantization. */
    fun packImage(img: Img, spec: TensorSpec, mean: FloatArray? = null, std: FloatArray? = null): Any = when (spec.dtype) {
        "uint8" -> Pixels.toNhwcU8(img, spec.scale ?: (1.0 / 255.0), spec.zeroPoint)
        "float32" -> Pixels.toNhwcFloat(img, mean ?: floatArrayOf(0f, 0f, 0f), std ?: floatArrayOf(1f, 1f, 1f))
        else -> throw IllegalArgumentException("unsupported image input dtype ${spec.dtype}")
    }
}
