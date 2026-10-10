package com.hivecore.node.gen

import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Png
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenExecutorsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Generator that paints a flat colour derived from the seed (no model). */
    class FakeGen : ImageGenerator {
        val seeds = ArrayList<Long>()
        override suspend fun generate(p: GenParams): GenImage {
            seeds += p.seed
            val c = (p.seed * 37 % 256).toInt()
            val img = Img.filled(8, 8, Img.rgb(c, 255 - c, 100))
            val rgb = ByteArray(8 * 8 * 3) { k -> when (k % 3) { 0 -> c.toByte(); 1 -> (255 - c).toByte(); else -> 100 } }
            val meta = buildJsonObject { put("aiGenerated", true); put("seed", p.seed) }
            val png = SdPipeline.withText(Png.encode(img, gray = false), mapOf("AI-Generated" to "true"))
            return GenImage(png, rgb, 8, 8, meta, SdPipeline.sha256(png), SdPipeline.sha256(rgb), mapOf("total" to System.nanoTime() % 1000), emptyMap())
        }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun task(op: Op, params: String) = TaskSpec("t1", op, obj(params))

    @Test fun genImageStableJsonAndFiles() = runTest {
        val out = File(tmp.root, "gen")
        val a = GenExecutor(FakeGen(), out).execute(task(Op.GEN_IMAGE, """{"prompt":"a red mug","seed":7}"""), emptyList(), emptyList()).getOrThrow()
        val b = GenExecutor(FakeGen(), out).execute(task(Op.GEN_IMAGE, """{"prompt":"a red mug","seed":7}"""), emptyList(), emptyList()).getOrThrow()
        assertEquals(a.toString(), b.toString())   // no timings or paths that vary
        val item = a.jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("seed", "out", "meta", "sha256", "rgbSha256", "w", "h", "aiGenerated", "postCheck"), item.keys.toList())
        assertTrue(File(out, item["out"]!!.jsonPrimitive.content).isFile)
        assertTrue(File(out, item["meta"]!!.jsonPrimitive.content).readText().contains("\"aiGenerated\":true"))
        assertEquals("false", a.jsonObject["blocked"]!!.jsonPrimitive.content)
    }

    @Test fun blockedPromptNeverGenerates() = runTest {
        val gen = FakeGen(); val out = File(tmp.root, "gen")
        val r = GenExecutor(gen, out).execute(task(Op.GEN_IMAGE, """{"prompt":"a nude woman","seed":1}"""), emptyList(), emptyList()).getOrThrow().jsonObject
        assertEquals("true", r["blocked"]!!.jsonPrimitive.content)
        assertEquals("sexual", r["category"]!!.jsonPrimitive.content)
        assertTrue(gen.seeds.isEmpty())
        assertFalse(out.exists() && out.listFiles()!!.isNotEmpty())
    }

    @Test fun postCheckDropsOutput() = runTest {
        val out = File(tmp.root, "gen")
        val r = GenExecutor(FakeGen(), out, postCheck = { false })
            .execute(task(Op.GEN_IMAGE, """{"prompt":"a red mug","seed":1}"""), emptyList(), emptyList()).getOrThrow().jsonObject
        val item = r["items"]!!.jsonArray.single().jsonObject
        assertEquals("output", item["stage"]!!.jsonPrimitive.content)
        assertFalse(out.exists() && out.listFiles()!!.isNotEmpty())
    }

    @Test fun variantsFanOutOverSeeds() = runTest {
        val gen = FakeGen()
        val r = GenExecutor(gen, tmp.root).execute(task(Op.GEN_VARIANTS, """{"prompt":"a red mug","seed":10,"count":3}"""), emptyList(), emptyList()).getOrThrow()
        assertEquals(listOf(10L, 11L, 12L), gen.seeds)
        assertEquals(3, r.jsonObject["items"]!!.jsonArray.size)
        val g2 = FakeGen()
        GenExecutor(g2, tmp.root).execute(task(Op.GEN_VARIANTS, """{"prompt":"a red mug","seeds":[5,2]}"""), emptyList(), emptyList()).getOrThrow()
        assertEquals(listOf(5L, 2L), g2.seeds)
    }

    @Test fun scoreRanksDeterministically() = runTest {
        val dir = tmp.newFolder("c")
        val files = listOf("a.png", "b.png", "c.png").map { File(dir, it).apply { writeBytes(byteArrayOf(1)) } }
        val clip = mapOf("a.png" to 0.21, "b.png" to 0.30, "c.png" to 0.35)
        val codec = object : ImageCodec {
            override fun decode(file: File): Img = Img.filled(600, 600, Img.gray(if (file.name == "c.png") 5 else 128))   // c is dark
            override fun encode(img: Img, format: String, quality: Int) = Png.encode(img)
        }
        val ex = GenExecutor(FakeGen(), tmp.root, codec = codec, clip = { f, _ -> clip.getValue(f.name) }, postCheck = { true })
        val r = ex.execute(task(Op.SCORE_IMAGE, """{"prompt":"a red mug"}"""), emptyList(), files).getOrThrow().jsonObject
        // a & b are IQA-flat ("blurry") too; all fail IQA except none → order by clip among equals
        val ranking = r["ranking"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, ranking.size)
        val again = ex.execute(task(Op.SCORE_IMAGE, """{"prompt":"a red mug"}"""), emptyList(), files.reversed()).getOrThrow().jsonObject
        assertEquals(ranking, again["ranking"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("0.35", r["items"]!!.jsonArray[2].jsonObject["clip"]!!.jsonPrimitive.content)
        assertEquals(listOf("c.png", "b.png", "a.png"), ranking)
    }

    @Test fun upscaleDelegatesToSuperres() = runTest {
        var seen: TaskSpec? = null
        val sr = SuperRes { t, _ -> seen = t; Result.success(JsonPrimitive("ok") as JsonElement) }
        val f = File(tmp.root, "x.png").apply { writeBytes(byteArrayOf(1)) }
        val r = GenExecutor(FakeGen(), tmp.root, superRes = sr).execute(task(Op.UPSCALE, """{"overlap":16}"""), emptyList(), listOf(f)).getOrThrow().jsonObject
        assertEquals(Op.SUPERRES, seen!!.op)
        assertEquals("real_esrgan_w8a8", seen!!.modelId)
        assertEquals("UPSCALE", r["op"]!!.jsonPrimitive.content)
        assertEquals("true", r["aiGenerated"]!!.jsonPrimitive.content)
    }
}
