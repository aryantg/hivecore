package com.hivecore.node.media

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.runtime.models.TensorIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A scripted Whisper: checks every decoder call's inputs and emits [script] after the 4 prompt tokens. */
class ScriptedWhisper(private val script: List<Int>, private val loud: Map<Int, Int> = emptyMap()) : ModelRuntime {
    override val id = "ort_qnn"
    val inputIds = ArrayList<Int>()
    var encoderCalls = 0
    var melBytes = 0
    private var step = 0
    override fun supports(m: ModelManifest) = true
    override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> =
        Result.success(object : ModelSession { override val manifest = m; override fun close() {} })

    private fun i32(v: Any): Int = ByteBuffer.wrap(TensorIo.toBytes(v, "int32")).order(ByteOrder.LITTLE_ENDIAN).int
    private fun halfs(v: Any): FloatArray = TensorIo.dequantize(TensorIo.toBytes(v, "float16"), "float16")

    override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = runCatching {
        if (inputs["graph"] == "encoder") {
            encoderCalls++
            melBytes = TensorIo.toBytes(inputs.getValue("input_features"), "float16").size
            (0 until 4).flatMap { i -> listOf("k_cache_cross_$i", "v_cache_cross_$i") }.associateWith { ByteArray(6 * 64 * 1500 * 2) { 7 } }
        } else {
            check(inputs["graph"] == "decoder")
            val n = step
            check(i32(inputs.getValue("position_ids")) == n) { "position ${i32(inputs.getValue("position_ids"))} at step $n" }
            val mask = halfs(inputs.getValue("attention_mask"))
            check(mask.size == 200)
            for (j in 0 until 200) check(mask[j] == if (j >= 199 - n) 0f else -100f) { "mask[$j] at step $n" }
            for (i in 0 until 4) for (kv in listOf("k", "v")) {
                val c = TensorIo.toBytes(inputs.getValue("${kv}_cache_self_${i}_in"), "float16")
                check(c.size == 6 * 64 * 199 * 2)
                check(c.all { it == n.toByte() }) { "self cache $kv$i not fed back at step $n" }
                check(TensorIo.toBytes(inputs.getValue("${kv}_cache_cross_$i"), "float16").all { it == 7.toByte() })
            }
            val tok = i32(inputs.getValue("input_ids"))
            inputIds += tok
            step++
            val target = if (n >= 3) script.getOrElse(n - 3) { WhisperTokenizer.EOT } else 0
            val logits = FloatArray(51865)
            logits[target] = 5f
            loud.forEach { (id, at) -> if (at == n) logits[id] = 9f }
            val out = LinkedHashMap<String, Any>()
            out["logits"] = WhisperAsr.halves(logits)
            for (i in 0 until 4) for (kv in listOf("k", "v")) out["${kv}_cache_self_${i}_out"] = ByteArray(6 * 64 * 199 * 2) { (n + 1).toByte() }
            out
        }
    }
}

class WhisperAsrTest {
    private val manifest = ModelManifest(id = "whisper_tiny", task = "asr", runtime = "ort_qnn", files = listOf("encoder.onnx", "decoder.onnx"),
        sha256 = "x", licence = "apache-2.0")

    private fun asr(rt: ScriptedWhisper, max: Int = 120) =
        WhisperAsr(FakeModelPool(FakeModelRegistry(listOf(manifest)), rt), rt, null, maxTokens = max)

    @Test fun decodeLoopFeedsCachesMaskAndStopsAtEot() = runBlocking {
        val rt = ScriptedWhisper(listOf(100, 200, 300))
        val r = asr(rt).transcribe(ShortArray(16000) { (it % 100).toShort() }).getOrThrow()
        assertEquals(listOf(100, 200, 300), r.tokens)
        assertEquals(1, rt.encoderCalls)
        assertEquals(80 * 3000 * 2, rt.melBytes)
        assertEquals(listOf(50258, 50259, 50359, 50363, 100, 200, 300), rt.inputIds)
        assertEquals(7, r.steps)                      // 4 prompt calls + 3 tokens; the EOT call ends it
        assertEquals("", r.text)                      // no tokenizer
    }

    @Test fun suppressesTimestampsAndFirstEot() = runBlocking {
        // step 3 (first generated): EOT is loudest → suppressed; step 4: a timestamp id is loudest → suppressed
        val rt = ScriptedWhisper(listOf(11, 12), loud = mapOf(WhisperTokenizer.EOT to 3, 50400 to 4))
        val r = asr(rt).transcribe(ShortArray(1600)).getOrThrow()
        assertEquals(listOf(11, 12), r.tokens)
    }

    @Test fun maxTokensAndDeterminism() = runBlocking {
        val long = List(500) { 1000 + it }
        val a = asr(ScriptedWhisper(long), max = 120).transcribe(ShortArray(8000) { it.toShort() }).getOrThrow()
        val b = asr(ScriptedWhisper(long), max = 120).transcribe(ShortArray(8000) { it.toShort() }).getOrThrow()
        assertEquals(120, a.tokens.size)
        assertEquals(a.tokens, b.tokens)
        assertTrue(a.steps <= 199)
    }

    @Test fun argmaxTiesGoToLowestId() {
        val l = FloatArray(51865); l[40] = 2f; l[30] = 2f
        assertEquals(30, WhisperAsr.argmax(WhisperAsr.halves(l), first = false))
    }
}
