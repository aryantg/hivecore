package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.Comparator
import com.hivecore.core.Op
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Replica comparison (swarm plan §4A.3, §6.4). Rule kinds:
 *  - `{"kind":"hash"}` and no rule (null, `{}` or unknown kind): canonical-JSON equality ([Canon])
 *  - `{"kind":"cosine","min":x}` (default 0.999): two number arrays (nested arrays are flattened; an object's
 *    `vector`/`embedding` field is used) of equal length with cosine ≥ min; two zero vectors agree
 *  - `{"kind":"iou","min":x}` (default 0.5): detection lists `[{label, box:[l,t,r,b], score}]` (or an object's
 *    `detections`): same count, and every detection pairs one-to-one with a same-label detection of IoU ≥ min
 *    (greedy, highest IoU first, ties by index); scores are not compared
 *  - `{"kind":"text"}`: trimmed string equality (non-strings: canonical form)
 */
class ComparatorImpl : Comparator {

    override fun agree(op: Op, a: JsonElement, b: JsonElement, rule: JsonObject?): Boolean {
        val kind = (rule?.get("kind") as? JsonPrimitive)?.content
        val min = (rule?.get("min") as? JsonPrimitive)?.content?.toDoubleOrNull()
        return when (kind) {
            "cosine" -> cosine(a, b, min ?: 0.999)
            "iou" -> iou(a, b, min ?: 0.5)
            "text" -> text(a).trim() == text(b).trim()
            else -> Canon.equal(a, b)
        }
    }

    private fun text(e: JsonElement): String = if (e is JsonPrimitive && e.isString) e.content else Canon.canonical(e)

    private fun cosine(a: JsonElement, b: JsonElement, min: Double): Boolean {
        val x = vector(a) ?: return false
        val y = vector(b) ?: return false
        if (x.size != y.size || x.isEmpty()) return false
        var dot = 0.0; var nx = 0.0; var ny = 0.0
        for (i in x.indices) { dot += x[i] * y[i]; nx += x[i] * x[i]; ny += y[i] * y[i] }
        if (nx == 0.0 && ny == 0.0) return true
        if (nx == 0.0 || ny == 0.0) return false
        return dot / (sqrt(nx) * sqrt(ny)) >= min
    }

    private fun vector(e: JsonElement): List<Double>? {
        val src = if (e is JsonObject) e["vector"] ?: e["embedding"] ?: return null else e
        val out = ArrayList<Double>()
        fun walk(x: JsonElement): Boolean = when (x) {
            is JsonArray -> x.all { walk(it) }
            is JsonPrimitive -> if (x.isString) false else x.content.toDoubleOrNull()?.also { out += it } != null
            else -> false
        }
        return if (src is JsonArray && walk(src)) out else null
    }

    private data class Det(val label: String, val box: DoubleArray)

    private fun detections(e: JsonElement): List<Det>? {
        val arr = (if (e is JsonObject) e["detections"] else e) as? JsonArray ?: return null
        return arr.map { d ->
            val o = d as? JsonObject ?: return null
            val label = (o["label"] as? JsonPrimitive)?.content ?: return null
            val box = (o["box"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null } ?: return null
            if (box.size != 4) return null
            Det(label, box.toDoubleArray())
        }
    }

    private fun iou(a: JsonElement, b: JsonElement, min: Double): Boolean {
        val x = detections(a) ?: return false
        val y = detections(b) ?: return false
        if (x.size != y.size) return false
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for (i in x.indices) for (j in y.indices) if (x[i].label == y[j].label) {
            val v = iouOf(x[i].box, y[j].box)
            if (v >= min) pairs += Triple(i, j, v)
        }
        pairs.sortWith(compareByDescending<Triple<Int, Int, Double>> { it.third }.thenBy { it.first }.thenBy { it.second })
        val usedX = HashSet<Int>(); val usedY = HashSet<Int>()
        for ((i, j, _) in pairs) if (i !in usedX && j !in usedY) { usedX += i; usedY += j }
        return usedX.size == x.size
    }

    companion object {
        fun iouOf(p: DoubleArray, q: DoubleArray): Double {
            val iw = max(0.0, min(p[2], q[2]) - max(p[0], q[0]))
            val ih = max(0.0, min(p[3], q[3]) - max(p[1], q[1]))
            val inter = iw * ih
            val area = { r: DoubleArray -> max(0.0, r[2] - r[0]) * max(0.0, r[3] - r[1]) }
            val union = area(p) + area(q) - inter
            return if (union <= 0.0) (if (p.contentEquals(q)) 1.0 else 0.0) else inter / union
        }
    }
}
