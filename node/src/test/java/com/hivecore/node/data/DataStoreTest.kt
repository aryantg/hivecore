package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import com.hivecore.core.fakes.FakeSchemaSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DataStoreTest {
    private lateinit var db: JdbcSqlDb

    @Before fun setUp() { db = JdbcSqlDb.inMemory() }
    @After fun tearDown() = db.close()

    @Test fun migrateIsIdempotentAndCreatesSystemTables() {
        val s = DataStore(db)
        s.migrate(); s.migrate()
        val names = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").map { it["name"] as String }
        for (t in listOf("_tables", "_columns", "_sources", "_intent_log", "_rollups", "_meta")) assertTrue(t, t in names)
    }

    @Test fun seedHrMatchesTheManifestAndTheFakeSchema() {
        val s = Fixtures.store(NodeRole.HR, db)
        val schema = s.schema()
        assertEquals(NodeRole.HR, schema.node)
        assertEquals(1, schema.tables.size)
        val emp = schema.tables[0]
        val fake = FakeSchemaSource.EMPLOYEES
        assertEquals(fake.name, emp.name)
        assertEquals(61L, emp.rowCount)
        assertEquals(fake.aliases, emp.aliases)
        assertFalse(emp.confidential)
        assertTrue(emp.isSensitive)
        assertEquals(fake.columns.map { it.name }, emp.columns.map { it.name })
        assertEquals(fake.columns.map { it.type }, emp.columns.map { it.type })
        assertEquals(fake.columns.map { it.confidential }, emp.columns.map { it.confidential })
        assertEquals(fake.columns.map { it.identifier }, emp.columns.map { it.identifier })
        assertEquals(fake.columns.map { it.groupable }, emp.columns.map { it.groupable })
        assertTrue("pay" in emp.columns.first { it.name == "salary" }.aliases)
        // Idempotent: seeding again neither duplicates rows nor sources.
        s.seedNow(NodeRole.HR)
        assertEquals(61L, s.rowCount("employees"))
        assertEquals(1, s.sources("employees").size)
        assertTrue(s.sources("employees")[0].seed)
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM t_employees WHERE name = 'Rahul Sharma'")[0]["n"])
    }

    @Test fun seedSalesAndAccountsDeriveMonthForInvoices() {
        val sales = Fixtures.store(NodeRole.SALES)
        val st = sales.schema().tables.single()
        assertEquals("sales", st.name)
        assertEquals(1204L, st.rowCount)
        assertEquals(FakeSchemaSource.SALES.columns.map { it.name to it.groupable }, st.columns.map { it.name to it.groupable })
        assertTrue(sales.derivedColumns("sales").isEmpty())

        val acc = Fixtures.store(NodeRole.ACCOUNTS, JdbcSqlDb.inMemory())
        val inv = acc.schema().tables.single()
        assertEquals("invoices", inv.name)
        assertEquals(40L, inv.rowCount)
        assertEquals(FakeSchemaSource.INVOICES.columns.map { it.name }, inv.columns.map { it.name })
        assertEquals(FakeSchemaSource.INVOICES.columns.map { it.groupable }, inv.columns.map { it.groupable })
        assertEquals(listOf("month"), acc.derivedColumns("invoices"))
        val byMonth = acc.db.query("SELECT month, COUNT(*) AS n FROM t_invoices GROUP BY month ORDER BY month")
        val expected = Fixtures.expectedGroups("invoices_by_month", "total")
        assertEquals(expected.keys.sorted(), byMonth.map { it["month"] })
        assertEquals(expected.values.map { it.second }, byMonth.map { it["n"] })
        assertTrue(acc.schema().docs.isEmpty())
    }

    @Test fun q4XlsxAppendsToSalesBecauseTheColumnSetMatches() {
        val s = Fixtures.store(NodeRole.SALES)
        val data = XlsxReader.read(Fixtures.file("q4_sales.xlsx"))
        assertEquals("sales", s.appendTargetFor(data.header))
        val r = s.importTable("q4_sales", data.header, data.rows, "q4_sales.xlsx", seed = false)
        assertTrue(r.appended)
        assertEquals("sales", r.table)
        assertEquals(1380L, r.rows)
        assertEquals(2584L, s.rowCount("sales"))
        val sources = s.sources("sales")
        assertEquals(2, sources.size)
        assertFalse(sources[1].seed)
        assertEquals("q4_sales.xlsx", sources[1].fileName)
        assertEquals(listOf("sales"), s.tableNames())
        // The seed table's manifest flags survive the append.
        assertTrue(s.table("sales")!!.columns.first { it.name == "region" }.groupable)
        assertTrue(s.table("sales")!!.columns.first { it.name == "rep_id" }.identifier)
        // Reset removes exactly the appended rows and keeps the seed.
        s.resetDemo()
        assertEquals(1204L, s.rowCount("sales"))
        assertEquals(1, s.sources().size)
    }

    @Test fun droppedSheetGetsAutomaticFlags() {
        val s = Fixtures.store(NodeRole.ACCOUNTS, db)
        val header = listOf("City", "Product", "Amount", "Customer Name", "Qty")
        val cities = listOf("Pune", "Mumbai", "Delhi", "Chennai")
        val rows = (0 until 40).map { i -> listOf(cities[i % 4], "SKU-%03d".format(i), "${(i + 1) * 100}", "Cust ${i % 7}", if (i % 5 == 0) "" else "${i % 5}.5") }
        assertNull(s.appendTargetFor(header))
        val r = s.importTable("shop.xlsx", header, rows, "shop.xlsx", seed = false)
        assertFalse(r.appended)
        assertEquals("shop", r.table)
        assertEquals(listOf("city", "product", "amount", "customer_name", "qty"), r.columns)
        val t = s.table("shop")!!
        assertEquals(40L, t.rowCount)
        val c = t.columns.associateBy { it.name }
        assertEquals(ColType.TEXT, c["city"]!!.type); assertTrue(c["city"]!!.groupable); assertFalse(c["city"]!!.identifier)
        assertEquals(ColType.TEXT, c["product"]!!.type); assertFalse(c["product"]!!.groupable); assertTrue(c["product"]!!.identifier)   // 40 distinct of 40
        assertEquals(ColType.INTEGER, c["amount"]!!.type); assertFalse(c["amount"]!!.groupable); assertFalse(c["amount"]!!.identifier)
        assertTrue(c["customer_name"]!!.identifier)   // by name token, although only 7 distinct
        assertTrue(c["customer_name"]!!.groupable)
        assertEquals(ColType.REAL, c["qty"]!!.type)
        assertTrue(t.columns.none { it.confidential })
        assertFalse(t.isSensitive)
        // Empty cells are NULL, REAL values bound as doubles.
        assertEquals(8L, db.query("SELECT COUNT(*) AS n FROM t_shop WHERE qty IS NULL")[0]["n"])
        assertEquals(1.5, db.query("SELECT qty FROM t_shop WHERE product = 'SKU-001'")[0]["qty"])
        // "amount by city" compiles on this table and the group refusal lists its groupable columns.
        val compiler = RuleIntentCompiler()
        val schema = s.schema()
        assertTrue(compiler.compile(com.hivecore.core.Intent("shop", "sum", "amount", listOf("city")), schema) is com.hivecore.core.Compiled.Query)
        val refused = compiler.compile(com.hivecore.core.Intent("shop", "sum", "amount", listOf("product")), schema) as com.hivecore.core.Compiled.Refused
        assertEquals("cannot group by product; this table allows: city, customer_name", refused.reason)
        // The owner marks a column confidential.
        s.setConfidential("shop", "amount", true)
        assertTrue(s.table("shop")!!.columns.first { it.name == "amount" }.confidential)
        assertTrue(s.table("shop")!!.isSensitive)
        // A second drop of the same sheet appends; a different sheet with a clashing name gets a suffix.
        assertEquals("shop", s.importTable("shop", header, rows.take(3), "shop (1).xlsx", seed = false).table)
        assertEquals(43L, s.rowCount("shop"))
        assertEquals("shop_2", s.importTable("shop", listOf("a", "b"), listOf(listOf("1", "2")), "shop.csv", seed = false).table)
        // Reset drops the non-seed tables entirely and keeps invoices.
        s.resetDemo()
        assertEquals(listOf("invoices"), s.tableNames())
        assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 't_shop'").isEmpty())
    }

    @Test fun headerNormalisationAndTypeInference() {
        assertEquals(listOf("order_id", "c_1st_qty", "r_gion", "col_4", "region", "region_2", "src"),
            DataStore.normaliseHeader(listOf("Order ID", "1st Qty", "Région", "", "region", "Region", "_src")))
        assertEquals("q4_sales", DataStore.normaliseName("Q4 Sales"))
        assertEquals(ColType.INTEGER, DataStore.inferType(sequenceOf("1", "", " 2 ", "-3")))
        assertEquals(ColType.REAL, DataStore.inferType(sequenceOf("1", "2.5", "")))
        assertEquals(ColType.TEXT, DataStore.inferType(sequenceOf("1", "x")))
        assertEquals(ColType.TEXT, DataStore.inferType(sequenceOf("2026-01", "2026-02")))
        assertEquals(ColType.INTEGER, DataStore.inferType(emptySequence()))
        assertEquals(5L, DataStore.convert("5", ColType.INTEGER))
        assertEquals(5.0, DataStore.convert("5", ColType.REAL))
        assertEquals("5", DataStore.convert("5", ColType.TEXT))
        assertNull(DataStore.convert("  ", ColType.INTEGER))
    }

    @Test fun deleteTableIntentLogRollupsAndDocHooks() {
        val s = Fixtures.store(NodeRole.HR, db)
        s.importTable("extra", listOf("k", "v"), listOf(listOf("a", "1")), "extra.csv", seed = false)
        assertEquals(listOf("employees", "extra"), s.tableNames())
        s.deleteTable("extra")
        assertEquals(listOf("employees"), s.tableNames())
        assertTrue(db.query("SELECT 1 FROM _columns WHERE tbl = 'extra'").isEmpty())
        assertTrue(db.query("SELECT 1 FROM _sources WHERE tbl = 'extra'").isEmpty())
        assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 't_extra'").isEmpty())

        s.logIntent("job1", "How many?", "HR", "{\"intents\":[]}", true, "SELECT 1", "ok", 120, "NPU")
        val log = db.query("SELECT * FROM _intent_log")
        assertEquals(1, log.size)
        assertEquals("job1", log[0]["jobId"]); assertEquals(1L, log[0]["parsedOk"]); assertEquals(120L, log[0]["ms"])

        db.exec("CREATE TABLE _r_hr_headcount_region AS SELECT region, COUNT(*) AS n FROM t_employees GROUP BY region")
        db.exec("INSERT INTO _rollups (name, tbl, signature, builtAt) VALUES (?, ?, ?, ?)", listOf("_r_hr_headcount_region", "employees", SqlQueryExecutor.rollupSignature("employees", listOf("region")), 1L))
        var resetDocs = 0
        s.docResetter = { resetDocs++ }
        s.resetDemo()
        assertEquals(1, resetDocs)
        assertTrue(db.query("SELECT 1 FROM _rollups").isEmpty())
        assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = '_r_hr_headcount_region'").isEmpty())
        assertEquals(61L, s.rowCount("employees"))

        val deleted = ArrayList<String>()
        s.docDeleter = { deleted.add(it) }
        s.deleteDoc("doc_x")
        assertEquals(listOf("doc_x"), deleted)
        s.docLister = { listOf(FakeSchemaSource.ONBOARDING) }
        assertEquals(listOf("onboarding_checklist.docx"), s.schema().docs.map { it.name })
        assertNotNull(db.query("SELECT value FROM _meta WHERE key = 'seeded_role'")[0]["value"])
    }
}
