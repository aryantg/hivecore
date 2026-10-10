package com.hivecore.node.docai

import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeClosedChooser
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeOcr
import com.hivecore.node.data.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocAiExecutorsTest {

    private fun executor(llm: FakeLlmEngine = FakeLlmEngine()): Pair<DocAiExecutor, FakeOcr> {
        val ocr = FakeOcr()
        for (i in 1..12) {
            val n = "invoice_%02d".format(i)
            ocr.canned["$n.png"] = FakeOcr.fromText(Fixtures.text("$n.txt"))
        }
        ocr.canned["id.png"] = FakeOcr.fromText(DocTestData.ID_CARD)
        return DocAiExecutor(ocr, llm, FakeClosedChooser(), DocTestData.schemas) to ocr
    }

    private fun task(op: Op, params: Map<String, String> = emptyMap()) =
        TaskSpec("t-${op.name.lowercase()}", op, JsonObject(params.mapValues { JsonPrimitive(it.value) }))

    private suspend fun pipeline(ex: DocAiExecutor, image: String): Map<Op, JsonElement> {
        val ocr = ex.execute(task(Op.OCR), emptyList(), listOf(File(image))).getOrThrow()
        val layout = ex.execute(task(Op.LAYOUT), listOf(ocr), emptyList()).getOrThrow()
        val cls = ex.execute(task(Op.DOC_CLASSIFY, mapOf("filename" to image)), listOf(layout), emptyList()).getOrThrow()
        val meta = ex.execute(task(Op.METADATA), listOf(layout, cls), emptyList()).getOrThrow()
        val red = ex.execute(task(Op.REDACT), listOf(ocr), emptyList()).getOrThrow()
        val fields = ex.execute(task(Op.FIELDS), listOf(ocr), emptyList()).getOrThrow()
        return mapOf(Op.OCR to ocr, Op.LAYOUT to layout, Op.DOC_CLASSIFY to cls, Op.METADATA to meta, Op.REDACT to red, Op.FIELDS to fields)
    }

    @Test fun opsCovered() {
        assertEquals(setOf(Op.OCR, Op.LAYOUT, Op.DOC_CLASSIFY, Op.METADATA, Op.REDACT, Op.FIELDS), executor().first.ops)
    }

    @Test fun defaultSchemasAreTheShippedOnes() {
        val ex = DocAiExecutor(FakeOcr(), null, FakeClosedChooser())
        assertEquals(setOf(Op.OCR, Op.LAYOUT, Op.DOC_CLASSIFY, Op.METADATA, Op.REDACT, Op.FIELDS), ex.ops)
        assertEquals(DocSchemas.CLASSES, DocSchemas.builtin().byClass.keys.toList())
    }

    @Test fun invoicePipelineGivesTheGoldenOutputs() = runTest {
        val out = pipeline(executor().first, "invoice_01.png")
        assertEquals("""{"agreeing":0,"by":"rule","class":"invoice","confidence":1,"options":["invoice","receipt","purchase_order","contract","resume","id_document","letter","report","other"],""" +
            """"runs":0,"scores":{"contract":0,"id_document":0,"invoice":14,"letter":0,"purchase_order":0,"receipt":0,"report":0,"resume":0},"votes":[14,0,0,0,0,0,0,0,0]}""",
            Canon.canonical(out.getValue(Op.DOC_CLASSIFY)))
        assertEquals("""{"by":{"date":"regex","invoice_no":"regex","status":"regex","total":"regex","vendor":"regex"},"class":"invoice",""" +
            """"fields":{"date":"2026-10-12","gstin":null,"invoice_no":"1007","status":"PAID","total":45600,"vendor":"Mehta Stationers"},""" +
            """"missing":["gstin"],"rejected":[],"review":[]}""", Canon.canonical(out.getValue(Op.METADATA)))
        assertEquals("""{"by":{"date":"regex","status":"regex","total":"regex","vendor":"regex"},"date":"2026-10-12","missing":[],"review":[],"status":"PAID","total":45600,"vendor":"Mehta Stationers"}""",
            Canon.canonical(out.getValue(Op.FIELDS)))
        assertEquals("""{"boxes":[],"log":[]""", Canon.canonical(out.getValue(Op.REDACT)).substringBefore(""","text""""))
    }

    @Test fun ocrOutputIsTheLineListWithBoxes() = runTest {
        val (ex, _) = executor()
        val ocr = ex.execute(task(Op.OCR), emptyList(), listOf(File("id.png"))).getOrThrow()
        val canon = Canon.canonical(ocr)
        assertTrue(canon, canon.startsWith("""{"lines":[{"bottom":56,"left":40,"right":230,"text":"GOVERNMENT OF INDIA","top":40},"""))
        assertEquals(DocTestData.ID_CARD.lines().size, DocAiExecutor.linesOf(ocr)!!.size)
        // params.path is the fallback when no blob is attached
        assertEquals(ocr, ex.execute(task(Op.OCR, mapOf("path" to "id.png")), emptyList(), emptyList()).getOrThrow())
    }

    @Test fun outputsAreStableAcrossRunsAndExecutors() = runTest {
        for (img in listOf("invoice_01.png", "invoice_10.png", "invoice_12.png", "id.png")) {
            val llm = { FakeLlmEngine().apply { replies["vikram"] = """{"total": 112000}"""; replies["bluewave"] = """{"status": "Settled"}""" } }
            val a = pipeline(executor(llm()).first, img)
            val b = pipeline(executor(llm()).first, img)
            for (op in a.keys) assertEquals("$img $op", Canon.hash(a.getValue(op)), Canon.hash(b.getValue(op)))
        }
    }

    @Test fun fieldsUseTheLlmForWhatTheRegexMisses() = runTest {
        val llm = FakeLlmEngine().apply { replies["vikram"] = """{"vendor":"x","date":"2026-01-01","total":"₹ 1,12,000.00","status":"Settled"}""" }
        val f = pipeline(executor(llm).first, "invoice_10.png").getValue(Op.FIELDS)
        assertEquals("""{"by":{"date":"regex","status":"regex","total":"llm","vendor":"regex"},"date":"2026-10-11","missing":[],"review":["total"],"status":"PAID","total":112000,"vendor":"Vikram Hardware"}""",
            Canon.canonical(f))
    }

    @Test fun idCardRedactsTheAadhaarLineAndNeverExtractsIt() = runTest {
        val out = pipeline(executor().first, "id.png")
        val cls = out.getValue(Op.DOC_CLASSIFY) as JsonObject
        assertEquals(JsonPrimitive("id_document"), cls["class"])
        val red = Canon.canonical(out.getValue(Op.REDACT))
        assertTrue(red, red.contains("""{"bottom":136,"kinds":["AADHAAR"],"left":40,"line":4,"right":180,"top":120}"""))
        assertTrue(red, red.contains("\\n[AADHAAR]\\n"))
        val meta = Canon.canonical(out.getValue(Op.METADATA))
        assertTrue(meta, meta.contains(""""fields":{"kind":"aadhaar","name":"Rahul Verma"}"""))
        assertTrue(meta, !meta.contains("2345"))
    }

    @Test fun missingInputsFailCleanly() = runTest {
        val (ex, _) = executor()
        assertTrue(ex.execute(task(Op.OCR), emptyList(), emptyList()).isFailure)
        assertTrue(ex.execute(task(Op.LAYOUT), emptyList(), emptyList()).isFailure)
        assertTrue(ex.execute(task(Op.METADATA, mapOf("text" to "Report")), emptyList(), emptyList()).isFailure)  // no class
        assertTrue(ex.execute(task(Op.AGG), emptyList(), emptyList()).isFailure)
        // params.text works for REDACT and METADATA with params.class
        val red = ex.execute(task(Op.REDACT, mapOf("text" to "PAN ABCDE1234F")), emptyList(), emptyList()).getOrThrow()
        assertEquals("""{"boxes":[],"log":[{"end":14,"kind":"PAN","start":4}],"text":"PAN [PAN]"}""", Canon.canonical(red))
        val meta = ex.execute(task(Op.METADATA, mapOf("text" to "Quarterly Report\nDate: 2026-10-01", "class" to "report")), emptyList(), emptyList()).getOrThrow()
        assertTrue(Canon.canonical(meta).contains(""""fields":{"date":"2026-10-01","title":"Quarterly Report"}"""))
    }
}
