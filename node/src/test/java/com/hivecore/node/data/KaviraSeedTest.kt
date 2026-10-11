package com.hivecore.node.data

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import com.hivecore.engine.workload.Recipes
import com.hivecore.engine.workload.RecipeSource
import com.hivecore.node.TestAssets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The Kavira Pharmaceuticals seed (tools/kavira): each role's phone loads its share, and the table recipes find their rows. */
class KaviraSeedTest {
    private val assetRoot = listOf(File("src/main/assets"), File("node/src/main/assets")).first { File(it, "seed/seed_manifest.json").isFile }
    private val assets = AssetReader { path -> File(assetRoot, path).inputStream() }
    private val manifestText = File(assetRoot, "seed/seed_manifest.json").readText()
    private val manifest = Json.parseToJsonElement(manifestText).jsonObject

    private fun store(role: NodeRole): DataStore = DataStore(JdbcSqlDb.inMemory(), assets) { role }.also { it.migrate(); it.seedNow(role) }

    @Test fun eachRoleLoadsItsShareWithTheManifestCounts() {
        val expected = mapOf(
            NodeRole.SALES to listOf("sales_monthly", "products", "branch_master", "sales_force", "events", "stockists"),
            NodeRole.HR to listOf("staff", "compensation", "salary_history", "devices"),
            NodeRole.ACCOUNTS to listOf("financials", "receivables", "budget_vs_actual"),
        )
        val seen = HashSet<String>()
        for ((role, tables) in expected) {
            val s = store(role)
            assertEquals(role.name, tables.sorted(), s.tableNames().sorted())
            for (t in tables) {
                assertTrue("$t is on one phone only", seen.add(t))
                val rows = manifest.getValue("tables").jsonObject.getValue(t).jsonObject.getValue("rows").jsonPrimitive.long
                assertEquals(t, rows, s.rowCount(t))
                assertTrue("$t is a seed table", s.isSeedTable(t))
            }
            // the demo's scripted phones show exactly what the real seed holds
            val demo = SeedManifestSchema.schema(role, manifestText)
            val real = s.schema()
            assertEquals(real.tables.map { it.name to it.rowCount }.sortedBy { it.first }, demo.tables.map { it.name to it.rowCount }.sortedBy { it.first })
            for (t in real.tables) {
                val d = demo.tables.single { it.name == t.name }
                assertEquals(t.name, t.columns, d.columns)
                assertEquals(t.name, t.aliases, d.aliases)
            }
        }
    }

    @Test fun theFiguresMatchTheGeneratorsAnswerKey() {
        val s = store(NodeRole.SALES)
        // answer_key_sales.json: FY2024 43.12 Cr, FY2025 49.62 Cr, FY2026 59.51 Cr (net sales, crore)
        val byFy = s.db.query("SELECT fiscal_year, SUM(net_sales) AS v FROM t_sales_monthly GROUP BY fiscal_year ORDER BY fiscal_year")
            .associate { it["fiscal_year"] as String to (it["v"] as Long) }
        assertEquals(431_190_000L, byFy["FY2024"])
        assertEquals(496_232_700L, byFy["FY2025"])
        assertEquals(595_118_300L, byFy["FY2026"])
        val hr = store(NodeRole.HR)
        assertEquals(280L, hr.rowCount("staff"))
        // the pay columns are confidential, so only AVG/COUNT with the 5-person floor ever leave the HR phone
        val comp = hr.schema().tables.single { it.name == "compensation" }
        assertEquals(setOf("ctc", "fixed_pay", "variable_target", "incentive_paid", "travel_allowance"), comp.columns.filter { it.confidential }.map { it.name }.toSet())
        assertFalse(hr.schema().tables.single { it.name == "staff" }.isSensitive)
        // the deck's measure: "sales" names net_sales and nothing else across the three phones
        val salesAliases = listOf(NodeRole.SALES, NodeRole.HR, NodeRole.ACCOUNTS).flatMap { r -> SeedManifestSchema.schema(r, manifestText).tables.flatMap { t -> t.columns.filter { "sales" in it.aliases }.map { "${t.name}.${it.name}" } } }
        assertEquals(listOf("sales_monthly.net_sales"), salesAliases)
    }

    @Test fun tableRecipesFindTheirItemsOnTheOwnersPhone() {
        val recipes = Recipes.fromDir(listOf(File("../engine/src/main/assets/swarm/recipes"), File("engine/src/main/assets/swarm/recipes")).first { it.isDirectory })
            .associateBy { it.id }
        val owner = mapOf("stockist_letters" to NodeRole.SALES, "brand_reviews" to NodeRole.SALES, "branch_review_packs" to NodeRole.SALES, "launch_messages" to NodeRole.SALES)
        val count = mapOf("stockist_letters" to 48, "brand_reviews" to 19, "branch_review_packs" to 3, "launch_messages" to 3)
        for ((id, role) in owner) {
            val r = recipes.getValue(id)
            assertEquals("table", r.input)
            val items = RecipeItems(store(role)).items(r.source!!)
            assertEquals(id, count.getValue(id), items.size)
            for (i in items) {
                val o = i.jsonObject
                assertTrue(id, o.getValue("item").jsonPrimitive.content.isNotBlank())
                assertTrue(id, o.getValue("text").jsonPrimitive.content.lines().size >= 1)
            }
        }
        val letter = RecipeItems(store(NodeRole.SALES)).items(recipes.getValue("stockist_letters").source!!).first().jsonObject
        assertEquals("KA-01 · Bengaluru East · BLR", letter.getValue("item").jsonPrimitive.content)
        assertEquals("stockist_id: KA-01; town: Bengaluru East; branch: BLR\n" +
            "FY2025: stockist_sales 4,520,000; avg_payment_days 47.0; rows 1\n" +
            "FY2026: stockist_sales 4,900,000; avg_payment_days 52.0; rows 1", letter.getValue("text").jsonPrimitive.content)
        // every Kavira recipe that reads a table is in the built-in set
        assertTrue(Recipes.KAVIRA.all { it in Recipes.BUILT_IN })
    }

    @Test fun aTableRecipeNeverReadsPayAndOnlyRunsWhereTheTableIs() {
        val hr = RecipeItems(store(NodeRole.HR))
        try { hr.items(RecipeSource("compensation", listOf("branch"), avg = listOf("ctc"))); fail("pay was read") }
        catch (e: IllegalArgumentException) { assertTrue(e.message, e.message!!.contains("confidential")) }
        try { RecipeItems(store(NodeRole.ACCOUNTS)).items(RecipeSource("stockists", listOf("town"))); fail("ran away from its table") }
        catch (e: IllegalArgumentException) { assertTrue(e.message, e.message!!.contains("not on this phone")) }
        // the pay-free directory may be fanned out
        assertEquals(3, hr.items(RecipeSource("staff", listOf("branch"))).size)
        val s = store(NodeRole.SALES)
        s.setTablePrivate("branch_master", true)
        try { RecipeItems(s).items(RecipeSource("branch_master", listOf("branch"))); fail("a private table was fanned out") }
        catch (e: IllegalArgumentException) { assertTrue(e.message, e.message!!.contains("private")) }
    }

    @Test fun pickingARoleLeavesOnlyThatPhonesShare() {
        // a phone seeded before the Kavira data: the old tour's sales table (whose revenue also answers to "sales")
        val s = DataStore(JdbcSqlDb.inMemory(), assets) { NodeRole.SALES }.also { it.migrate() }
        s.seedFromManifest(NodeRole.SALES, Fixtures.text("seed_manifest.json"))
        assertEquals(listOf("sales"), s.tableNames())
        s.importTable("my_upload", listOf("k", "v"), listOf(listOf("a", "1")), "my_upload.csv", seed = false)
        s.seedNow(NodeRole.SALES)
        assertEquals(listOf("branch_master", "events", "my_upload", "products", "sales_force", "sales_monthly", "stockists"), s.tableNames().sorted())
        // switching the phone to HR swaps the share; the owner's own upload stays
        s.seedNow(NodeRole.HR)
        assertEquals(listOf("compensation", "devices", "my_upload", "salary_history", "staff"), s.tableNames().sorted())
        // seeding every role on one phone keeps them all
        NodeRole.entries.forEach { s.seedNow(it, exclusive = false) }
        assertEquals(14, s.tableNames().size)
        assertFalse("same manifest: nothing to do", s.reseedIfManifestChanged())
    }

    @Test fun anOldSeedDocumentGoesButALiveShareStays() {
        val db = JdbcSqlDb.inMemory()
        val docs = com.hivecore.node.docs.DocStore(db)
        val s = DataStore(db, assets) { NodeRole.SALES }.also { it.migrate() }
        s.docLister = { docs.docs() }; s.docDeleter = { docs.delete(it) }
        s.seedDocLister = { docs.records().filter { it.seed }.map { it.def } }
        val unit = listOf(com.hivecore.node.docs.TextUnit("p.1", "Q3 priorities"))
        docs.addDocument("q3_review.pptx", "pptx", unit, seed = true, hash = "h-q3")
        docs.addDocument("leave_policy.pdf", "pdf", unit, seed = false, hash = "h-leave")   // shared in live, during a tour
        s.seedNow(NodeRole.SALES)
        assertEquals(listOf("leave_policy.pdf"), docs.docs().map { it.name })
    }

    @Test fun anUpdatedManifestReseedsAPhoneOnce() {
        val s = DataStore(JdbcSqlDb.inMemory(), assets) { NodeRole.HR }.also { it.migrate() }
        assertFalse("never seeded: left alone", s.reseedIfManifestChanged())
        s.seedFromManifest(NodeRole.HR, Fixtures.text("seed_manifest.json"))   // the old tour seed
        assertEquals(listOf("employees"), s.tableNames())
        assertTrue(s.reseedIfManifestChanged())
        assertEquals(listOf("compensation", "devices", "salary_history", "staff"), s.tableNames().sorted())
        assertFalse(s.reseedIfManifestChanged())
    }

    @Test fun theDeckSubQuestionHasACachedIntentOnTheSalesPhone() {
        val t = TestAssets.templates().intents("sales by month FY2024 26", NodeRole.SALES)!!.set.intents.single()
        assertEquals("sales_monthly", t.table)
        assertEquals("net_sales", t.column)
        assertEquals(listOf("month"), t.groupBy)
        assertEquals(listOf("2023-04", "2026-03"), t.filters.map { it.value.jsonPrimitive.content })
        // and it compiles against the seeded schema
        val c = RuleIntentCompiler().compile(t, store(NodeRole.SALES).schema())
        assertTrue(c.toString(), c is com.hivecore.core.Compiled.Query)
        // every few-shot answer names only Kavira tables
        val names = NodeRole.entries.flatMap { r -> TestAssets.fewShots(r).flatMap { s -> Json.parseToJsonElement(s.answerJson).jsonObject["intents"]?.jsonArray?.map { it.jsonObject.getValue("table").jsonPrimitive.content }.orEmpty() } }
        assertTrue(names.toString(), names.isNotEmpty() && names.all { it in setOf("sales_monthly", "compensation", "staff", "financials", "receivables") })
    }
}
