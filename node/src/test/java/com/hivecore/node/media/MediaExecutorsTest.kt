package com.hivecore.node.media

import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaExecutorsTest {
    private val dir: File = Files.createTempDirectory("media").toFile()

    /** 70 s of loud audio with a quiet gap at 27 s; the "file" content is ignored by the fake decoder. */
    private val pcm = ShortArray(70 * 16000) { if (it in 432000 until 433600) 0 else if (it % 2 == 0) 3000 else -3000 }
    private val blob = File(dir, "meeting.wav").also { it.writeBytes(byteArrayOf(1)) }
    private val calls = ArrayList<Pair<String, Int>>()
    private val frames = FrameSource { v, p, _ ->
        FrameSampleResult(12_000, SceneMath.fixedTimes(12_000, p.everyMs).map { SampledFrame(it, "fixed", "f$it.jpg", "sha$it", 640, 360) })
    }
    private val ex = MediaExecutor({ id, a -> calls += id to a.size; AsrResult("words ${a.size / 16000}", listOf(a.size / 16000), 1, 2, 3, 4) },
        { pcm }, frames, dir)

    private fun task(op: Op, params: String = "{}") = TaskSpec("t", op, Json.parseToJsonElement(params).jsonObject)

    @Test fun vadSplitJsonIsStable() = runBlocking {
        val a = ex.execute(task(Op.VAD_SPLIT), emptyList(), listOf(blob)).getOrThrow()
        val b = ex.execute(task(Op.VAD_SPLIT), emptyList(), listOf(blob)).getOrThrow()
        assertEquals(a.toString(), b.toString())
        val segs = a.jsonObject["segments"]!!.jsonArray
        assertEquals(3, segs.size)
        assertEquals("""{"i":0,"start":0,"end":433280,"startMs":0,"endMs":27080,"speech":true}""", segs[0].toString())
        assertEquals("70000", a.jsonObject["durationMs"].toString())
    }

    @Test fun asrRunsVadSegmentsAndStitches() = runBlocking {
        val vad = ex.execute(task(Op.VAD_SPLIT), emptyList(), listOf(blob)).getOrThrow()
        val r = ex.execute(task(Op.ASR), listOf(vad), listOf(blob)).getOrThrow().jsonObject
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.first == "whisper_tiny" && it.second <= 30 * 16000 })
        assertEquals("""{"i":0,"startMs":0,"endMs":27080,"text":"words 27","tokens":[27]}""", r["segments"]!!.jsonArray[0].toString())
        assertEquals("ASR", r["op"]!!.jsonPrimitive.content)
        // one shard by params
        val one = ex.execute(task(Op.ASR, """{"startMs":1000,"endMs":3000,"i":4}"""), emptyList(), listOf(blob)).getOrThrow().jsonObject
        assertEquals("words 2", one["text"]!!.jsonPrimitive.content)
        // shard merge: no blobs, ASR outputs as inputs, out of order
        val s1 = ex.execute(task(Op.ASR, """{"startMs":0,"endMs":30000,"i":0}"""), emptyList(), listOf(blob)).getOrThrow()
        val s2 = ex.execute(task(Op.ASR, """{"startMs":29000,"endMs":40000,"i":1}"""), emptyList(), listOf(blob)).getOrThrow()
        val m = ex.execute(task(Op.ASR), listOf(s2, s1), emptyList()).getOrThrow().jsonObject
        assertEquals("words 30 words 11", m["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("0", "1"), m["segments"]!!.jsonArray.map { it.jsonObject["i"].toString() })
    }

    @Test fun frameSampleAndTrackAgg() = runBlocking {
        val fs = ex.execute(task(Op.FRAME_SAMPLE, """{"everyMs":5000}"""), emptyList(), listOf(blob)).getOrThrow()
        assertEquals("""{"i":1,"tMs":5000,"reason":"fixed","file":"f5000.jpg","sha256":"sha5000","w":640,"h":360}""",
            fs.jsonObject["frames"]!!.jsonArray[1].toString())
        fun det(item: String, y: Int) = Json.parseToJsonElement(
            """{"item":"$item","w":400,"h":400,"detections":[{"label":"person","box":[50,$y,90,${y + 80}],"score":0.9}]}""")
        val detect = JsonObject(mapOf("op" to Json.parseToJsonElement("\"DETECT\""),
            "items" to JsonArray(listOf(det("f10000.jpg", 180), det("f0.jpg", 100), det("f5000.jpg", 140)))))
        val params = """{"line":{"x1":0,"y1":0.5,"x2":1,"y2":0.5},"startMs":59000}"""
        val a = ex.execute(task(Op.TRACK_AGG, params), listOf(fs, detect), emptyList()).getOrThrow()
        val b = ex.execute(task(Op.TRACK_AGG, params), listOf(detect, fs), emptyList()).getOrThrow()
        assertEquals(a.toString(), b.toString())
        assertEquals("""{"op":"TRACK_AGG","frames":3,"tracks":[{"id":1,"label":"person","firstMs":59000,"lastMs":69000,"frames":3,"crossedMs":69000}],"counts":[{"minute":1,"label":"person","n":1}]}""",
            a.toString())
    }
}
