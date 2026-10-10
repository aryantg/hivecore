package com.hivecore.node.docai

import com.hivecore.core.ClosedChooser
import com.hivecore.core.LlmEngine
import com.hivecore.core.Ocr
import com.hivecore.core.OcrLine
import com.hivecore.core.Op
import com.hivecore.core.OpExecutor
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Swarm plan §4A.3 document AI executors: OCR, LAYOUT, DOC_CLASSIFY, METADATA, REDACT and FIELDS (§4). Every output
 * is plain JSON built in a fixed shape from deterministic values, so replicas compare by canonical JSON.
 *
 * Inputs (by task input order; the first input of the right shape is used):
 *  - OCR: the image blobs → `{"op":"OCR","items":[{"item","lines":[…]}…]}`, one item per blob; or `params.path` →
 *    `{"lines":[{"text","left","top","right","bottom"}…]}`. An OCR output with exactly one item also reads as its lines;
 *  - LAYOUT: an OCR output → `{"rows":[[cell…]…],"text":[row…],"pairs":[{"key","label","value","row","how"}…]}`;
 *  - DOC_CLASSIFY: an OCR or LAYOUT output (or `params.text`), `params.filename` optional →
 *    `{"class","by","confidence","agreeing","runs","options","votes","scores"}`, class null when undecided;
 *  - METADATA: an OCR or LAYOUT output plus a DOC_CLASSIFY output (or `params.class`) → [Metadata.toJson];
 *  - REDACT: an OCR output (text + boxes) or a LAYOUT output / `params.text` (text only) →
 *    `{"text","log":[{"kind","start","end"}…],"boxes":[{"line","left","top","right","bottom","kinds"}…]}`;
 *  - FIELDS: an OCR or LAYOUT output → the standard invoice fields
 *    `{"vendor","date","total","status","by":{…},"missing":[…],"review":[…]}`: [InvoiceRegex], then
 *    [LlmFieldExtractor] for whatever is missing (an engine failure or unparseable reply fails the task).
 *
 * [llm] null = rules only (METADATA and FIELDS never ask a model); [schemas] defaults to the class schemas shipped
 * with this module.
 */
class DocAiExecutor(
    private val ocr: Ocr,
    private val llm: LlmEngine?,
    private val chooser: ClosedChooser,
    private val schemas: DocSchemas = DocSchemas.builtin(),
) : OpExecutor {

    override val ops: Set<Op> = setOf(Op.OCR, Op.LAYOUT, Op.DOC_CLASSIFY, Op.METADATA, Op.REDACT, Op.FIELDS)

    private val classifier = DocClassifier(chooser, schemas)
    private val metadata = MetadataExtractor(schemas, llm)

    override suspend fun execute(task: TaskSpec, inputs: List<JsonElement>, blobs: List<File>): Result<JsonElement> = try {
        when (task.op) {
            Op.OCR -> runOcr(task, blobs)
            Op.LAYOUT -> layoutOf(task, inputs)?.let { Result.success(layoutJson(it)) } ?: missingInput(task, "OCR lines")
            Op.DOC_CLASSIFY -> runClassify(task, inputs)
            Op.METADATA -> runMetadata(task, inputs)
            Op.REDACT -> runRedact(task, inputs)
            Op.FIELDS -> runFields(task, inputs)
            else -> Result.failure(IllegalArgumentException("DocAiExecutor does not run ${task.op}"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: IllegalArgumentException) { Result.failure(e) }

    private suspend fun runOcr(task: TaskSpec, blobs: List<File>): Result<JsonElement> {
        if (blobs.isEmpty()) {
            val path = str(task.params["path"]) ?: return missingInput(task, "an image")
            return Result.success(ocrJson(ocr.lines(path).getOrElse { return Result.failure(it) }))
        }
        // A shard chunk carries several photos: one item per blob, in order, so the concat merge keeps every page.
        val items = blobs.map { f ->
            val lines = ocr.lines(f.path).getOrElse { return Result.failure(it) }
            JsonObject(linkedMapOf<String, JsonElement>("item" to JsonPrimitive(f.name)) + ocrJson(lines))
        }
        return Result.success(buildJsonObject { put("op", Op.OCR.name); put("items", JsonArray(items)) })
    }

    private suspend fun runClassify(task: TaskSpec, inputs: List<JsonElement>): Result<JsonElement> {
        val text = textOf(task, inputs) ?: return missingInput(task, "OCR or LAYOUT output")
        val r = classifier.classify(text, str(task.params["filename"]))
        val d = r.decision
        return Result.success(buildJsonObject {
            put("class", d.value?.let { JsonPrimitive(it) } ?: JsonNull)
            put("by", r.by)
            put("confidence", d.confidence)
            put("agreeing", d.agreeing)
            put("runs", d.runs)
            put("options", JsonArray(d.options.map { JsonPrimitive(it) }))
            put("votes", JsonArray(d.votes.map { JsonPrimitive(it) }))
            put("scores", JsonObject(r.scores.mapValues { JsonPrimitive(it.value) }))
        })
    }

    private suspend fun runMetadata(task: TaskSpec, inputs: List<JsonElement>): Result<JsonElement> {
        val layout = layoutOf(task, inputs) ?: return missingInput(task, "OCR or LAYOUT output")
        val docClass = str(task.params["class"])
            ?: inputs.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { o -> "scores" in o }?.let { o -> str(o["class"]) } }
            ?: return Result.failure(IllegalArgumentException("METADATA ${task.id}: no document class (DOC_CLASSIFY undecided)"))
        val m = metadata.extract(docClass, layout).getOrElse { return Result.failure(it) }
        return Result.success(m.toJson(schemas[docClass]))
    }

    private fun runRedact(task: TaskSpec, inputs: List<JsonElement>): Result<JsonElement> {
        val lines = inputs.firstNotNullOfOrNull { linesOf(it) }
        val text = when {
            lines != null -> Redactor.joinLines(lines)
            else -> str(task.params["text"]) ?: inputs.firstNotNullOfOrNull { rowsTextOf(it) }?.joinToString("\n")
        } ?: return missingInput(task, "text or OCR lines")
        val r = Redactor.redact(text)
        val boxes = lines?.let { Redactor.boxes(it) } ?: emptyList()
        return Result.success(buildJsonObject {
            put("text", r.text)
            put("log", buildJsonArray { r.log.forEach { x -> add(buildJsonObject { put("kind", x.kind); put("start", x.start); put("end", x.end) }) } })
            put("boxes", buildJsonArray {
                boxes.forEach { b ->
                    add(buildJsonObject {
                        put("line", b.line); put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
                        put("kinds", JsonArray(b.kinds.map { JsonPrimitive(it) }))
                    })
                }
            })
        })
    }

    private suspend fun runFields(task: TaskSpec, inputs: List<JsonElement>): Result<JsonElement> {
        val rows = layoutOf(task, inputs)?.text ?: return missingInput(task, "OCR or LAYOUT output")
        var p = InvoiceRegex.extract(rows)
        if (!p.complete && llm != null) p = LlmFieldExtractor(llm).fill(p, rows).getOrElse { return Result.failure(it) }.partial
        return Result.success(fieldsJson(p))
    }

    // ------------------------------------------------------------------ input decoding

    private fun layoutOf(task: TaskSpec, inputs: List<JsonElement>): LayoutResult? {
        for (e in inputs) {
            linesOf(e)?.let { return Layout.of(it) }
            rowsOf(e)?.let { return Layout.ofRows(it) }
        }
        return str(task.params["text"])?.let { t -> Layout.ofRows(t.lines().map { listOf(it) }) }
    }

    private fun textOf(task: TaskSpec, inputs: List<JsonElement>): String? =
        layoutOf(task, inputs)?.text?.joinToString("\n")

    private fun missingInput(task: TaskSpec, what: String): Result<JsonElement> =
        Result.failure(IllegalArgumentException("${task.op} ${task.id}: needs $what"))

    companion object {
        fun ocrJson(lines: List<OcrLine>): JsonObject = buildJsonObject {
            put("lines", buildJsonArray {
                lines.forEach { l -> add(buildJsonObject { put("text", l.text); put("left", l.left); put("top", l.top); put("right", l.right); put("bottom", l.bottom) }) }
            })
        }

        fun layoutJson(r: LayoutResult): JsonObject = buildJsonObject {
            put("rows", JsonArray(r.rows.map { row -> JsonArray(row.map { JsonPrimitive(it) }) }))
            put("text", JsonArray(r.text.map { JsonPrimitive(it) }))
            put("pairs", buildJsonArray {
                r.pairs.forEach { p -> add(buildJsonObject { put("key", p.key); put("label", p.label); put("value", p.value); put("row", p.row); put("how", p.how) }) }
            })
        }

        fun fieldsJson(p: Partial): JsonObject = buildJsonObject {
            put("vendor", p.vendor?.let { JsonPrimitive(it) } ?: JsonNull)
            put("date", p.date?.let { JsonPrimitive(it) } ?: JsonNull)
            put("total", p.total?.let { JsonPrimitive(it) } ?: JsonNull)
            put("status", p.status?.let { JsonPrimitive(it) } ?: JsonNull)
            put("by", JsonObject(Partial.FIELDS.filter { it in p.by }.associateWith { JsonPrimitive(p.by.getValue(it)) }))
            put("missing", JsonArray(p.missing.map { JsonPrimitive(it) }))
            put("review", JsonArray(p.llmFields.map { JsonPrimitive(it) }))
        }

        /** OCR lines from an OCR output, or null. */
        fun linesOf(e: JsonElement): List<OcrLine>? {
            val o = e as? JsonObject ?: return null
            val single = (o["items"] as? JsonArray)?.singleOrNull() as? JsonObject
            val arr = o["lines"] as? JsonArray ?: single?.get("lines") as? JsonArray ?: return null
            return arr.map { x ->
                val o = x as? JsonObject ?: return null
                OcrLine(str(o["text"]) ?: return null, int(o["left"]) ?: return null, int(o["top"]) ?: return null,
                    int(o["right"]) ?: return null, int(o["bottom"]) ?: return null)
            }
        }

        /** Row cells from a LAYOUT output, or null. */
        fun rowsOf(e: JsonElement): List<List<String>>? {
            val arr = (e as? JsonObject)?.get("rows") as? JsonArray ?: return null
            return arr.map { r -> (r as? JsonArray ?: return null).map { str(it) ?: return null } }
        }

        private fun rowsTextOf(e: JsonElement): List<String>? = rowsOf(e)?.map { it.joinToString("  ") }
            ?: linesOf(e)?.let { RowGrouper.rowsText(it) }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
        private fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    }
}
