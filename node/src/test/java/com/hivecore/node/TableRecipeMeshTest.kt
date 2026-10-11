package com.hivecore.node

import com.hivecore.core.ChunkState
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.JobRequest
import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.engine.workload.Recipes
import com.hivecore.node.data.AssetReader
import com.hivecore.node.data.DataStore
import com.hivecore.node.data.RecipeItems
import com.hivecore.node.textai.LlmMapExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A table recipe end to end over three phones (phone finding 2026-10-11: "Stockist letters" failed because every chunk
 * reached LLM_MAP with its items under a key the text executors do not read). The real recipe file, the real Kavira
 * stockists table, the real LLM_MAP executor over a fake model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableRecipeMeshTest {
    private var harness: SwarmHarness? = null
    @After fun tearDown() { harness?.close() }

    private val assetRoot = listOf(File("src/main/assets"), File("node/src/main/assets")).first { File(it, "seed/seed_manifest.json").isFile }
    private val recipes = Recipes.fromDir(listOf(File("../engine/src/main/assets/swarm/recipes"), File("engine/src/main/assets/swarm/recipes")).first { it.isDirectory })

    private val letter = """{"greeting":"Dear partner,","body":"Thank you for your purchases this year.","closing":"Kavira Pharmaceuticals"}"""

    @Test fun stockistLettersRunAcrossThePhones() = runTest {
        val h = SwarmHarness(this).also { harness = it }
        val (hr, sales, accounts) = h.hrSalesAccounts()
        val engines = listOf(hr, sales, accounts).associate { n -> n.id to FakeLlmEngine().apply { fallback = letter }.also { n.runner.register(LlmMapExecutor(it)) } }
        val kavira = DataStore(JdbcSqlDb.inMemory(), AssetReader { File(assetRoot, it).inputStream() }) { NodeRole.SALES }.also { it.migrate(); it.seedNow(NodeRole.SALES) }
        sales.swarm.recipeDefs = { id -> recipes.firstOrNull { it.id == id } }
        sales.swarm.tableItems = { src -> RecipeItems(kavira).items(src) }
        h.start()

        val views = sales.swarm.run(JobRequest("Stockist letters", recipeId = "stockist_letters")).toList()
        testScheduler.runCurrent()
        val last = views.last()
        assertTrue(last.error ?: last.partialReason ?: "not done", last.done && !last.partial)
        assertTrue(last.chunks.all { it.state == ChunkState.DONE })
        assertTrue("shared out: ${last.nodes.map { it.name }}", last.nodes.size >= 2)

        val items = (last.result as JsonObject)["stages"]!!.jsonObject["letter"]!!.jsonObject["items"]!!.jsonArray
        assertEquals(48, items.size)
        assertEquals("KA-01 · Bengaluru East · BLR", items[0].jsonObject["item"]!!.jsonPrimitive.content)
        assertEquals("Dear partner,", items[0].jsonObject["output"]!!.jsonObject["greeting"]!!.jsonPrimitive.content)
        // each prompt carried that stockist's own figures, filled into the template
        val prompts = engines.values.flatMap { e -> e.calls.map { (msgs, _) -> msgs.last().content } }
        assertEquals("replicate none: one model call per letter", 48, prompts.size)
        assertTrue(prompts.any { "stockist_id: KA-01; town: Bengaluru East; branch: BLR" in it && "FY2026: stockist_sales 4,900,000" in it })
        assertTrue(prompts.all { it.startsWith("You write for Kavira Pharmaceuticals") })
    }

    @Test fun aPromptRecipesTextStageReadsTheBrief() {
        val kit = recipes.first { it.id == "launch_kit" }.toRecipe()
        val staged = com.hivecore.engine.RecipeJobs.promptJobs("j", kit, "Vildora M for type 2 diabetes", "me", private = false)
        val words = staged.jobs.single { it.op == com.hivecore.core.Op.LLM_MAP }
        val slice = com.hivecore.engine.split.Splitters.whole(words.opKey, words.params, words.input).params
        val units = com.hivecore.node.textai.TextInputs.units(slice, emptyList(), emptyList())
        assertEquals(listOf("Vildora M for type 2 diabetes"), units.map { it.text })
        assertEquals(4, (staged.jobs.single { it.op == com.hivecore.core.Op.GEN_IMAGE }.input as com.hivecore.engine.split.SplitInput.Seeds).n)
        // the measured time per item sizes the job and is not passed to the executor
        assertEquals(11_000.0, words.unitMs!!, 0.0)
        assertTrue("unitMs" !in words.params)
        val letters = com.hivecore.engine.RecipeJobs.itemJobs("j", recipes.first { it.id == "stockist_letters" }.toRecipe(), emptyList(), "me", false, key = "texts").jobs.single()
        assertEquals(8_500.0, letters.unitMs!!, 0.0)
        assertEquals(1, letters.replicas)
    }
}
