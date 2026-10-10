package com.hivecore.mesh

import com.hivecore.core.JdbcSqlDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStoreTest {
    private val db = JdbcSqlDb.inMemory()
    private val store = PeerStore(db)

    @After fun tearDown() = db.close()

    @Test fun pinLookupUpdateForget() {
        store.pin("n1", "Sales", "PK1", "APK1", listOf("sales", "q3_review.pptx"), 1000L, "bt", "AA:BB")
        store.pin("n2", "Accounts", "PK2", null, listOf("invoices"), 2000L)
        assertTrue(store.isPinned("n1"))
        assertFalse(store.isPinned("nope"))

        val p = store.get("n1")!!
        assertEquals("Sales", p.name); assertEquals("PK1", p.pubkey); assertEquals("APK1", p.approvalPubkey)
        assertEquals(1000L, p.pinnedAt); assertEquals(1000L, p.lastSeen); assertEquals("bt", p.lastTransport); assertEquals("AA:BB", p.lastEndpoint)
        assertEquals(listOf("sales", "q3_review.pptx"), p.holds)
        assertNull(store.get("n2")!!.approvalPubkey)
        assertNull(store.get("n2")!!.lastTransport)

        assertEquals("n1", store.byEndpoint("bt", "AA:BB")!!.nodeId)
        assertNull(store.byEndpoint("nearby", "AA:BB"))

        store.updateFromHello("n1", "Sales 2", null, listOf("sales"), 3000L, "nearby", "ep9")
        val u = store.get("n1")!!
        assertEquals("Sales 2", u.name); assertNull(u.approvalPubkey); assertEquals(listOf("sales"), u.holds)
        assertEquals(3000L, u.lastSeen); assertEquals("nearby", u.lastTransport); assertEquals("ep9", u.lastEndpoint)
        assertEquals("PK1", u.pubkey)            // the node key never changes through updateFromHello
        assertEquals(1000L, u.pinnedAt)
        assertEquals("n1", store.byEndpoint("nearby", "ep9")!!.nodeId)

        store.touch("n2", 4000L, "bt", "CC:DD")
        assertEquals(4000L, store.get("n2")!!.lastSeen)
        assertEquals("CC:DD", store.get("n2")!!.lastEndpoint)

        assertEquals(listOf("Accounts", "Sales 2"), store.all().map { it.name })
        store.forget("n1")
        assertNull(store.get("n1"))
        assertEquals(1, store.all().size)
        // Re-pinning replaces everything, including pinnedAt.
        store.pin("n2", "Accounts", "PK2b", "APK2", emptyList(), 5000L)
        assertEquals("PK2b", store.get("n2")!!.pubkey)
        assertEquals(5000L, store.get("n2")!!.pinnedAt)
    }

    @Test fun survivesReopenOnSameDb() {
        store.pin("n1", "HR", "PK", null, listOf("employees"), 1L)
        val again = PeerStore(db)   // CREATE TABLE IF NOT EXISTS is idempotent
        assertNotNull(again.get("n1"))
    }
}
