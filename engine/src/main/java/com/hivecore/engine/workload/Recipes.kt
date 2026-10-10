package com.hivecore.engine.workload

import com.hivecore.core.ColumnDef
import com.hivecore.core.Op
import com.hivecore.core.Recipe
import com.hivecore.core.TaskSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// Swarm plan §4A.1/§4A.4: built-in recipes live in assets/swarm/recipes/<id>.json. Each file is a core [Recipe] plus
// how it splits (§4B.1 axis), how the chunks merge back (§4B.2 merge kind), what the user picks as input and what
// it produces. [Recipes.parse] validates a file; [Recipes.load] reads and validates the whole folder.

/** One recipe file: the frozen [Recipe] fields plus the splitting/UI metadata the Jobs screen shows. */
@Serializable data class RecipeDef(
    val id: String,
    val version: String,
    val title: String,
    /** one line: what the job produces ("a row per invoice in t_invoices") */
    val produces: String,
    /** "shard" | "chunk" | "fanout" | "pipeline" (§4B.1) */
    val axis: String,
    /** "concat" | "stitch" | "tree" | "monoid" | "topk" | "vote" | "rank" (§4B.2) */
    val merge: String,
    /** what the item picker offers: "files" (system picker), "images" (camera roll), "prompt" (a brief), "none" */
    val input: String,
    val stages: List<TaskSpec>,
    val outputSchema: List<ColumnDef> = emptyList(),
    val replicate: String = "sample:0.1",
    val sink: String = "table",
    val itemKinds: List<String> = listOf("image"),
    /** the destination table when [sink] has a table */
    val table: String? = null,
) {
    /** True when the job makes images: results carry the "AI generated" badge and never go into data tables. */
    val generative: Boolean get() = stages.any { it.op in Recipes.GENERATIVE_OPS }
    val sinksTable: Boolean get() = sink == "table" || sink == "table+folder"
    val sinksFolder: Boolean get() = sink == "folder" || sink == "table+folder"

    fun toRecipe(): Recipe = Recipe(id, version, title, stages, outputSchema, replicate, sink, itemKinds)
}

object Recipes {
    const val ASSET_DIR = "swarm/recipes"

    val AXES = listOf("shard", "chunk", "fanout", "pipeline")
    val MERGES = listOf("concat", "stitch", "tree", "monoid", "topk", "vote", "rank")
    val INPUTS = setOf("files", "images", "prompt", "none")
    val SINKS = setOf("table", "folder", "table+folder")
    val ITEM_KINDS = setOf("image", "pdf", "doc", "text", "audio", "video", "prompt", "question", "model")
    val GENERATIVE_OPS = setOf(Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.UPSCALE)

    /** §4B.1 table: the merge kinds each axis may declare. */
    val MERGES_BY_AXIS: Map<String, Set<String>> = mapOf(
        "shard" to setOf("concat", "monoid"),
        "chunk" to setOf("stitch", "tree", "monoid", "topk"),
        "fanout" to setOf("vote", "rank"),
        "pipeline" to setOf("concat"),
    )

    /** The recipes every build ships (plan §4A.4 plus the two splitting showcases). */
    val BUILT_IN = listOf(
        "invoice_inbox", "site_photo_audit", "meeting_minutes", "cctv_count", "catalog_cleanup", "product_shot_batch",
        "gate2_eval", "synth_questions", "bench_all", "long_doc_summary", "image_batch_variants", "photo_text",
    )

    private val ID = Regex("[a-z][a-z0-9_]*")
    private val REPLICATE = Regex("all|none|sample:(0(\\.\\d+)?|1(\\.0+)?)")
    private val json = Json { ignoreUnknownKeys = false }

    fun axisLabel(axis: String): String = when (axis) {
        "shard" -> "Shard"; "chunk" -> "Chunk"; "fanout" -> "Fan-out"; "pipeline" -> "Pipeline"; "graph" -> "Graph"
        else -> axis.replaceFirstChar { it.uppercase() }
    }

    fun mergeLabel(merge: String): String = when (merge) {
        "concat" -> "concatenated in order"; "stitch" -> "stitched in order"; "tree" -> "tree-merged"
        "monoid" -> "summed"; "topk" -> "top-k merged"; "vote" -> "majority vote"; "rank" -> "ranked by score"
        else -> merge
    }

    /** Parses and validates one recipe file; throws [IllegalArgumentException] with the first problem found. */
    fun parse(text: String): RecipeDef {
        val r = try { json.decodeFromString(RecipeDef.serializer(), text) } catch (e: Exception) {
            throw IllegalArgumentException("recipe does not parse: ${e.message}", e)
        }
        val errors = validate(r)
        require(errors.isEmpty()) { "${r.id}: ${errors.joinToString("; ")}" }
        return r
    }

    /** Every problem with [r]; empty = valid. */
    fun validate(r: RecipeDef): List<String> {
        val e = ArrayList<String>()
        if (!ID.matches(r.id)) e += "id '${r.id}' is not lower_snake_case"
        if (r.version.isBlank()) e += "no version"
        if (r.title.isBlank()) e += "no title"
        if (r.produces.isBlank()) e += "no 'produces' line"
        if (r.axis !in AXES) e += "unknown axis '${r.axis}'"
        if (r.merge !in MERGES) e += "unknown merge '${r.merge}'"
        MERGES_BY_AXIS[r.axis]?.let { if (r.merge !in it) e += "axis ${r.axis} cannot merge by ${r.merge} (allowed: ${it.joinToString()})" }
        if (r.input !in INPUTS) e += "unknown input '${r.input}'"
        if (r.sink !in SINKS) e += "unknown sink '${r.sink}'"
        if (!REPLICATE.matches(r.replicate)) e += "bad replicate '${r.replicate}'"
        if (r.itemKinds.isEmpty()) e += "no item kinds"
        r.itemKinds.filter { it !in ITEM_KINDS }.forEach { e += "unknown item kind '$it'" }
        if (r.input == "prompt" && "prompt" !in r.itemKinds) e += "a prompt recipe must list item kind 'prompt'"
        if (r.input in setOf("files", "images") && r.itemKinds.none { it in setOf("image", "pdf", "doc", "text", "audio", "video") })
            e += "a file recipe needs a file item kind"
        if (r.stages.isEmpty()) e += "no stages"
        val seen = HashSet<String>()
        for (s in r.stages) {
            if (!seen.add(s.id)) e += "duplicate stage ${s.id}"
            s.inputs.filter { it !in seen || it == s.id }.forEach { e += "stage ${s.id} input $it is not an earlier stage" }
        }
        if (r.sinksTable) {
            if (r.outputSchema.isEmpty()) e += "a table sink needs an outputSchema"
            if (r.table.isNullOrBlank()) e += "a table sink needs a table name"
            if (r.outputSchema.map { it.name }.toSet().size != r.outputSchema.size) e += "duplicate output column"
        }
        if (r.generative) {
            if (!r.sinksFolder) e += "generated images go to a folder"
            if (r.sinksTable && r.outputSchema.any { it.name == "photo" }) e += "generated images are never stored as captured photos"
        }
        if (r.merge == "tree" && r.axis != "chunk") e += "tree merge needs the chunk axis"
        return e
    }

    /** Loads every `*.json` under [names] via [read] (asset paths relative to [ASSET_DIR]); ids must match the file names. */
    fun load(names: List<String>, read: (String) -> String): List<RecipeDef> {
        val out = names.filter { it.endsWith(".json") }.sorted().map { name ->
            val r = try { parse(read(name)) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("$name: ${e.message}", e) }
            require(r.id == name.removeSuffix(".json")) { "$name: id ${r.id} does not match the file name" }
            r
        }
        require(out.map { it.id }.toSet().size == out.size) { "duplicate recipe id" }
        return out
    }

    /** Android: `Recipes.fromAssets(context.assets::list) { context.assets.open("$ASSET_DIR/$it") }`. */
    fun fromAssets(list: (String) -> Array<String>?, open: (String) -> java.io.InputStream): List<RecipeDef> =
        load(list(ASSET_DIR)?.toList().orEmpty()) { name -> open("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) } }

    /** JVM tests and tools: the same folder on disk. */
    fun fromDir(dir: File): List<RecipeDef> =
        load(dir.list()?.toList().orEmpty()) { File(dir, it).readText() }
}
