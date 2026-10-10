package com.hivecore.node

import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.SplitInput
import com.hivecore.engine.split.SplitProgress
import com.hivecore.engine.split.SplitPlan
import com.hivecore.engine.split.SplitCommander
import com.hivecore.engine.split.ChunkPlanner
import com.hivecore.engine.split.Mergers
import com.hivecore.engine.split.Splitters
import com.hivecore.engine.text.Sentence
import com.hivecore.core.MergeKind
import com.hivecore.core.AggTable
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.LlmBackend
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.fakes.FakeClosedChooser
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.node.data.Fixtures
import com.hivecore.core.Canon
import com.hivecore.engine.ReducerImpl
import com.hivecore.node.textai.TextExecutor
import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.engine.pixels.Pixels
import com.hivecore.node.vision.PixelsTest.Companion.nearest
import com.hivecore.node.vision.PixelsTest.Companion.noise
import com.hivecore.engine.pixels.Png
import com.hivecore.node.vision.VisionExecutor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

/** merge(split(x)) == unsplit(x) for CONCAT / STITCH / MONOID / TOPK with the real executors (§4B.2), plus TREE/VOTE/RANK. */
class MergePropertiesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    /** Runs [job] split into its planned chunks and unsplit (one chunk), through [ex]; returns (merged, unsplit). */
    private suspend fun splitVsWhole(job: SplitJob, ex: OpExecutor, files: (List<String>) -> List<File> = { emptyList() }): Pair<JsonElement, JsonElement> {
        val plan = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow()
        assertTrue("expected several chunks, got ${plan.chunks.size}", plan.chunks.size >= 2)
        val outs = plan.chunks.map { c -> ex.execute(plan.task(c), emptyList(), files(c.blobs)).getOrThrow() }
        val merged = Mergers.merge(plan.kind, outs, plan.chunks, job.params)
        val whole = Splitters.whole(job.opKey, job.params, job.input)
        val unsplit = ex.execute(plan.task(whole), emptyList(), files(whole.blobs)).getOrThrow()
        return merged to unsplit
    }

    // ------------------------------------------------------------------ text executors

    private val testVocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "want", "##want", "##ed", "wa", "un", "runn", "##ing", ",", "low", "lowest", "!", "?")

    /** MiniLM-style runtime: the embedding is a function of the first token id. */
    private class MiniRuntime : ModelRuntime {
        override val id = "scripted"
        override fun supports(m: ModelManifest) = true
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> =
            Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> {
            val ids = java.nio.ByteBuffer.wrap(inputs["input_ids"] as ByteArray).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val a = ids.get(1)
            return Result.success(mapOf("embeddings" to byteArrayOf((134 + a).toByte(), (134 + 10 - a).toByte(), (134 + a % 3).toByte(), 134.toByte())))
        }
    }

    private fun textExecutor(): TextExecutor {
        val m = ModelManifest("minilm_v2_w8a8", "text_embedding", "litert_qnn", files = listOf("minilm_v2.tflite"),
            inputSpec = obj("""{"input_ids":{"shape":[1,8],"dtype":"int32"},"attention_mask":{"shape":[1,8],"dtype":"int32"}}"""),
            outputSpec = obj("""{"embeddings":{"shape":[1,4],"dtype":"uint8","quantization_parameters":{"scale":0.01,"zero_point":134}}}"""),
            sha256 = "0", licence = "MIT")
        val root = tmp.newFolder("models")
        File(root, m.id).mkdirs()
        File(File(root, m.id), "vocab.txt").writeText(testVocab.joinToString("\n") + "\n")
        val reg = FakeModelRegistry(listOf(m), root)
        val rt = MiniRuntime()
        val chooser = FakeClosedChooser().apply { answers["want"] = 1; answers["low"] = 2 }
        return TextExecutor(FakeLlmEngine(LlmBackend.NPU), chooser, FakeModelPool(reg, rt), rt, reg)
    }

    private val words = listOf("want", "lowest", "low", "unwanted", "running", "wa", "un", "want running", "lowest low", "runn")
    private fun texts(n: Int) = (0 until n).map { i -> buildJsonObject { put("id", "t%03d".format(i)); put("text", words[(i * 7) % words.size]) } }

    @Test fun embedConcatEqualsUnsplit() = runTest {
        val ex = textExecutor()
        val job = SplitJob("e", Op.EMBED, obj("""{"includeText":true}"""), "minilm_v2_w8a8", SplitInput.Items(texts(23)), unitsPerChunk = 5)
        val (merged, unsplit) = splitVsWhole(job, ex)
        assertEquals(Canon.canonical(unsplit), Canon.canonical(merged))
        assertEquals(23, merged.jsonObject["items"]!!.jsonArray.size)
    }

    @Test fun textClassifyConcatEqualsUnsplit() = runTest {
        val ex = textExecutor()
        val job = SplitJob("c", Op.TEXT_CLASSIFY, obj("""{"labels":["action","decision","info"]}"""), input = SplitInput.Items(texts(17)), chunks = 4)
        val (merged, unsplit) = splitVsWhole(job, ex)
        assertEquals(Canon.canonical(unsplit), Canon.canonical(merged))
    }

    private val longText = (1..30).joinToString(" ") { i -> if (i % 3 == 0) "We want item $i delivered." else if (i % 3 == 1) "The lowest bid was $i lakh." else "Running total stays at $i." }

    @Test fun sentenceClassifyStitchWithOverlapEqualsUnsplit() = runTest {
        val ex = textExecutor()
        val job = SplitJob("s", Op.TEXT_CLASSIFY, obj("""{"labels":["action","decision","info"],"granularity":"sentence"}"""),
            input = SplitInput.Text(longText, doc = "memo", overlap = 2), unitsPerChunk = 7)
        val plan = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow()
        assertEquals(MergeKind.STITCH, plan.kind)
        assertEquals(2L, plan.chunks[1].overlapBefore)
        val (merged, unsplit) = splitVsWhole(job, ex)
        assertEquals(Canon.canonical(unsplit), Canon.canonical(merged))
        assertEquals(30, merged.jsonObject["items"]!!.jsonArray.size)
    }

    @Test fun knnTopKEqualsUnsplit() = runTest {
        val ex = textExecutor()
        val corpus = ex.execute(SplitJob("x", Op.EMBED, JsonObject(emptyMap()), "minilm_v2_w8a8", SplitInput.Items(texts(31))).let {
            ChunkPlanner().plan(it, emptyList(), "me").getOrThrow().task(Splitters.whole(it.opKey, it.params, it.input))
        }, emptyList(), emptyList()).getOrThrow().jsonObject["items"]!!.jsonArray
        val job = SplitJob("k", Op.KNN, obj("""{"query":[1,0.2,0,0],"k":7}"""), input = SplitInput.Items(corpus, key = "corpus"), unitsPerChunk = 6)
        val (merged, unsplit) = splitVsWhole(job, ex)
        assertEquals(MergeKind.TOPK, ChunkPlanner().plan(job, emptyList(), "me").getOrThrow().kind)
        assertEquals(Canon.canonical(unsplit), Canon.canonical(merged))
        assertEquals(7, merged.jsonObject["hits"]!!.jsonArray.size)
    }

    @Test fun treeSummaryIsDeterministicAndExtractive() = runTest {
        val ex = TextExecutor(null, null, null, null)
        val text = (1..90).joinToString(" ") { "Sentence $it reports budget figure ${it * 3} for region ${it % 4}." }
        val job = SplitJob("t", Op.SUMMARIZE, obj("""{"sentences":4,"chunkSentences":20}"""), input = SplitInput.Text(text, "report"))
        val plan = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow()
        assertEquals(MergeKind.TREE, plan.kind)
        assertEquals(5, plan.chunks.size)
        assertEquals(listOf("m1_0", "m1_1", "m2_0", "m3_0"), plan.dag.tasks.filter { it.id.startsWith("m") }.map { it.id })
        assertEquals("m3_0", plan.dag.output)
        val outs = plan.chunks.map { ex.execute(plan.task(it), emptyList(), emptyList()).getOrThrow() }
        val a = Mergers.merge(plan.kind, outs, plan.chunks, job.params)
        val b = Mergers.merge(plan.kind, outs, plan.chunks, job.params)
        assertEquals(Canon.hash(a), Canon.hash(b))
        val picks = a.jsonObject["picks"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals(4, picks.size)
        val sentences = Splitters.sentenceUnits("report", text).map { it["text"]!!.jsonPrimitive.content }
        assertTrue(picks.all { it in sentences })
        assertEquals("tree", a.jsonObject["method"]!!.jsonPrimitive.content)
        // An injected selector is used for every pairwise merge and can only pick ids from its children.
        val custom = Mergers.merge(plan.kind, outs, plan.chunks, job.params) { cands, k, _ -> cands.takeLast(k).map { it.id } }
        assertEquals(outs.last().jsonObject["picks"]!!.jsonArray.last(), custom.jsonObject["picks"]!!.jsonArray.last())
    }

    // ------------------------------------------------------------------ vision executors

    private class MapCodec(val images: Map<String, Img>) : ImageCodec {
        override fun decode(file: File): Img = images[file.name] ?: error("no image ${file.name}")
        override fun encode(img: Img, format: String, quality: Int): ByteArray = Png.encode(img)
    }

    private class ScriptedRuntime(val fn: (Map<String, Any>) -> Map<String, Any>) : ModelRuntime {
        override val id = "scripted"
        override fun supports(m: ModelManifest) = true
        override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> =
            Result.success(object : ModelSession { override val manifest = m; override fun close() {} })
        override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = Result.success(fn(inputs))
    }

    @Test fun iqaShardsConcatEqualUnsplit() = runTest {
        val imgs = (0 until 9).associate { i -> "p$i.jpg" to if (i % 3 == 0) Img.filled(300, 300, Img.gray(20)) else noise(320 + i, 240, seed = i.toLong()) }
        val ex = VisionExecutor(FakeModelPool(FakeModelRegistry()), ScriptedRuntime { emptyMap() }, MapCodec(imgs), tmp.newFolder("out"))
        val job = SplitJob("q", Op.IQA, JsonObject(emptyMap()), input = SplitInput.Blobs(imgs.keys.toList()), unitsPerChunk = 2)
        val (merged, unsplit) = splitVsWhole(job, ex) { ids -> ids.map { File(tmp.root, it) } }
        assertEquals(Canon.canonical(unsplit), Canon.canonical(merged))
        assertEquals(9, merged.jsonObject["items"]!!.jsonArray.size)
    }

    @Test fun superresTilesStitchEqualUnsplitAndPixelsMatch() = runTest {
        val esrgan = ModelManifest("esrgan_w8a8", "vision", "litert_qnn", files = listOf("e.tflite"),
            inputSpec = obj("""{"image": {"shape": [1,128,128,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003921568859368563, "zero_point": 0}}}"""),
            outputSpec = obj("""{"upscaled_image": {"shape": [1,512,512,3], "dtype": "uint8", "quantization_parameters": {"scale": 0.003921568859368563, "zero_point": 0}}}"""),
            sha256 = "0", licence = "Apache-2.0")
        val src = noise(300, 200, seed = 9)
        val rt = ScriptedRuntime { inputs -> mapOf("upscaled_image" to Pixels.toNhwcU8(nearest(Pixels.fromNhwcU8(inputs["image"] as ByteArray, 128, 128), 4))) }
        val out = tmp.newFolder("tiles")
        val reg = FakeModelRegistry(listOf(esrgan))
        val ex = VisionExecutor(FakeModelPool(reg, rt), rt, MapCodec(mapOf("wide.jpg" to src)), out, reg)
        val job = SplitJob("u", Op.SUPERRES, JsonObject(emptyMap()), "esrgan_w8a8", SplitInput.Image("wide.jpg", 300, 200, 128, 16), unitsPerChunk = 4)
        val (merged, unsplit) = splitVsWhole(job, ex) { ids -> ids.map { File(tmp.root, it) } }
        assertEquals(Canon.canonical(unsplit), Canon.canonical(merged))
        // The stitched pixels from the tile shares equal a single-pass upscale.
        val tiles = merged.jsonObject["items"]!!.jsonArray[0].jsonObject["tileOutputs"]!!.jsonArray
        assertEquals((0 until 6).toList(), tiles.map { it.jsonObject["index"]!!.jsonPrimitive.content.toInt() })
        val plan = Pixels.tilePlan(300, 200, 128, 16)
        // Each stored tile is the model's output for that tile, and those tiles stitch to the single-pass image.
        val expectedTiles = plan.tiles.map { nearest(Pixels.tileImage(src, it), 4) }
        tiles.forEachIndexed { i, t ->
            val f = File(out, t.jsonObject["out"]!!.jsonPrimitive.content)
            assertTrue(f.readBytes().contentEquals(Png.encode(expectedTiles[i])))
        }
        assertEquals(nearest(src, 4), Pixels.stitch(plan, expectedTiles, 4))
    }

    // ------------------------------------------------------------------ MONOID on the seed sales table

    private fun partial(db: JdbcSqlDb, from: Int, to: Int): AggTable {
        val rows = db.query("SELECT region, COUNT(*) AS n, SUM(revenue) AS sum_revenue, MIN(revenue) AS min_revenue, MAX(revenue) AS max_revenue, " +
            "AVG(revenue) AS avg_revenue FROM (SELECT * FROM t_sales ORDER BY rowid LIMIT ? OFFSET ?) GROUP BY region", listOf((to - from).toLong(), from.toLong()))
        val cols = listOf("region", "n", "sum_revenue", "min_revenue", "max_revenue", "avg_revenue")
        return AggTable("Sales", "revenue by region", cols, rows.map { r -> cols.map { c -> val v = r[c]; if (v is Number) JsonPrimitive(BigDecimal(v.toString())) else JsonPrimitive(v.toString()) } },
            (to - from).toLong(), 0, "sql")
    }

    @Test fun salesAggregatesSplitByRowRangesMergeToTheFullAggregate() {
        val db = JdbcSqlDb.inMemory()
        Fixtures.store(NodeRole.SALES, db)
        val total = (db.query("SELECT COUNT(*) AS c FROM t_sales").single()["c"] as Number).toInt()
        assertTrue(total > 20)
        val chunks = Splitters.table("AGG@", JsonObject(emptyMap()), SplitInput.Table("t_sales", total), 7)
        assertTrue(chunks.size >= 3)
        val parts = chunks.map { c -> Canon.encodeTable(partial(db, c.ownFrom.toInt(), c.ownTo.toInt())) }
        val merged = Mergers.monoid(parts)
        val whole = Mergers.monoid(listOf(Canon.encodeTable(partial(db, 0, total))))
        assertEquals(Canon.canonical(whole), Canon.canonical(merged))
        // and against SQL over the whole table directly
        val sql = db.query("SELECT region, COUNT(*) AS n, SUM(revenue) AS s, MAX(revenue) AS mx FROM t_sales GROUP BY region ORDER BY region")
        val t = Canon.decodeTable(merged)
        assertEquals(sql.map { it["region"] }, t.rows.map { it[0].jsonPrimitive.content })
        assertEquals(sql.map { (it["n"] as Number).toLong() }, t.rows.map { it[1].jsonPrimitive.content.toLong() })
        assertEquals(sql.map { BigDecimal(it["s"].toString()).stripTrailingZeros() }, t.rows.map { BigDecimal(it[2].jsonPrimitive.content).stripTrailingZeros() })
        assertEquals(total.toLong(), t.rowsScanned)
        // a reduce over the merged partials equals the reduce over the unsplit aggregate
        val p = obj("""{"measure":"sum_revenue","k":2}""")
        assertEquals(Canon.canonical(ReducerImpl().run(Op.RANK, p, listOf(whole)).getOrThrow()), Canon.canonical(ReducerImpl().run(Op.RANK, p, listOf(merged)).getOrThrow()))
    }

    @Test fun monoidObjectsAndHistograms() {
        val a = obj("""{"op":"IQA","count":3,"sum":12,"min":1,"max":7,"mean":4,"hist":{"ok":2,"dark":1}}""")
        val b = obj("""{"op":"IQA","count":1,"sum":8,"min":8,"max":8,"mean":8,"hist":{"ok":1,"blurry":1}}""")
        assertEquals("""{"op":"IQA","count":4,"sum":20,"min":1,"max":8,"mean":5,"hist":{"ok":3,"dark":1,"blurry":1}}""", Mergers.monoid(listOf(a, b)).toString())
    }

    // ------------------------------------------------------------------ STITCH audio, VOTE, RANK

    @Test fun audioSegmentsStitchByOwnedWindow() = runTest {
        val job = SplitJob("a", Op.ASR, JsonObject(emptyMap()), input = SplitInput.Audio(70_000, "rec", overlapMs = 1_000))
        val plan = ChunkPlanner().plan(job, emptyList(), "me").getOrThrow()
        assertEquals(listOf(0L to 28_000L, 28_000L to 56_000L, 56_000L to 70_000L), plan.chunks.map { it.ownFrom to it.ownTo })
        assertEquals("audio 00:28–00:56", Splitters.label(job.input, plan.chunks[1]))
        // each chunk "transcribes" 5 s segments over its whole window, overlap included
        val all = (0 until 14).map { it * 5_000L to it * 5_000L + 5_000L }
        val outs = plan.chunks.map { c ->
            val from = c.params["fromMs"]!!.jsonPrimitive.content.toLong(); val to = c.params["toMs"]!!.jsonPrimitive.content.toLong()
            buildJsonObject {
                put("op", "ASR")
                putJsonArray("segments") { all.filter { (s, e) -> e > from && s < to }.forEach { (s, e) -> add(buildJsonObject { put("start", s); put("end", e); put("text", "w$s") }) } }
            }
        }
        val merged = Mergers.merge(plan.kind, outs, plan.chunks, job.params)
        assertEquals(all.map { "w${it.first}" }, merged.jsonObject["segments"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
    }

    @Test fun voteAndRank() = runTest {
        val runs = listOf("yes", "no", "yes", "unclear", "yes").map { buildJsonObject { put("op", "VLM_ASK"); put("value", it) } }
        val v = Mergers.merge(MergeKind.VOTE, runs, emptyList(), JsonObject(emptyMap())).jsonObject
        assertEquals("yes", v["value"]!!.jsonPrimitive.content)
        assertEquals(0.6, v["confidence"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val tie = Mergers.merge(MergeKind.VOTE, runs.take(2), emptyList(), JsonObject(emptyMap())).jsonObject
        assertEquals("yes", tie["value"]!!.jsonPrimitive.content)   // first seen in chunk order
        val cands = listOf(
            buildJsonObject { putJsonArray("items") { add(buildJsonObject { put("item", "b"); put("score", 0.5) }); add(buildJsonObject { put("item", "c"); put("score", 0.9) }) } },
            buildJsonObject { putJsonArray("items") { add(buildJsonObject { put("item", "a"); put("score", 0.5) }) } })
        val r = Mergers.merge(MergeKind.RANK, cands, emptyList(), JsonObject(emptyMap())).jsonObject["items"]!!.jsonArray
        assertEquals(listOf("c", "a", "b"), r.map { it.jsonObject["item"]!!.jsonPrimitive.content })
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))), JsonArray(r.map { it.jsonObject["rank"]!! }))
    }
}
