package com.hivecore.core.fakes

import com.hivecore.core.Hashing
import com.hivecore.core.LedgerEntry
import com.hivecore.core.LedgerStore
import com.hivecore.core.Signer
import java.util.Base64

/** In-memory hash chain with the ledger's rules (Ledger.hashOf: prevHash, jobId, kind, ts, entry), genesis prevHash = 64 zeros. */
class FakeLedgerStore(private val signer: Signer? = null, private val now: () -> Long = { System.currentTimeMillis() }) : LedgerStore {
    private val list = ArrayList<LedgerEntry>()
    private var seq = 0L

    @Synchronized
    override fun append(jobId: String, kind: String, entryJson: String): LedgerEntry {
        val prev = list.lastOrNull()?.entryHash ?: GENESIS
        val ts = now()
        val hash = hashOf(prev, jobId, kind, ts, entryJson)
        val sig = signer?.let { Base64.getEncoder().encodeToString(it.sign(hash.toByteArray())) } ?: "fake"
        val e = LedgerEntry(++seq, jobId, kind, entryJson, hash, prev, sig, ts = ts)
        list.add(e)
        return e
    }

    @Synchronized
    override fun countersign(jobId: String, peerNode: String, peerEntryHash: String, peerSig: String) {
        val i = list.indexOfLast { it.jobId == jobId }
        if (i >= 0) list[i] = list[i].copy(peerNode = peerNode, peerEntryHash = peerEntryHash, peerSig = peerSig)
    }

    @Synchronized
    override fun entries(limit: Int): List<LedgerEntry> = list.takeLast(limit).reversed()

    @Synchronized
    override fun verifyChain(): Boolean {
        var prev = GENESIS
        for (e in list) {
            if (e.prevHash != prev || e.entryHash != hashOf(e.prevHash, e.jobId, e.kind, e.ts, e.entry)) return false
            prev = e.entryHash
        }
        return true
    }

    @Synchronized
    override fun retain(days: Int) { val cutoff = now() - days * 86_400_000L; list.removeAll { it.ts < cutoff } }

    override fun markReset() { append("reset", "reset", "{\"reset\":true}") }

    /** Test helper: corrupt an entry to make [verifyChain] fail. */
    @Synchronized
    fun tamper(seq: Long) { val i = list.indexOfFirst { it.seq == seq }; if (i >= 0) list[i] = list[i].copy(entry = list[i].entry + " ") }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
        /** Same formula as `ledger.Ledger.hashOf` (kept here so core does not depend on the ledger package). */
        fun hashOf(prevHash: String, jobId: String, kind: String, ts: Long, entry: String): String =
            Hashing.sha256Hex("$prevHash\n$jobId\n$kind\n$ts\n$entry")
    }
}
