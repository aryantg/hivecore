package com.hivecore.engine.workload

import com.hivecore.core.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RecipesTest {
    private val dir = listOf(File("src/main/assets/swarm/recipes"), File("app/src/main/assets/swarm/recipes")).first { it.isDirectory }
    private val all = Recipes.fromDir(dir)

    @Test fun everyAssetRecipeParsesAndTheBuiltInSetIsComplete() {
        assertEquals(Recipes.BUILT_IN.sorted(), all.map { it.id })
        for (r in all) assertTrue("${r.id}: ${Recipes.validate(r)}", Recipes.validate(r).isEmpty())
    }

    @Test fun axisAndMergeAreConsistent() {
        for (r in all) {
            assertTrue(r.id, r.axis in Recipes.AXES)
            assertTrue("${r.id}: ${r.axis}/${r.merge}", r.merge in Recipes.MERGES_BY_AXIS.getValue(r.axis))
        }
        val byId = all.associateBy { it.id }
        assertEquals("chunk" to "tree", byId.getValue("long_doc_summary").let { it.axis to it.merge })
        assertEquals("fanout", byId.getValue("image_batch_variants").axis)
        assertEquals("chunk" to "stitch", byId.getValue("meeting_minutes").let { it.axis to it.merge })
        assertEquals("shard", byId.getValue("invoice_inbox").axis)
    }

    @Test fun plan4A4PipelinesAreTheDeclaredStages() {
        val byId = all.associateBy { it.id }
        assertEquals(listOf(Op.IQA, Op.IMG_PREPROC, Op.OCR, Op.LAYOUT, Op.DOC_CLASSIFY, Op.METADATA), byId.getValue("invoice_inbox").stages.map { it.op })
        assertEquals(listOf(Op.GEN_VARIANTS, Op.SCORE_IMAGE, Op.RANK, Op.UPSCALE), byId.getValue("product_shot_batch").stages.map { it.op })
        assertEquals(listOf(Op.SYNTH_GEN, Op.SYNTH_VALIDATE, Op.EVAL), byId.getValue("synth_questions").stages.map { it.op })
        assertEquals(listOf(Op.BENCH), byId.getValue("bench_all").stages.map { it.op })
    }

    @Test fun generativeRecipesGoToAFolderAndConvertToCoreRecipe() {
        val gen = all.filter { it.generative }.map { it.id }.toSet()
        assertEquals(setOf("product_shot_batch", "image_batch_variants"), gen)
        for (r in all.filter { it.generative }) { assertTrue(r.sinksFolder); assertEquals("prompt", r.input) }
        val core = all.first { it.id == "invoice_inbox" }.toRecipe()
        assertEquals("invoice_inbox", core.id)
        assertEquals(6, core.stages.size)
        assertEquals(listOf("image", "pdf"), core.itemKinds)
    }

    private val good = """{"id":"x","version":"1","title":"X","produces":"rows","axis":"shard","merge":"concat","input":"images",
        "itemKinds":["image"],"sink":"table","table":"t_x","stages":[{"id":"a","op":"IQA"},{"id":"b","op":"TAG","inputs":["a"]}],
        "outputSchema":[{"name":"v","type":"TEXT"}]}"""

    private fun rejects(json: String, contains: String) {
        try { Recipes.parse(json); fail("accepted: $json") } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$contains'", e.message.orEmpty().contains(contains))
        }
    }

    @Test fun validationRejectsInconsistentRecipes() {
        assertEquals("x", Recipes.parse(good).id)
        rejects(good.replace("\"merge\":\"concat\"", "\"merge\":\"vote\""), "cannot merge by vote")
        rejects(good.replace("\"axis\":\"shard\"", "\"axis\":\"diagonal\""), "unknown axis")
        rejects(good.replace("\"merge\":\"concat\"", "\"merge\":\"tree\""), "tree")
        rejects(good.replace("\"inputs\":[\"a\"]", "\"inputs\":[\"z\"]"), "not an earlier stage")
        rejects(good.replace("\"table\":\"t_x\",", ""), "table name")
        rejects(good.replace("\"op\":\"IQA\"", "\"op\":\"TELEPORT\""), "does not parse")
        rejects(good.replace("\"input\":\"images\"", "\"input\":\"prompt\""), "prompt")
        rejects(good.replace("\"op\":\"TAG\"", "\"op\":\"GEN_IMAGE\""), "folder")
    }

    @Test fun fileNameMustMatchId() {
        try { Recipes.load(listOf("y.json")) { good }; fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("does not match")) }
        assertFalse(Recipes.load(listOf("x.json", "README.md")) { good }.isEmpty())
    }
}
