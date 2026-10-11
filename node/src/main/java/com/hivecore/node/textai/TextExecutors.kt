package com.hivecore.node.textai

import com.hivecore.engine.text.Extractive
import com.hivecore.engine.text.Extraction
import com.hivecore.engine.text.SourceUnit
import com.hivecore.engine.text.Sentences
import com.hivecore.core.ChatMsg
import com.hivecore.core.ClosedChooser
import com.hivecore.core.GenParams
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.ModelRuntime
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import com.hivecore.node.vision.TensorIo
import com.hivecore.node.vision.TensorSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

// Swarm plan §4A.3 "Generative AI and text" executors. Text comes from (in order) params.text / params.texts
// [{id,text}] / params.units [{doc,unit,text}], the inputs (earlier task outputs: a string, or an object with
// units / texts / items[{item|id,text}] / segments[{text}] / picks[{id,doc,unit,text}] / text), and the blobs (UTF-8
// text files split into paragraphs "¶ n", doc = file name). Outputs are JSON with keys in a fixed order, lists in a
// fixed order and floats rounded (confidence 4 dp, scores 6 dp), so replicas compare by canonical-JSON hash. A model
// reply outside its closed schema fails the task with "rejected: …" (R3, R6); nothing is repaired.

internal object TJson {
    fun r4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
    fun r6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    fun JsonObject.strs(k: String): List<String>? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
}

/** Gathers [SourceUnit]s from params, inputs and blobs (see the file comment). */
object TextInputs {
    private val PARA = Regex("\\n\\s*\\n")

    fun units(p: JsonObject, inputs: List<JsonElement>, blobs: List<File>): List<SourceUnit> = with(TJson) {
        val out = ArrayList<SourceUnit>()
        p.str("text")?.let { out += SourceUnit("text", "0", it, "text") }
        out += fromObject(JsonObject(p.filterKeys { it == "texts" || it == "units" }), "params")
        inputs.forEachIndexed { i, e ->
            when (e) {
                is JsonPrimitive -> if (e.isString) out += SourceUnit("in$i", "0", e.content, "in$i")
                is JsonObject -> out += fromObject(e, "in$i")
                is JsonArray -> e.forEachIndexed { j, x -> if (x is JsonPrimitive && x.isString) out += SourceUnit("in$i", "$j", x.content) }
                else -> {}
            }
        }
        for (f in blobs) out += paragraphs(f.name, f.readText(Charsets.UTF_8))
        out.filter { it.text.isNotBlank() }
    }

    /** `{"columns":[…],"rows":[[…]]}` as text (at most [MAX_TABLE_ROWS] rows), or null when [o] is not a table. */
    fun tableText(o: JsonObject): String? = with(TJson) {
        val cols = o.arr("columns")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return null
        val rows = o.arr("rows") ?: return null
        if (cols.isEmpty()) return null
        val sb = StringBuilder()
        o.str("title")?.let { sb.append(it).append('\n') }
        sb.append(cols.joinToString(", "))
        for (r in rows.take(MAX_TABLE_ROWS)) {
            val cells = (r as? JsonArray) ?: continue
            sb.append('\n').append(cols.indices.joinToString(", ") { i -> "${cols[i]}: ${(cells.getOrNull(i) as? JsonPrimitive)?.content ?: ""}" })
        }
        if (rows.size > MAX_TABLE_ROWS) sb.append("\n… ${rows.size - MAX_TABLE_ROWS} more rows")
        sb.toString()
    }

    private const val MAX_TABLE_ROWS = 60

    fun paragraphs(doc: String, text: String): List<SourceUnit> =
        text.replace("\r\n", "\n").split(PARA).map { it.trim() }.filter { it.isNotEmpty() }
            .mapIndexed { i, t -> SourceUnit(doc, "¶ ${i + 1}", t) }

    private fun fromObject(o: JsonObject, fallbackDoc: String): List<SourceUnit> = with(TJson) {
        val out = ArrayList<SourceUnit>()
        // a table (an AGG or reduce output) reads as one unit: a header line and one "col: value" line per row
        tableText(o)?.let { out += SourceUnit(o.str("title") ?: fallbackDoc, "0", it, fallbackDoc) }
        o.arr("units")?.forEach { u -> (u as? JsonObject)?.let { x ->
            val doc = x.str("doc") ?: fallbackDoc; val unit = x.str("unit") ?: "0"
            x.str("text")?.let { out += SourceUnit(doc, unit, it, x.str("id") ?: "$doc${Sentences.SEP}$unit") }
        } }
        o.arr("texts")?.forEachIndexed { i, u -> (u as? JsonObject)?.let { x ->
            val id = x.str("id") ?: "$fallbackDoc#$i"
            x.str("text")?.let { out += SourceUnit(id, "0", it, id) }
        } }
        o.arr("picks")?.forEach { u -> (u as? JsonObject)?.let { x ->
            val doc = x.str("doc") ?: fallbackDoc; val unit = x.str("unit") ?: "0"
            x.str("text")?.let { out += SourceUnit(doc, unit, it, x.str("id") ?: "$doc${Sentences.SEP}$unit") }
        } }
        o.arr("items")?.forEachIndexed { i, u -> (u as? JsonObject)?.let { x ->
            val id = x.str("item") ?: x.str("id") ?: "$fallbackDoc#$i"
            x.str("text")?.let { out += SourceUnit(id, "0", it, id) }
            x.arr("segments")?.let { out += segments(id, it) }
        } }
        o.arr("segments")?.let { out += segments(o.str("item") ?: fallbackDoc, it) }
        if (out.isEmpty()) o.str("text")?.let { out += SourceUnit(o.str("item") ?: fallbackDoc, "0", it, o.str("item") ?: fallbackDoc) }
        out
    }

    private fun segments(doc: String, a: JsonArray): List<SourceUnit> = with(TJson) {
        a.mapIndexedNotNull { j, s -> (s as? JsonObject)?.str("text")?.let { SourceUnit(doc, "seg $j", it) } }
    }

    /** Vectors from earlier EMBED outputs (items with q8) or params.corpus [{id, q8}]; ids must be unique. */
    fun vectors(p: JsonObject, inputs: List<JsonElement>): List<Triple<String, ByteArray, String?>> = with(TJson) {
        val out = ArrayList<Triple<String, ByteArray, String?>>()
        fun take(a: JsonArray?) = a?.forEach { u -> (u as? JsonObject)?.let { x ->
            val id = x.str("id"); val q = x.str("q8")
            if (id != null && q != null) out += Triple(id, unb64(q), x.str("text"))
        } }
        take(p.arr("corpus"))
        for (e in inputs) (e as? JsonObject)?.let { take(it.arr("items")) }
        require(out.map { it.first }.toSet().size == out.size) { "duplicate vector ids" }
        out
    }
}

/**
 * EXTRACT, SUMMARIZE, TEXT_CLASSIFY, EMBED, KNN, CLUSTER, GEN_TEXT, INFER. [ops] lists only what the given
 * dependencies allow: EXTRACT/SUMMARIZE (extractive)/KNN/CLUSTER always (deterministic fallbacks), GEN_TEXT with an
 * engine, TEXT_CLASSIFY with a chooser, EMBED/INFER with a pool + runtime.
 */
class TextExecutor(
    private val llm: LlmEngine?,
    private val chooser: ClosedChooser?,
    private val pool: ModelPool?,
    private val runtime: ModelRuntime?,
    private val registry: ModelRegistry? = null,
) : OpExecutor {
    override val ops: Set<Op> = buildSet {
        add(Op.EXTRACT); add(Op.SUMMARIZE); add(Op.KNN); add(Op.CLUSTER)
        if (llm != null) add(Op.GEN_TEXT)
        if (chooser != null) add(Op.TEXT_CLASSIFY)
        if (pool != null && runtime != null) { add(Op.EMBED); add(Op.INFER) }
    }

    private val embedder: Embedder? = if (pool != null && runtime != null) Embedder(pool, runtime, registry) else null

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = runCatching {
        require(task.op in ops) { "TextExecutor cannot run ${task.op} here" }
        when (task.op) {
            Op.EXTRACT -> extract(task, inputs, blobs)
            Op.SUMMARIZE -> summarize(task, inputs, blobs)
            Op.TEXT_CLASSIFY -> classify(task, inputs, blobs)
            Op.EMBED -> embed(task, inputs, blobs)
            Op.KNN -> knn(task, inputs)
            Op.CLUSTER -> cluster(task, inputs)
            Op.GEN_TEXT -> genText(task, inputs, blobs)
            Op.INFER -> infer(task, blobs)
            else -> error("unreachable")
        }
    }

    private fun modelId(task: TaskSpec): String = with(TJson) { task.modelId ?: task.params.str("modelId") } ?: throw IllegalArgumentException("${task.op} needs a modelId")

    private fun units(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): List<SourceUnit> =
        TextInputs.units(task.params, inputs, blobs).also { require(it.isNotEmpty()) { "${task.op} has no text" } }

    private fun picksJson(e: Extraction): JsonObject = buildJsonObject {
        put("method", e.method)
        put("candidates", e.candidates)
        e.confidence?.let { put("confidence", TJson.r4(it)) }
        putJsonArray("picks") {
            e.picks.forEach { s -> add(buildJsonObject { put("id", s.id); put("doc", s.doc); put("unit", s.unit); put("text", s.text) }) }
        }
        putJsonArray("citations") { e.citations.forEach { add(JsonPrimitive(it)) } }
    }

    private fun merge(head: JsonObject, tail: JsonObject) = JsonObject(LinkedHashMap<String, JsonElement>(head).apply { putAll(tail) })

    // ------------------------------------------------------------------------------------------------- EXTRACT

    /** params: question (required), maxPick (3). */
    private suspend fun extract(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): JsonElement = with(TJson) {
        val q = task.params.str("question") ?: throw IllegalArgumentException("EXTRACT needs a question")
        val sentences = Sentences.of(units(task, inputs, blobs))
        val e = Extractive(llm, chooser).extract(q, sentences, task.params.int("maxPick") ?: 3)
        if (!e.ok) throw IllegalStateException("rejected: ${e.rejected}")
        merge(buildJsonObject { put("op", Op.EXTRACT.name); put("question", q) }, picksJson(e))
    }

    // ----------------------------------------------------------------------------------------------- SUMMARIZE

    /** params: mode (extractive | abstractive), sentences (5). Abstractive needs the engine and passes [GroundCheck]. */
    private suspend fun summarize(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): JsonElement = with(TJson) {
        val p = task.params
        val k = p.int("sentences") ?: 5
        val us = units(task, inputs, blobs)
        when (val mode = p.str("mode") ?: "extractive") {
            "extractive" -> {
                val e = Extractive(llm, null).summarize(Sentences.of(us), k)
                if (!e.ok) throw IllegalStateException("rejected: ${e.rejected}")
                merge(buildJsonObject { put("op", Op.SUMMARIZE.name); put("mode", mode) }, picksJson(e))
            }
            "abstractive" -> {
                val engine = llm ?: throw IllegalStateException("abstractive SUMMARIZE needs the LLM")
                val source = us.joinToString("\n") { it.text }
                val text = engine.generate(abstractivePrompt(source, k), GenParams(maxTokens = 40 * k + 32)).getOrThrow().text.trim()
                require(text.isNotEmpty()) { "rejected: empty summary" }
                val g = GroundCheck.check(text, source)
                if (!g.ok) throw IllegalStateException("rejected: ungrounded " + g.violations.joinToString { "${it.kind} '${it.text}'" })
                buildJsonObject {
                    put("op", Op.SUMMARIZE.name); put("mode", mode); put("generated", true); put("text", text)
                    putJsonArray("sources") { us.map { it.key }.distinct().forEach { add(JsonPrimitive(it)) } }
                }
            }
            else -> throw IllegalArgumentException("SUMMARIZE mode must be extractive or abstractive")
        }
    }

    // ------------------------------------------------------------------------------------------- TEXT_CLASSIFY

    /**
     * params: labels (required), question, k (3), granularity (unit | sentence), mode (llm | centroid). Centroid mode:
     * modelId + examples {label: [texts]}, minMargin (0), minScore (0).
     */
    private suspend fun classify(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): JsonElement = with(TJson) {
        val p = task.params
        val labels = p.strs("labels") ?: throw IllegalArgumentException("TEXT_CLASSIFY needs labels")
        require(labels.isNotEmpty()) { "TEXT_CLASSIFY needs labels" }
        val us = units(task, inputs, blobs)
        val items: List<Pair<String, String>> = when (p.str("granularity") ?: "unit") {
            "sentence" -> Sentences.of(us).map { it.id to it.text }
            "unit" -> us.map { it.key to it.text }
            else -> throw IllegalArgumentException("granularity must be unit or sentence")
        }
        val mode = p.str("mode") ?: "llm"
        val out = ArrayList<JsonElement>()
        when (mode) {
            "llm" -> {
                val tc = TextClassifier(chooser ?: throw IllegalStateException("TEXT_CLASSIFY needs the chooser"))
                for ((id, text) in items) {
                    val d = tc.classify(text, labels, p.str("question"), p.int("k") ?: 3)
                    require(d.value == null || d.value in labels) { "rejected: label outside the set: ${d.value}" }
                    out += buildJsonObject {
                        put("id", id)
                        put("label", d.value?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("confidence", r4(d.confidence)); put("agreeing", d.agreeing); put("runs", d.runs)
                        putJsonObject("votes") { labels.forEachIndexed { i, l -> put(l, d.votes.getOrElse(i) { 0 }) } }
                    }
                }
            }
            "centroid" -> {
                val emb = embedder ?: throw IllegalStateException("centroid TEXT_CLASSIFY needs the model pool")
                val ex = p.obj("examples") ?: throw IllegalArgumentException("centroid mode needs examples")
                val model = modelId(task)
                val exVecs = LinkedHashMap<String, List<FloatArray>>()
                for (l in labels) {
                    val texts = ex.strs(l) ?: emptyList()
                    require(texts.isNotEmpty()) { "no examples for label $l" }
                    exVecs[l] = emb.embed(model, texts.mapIndexed { i, t -> "$l#$i" to t }).map { Embedder.dequantize(it.q8) }
                }
                val nc = NearestCentroid(exVecs, p.dbl("minScore") ?: 0.0, p.dbl("minMargin") ?: 0.0)
                for (e in emb.embed(model, items)) {
                    val pr = nc.predict(Embedder.dequantize(e.q8))
                    out += buildJsonObject {
                        put("id", e.id)
                        put("label", pr.label?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("score", r6(pr.score)); put("margin", r6(pr.margin))
                    }
                }
            }
            else -> throw IllegalArgumentException("TEXT_CLASSIFY mode must be llm or centroid")
        }
        buildJsonObject {
            put("op", Op.TEXT_CLASSIFY.name); put("mode", mode)
            putJsonArray("labels") { labels.forEach { add(JsonPrimitive(it)) } }
            put("items", JsonArray(out))
        }
    }

    // --------------------------------------------------------------------------------------------------- EMBED

    /** params: modelId, prefix, granularity (unit | sentence), float (false: adds the unit vector, 6 dp), includeText (false). */
    private suspend fun embed(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): JsonElement = with(TJson) {
        val p = task.params
        val model = modelId(task)
        val us = units(task, inputs, blobs)
        val items = when (p.str("granularity") ?: "unit") {
            "sentence" -> Sentences.of(us).map { it.id to it.text }
            else -> us.map { it.key to it.text }
        }
        require(items.map { it.first }.toSet().size == items.size) { "duplicate item ids" }
        val texts = items.toMap()
        val es = embedder!!.embed(model, items, p.str("prefix"))
        buildJsonObject {
            put("op", Op.EMBED.name); put("model", model)
            put("dim", es.firstOrNull()?.vec?.size ?: 0)
            putJsonArray("items") {
                es.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id); put("tokens", e.tokens); put("truncated", e.truncated)
                        put("q8", b64(e.q8)); put("sha256", sha256(e.q8))
                        if (p.bool("float") == true) putJsonArray("vec") { e.vec.forEach { add(JsonPrimitive(r6(it.toDouble()))) } }
                        if (p.bool("includeText") == true) put("text", texts.getValue(e.id))
                    })
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------- KNN

    /** params: k (5), minScore (-1), query: queryQ8 (base64) | query (floats) | queryText (+ modelId, prefix). */
    private suspend fun knn(task: TaskSpec, inputs: List<JsonElement>): JsonElement = with(TJson) {
        val p = task.params
        val corpus = TextInputs.vectors(p, inputs)
        require(corpus.isNotEmpty()) { "KNN has no vectors" }
        val q: ByteArray = p.str("queryQ8")?.let { unb64(it) }
            ?: (p["query"] as? JsonArray)?.let { a -> Embedder.quantize(Embedder.l2(FloatArray(a.size) { (a[it] as JsonPrimitive).doubleOrNull!!.toFloat() })) }
            ?: p.str("queryText")?.let { t ->
                val emb = embedder ?: throw IllegalStateException("queryText needs the model pool")
                emb.embed(modelId(task), listOf("query" to t), p.str("prefix")).single().q8
            }
            ?: throw IllegalArgumentException("KNN needs queryQ8, query or queryText")
        val k = p.int("k") ?: 5
        val hits = Knn.topK(q, corpus.map { it.first to it.second }, k, p.dbl("minScore") ?: -1.0)
        buildJsonObject {
            put("op", Op.KNN.name); put("k", k)
            putJsonArray("hits") { hits.forEach { h -> add(buildJsonObject { put("id", h.id); put("score", h.score) }) } }
        }
    }

    // ------------------------------------------------------------------------------------------------- CLUSTER

    /**
     * params: mode (kmeans | dedupe); kmeans: k (required), iterations (20), jobId (default the task id) → seed,
     * themes (true: label each cluster from member texts when the EMBED output carried them); dedupe: threshold (0.95).
     */
    private fun cluster(task: TaskSpec, inputs: List<JsonElement>): JsonElement = with(TJson) {
        val p = task.params
        val vs = TextInputs.vectors(p, inputs)
        require(vs.isNotEmpty()) { "CLUSTER has no vectors" }
        when (val mode = p.str("mode") ?: "kmeans") {
            "dedupe" -> {
                val th = p.dbl("threshold") ?: 0.95
                val groups = Cluster.dedupe(vs.map { it.first to it.second }, th)
                buildJsonObject {
                    put("op", Op.CLUSTER.name); put("mode", mode); put("threshold", th)
                    putJsonArray("groups") { groups.forEach { g -> add(buildJsonArray { g.forEach { add(JsonPrimitive(it)) } }) } }
                }
            }
            "kmeans" -> {
                val k = p.int("k") ?: throw IllegalArgumentException("CLUSTER needs k")
                val jobId = p.str("jobId") ?: task.id
                val seed = Cluster.seed(jobId)
                val floats = vs.associate { it.first to Embedder.dequantize(it.second) }
                val r = Cluster.kmeans(floats.entries.map { it.key to it.value }, k, seed, p.int("iterations") ?: Cluster.ITERATIONS)
                val texts = vs.mapNotNull { (id, _, t) -> t?.let { id to it } }.toMap()
                val themes = if (p.bool("themes") != false) Cluster.themes(r, floats, texts) else null
                buildJsonObject {
                    put("op", Op.CLUSTER.name); put("mode", mode); put("k", r.k); put("jobId", jobId); put("iterations", r.iterations)
                    putJsonArray("assignments") { r.assignments.forEach { (id, c) -> add(buildJsonObject { put("id", id); put("cluster", c) }) } }
                    putJsonArray("clusters") {
                        r.members.forEachIndexed { c, ms ->
                            add(buildJsonObject {
                                put("cluster", c); put("size", ms.size)
                                themes?.get(c)?.let { t ->
                                    put("exemplar", t.exemplar)
                                    put("theme", t.label?.let { l -> buildJsonObject { put("id", l.id); put("text", l.text) } } ?: JsonNull)
                                }
                                putJsonArray("members") { ms.forEach { add(JsonPrimitive(it)) } }
                            })
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("CLUSTER mode must be kmeans or dedupe")
        }
    }

    // ------------------------------------------------------------------------------------------------ GEN_TEXT

    /** params: instruction (required), schema (required, GenText subset), maxTokens (256); source text optional. */
    private suspend fun genText(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): JsonElement = with(TJson) {
        val p = task.params
        val instruction = p.str("instruction") ?: throw IllegalArgumentException("GEN_TEXT needs an instruction")
        val schema = GenText.Schema.from(p.obj("schema") ?: throw IllegalArgumentException("GEN_TEXT needs a schema"))
        val context = TextInputs.units(p, inputs, blobs).joinToString("\n") { it.text }.ifBlank { null }
        when (val o = GenText(llm!!).generate(instruction, schema, context, p.int("maxTokens") ?: 256).getOrThrow()) {
            is GenText.Outcome.Ok -> buildJsonObject { put("op", Op.GEN_TEXT.name); put("value", o.value) }
            is GenText.Outcome.Rejected -> throw IllegalStateException("rejected: ${o.reason}")
        }
    }

    // --------------------------------------------------------------------------------------------------- INFER

    /**
     * Generic small-model inference. params: modelId, inputs {tensor: {data: [numbers]} | {b64: raw little-endian
     * bytes}}, blobInput (tensor name fed the first blob's raw bytes). Dtypes come from the manifest's inputSpec.
     * Output: {op, model, outputs: [{name, dtype, shape, b64, sha256}]} in outputSpec order (else runtime keys sorted).
     */
    private suspend fun infer(task: TaskSpec, blobs: List<File>): JsonElement = with(TJson) {
        val p = task.params
        val model = modelId(task)
        val result = pool!!.with(model) { s ->
            val m = s.manifest
            val specs = TensorIo.inputs(m.inputSpec)
            val given = p.obj("inputs") ?: JsonObject(emptyMap())
            val feed = LinkedHashMap<String, Any>()
            for ((name, v) in given) {
                val spec = specs.firstOrNull { it.name == name } ?: throw IllegalArgumentException("$model has no input $name")
                feed[name] = packInput(v as? JsonObject ?: throw IllegalArgumentException("input $name must be an object"), spec)
            }
            p.str("blobInput")?.let { name ->
                val spec = specs.firstOrNull { it.name == name } ?: throw IllegalArgumentException("$model has no input $name")
                feed[name] = rawToValue(blobs.firstOrNull()?.readBytes() ?: throw IllegalArgumentException("blobInput needs a blob"), spec.dtype)
            }
            for (spec in specs) require(spec.name in feed) { "missing input ${spec.name}" }
            val out = runtime!!.run(s, feed).getOrThrow()
            val oSpecs = TensorIo.outputs(m.outputSpec)
            if (oSpecs.isNotEmpty()) oSpecs.map { spec -> Triple(spec.name, TensorIo.find(out, spec), spec) }
            else out.keys.sorted().map { Triple(it, out.getValue(it), null as TensorSpec?) }
        }.getOrThrow()
        buildJsonObject {
            put("op", Op.INFER.name); put("model", model)
            putJsonArray("outputs") {
                result.forEach { (name, v, spec) ->
                    val (bytes, dtype) = toBytes(v, spec?.dtype)
                    add(buildJsonObject {
                        put("name", name); put("dtype", dtype)
                        putJsonArray("shape") { (spec?.shape ?: emptyList()).forEach { add(JsonPrimitive(it)) } }
                        put("b64", b64(bytes)); put("sha256", sha256(bytes))
                    })
                }
            }
        }
    }

    companion object {
        fun abstractivePrompt(source: String, k: Int): List<ChatMsg> = listOf(
            ChatMsg("system", "You summarise a source text in at most $k short sentences. Use only names, numbers, dates and terms that appear in the source. Do not add anything."),
            ChatMsg("user", "Source:\n" + source.take(6000) + "\nSummary:"),
        )

        private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

        /** Per the runtime convention: float32 → FloatArray, everything else raw little-endian bytes. */
        fun packInput(o: JsonObject, spec: TensorSpec): Any = with(TJson) {
            o.str("b64")?.let { return rawToValue(unb64(it), spec.dtype) }
            val data = o.arr("data") ?: throw IllegalArgumentException("input ${spec.name} needs data or b64")
            val nums = data.map { (it as? JsonPrimitive) ?: throw IllegalArgumentException("non-numeric data in ${spec.name}") }
            if (spec.count > 0) require(nums.size == spec.count) { "input ${spec.name}: ${nums.size} values, spec ${spec.shape} needs ${spec.count}" }
            when (spec.dtype) {
                "float32" -> FloatArray(nums.size) { nums[it].doubleOrNull!!.toFloat() }
                "int32" -> TensorIo.int32Bytes(IntArray(nums.size) { nums[it].intOrNull!! })
                "int64" -> le(nums.size * 8).also { b -> nums.forEach { b.putLong(it.longOrNull!!) } }.array()
                "uint16", "int16" -> le(nums.size * 2).also { b -> nums.forEach { b.putShort(it.intOrNull!!.toShort()) } }.array()
                "uint8", "int8" -> ByteArray(nums.size) { nums[it].intOrNull!!.toByte() }
                else -> throw IllegalArgumentException("input ${spec.name}: dtype ${spec.dtype} needs b64")
            }
        }

        fun rawToValue(b: ByteArray, dtype: String): Any =
            if (dtype == "float32") ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { f -> FloatArray(f.remaining()).also { f.get(it) } } else b

        /** A runtime output value as little-endian bytes + its dtype. */
        fun toBytes(v: Any, specDtype: String?): Pair<ByteArray, String> = when (v) {
            is ByteArray -> v to (specDtype ?: "uint8")
            is FloatArray -> le(v.size * 4).also { b -> v.forEach { b.putFloat(it) } }.array() to "float32"
            is IntArray -> TensorIo.int32Bytes(v) to "int32"
            is LongArray -> le(v.size * 8).also { b -> v.forEach { b.putLong(it) } }.array() to "int64"
            is ShortArray -> le(v.size * 2).also { b -> v.forEach { b.putShort(it) } }.array() to (specDtype ?: "int16")
            is ByteBuffer -> v.duplicate().let { b -> b.rewind(); ByteArray(b.remaining()).also { b.get(it) } } to (specDtype ?: "uint8")
            else -> throw IllegalArgumentException("unsupported output value ${v::class.java.simpleName}")
        }
    }
}
