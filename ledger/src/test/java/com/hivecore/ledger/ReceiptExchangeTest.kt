package com.hivecore.ledger

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.LedgerEntry
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeLedgerStore
import com.hivecore.core.fakes.FakeSigner
import com.hivecore.mesh.HelloBody
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.ReceiptBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ReceiptExchangeTest {
    private var clock = 1_700_000_000_000L
    private val scopes = ArrayList<CoroutineScope>()

    /** A foreground scope on the test scheduler (backgroundScope events are skipped by advanceUntilIdle). */
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    inner class Node(bus: FakeBus, val id: String, val name: String, scope: CoroutineScope) {
        val signer = FakeSigner()
        val db = JdbcSqlDb.inMemory()
        val store = PeerStore(db)
        val transport = bus.node(id, name)
        val router = MeshRouter(mapOf("fake" to transport), signer, store, { HelloBody("", name, "") }, scope, now = { clock })
        val ledger = Ledger(db, signer) { clock }
        val exchange = ReceiptExchange(router, ledger) { clock }.also { it.start(scope) }
        val nodeId get() = router.nodeId
        fun pin(o: Node) = store.pin(o.nodeId, o.name, o.signer.publicKeyB64, null, emptyList(), clock)
    }

    private suspend fun TestScope.mesh(vararg ids: Pair<String, String>): List<Node> {
        val bus = FakeBus()
        val nodes = ids.map { Node(bus, it.first, it.second, scope()) }
        for (a in nodes) for (b in nodes) if (a !== b) a.pin(b)
        testScheduler.runCurrent()
        for (n in nodes) n.router.start()
        advanceUntilIdle()
        return nodes
    }

    @Test fun askerAndParticipantsCountersignEachOther() = runTest {
        val (hr, sales, accounts) = mesh("hr" to "HR", "sales" to "Sales", "accounts" to "Accounts")
        val jobId = "job-1"
        // Participants answer first (their RECEIPTs reach the asker before it has written its own entry).
        val sE = sales.exchange.afterJobNode(jobId, hr.nodeId, """{"requester":"HR","rows":1204}""")
        val aE = accounts.exchange.afterJobNode(jobId, hr.nodeId, """{"requester":"HR","rows":40}""")
        advanceUntilIdle()
        assertEquals("answer", sE.kind)
        assertNull(hr.ledger.entries().firstOrNull())              // nothing yet: the receipts are held
        val hE = hr.exchange.afterJobAsker(jobId, listOf(sales.nodeId, accounts.nodeId, hr.nodeId), """{"q":"Q1","phones":3}""")
        advanceUntilIdle()
        assertEquals("ask", hE.kind)

        // Both participants hold the asker's hash and signature on their answer entry.
        for ((n, e) in listOf(sales to sE, accounts to aE)) {
            val stored = n.ledger.entriesFor(jobId).single()
            assertEquals(n.id, hr.nodeId, stored.peerNode)
            assertEquals(hE.entryHash, stored.peerEntryHash)
            assertEquals(hE.sig, stored.peerSig)
            assertTrue(n.ledger.verifyPeerSig(stored, hr.signer.publicKeyB64))
            assertTrue(n.ledger.verifyChain())
            assertEquals(e.entryHash, stored.entryHash)
        }
        // The asker holds both participants' receipts: one on the ask entry, the second as a receipt entry.
        val hrRows = hr.ledger.entriesFor(jobId)
        assertEquals(2, hrRows.size)
        val byPeer = hrRows.associateBy { it.peerNode }
        assertEquals(sE.entryHash, byPeer[sales.nodeId]!!.peerEntryHash)
        assertEquals(aE.entryHash, byPeer[accounts.nodeId]!!.peerEntryHash)
        assertTrue(hr.ledger.verifyPeerSig(byPeer[sales.nodeId]!!, sales.signer.publicKeyB64))
        assertTrue(hr.ledger.verifyPeerSig(byPeer[accounts.nodeId]!!, accounts.signer.publicKeyB64))
        assertTrue(hr.ledger.verifyChain())
        assertTrue(hr.ledger.verifyPeerSig(byPeer[sales.nodeId]!!, hr.signer.publicKeyB64).not())
    }

    @Test fun askerFirstThenParticipant() = runTest {
        val (hr, sales) = mesh("hr" to "HR", "sales" to "Sales")
        val hE = hr.exchange.afterJobAsker("job-2", listOf(sales.nodeId), "{}")
        advanceUntilIdle()
        assertNull(sales.ledger.entries().firstOrNull())          // held until the answer entry exists
        val sE = sales.exchange.afterJobNode("job-2", hr.nodeId, "{}")
        advanceUntilIdle()
        assertEquals(hE.entryHash, sales.ledger.entriesFor("job-2").single().peerEntryHash)
        assertEquals(sE.entryHash, hr.ledger.entriesFor("job-2").single().peerEntryHash)
    }

    @Test fun worksAgainstTheFakeLedgerAndIgnoresGarbage() = runTest {
        val (hr, sales) = mesh("hr" to "HR", "sales" to "Sales")
        val fake = FakeLedgerStore(hr.signer) { clock }
        val ex = ReceiptExchange(hr.router, fake) { clock }
        fun signed(by: FakeSigner, hash: String) = Base64.getEncoder().encodeToString(by.sign(hash.toByteArray()))
        ex.onReceipt(sales.nodeId, "not json")
        // Unverified countersignatures are never stored: no sig, garbage sig, wrong key, `node` != sender.
        ex.onReceipt(sales.nodeId, MeshJson.encode(ReceiptBody("job-3", sales.nodeId, "h", "p", clock, null)))
        ex.onReceipt(sales.nodeId, MeshJson.encode(ReceiptBody("job-3", sales.nodeId, "h", "p", clock, "s")))
        ex.onReceipt(sales.nodeId, MeshJson.encode(ReceiptBody("job-3", sales.nodeId, "h", "p", clock, signed(FakeSigner(), "h"))))
        ex.onReceipt(sales.nodeId, MeshJson.encode(ReceiptBody("job-3", hr.nodeId, "h", "p", clock, signed(sales.signer, "h"))))
        ex.onReceipt("stranger", MeshJson.encode(ReceiptBody("job-3", "stranger", "h", "p", clock, signed(sales.signer, "h"))))
        val e = ex.afterJobAsker("job-3", listOf(sales.nodeId), "{}")
        assertNull(fake.entries().single().peerEntryHash)
        assertEquals(e.entryHash, fake.entries().single().entryHash)
        // The real thing is stored.
        ex.onReceipt(sales.nodeId, MeshJson.encode(ReceiptBody("job-3", sales.nodeId, "h", "p", clock, signed(sales.signer, "h"))))
        assertEquals("h", fake.entries().single().peerEntryHash)
        advanceUntilIdle()
        // sales received a RECEIPT for a job it never answered: held, nothing written.
        assertNull(sales.ledger.entries().firstOrNull())
    }

    @Test fun anAskersOwnAnswerEntryDoesNotTakeThePeersReceipt() = runTest {
        val (hr, sales) = mesh("hr" to "HR", "sales" to "Sales")
        val jobId = "job-self"
        // Measured order on the phones: HR's own node answers its job first, then Sales' receipt lands, then HR's ask.
        val own = hr.exchange.afterJobNode(jobId, hr.nodeId, """{"requester":"HR","rows":61}""")
        sales.exchange.afterJobNode(jobId, hr.nodeId, """{"requester":"HR","rows":1204}""")
        advanceUntilIdle()
        val ask = hr.exchange.afterJobAsker(jobId, listOf(hr.nodeId, sales.nodeId), """{"q":"Q1","phones":2}""")
        advanceUntilIdle()
        val entries = hr.ledger.entries().associateBy { it.kind }
        assertEquals(sales.nodeId, entries.getValue("ask").peerNode)
        assertNull(entries.getValue("answer").peerNode)
        assertEquals(own.seq, entries.getValue("answer").seq); assertEquals(ask.seq, entries.getValue("ask").seq)
        // Sales' answer entry carries HR's ask receipt.
        assertEquals(hr.nodeId, sales.ledger.entries().single().peerNode)
        assertTrue(hr.ledger.verifyChain())
    }

    /** §13.6 for a split job: the orchestrator's `plan` entry and every executor's `task` entries countersign each other. */
    @Test fun splitJobReceiptsGoToEveryPhoneThatExecutedWork() = runTest {
        val (hr, sales, accounts) = mesh("hr" to "HR", "sales" to "Sales", "accounts" to "Accounts")
        val jobId = "split-1"
        fun task(chunk: String, index: Int, node: String) = TaskEntry(jobId, "$jobId#1", chunk, index,
            executors = listOf(Executor(node, 1, "ok", "h$index")), outputHash = "h$index", source = "node:$node", verifiedBy = listOf(hr.nodeId))
        // Sales ran two chunks and sends one receipt (over its newest entry); Accounts ran one. Both land before the plan.
        val sE = sales.exchange.afterSplitExecutor(jobId, hr.nodeId, listOf(task("c0", 0, sales.nodeId), task("c1", 1, sales.nodeId)))
        val aE = accounts.exchange.afterSplitExecutor(jobId, hr.nodeId, SplitLedger.json.encodeToString(TaskEntry.serializer(), task("c2", 2, accounts.nodeId)))
        advanceUntilIdle()
        assertEquals(listOf("task", "task"), sE.map { it.kind })
        assertEquals(sE[0].entryHash, sE[1].prevHash)
        assertNull(hr.ledger.entries().firstOrNull())                           // held until the plan entry exists
        val plan = PlanEntry(jobId, "$jobId#1", "ph", "recipe", "local", "public", "done", emptyMap(), chunks = 3,
            chunkOrder = listOf(ChunkRef(0, "c0", "h0"), ChunkRef(1, "c1", "h1"), ChunkRef(2, "c2", "h2")), resultHash = "r",
            executors = listOf(sales.nodeId, accounts.nodeId), createdTs = clock, finishedTs = clock)
        val hE = hr.exchange.afterSplitOrchestrator(jobId, listOf(sales.nodeId, accounts.nodeId, hr.nodeId), SplitLedger.json.encodeToString(PlanEntry.serializer(), plan))
        advanceUntilIdle()
        assertEquals("plan", hE.kind)
        // Each executor's newest task entry carries the orchestrator's plan hash and signature.
        for ((n, e) in listOf(sales to sE.last(), accounts to aE)) {
            val stored = n.ledger.entriesFor(jobId).last()
            assertEquals(e.entryHash, stored.entryHash)
            assertEquals(hr.nodeId, stored.peerNode); assertEquals(hE.entryHash, stored.peerEntryHash)
            assertTrue(n.ledger.verifyPeerSig(stored, hr.signer.publicKeyB64))
            assertTrue(n.ledger.verifyChain())
        }
        assertNull(sales.ledger.entriesFor(jobId).first().peerNode)              // the older task entry is covered by the chain
        // The orchestrator holds both executors' receipts: one on the plan entry, the second as a receipt entry.
        val byPeer = hr.ledger.entriesFor(jobId).associateBy { it.peerNode }
        assertEquals(sE.last().entryHash, byPeer[sales.nodeId]!!.peerEntryHash)
        assertEquals(aE.entryHash, byPeer[accounts.nodeId]!!.peerEntryHash)
        assertTrue(hr.ledger.verifyChain())
        assertTrue(hr.ledger.verifyPeerSig(byPeer[sales.nodeId]!!, sales.signer.publicKeyB64))
        assertTrue(hr.ledger.verifyPeerSig(byPeer[accounts.nodeId]!!, accounts.signer.publicKeyB64))
        assertEquals(emptyList<LedgerEntry>(), sales.exchange.afterSplitExecutor(jobId, hr.nodeId, emptyList()))
    }
}
