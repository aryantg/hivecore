package com.hivecore.ledger

import com.hivecore.core.Hashing
import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.Verify
import com.hivecore.core.fakes.FakeLedgerStore
import com.hivecore.core.fakes.FakeSigner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

class LedgerTest {
    private var clock = 1_700_000_000_000L
    private val db = JdbcSqlDb.inMemory()
    private val signer = FakeSigner()
    private val ledger = Ledger(db, signer) { clock }

    @After fun tearDown() = db.close()

    @Test fun chainLinksHashesAndSignatures() {
        val e1 = ledger.append("job-1", "ask", """{"q":"Q1"}""")
        val e2 = ledger.append("job-2", "answer", """{"q":"Q2"}""")
        val e3 = ledger.append("close-1", "close", """{"pages":3}""")
        assertEquals(Ledger.GENESIS, e1.prevHash)
        assertEquals(FakeLedgerStore.GENESIS, e1.prevHash)
        assertEquals(Hashing.sha256Hex(Ledger.GENESIS + "\njob-1\nask\n$clock\n" + """{"q":"Q1"}"""), e1.entryHash)
        assertEquals(Ledger.hashOf(Ledger.GENESIS, "job-1", "ask", clock, """{"q":"Q1"}"""), e1.entryHash)
        assertEquals(e1.entryHash, e2.prevHash)
        assertEquals(e2.entryHash, e3.prevHash)
        assertEquals(listOf(1L, 2L, 3L), listOf(e1.seq, e2.seq, e3.seq))
        assertTrue(Verify.verify(signer.publicKeyB64, e2.entryHash.toByteArray(), Base64.getDecoder().decode(e2.sig)))
        assertTrue(ledger.verifyChain())
        assertEquals(listOf(3L, 2L, 1L), ledger.entries().map { it.seq })   // newest first
        assertEquals(listOf(3L, 2L), ledger.entries(2).map { it.seq })
        assertEquals(e2, ledger.entries().first { it.seq == 2L })
        assertEquals(3L, ledger.count())

        // Same algorithm as the Wave 0 fake, so the ask agent's tests transfer.
        val fake = FakeLedgerStore(signer) { clock }
        val f1 = fake.append("job-1", "ask", """{"q":"Q1"}"""); val f2 = fake.append("job-2", "answer", """{"q":"Q2"}""")
        assertEquals(e1.entryHash, f1.entryHash); assertEquals(e2.entryHash, f2.entryHash)
    }

    @Test fun tamperingIsDetected() {
        ledger.append("job-1", "ask", """{"q":"Q1"}""")
        ledger.append("job-2", "ask", """{"q":"Q2"}""")
        ledger.append("job-3", "ask", """{"q":"Q3"}""")
        assertTrue(ledger.verifyChain())
        db.exec("UPDATE _receipts SET entry = entry || ' ' WHERE seq = 2")   // FakeLedgerStore.tamper does the same
        assertFalse(ledger.verifyChain())
        db.exec("UPDATE _receipts SET entry = ? WHERE seq = 2", listOf("""{"q":"Q2"}"""))
        assertTrue(ledger.verifyChain())

        // Re-hashing a changed entry (and re-linking) is caught by the signature; forging a signature by the hash.
        val forged = Hashing.sha256Hex(Ledger.GENESIS + """{"q":"forged"}""")
        db.exec("UPDATE _receipts SET entry = ?, entryHash = ? WHERE seq = 1", listOf("""{"q":"forged"}""", forged))
        assertFalse(ledger.verifyChain())
        val sig = Base64.getEncoder().encodeToString(signer.sign(forged.toByteArray()))
        db.exec("UPDATE _receipts SET sig = ? WHERE seq = 1", listOf(sig))
        assertFalse(ledger.verifyChain())   // the next link's prevHash no longer matches
        db.exec("DELETE FROM _receipts WHERE seq = 2")
        assertFalse(ledger.verifyChain())   // #3 still points at the deleted #2
    }

    @Test fun jobIdKindAndTsAreInsideTheHash() {
        ledger.append("job-1", "answer", """{"outcome":"declined"}""")
        ledger.append("job-2", "ask", "{}")
        assertTrue(ledger.verifyChain())
        db.exec("UPDATE _receipts SET kind = 'close' WHERE seq = 1")
        assertFalse(ledger.verifyChain())
        db.exec("UPDATE _receipts SET kind = 'answer', jobId = 'job-9' WHERE seq = 1")
        assertFalse(ledger.verifyChain())
        db.exec("UPDATE _receipts SET jobId = 'job-1', ts = ts + 1 WHERE seq = 1")
        assertFalse(ledger.verifyChain())
        db.exec("UPDATE _receipts SET ts = ts - 1 WHERE seq = 1")
        assertTrue(ledger.verifyChain())
    }

    @Test fun verifyChainChecksCountersignaturesWithThePinnedKeys() {
        val sales = FakeSigner()
        ledger.peerKey = { if (it == "sales-node") sales.publicKeyB64 else null }
        ledger.append("job-1", "ask", "{}")
        val h = "ab".repeat(32)
        ledger.countersign("job-1", "sales-node", h, Base64.getEncoder().encodeToString(sales.sign(h.toByteArray())))
        assertTrue(ledger.verifyChain())
        // A countersignature that does not verify with the pinned key breaks the chain.
        db.exec("UPDATE _receipts SET peerSig = ? WHERE seq = 1", listOf(Base64.getEncoder().encodeToString(FakeSigner().sign(h.toByteArray()))))
        assertFalse(ledger.verifyChain())
        // A peer that is no longer pinned cannot be checked: logged, not a break.
        ledger.peerKey = { null }
        assertTrue(ledger.verifyChain())
    }

    @Test fun receiptAndResetEntriesAreRealJson() {
        ledger.append("job\"1", "ask", "{}")
        ledger.countersign("job\"1", "a", "h1", "s1")
        ledger.countersign("job\"1", "peer\",\"x", "h2", "s2")
        val receipt = ledger.entriesFor("job\"1").last()
        assertEquals("receipt", receipt.kind)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(receipt.entry).jsonObject
        assertEquals("job\"1", o["jobId"]!!.jsonPrimitive.content)
        assertEquals("peer\",\"x", o["peer"]!!.jsonPrimitive.content)
        ledger.markReset()
        assertEquals("true", kotlinx.serialization.json.Json.parseToJsonElement(ledger.entries().first().entry).jsonObject["reset"]!!.jsonPrimitive.content)
    }

    @Test fun countersignIsStoredPerPeer() {
        ledger.append("job-1", "ask", """{"q":"Q1"}""")
        val salesSigner = FakeSigner()
        val salesHash = "ab".repeat(32)
        val salesSig = Base64.getEncoder().encodeToString(salesSigner.sign(salesHash.toByteArray()))
        ledger.countersign("job-1", "sales-node", salesHash, salesSig)
        val e = ledger.entriesFor("job-1").single()
        assertEquals("sales-node", e.peerNode); assertEquals(salesHash, e.peerEntryHash); assertEquals(salesSig, e.peerSig)
        assertTrue(ledger.verifyPeerSig(e, salesSigner.publicKeyB64))
        assertFalse(ledger.verifyPeerSig(e, FakeSigner().publicKeyB64))
        assertTrue(ledger.verifyChain())

        // A second participant's receipt for the same ask does not overwrite the first.
        ledger.countersign("job-1", "accounts-node", "cd".repeat(32), "sig2")
        val forJob = ledger.entriesFor("job-1")
        assertEquals(2, forJob.size)
        assertEquals("sales-node", forJob[0].peerNode)
        assertEquals("receipt", forJob[1].kind); assertEquals("accounts-node", forJob[1].peerNode); assertEquals("cd".repeat(32), forJob[1].peerEntryHash)
        assertTrue(ledger.verifyChain())
        // The same peer again updates in place.
        ledger.countersign("job-1", "sales-node", "ef".repeat(32), "sig3")
        assertEquals(2, ledger.entriesFor("job-1").size)
        assertEquals("ef".repeat(32), ledger.entriesFor("job-1")[0].peerEntryHash)
        // Unknown job: ignored.
        ledger.countersign("job-404", "sales-node", "x", "y")
        assertEquals(2L, ledger.count())
        assertFalse(ledger.verifyPeerSig(ledger.entriesFor("job-1")[1], salesSigner.publicKeyB64))   // "sig2" is not base64 DER
    }

    @Test fun retentionAndResetMarker() {
        val day = 86_400_000L
        clock = 1_700_000_000_000L; ledger.append("old-1", "ask", "{}")
        clock += day; ledger.append("old-2", "answer", "{}")
        clock += 10 * day; ledger.append("new-1", "ask", "{}")
        clock += day
        ledger.retain(7)
        assertEquals(listOf("new-1"), ledger.entries().map { it.jobId })
        assertTrue(ledger.verifyChain())           // the surviving head keeps its original prevHash
        val next = ledger.append("new-2", "ask", "{}")
        assertEquals(ledger.entriesFor("new-1").single().entryHash, next.prevHash)

        // The newest entry is never deleted, even when it is old.
        clock += 30 * day
        ledger.retain(7)
        assertEquals(listOf("new-2"), ledger.entries().map { it.jobId })

        assertNull(ledger.lastResetSeq())
        ledger.markReset()
        val marker = ledger.entries().first()
        assertEquals("reset", marker.kind); assertEquals("reset", marker.jobId)
        assertEquals(marker.seq, ledger.lastResetSeq())
        ledger.append("after-1", "ask", "{}"); ledger.append("after-2", "close", "{}")
        ledger.deleteAfterLastReset()
        assertEquals(listOf("reset", "new-2"), ledger.entries().map { it.jobId })
        assertTrue(ledger.verifyChain())
        assertNotNull(ledger.lastResetSeq())
    }

    @Test fun reopeningKeepsTheChain() {
        ledger.append("job-1", "ask", "{}")
        val again = Ledger(db, signer) { clock }
        val e2 = again.append("job-2", "ask", "{}")
        assertEquals(ledger.entriesFor("job-1").single().entryHash, e2.prevHash)
        assertTrue(again.verifyChain())
        assertTrue(ledger.hasJob("job-2"))
    }
}
