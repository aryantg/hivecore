package com.hivecore.engine.split

import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.JobProgress
import com.hivecore.core.NodeLoad

/**
 * The splitter's internal progress ([SplitProgress]) as the core/Jobs.kt view the Jobs screen renders (spec section
 * 17): axis and merge as lower-case strings, human chunk labels, phone display names, link kind per phone, the
 * duplicated / re-cut / verified / cloud badges per chunk, the partial reason, the resumable flag, speed-up only once done.
 */
object JobViews {
    fun of(p: SplitProgress, title: String, nameOf: (String) -> String): JobProgress {
        val elapsedS = maxOf(1L, p.elapsedMs) / 1000.0
        return JobProgress(
            jobId = p.jobId,
            title = title,
            axis = p.axis.lowercase(),
            merge = p.kind.lowercase(),
            chunks = p.chunks.map { c ->
                ChunkView(c.index, c.chunkId, when (c.status) {
                    ChunkStatus.QUEUED -> ChunkState.QUEUED
                    ChunkStatus.RUNNING -> ChunkState.RUNNING
                    ChunkStatus.DUPLICATED -> ChunkState.DUPLICATED
                    ChunkStatus.DONE -> ChunkState.DONE
                    ChunkStatus.FAILED -> ChunkState.FAILED
                }, c.nodes.firstOrNull()?.let { n -> if (n == "memo" || n == "recut" || n.startsWith("cloud:")) n else nameOf(n) },
                    c.ms.takeIf { c.status == ChunkStatus.DONE }, c.label,
                    duplicated = c.duplicated, recut = c.recut || c.recutOf != null, recutOf = c.recutOf, verified = c.verified, cloud = c.cloud)
            },
            nodes = p.nodes.map { n -> NodeLoad(n.node, nameOf(n.node), n.chunks, n.busyMs, Math.round(n.units / elapsedS * 100.0) / 100.0, link = n.link) },
            bytesMoved = p.bytesMoved,
            elapsedMs = p.elapsedMs,
            singlePhoneMs = p.singlePhoneMs.toLong().takeIf { it > 0 },
            speedup = if (p.done && p.elapsedMs > 0) Math.round(p.singlePhoneMs / p.elapsedMs * 100.0) / 100.0 else null,
            identicalToSingle = null,
            done = p.done,
            resultSummary = if (p.done) summary(p, nameOf) else null,
            resultRef = p.resultHash?.let { "split:${it.take(16)}" },
            error = p.error,
            partial = p.partial,
            partialReason = p.partialReason,
            resumable = p.resumable,
            cloud = p.cloud,
            unverified = p.unverified,
        )
    }

    private fun summary(p: SplitProgress, nameOf: (String) -> String): String {
        val phones = p.nodes.filter { it.chunks > 0 }
        val units = p.chunks.filter { it.recutOf == null }.sumOf { it.units }
        val originals = p.chunks.count { it.recutOf == null }
        return "$originals chunks ($units units) on ${phones.size} phone${if (phones.size == 1) "" else "s"} → ${p.kind.lowercase()} merge" +
            (if (p.duplicates > 0) ", ${p.duplicates} duplicated" else "") +
            (if (p.recuts > 0) ", ${p.recuts} re-cut" else "") +
            (if (p.reassigned > 0) ", ${p.reassigned} reassigned" else "") +
            (if (p.mismatches > 0) ", ${p.mismatches} replica mismatches" else "") +
            (if (p.unverified > 0) ", ${p.unverified} not verified" else "") +
            (if (p.cloud.isNotEmpty()) ", cloud: ${p.cloud.joinToString()}" else "") +
            (if (p.partial) ", partial: ${p.partialReason}" else "") +
            ", result ${p.resultHash?.take(8) ?: "none"}"
    }
}
