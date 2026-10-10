package com.hivecore.app

import com.hivecore.app.demo.DemoReset
import com.hivecore.app.demo.PermState
import com.hivecore.app.demo.PermissionsChecklist
import com.hivecore.app.demo.SampleInvoices
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.MemoryPrefs
import com.hivecore.ui.ports.ResetOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The demo shortcuts' pure parts: invoice text, the permission checklist, and what the reset clears (and keeps). */
class DemoToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun invoicesAreDeterministicAndAddUp() {
        val a = SampleInvoices.text(3)
        assertEquals(a, SampleInvoices.text(3))
        assertNotEquals(a, SampleInvoices.text(4))
        assertEquals("INV-2026-1003", a.number)
        assertEquals(3, a.items.size)
        assertEquals("INVOICE", a.lines.first())
        assertTrue(a.lines.last().startsWith("TOTAL  ₹ "))
        val sum = a.items.sumOf { it.substringAfter("₹ ").replace(",", "").toInt() }
        assertEquals(SampleInvoices.rupees(sum), a.total.removePrefix("₹ "))
    }

    @Test fun checklistListsOnlyWhatApplies() {
        val s = PermState(camera = true, nearby = false, bluetooth = true, notifications = true, allFiles = null, batteryExempt = false, fullScreenIntent = null, fingerprint = true)
        val items = PermissionsChecklist.items(s)
        assertEquals(listOf("camera", "nearby", "bluetooth", "notifications", "battery", "fingerprint"), items.map { it.id })
        assertEquals(listOf("nearby", "battery"), items.filter { !it.granted }.map { it.id })
        assertTrue(PermissionsChecklist.items(s.copy(allFiles = false, fullScreenIntent = true)).any { it.id == "all_files" && !it.granted })
    }

    @Test fun resetClearsTheDemoAndKeepsTheRest() {
        val files = tmp.newFolder("files"); val cache = tmp.newFolder("cache")
        File(files, "blobs").mkdirs(); File(files, "blobs/b1").writeText("x")
        File(cache, "picked").mkdirs(); File(cache, "picked/sample_0001.png").writeText("x")
        File(files, "hivecore.log").writeText("keep")
        val db = JdbcSqlDb.inMemory().apply {
            exec("CREATE TABLE _answered (h TEXT)"); exec("INSERT INTO _answered VALUES ('q')")
            exec("CREATE TABLE _receipts (r TEXT)"); exec("INSERT INTO _receipts VALUES ('keep')")
            exec("CREATE TABLE _private (t TEXT)"); exec("INSERT INTO _private VALUES ('employees')")
        }
        val journal = JdbcSqlDb.inMemory().apply { exec("CREATE TABLE jobs (id TEXT)"); exec("INSERT INTO jobs VALUES ('j1')"); exec("CREATE TABLE chunk_memo (k TEXT)"); exec("INSERT INTO chunk_memo VALUES ('m')") }
        val prefs = MemoryPrefs().apply { putString("release:TABLE:sales", "x"); putBoolean("hidden_table:hr", true); putString("node_role", "SALES"); putString("relay_address", "r") }

        val lines = DemoReset(files, cache, prefs, db, journal).run(ResetOptions())
        assertEquals(4, lines.size)
        assertEquals(0L, journal.query("SELECT COUNT(*) AS n FROM jobs")[0]["n"])
        assertEquals(0L, journal.query("SELECT COUNT(*) AS n FROM chunk_memo")[0]["n"])
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM _answered")[0]["n"])
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM _private")[0]["n"])
        assertEquals("the ledger survives", 1L, db.query("SELECT COUNT(*) AS n FROM _receipts")[0]["n"])
        assertEquals(setOf("node_role", "relay_address"), prefs.keys())
        assertTrue(File(files, "hivecore.log").exists())
        assertEquals(0, File(files, "blobs").listFiles()!!.size)
        assertEquals(0, File(cache, "picked").listFiles()!!.size)
    }
}
