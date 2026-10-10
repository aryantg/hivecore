package com.hivecore.node.data

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Private mode (swarm plan §7, optional): per-table and per-document marks persisted in `_private`. */
class PrivateMarksTest {
    private lateinit var db: JdbcSqlDb

    @Before fun setUp() { db = JdbcSqlDb.inMemory() }
    @After fun tearDown() = db.close()

    @Test fun defaultOffThenPersistsAcrossStoreInstances() {
        val s = Fixtures.store(NodeRole.HR, db)
        assertTrue(s.privateTables().isEmpty())
        assertTrue(s.privateDocs().isEmpty())
        assertFalse(s.isTablePrivate("employees"))

        s.setTablePrivate("employees", true)
        s.setDocPrivate("doc-7", true)
        s.setTablePrivate("employees", true)   // idempotent

        val again = DataStore(db).also { it.migrate() }   // a fresh instance over the same database (app restart)
        assertEquals(setOf("employees"), again.privateTables())
        assertEquals(setOf("doc-7"), again.privateDocs())
        assertTrue(again.isTablePrivate("employees"))
        assertTrue(again.isDocPrivate("doc-7"))

        again.setTablePrivate("employees", false)
        assertTrue(s.privateTables().isEmpty())
        assertEquals(setOf("doc-7"), s.privateDocs())
    }

    @Test fun deletingTableOrDocDropsItsMark() {
        val s = Fixtures.store(NodeRole.HR, db)
        var deletedDoc: String? = null
        s.docDeleter = { deletedDoc = it }
        s.setTablePrivate("employees", true)
        s.setDocPrivate("doc-1", true)
        s.deleteTable("employees")
        s.deleteDoc("doc-1")
        assertEquals("doc-1", deletedDoc)
        assertTrue(s.privateTables().isEmpty())
        assertTrue(s.privateDocs().isEmpty())
    }

    @Test fun privateMarksDoNotChangeConfidentialFlags() {
        val s = Fixtures.store(NodeRole.HR, db)
        val before = s.schema()
        s.setTablePrivate("employees", true)
        assertEquals(before, s.schema())
    }
}
