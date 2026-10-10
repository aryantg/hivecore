package com.hivecore.ledger

import com.hivecore.core.Hashing
import com.hivecore.core.LedgerEntry
import com.hivecore.core.LedgerStore
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.core.SqlDb
import com.hivecore.core.Verify
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Plan 10 ledger: `_receipts(seq PK AUTOINCREMENT, jobId, kind, entry TEXT, entryHash, prevHash, sig, peerNode,
 * peerEntryHash, peerSig, ts)`. `entryHash = sha256Hex(prevHash \n jobId \n kind \n ts \n entry)` ([hashOf]: the
 * row's jobId, kind and ts are inside the hash, so none can be edited without breaking the chain), genesis
 * `prevHash = "0" * 64`, `sig` = node key over the entryHash bytes (base64 DER). Kinds: `ask`, `answer`, `close`,
 * `reset` (marker), and `receipt` (a second peer's countersignature for a job that already carries one, see
 * [countersign]).
 *
 * [verifyChain] checks every link (prevHash -> previous entryHash), every entryHash, every signature with this
 * node's key and every peer countersignature with that peer's pinned key ([peerKey]; a peer that has since been
 * forgotten cannot be checked and is logged). After [retain] the oldest surviving row keeps its original prevHash,
 * so linkage is checked between consecutive rows only; the first row's prevHash is accepted as-is.
 */
class Ledger(
    private val db: SqlDb,
    private val signer: Signer,
    private val now: () -> Long = { System.currentTimeMillis() }
) : LedgerStore {

    /** Pinned node key of a peer (set by [ReceiptExchange] from the router); null when the peer is not pinned. */
    @Volatile var peerKey: (String) -> String? = { null }

    init {
        db.exec(
            """CREATE TABLE IF NOT EXISTS _receipts(
                 seq INTEGER PRIMARY KEY AUTOINCREMENT, jobId TEXT NOT NULL, kind TEXT NOT NULL, entry TEXT NOT NULL,
                 entryHash TEXT NOT NULL, prevHash TEXT NOT NULL, sig TEXT NOT NULL,
                 peerNode TEXT, peerEntryHash TEXT, peerSig TEXT, ts INTEGER NOT NULL)"""
        )
        db.exec("CREATE INDEX IF NOT EXISTS _receipts_job ON _receipts(jobId)")
    }

    @Synchronized
    override fun append(jobId: String, kind: String, entryJson: String): LedgerEntry =
        appendRow(jobId, kind, entryJson, null, null, null)

    private fun appendRow(jobId: String, kind: String, entryJson: String,
                          peerNode: String?, peerEntryHash: String?, peerSig: String?): LedgerEntry = db.tx {
        val prev = db.query("SELECT entryHash FROM _receipts ORDER BY seq DESC LIMIT 1").firstOrNull()
            ?.get("entryHash") as String? ?: GENESIS
        val ts = now()
        val hash = hashOf(prev, jobId, kind, ts, entryJson)
        val sig = Base64.getEncoder().encodeToString(signer.sign(hash.toByteArray(Charsets.UTF_8)))
        db.exec(
            "INSERT INTO _receipts(jobId, kind, entry, entryHash, prevHash, sig, peerNode, peerEntryHash, peerSig, ts) VALUES (?,?,?,?,?,?,?,?,?,?)",
            listOf(jobId, kind, entryJson, hash, prev, sig, peerNode, peerEntryHash, peerSig, ts)
        )
        val seq = (db.query("SELECT last_insert_rowid() AS seq").first()["seq"] as Number).toLong()
        SLog.d(TAG, "appended #$seq $kind for $jobId hash=${hash.take(8)}")
        LedgerEntry(seq, jobId, kind, entryJson, hash, prev, sig, peerNode, peerEntryHash, peerSig, ts)
    }

    /**
     * Stores a peer's entry hash and signature on the local entry for [jobId]. The newest entry for the job
     * without a countersignature (or already countersigned by the same peer) is updated; if every entry for the
     * job already carries another peer's countersignature (an ask with several participants), a `receipt` entry
     * is appended so no peer's receipt is lost. Unknown jobs are logged and ignored.
     */
    @Synchronized
    override fun countersign(jobId: String, peerNode: String, peerEntryHash: String, peerSig: String) =
        countersign(jobId, null, peerNode, peerEntryHash, peerSig)

    /** [kind] non-null: only this node's entry of that kind is countersigned (the asker's `ask`, not its own `answer`). */
    @Synchronized
    override fun countersign(jobId: String, kind: String?, peerNode: String, peerEntryHash: String, peerSig: String) {
        db.tx {
            val target = db.query(
                "SELECT seq FROM _receipts WHERE jobId = ? AND kind != 'receipt'" + (if (kind != null) " AND kind = ?" else "") +
                    " AND (peerNode IS NULL OR peerNode = ?) ORDER BY seq DESC LIMIT 1",
                listOfNotNull(jobId, kind, peerNode)
            ).firstOrNull()
            if (target != null) {
                db.exec("UPDATE _receipts SET peerNode = ?, peerEntryHash = ?, peerSig = ? WHERE seq = ?",
                    listOf(peerNode, peerEntryHash, peerSig, target["seq"]))
                SLog.d(TAG, "countersigned $jobId by ${peerNode.take(8)}")
            } else if (hasJob(jobId)) {
                appendRow(jobId, "receipt", buildJsonObject { put("jobId", jobId); put("peer", peerNode) }.toString(), peerNode, peerEntryHash, peerSig)
            } else {
                SLog.w(TAG, "countersign for unknown job $jobId from ${peerNode.take(8)} ignored")
            }
        }
    }

    fun hasJob(jobId: String): Boolean =
        db.query("SELECT 1 FROM _receipts WHERE jobId = ? LIMIT 1", listOf(jobId)).isNotEmpty()

    /** Newest first. */
    override fun entries(limit: Int): List<LedgerEntry> =
        db.query("SELECT * FROM _receipts ORDER BY seq DESC LIMIT ?", listOf(limit)).map(::row)

    fun entriesFor(jobId: String): List<LedgerEntry> =
        db.query("SELECT * FROM _receipts WHERE jobId = ? ORDER BY seq", listOf(jobId)).map(::row)

    fun count(): Long = (db.query("SELECT COUNT(*) AS n FROM _receipts").first()["n"] as Number).toLong()

    @Synchronized
    override fun verifyChain(): Boolean {
        var prev: String? = null
        var ok = true
        for (r in db.query("SELECT * FROM _receipts ORDER BY seq")) {
            val e = row(r)
            if (prev != null && e.prevHash != prev) { SLog.w(TAG, "chain broken at #${e.seq}: prevHash mismatch"); ok = false }
            if (e.entryHash != hashOf(e.prevHash, e.jobId, e.kind, e.ts, e.entry)) { SLog.w(TAG, "chain broken at #${e.seq}: entryHash mismatch"); ok = false }
            val sig = runCatching { Base64.getDecoder().decode(e.sig) }.getOrNull()
            if (sig == null || !Verify.verify(signer.publicKeyB64, e.entryHash.toByteArray(Charsets.UTF_8), sig)) {
                SLog.w(TAG, "chain broken at #${e.seq}: signature does not verify"); ok = false
            }
            val peer = e.peerNode
            if (ok && peer != null) {
                val key = peerKey(peer)
                if (key == null) SLog.w(TAG, "#${e.seq}: countersignature by ${peer.take(8)} not checked (peer not pinned)")
                else if (!verifyPeerSig(e, key)) { SLog.w(TAG, "chain broken at #${e.seq}: countersignature by ${peer.take(8)} does not verify"); ok = false }
            }
            if (!ok) return false
            prev = e.entryHash
        }
        return true
    }

    /** Verifies a peer's countersignature on an entry with that peer's pinned node key. */
    fun verifyPeerSig(e: LedgerEntry, peerPubKeyB64: String): Boolean {
        val h = e.peerEntryHash ?: return false
        val sig = runCatching { Base64.getDecoder().decode(e.peerSig ?: return false) }.getOrNull() ?: return false
        return Verify.verify(peerPubKeyB64, h.toByteArray(Charsets.UTF_8), sig)
    }

    /** Deletes entries older than [days]; the newest entry is always kept so the chain head survives. */
    @Synchronized
    override fun retain(days: Int) {
        val cutoff = now() - days * 86_400_000L
        db.exec("DELETE FROM _receipts WHERE ts < ? AND seq < (SELECT MAX(seq) FROM _receipts)", listOf(cutoff))
        SLog.d(TAG, "retain($days): ${count()} entries remain")
    }

    /** Appends a `reset` marker (RESET_DEMO deletes everything after the last marker, then calls this). */
    override fun markReset() { append("reset", "reset", buildJsonObject { put("reset", true); put("ts", now()) }.toString()) }

    /** Seq of the last reset marker, or null. */
    fun lastResetSeq(): Long? =
        (db.query("SELECT MAX(seq) AS s FROM _receipts WHERE kind = 'reset'").first()["s"] as Number?)?.toLong()

    /** Deletes every entry after the last reset marker (RESET_DEMO), keeping the marker itself. */
    @Synchronized
    fun deleteAfterLastReset() {
        val s = lastResetSeq() ?: return
        db.exec("DELETE FROM _receipts WHERE seq > ?", listOf(s))
    }

    private fun row(r: Map<String, Any?>) = LedgerEntry(
        seq = (r["seq"] as Number).toLong(),
        jobId = r["jobId"] as String,
        kind = r["kind"] as String,
        entry = r["entry"] as String,
        entryHash = r["entryHash"] as String,
        prevHash = r["prevHash"] as String,
        sig = r["sig"] as String,
        peerNode = r["peerNode"] as String?,
        peerEntryHash = r["peerEntryHash"] as String?,
        peerSig = r["peerSig"] as String?,
        ts = (r["ts"] as Number).toLong()
    )

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        /** The chained hash of one row: every field but the signatures goes in, newline-separated. */
        fun hashOf(prevHash: String, jobId: String, kind: String, ts: Long, entry: String): String =
            Hashing.sha256Hex("$prevHash\n$jobId\n$kind\n$ts\n$entry")
        private const val TAG = "Ledger"
    }
}
