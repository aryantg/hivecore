package com.hivecore.ledger

import com.hivecore.core.LedgerEntry
import com.hivecore.core.LedgerStore
import com.hivecore.core.SLog
import com.hivecore.core.Verify
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ReceiptBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Plan 10 receipt exchange. After a job the asker appends its `ask` entry and sends a RECEIPT to every
 * participant; every participant appends its `answer` entry and sends a RECEIPT to the asker. An inbound RECEIPT
 * countersigns the matching local entry (peerNode, peerEntryHash, peerSig). Because a participant's RECEIPT can
 * arrive before the asker has written its own entry, receipts for jobs this node has not recorded yet are held
 * (bounded) and applied when [afterJobAsker] / [afterJobNode] runs.
 *
 * A RECEIPT is stored only when its `sig` verifies over `entryHash` with the sender's pinned node key and its
 * `node` is the sender; anything else is dropped, so a countersignature on the ledger is always a real one. The
 * same pinned keys let [Ledger.verifyChain] re-check every countersignature later.
 */
class ReceiptExchange(
    private val router: MeshRouter,
    private val ledger: LedgerStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    /** "jobId" and "jobId|kind" for every entry this node wrote. */
    private val localJobs = LinkedHashSet<String>()
    private val held = LinkedHashMap<String, MutableList<Pair<String, ReceiptBody>>>()   // key(jobId, target kind) -> (from, body)
    private var job: Job? = null

    init { (ledger as? Ledger)?.peerKey = { router.pinnedKey(it) } }

    /** Starts listening for RECEIPTs on [MeshRouter.verified]. Call once, before the router starts. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            router.verified.collect { v -> if (v.type == MsgType.RECEIPT) onReceipt(v.from, v.body) }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Asker side: records the `ask` entry and sends its RECEIPT to each participant (excluding self). */
    suspend fun afterJobAsker(jobId: String, participants: List<String>, entryJson: String): LedgerEntry {
        val e = ledger.append(jobId, "ask", entryJson)
        remember(jobId, e.kind)
        val body = MeshJson.encode(receiptOf(e))
        for (p in participants) {
            if (p == router.nodeId) continue
            val r = router.send(p, MsgType.RECEIPT, body)
            if (r.isFailure) SLog.w(TAG, "RECEIPT for $jobId to ${p.take(8)} not sent: ${r.exceptionOrNull()?.message}")
        }
        applyHeld(jobId, e.kind)
        return e
    }

    /** Node side: records the `answer` entry and sends its RECEIPT to the asker. */
    suspend fun afterJobNode(jobId: String, asker: String, entryJson: String): LedgerEntry {
        val e = ledger.append(jobId, "answer", entryJson)
        remember(jobId, e.kind)
        if (asker != router.nodeId) {
            val r = router.send(asker, MsgType.RECEIPT, MeshJson.encode(receiptOf(e)))
            if (r.isFailure) SLog.w(TAG, "RECEIPT for $jobId to asker ${asker.take(8)} not sent: ${r.exceptionOrNull()?.message}")
        }
        applyHeld(jobId, e.kind)
        return e
    }

    /** Handles one verified inbound RECEIPT (public so tests and the ask agent can feed it directly). */
    fun onReceipt(from: String, bodyJson: String) {
        val b = try { MeshJson.decode<ReceiptBody>(bodyJson) } catch (e: Exception) {
            SLog.w(TAG, "RECEIPT from ${from.take(8)} does not parse: ${e.message}"); return
        }
        val why = rejection(from, b)
        if (why != null) { SLog.w(TAG, "RECEIPT for ${b.jobId} from ${from.take(8)} dropped: $why"); return }
        // Wave 3: on the asker, its own node writes an `answer` entry for the job before the `ask` entry; a peer's
        // answer receipt that arrived in between countersigned that self-answer and the `ask` stayed open.
        val target = targetKind(b.kind)
        val k = key(b.jobId, target)
        val known = synchronized(lock) { k in localJobs }
        if (known) {
            ledger.countersign(b.jobId, target, from, b.entryHash, b.sig ?: "")
            SLog.d(TAG, "stored receipt for ${b.jobId} from ${from.take(8)}")
        } else {
            synchronized(lock) {
                held.getOrPut(k) { ArrayList() }.add(from to b)
                while (held.size > MAX_HELD) held.remove(held.keys.first())
            }
            SLog.d(TAG, "holding receipt for ${b.jobId} from ${from.take(8)} until the local entry exists")
        }
    }

    /** Null when [b] is a countersignature by [from] that verifies with its pinned key; else the reason. */
    private fun rejection(from: String, b: ReceiptBody): String? {
        if (b.node != from) return "node != from"
        val key = router.pinnedKey(from) ?: return "sender is not pinned"
        val sig = b.sig?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return "no signature"
        if (!Verify.verify(key, b.entryHash.toByteArray(Charsets.UTF_8), sig)) return "signature does not verify"
        return null
    }

    private fun receiptOf(e: LedgerEntry) = ReceiptBody(e.jobId, router.nodeId, e.entryHash, e.prevHash, now(), e.sig, e.kind)

    /** The local entry kind a receipt of [senderKind] countersigns; null (any) for receipts from older builds. */
    private fun targetKind(senderKind: String?): String? = when (senderKind) { "answer" -> "ask"; "ask" -> "answer"; else -> null }

    private fun key(jobId: String, kind: String?) = if (kind == null) jobId else "$jobId|$kind"

    private fun remember(jobId: String, kind: String) = synchronized(lock) {
        localJobs.add(jobId); localJobs.add(key(jobId, kind))
        while (localJobs.size > MAX_JOBS * 2) localJobs.remove(localJobs.first())
    }

    private fun applyHeld(jobId: String, kind: String) {
        val list = synchronized(lock) { (held.remove(key(jobId, kind)) ?: emptyList()) + (held.remove(jobId) ?: emptyList()) }
        for ((from, b) in list) {
            ledger.countersign(jobId, targetKind(b.kind), from, b.entryHash, b.sig ?: "")
            SLog.d(TAG, "stored held receipt for $jobId from ${from.take(8)}")
        }
    }

    companion object {
        private const val TAG = "ReceiptExchange"
        private const val MAX_HELD = 100
        private const val MAX_JOBS = 1000
    }
}
