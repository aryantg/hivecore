package com.hivecore.ui

import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.NodeLoad
import com.hivecore.core.fakes.FakeJobRunner
import com.hivecore.ui.vm.JobsViewModel
import com.hivecore.ui.vm.ResultView
import com.hivecore.ui.vm.ResultViews
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The result viewer's mapping and the honest "identical to one phone" badge. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultViewsTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun done(jobId: String, phones: Int, result: JsonElement?) = JobProgress(jobId, "Photo text (OCR)", "shard", "concat",
        listOf(ChunkView(0, "c0", ChunkState.DONE, "Sales", 100)), List(phones) { NodeLoad("n$it", "Phone $it", 1, 100, 1.0) },
        done = true, resultRef = "split:abc", result = result)

    @Test fun ocrOutputReadsAsTexts() {
        val r = json("""{"stages":{"ocr":{"op":"OCR","items":[
            {"item":"blob:cache/picked/sample_0001.png","lines":[{"text":"INVOICE","left":0},{"text":"Total ₹ 1,200"}]},
            {"item":"b2","lines":[]}]}}}""")
        val v = ResultViews.of(done("j", 3, r), null) as ResultView.Texts
        assertEquals(listOf("sample_0001.png", "b2"), v.items.map { it.item })
        assertEquals(2, v.items[0].lines)
        assertEquals("INVOICE\nTotal ₹ 1,200", v.items[0].text)
        assertTrue(ResultViews.csv(v).startsWith("item,lines,text\nsample_0001.png,2,\"INVOICE\nTotal ₹ 1,200\""))
    }

    @Test fun flatItemsReadAsATableAndUnknownAsRaw() {
        val t = ResultViews.view(json("""{"items":[{"region":"East","revenue":12},{"region":"West","revenue":9}]}""")) as ResultView.Table
        assertEquals(listOf("region", "revenue"), t.columns)
        assertEquals(listOf(listOf("East", "12"), listOf("West", "9")), t.rows)
        val c = ResultViews.view(json("""{"columns":["a","b"],"rows":[[1,2]]}""")) as ResultView.Table
        assertEquals(listOf(listOf("1", "2")), c.rows)
        assertTrue(ResultViews.view(json("""{"summary":"ok"}""")) is ResultView.Raw)
        assertNull(ResultViews.of(done("j", 1, null), null))
    }

    @Test fun identicalOnlyAfterAOnePhoneRunOfTheSameRequest() = runTest {
        val req = JobRequest("Photo text (OCR)", recipeId = "photo_text", inputs = listOf("/p/a.png", "/p/b.png"))
        var n = 0
        val runner = FakeJobRunner { r -> n++; listOf(done("job-$n", if (r.private) 1 else 3, null)) }
        val vm = JobsViewModel(runner, MutableStateFlow(emptyList()), { emptyList() }, backgroundScope, StandardTestDispatcher(testScheduler), resultHash = { "same-hash" })

        vm.submit(req.copy(private = true)); settle()
        assertNull("nothing to compare with yet", vm.state.value.current!!.identicalToSingle)
        assertNull(com.hivecore.ui.vm.JobFormat.identicalOrNull(vm.state.value.current!!.identicalToSingle))

        vm.submit(req); settle()
        assertEquals(true, vm.state.value.current!!.identicalToSingle)

        vm.submit(req.copy(inputs = listOf("/p/c.png"))); settle()
        assertNull("a different request is not compared", vm.state.value.current!!.identicalToSingle)
    }

    @Test fun theSheetOpensOnlyWithAResult() = runTest {
        val runner = FakeJobRunner { listOf(done("j1", 2, json("""{"items":[{"a":1}]}"""))) }
        val vm = JobsViewModel(runner, MutableStateFlow(emptyList()), { emptyList() }, backgroundScope, StandardTestDispatcher(testScheduler))
        var external: String? = null
        vm.openResult = { external = it }
        vm.submit(JobRequest("t", recipeId = "x")); settle()
        vm.openResult()
        assertTrue(vm.state.value.viewingResult)
        assertNull(external)
        vm.closeResult()
        assertEquals(false, vm.state.value.viewingResult)
    }
}
