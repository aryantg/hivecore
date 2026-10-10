package com.hivecore.engine

import com.hivecore.core.Hashing
import com.hivecore.core.SLog
import com.hivecore.mesh.BlobBody
import com.hivecore.mesh.BlobDeletedBody
import java.io.File
import java.util.Base64

/**
 * Content-addressed blob files for released items (§13.5, P2): `<dir>/<sha256>` where `dir` is `files/blobs`.
 * [chunks] splits a blob into BLOB bodies of at most [chunkBytes] raw bytes (≤ 900 KB, [MAX_CHUNK]); [onChunk]
 * reassembles them in memory and keeps the file only when the whole content hashes to `blobId`, so a corrupted or
 * forged piece loses the blob instead of storing wrong bytes ([rejections] counts those per blob). After the
 * TASK_RESULT that used a blob is countersigned, [deleteAfterCountersign] removes the files and returns the
 * BLOB_DELETED body for the owner. The sender-side rule of §14 (re-sent once, then the phone is skipped) is
 * [BlobDeliveries].
 *
 * Note: the router's envelope limit (MeshRouter.MAX_ENVELOPE, 64 KB) and Nearby's 32 KB bytes payloads bound what
 * travels today, so SwarmNode uses a smaller chunk size than the 900 KB the plan allows.
 */
class BlobStore(
    private val dir: File,
    val chunkBytes: Int = MAX_CHUNK,
    private val maxBlobBytes: Long = 64L * 1024 * 1024,
) {
    init { require(chunkBytes in 1..MAX_CHUNK) { "chunkBytes must be 1..$MAX_CHUNK" } }

    private class Partial(val total: Int, val owner: String, val jobId: String) {
        val parts = HashMap<Int, ByteArray>()
        var bytes = 0L
    }

    private val lock = Any()
    private val partial = HashMap<String, Partial>()
    private val rejected = HashMap<String, Int>()

    /** How many received copies of [blobId] were dropped because a piece was corrupted or forged (§13.5). */
    fun rejections(blobId: String): Int = synchronized(lock) { rejected[blobId] ?: 0 }

    /** Every blob this phone has dropped at least once, with the count (the node reports `blobs_missing` to the owner). */
    fun rejections(): Map<String, Int> = synchronized(lock) { rejected.toMap() }

    private fun reject(blobId: String) { synchronized(lock) { rejected[blobId] = (rejected[blobId] ?: 0) + 1 } }

    private fun ensureDir() { if (!dir.exists()) dir.mkdirs() }

    /** Stores [bytes] under their sha256 and returns the blobId (idempotent). */
    fun put(bytes: ByteArray): String {
        val id = Hashing.sha256Hex(bytes)
        ensureDir()
        val f = File(dir, id)
        if (!f.exists() || f.length() != bytes.size.toLong()) writeAtomically(f, bytes)
        return id
    }

    fun put(file: File): String = put(file.readBytes())

    /** The stored file of [blobId], or null. */
    fun file(blobId: String): File? = if (!validId(blobId)) null else File(dir, blobId).takeIf { it.isFile }

    fun has(blobId: String): Boolean = file(blobId) != null

    /** The BLOB bodies for [blobId], in order; empty when the blob is not stored. */
    fun chunks(blobId: String, owner: String, jobId: String, chunkBytes: Int = this.chunkBytes): List<BlobBody> {
        require(chunkBytes in 1..MAX_CHUNK) { "chunkBytes must be 1..$MAX_CHUNK" }
        val bytes = file(blobId)?.readBytes() ?: return emptyList()
        val total = maxOf(1, (bytes.size + chunkBytes - 1) / chunkBytes)
        val enc = Base64.getEncoder()
        return (0 until total).map { i ->
            val from = i * chunkBytes
            val to = minOf(bytes.size, from + chunkBytes)
            BlobBody(blobId, i, total, enc.encodeToString(bytes.copyOfRange(from, to)), owner, jobId)
        }
    }

    /**
     * One received chunk. Returns success(blobId) when this chunk completed a blob whose content verifies,
     * success(null) while chunks are still missing, and failure when the chunk is malformed or the reassembled
     * content does not hash to blobId (the partial blob is then discarded).
     */
    fun onChunk(b: BlobBody): Result<String?> {
        if (!validId(b.blobId)) return Result.failure(IllegalArgumentException("blobId is not a sha256"))
        if (b.total < 1 || b.seq !in 0 until b.total) return Result.failure(IllegalArgumentException("seq ${b.seq} of ${b.total}"))
        if (has(b.blobId)) return Result.success(b.blobId)
        val raw = runCatching { Base64.getDecoder().decode(b.bytesB64) }.getOrElse {
            return Result.failure(IllegalArgumentException("chunk is not base64"))
        }
        if (raw.size > MAX_CHUNK) return Result.failure(IllegalArgumentException("chunk of ${raw.size} B exceeds $MAX_CHUNK B"))
        val complete: Partial = synchronized(lock) {
            val p = partial.getOrPut(b.blobId) { Partial(b.total, b.owner, b.jobId) }
            if (p.total != b.total || p.owner != b.owner) {
                partial.remove(b.blobId)
                rejected[b.blobId] = (rejected[b.blobId] ?: 0) + 1
                return Result.failure(IllegalArgumentException("chunk disagrees with earlier chunks of ${b.blobId.take(8)}"))
            }
            val prev = p.parts.put(b.seq, raw)
            p.bytes += raw.size - (prev?.size ?: 0)
            if (p.bytes > maxBlobBytes) {
                partial.remove(b.blobId)
                return Result.failure(IllegalArgumentException("blob ${b.blobId.take(8)} exceeds $maxBlobBytes B"))
            }
            if (p.parts.size < p.total) return Result.success(null)
            partial.remove(b.blobId)
            p
        }
        val out = java.io.ByteArrayOutputStream(complete.bytes.toInt())
        for (i in 0 until complete.total) out.write(complete.parts.getValue(i))
        val bytes = out.toByteArray()
        val hash = Hashing.sha256Hex(bytes)
        if (hash != b.blobId) {
            reject(b.blobId)
            SLog.w(TAG, "blob ${b.blobId.take(8)} from ${b.owner.take(8)} rejected: content hashes to ${hash.take(8)}")
            return Result.failure(IllegalStateException("blob content does not match its id"))
        }
        ensureDir()
        writeAtomically(File(dir, b.blobId), bytes)
        return Result.success(b.blobId)
    }

    /** Chunks received so far for an incomplete blob (0 when none). */
    fun pendingChunks(blobId: String): Int = synchronized(lock) { partial[blobId]?.parts?.size ?: 0 }

    /** Deletes [blobIds] (and any partial state) and returns the BLOB_DELETED body naming the ones that were held. */
    fun deleteAfterCountersign(jobId: String, node: String, blobIds: List<String>): BlobDeletedBody {
        val deleted = blobIds.filter { id ->
            synchronized(lock) { partial.remove(id) }
            val f = file(id)
            f != null && f.delete()
        }
        return BlobDeletedBody(jobId, node, deleted)
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() } }
    }

    private fun validId(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }

    companion object {
        private const val TAG = "BlobStore"
        /** The plan's per-frame limit (§8.1). */
        const val MAX_CHUNK = 900 * 1024
    }
}

/**
 * Sender-side delivery counters for §14 "blob corrupted in transit: blob dropped, re-sent once, then that phone is
 * skipped for chunks needing it". The orchestrator's queue asks [canSend] before offering a blob-carrying chunk to a
 * phone (and [skips] for a whole chunk), records [sent] after the pieces went out, [delivered] when the phone
 * acknowledged the blob (its first TASK_RESULT over it, or BLOB_DELETED later), and [failed] when the phone reports
 * the blob missing or rejected (TASK_RESULT error class `blobs_missing`). After [maxFailures] failed deliveries of
 * one blob to one phone, [canSend] is false for that pair: the second send was the one re-send the rule allows.
 */
class BlobDeliveries(val maxFailures: Int = MAX_FAILURES) {
    private class Counters(var sends: Int = 0, var failures: Int = 0, var delivered: Boolean = false)

    private val lock = Any()
    private val pairs = HashMap<String, Counters>()
    private fun key(node: String, blobId: String) = "$node|$blobId"
    private fun at(node: String, blobId: String) = pairs.getOrPut(key(node, blobId)) { Counters() }

    fun sends(node: String, blobId: String): Int = synchronized(lock) { pairs[key(node, blobId)]?.sends ?: 0 }
    fun failures(node: String, blobId: String): Int = synchronized(lock) { pairs[key(node, blobId)]?.failures ?: 0 }
    /** The phone acknowledged [blobId]: no need to send it again before its next chunk. */
    fun holds(node: String, blobId: String): Boolean = synchronized(lock) { pairs[key(node, blobId)]?.delivered == true }

    fun sent(node: String, blobId: String) { synchronized(lock) { at(node, blobId).sends++ } }
    fun delivered(node: String, blobId: String) { synchronized(lock) { at(node, blobId).delivered = true } }

    /** One failed delivery; true when the blob may be re-sent to that phone (its one re-send), false when the phone is now skipped. */
    fun failed(node: String, blobId: String): Boolean = synchronized(lock) {
        val p = at(node, blobId); p.failures++; p.delivered = false
        p.failures < maxFailures
    }

    /** Whether [blobId] may still be sent to [node]. */
    fun canSend(node: String, blobId: String): Boolean = failures(node, blobId) < maxFailures

    /** Whether a chunk needing [blobIds] must not be offered to [node] (§14): one of its blobs is past its re-send. */
    fun skips(node: String, blobIds: Collection<String>): Boolean = blobIds.any { !canSend(node, it) }

    /** The blobs [node] is skipped for. */
    fun skipped(node: String): Set<String> = synchronized(lock) {
        pairs.filter { (k, p) -> k.startsWith("$node|") && p.failures >= maxFailures }.keys.map { it.substringAfter('|') }.toSet()
    }

    /** Forgets a phone (it left the job). */
    fun forget(node: String) { synchronized(lock) { pairs.keys.removeAll { it.startsWith("$node|") } } }

    companion object {
        /** §14: one re-send, then skip. */
        const val MAX_FAILURES = 2
    }
}
