package com.hivecore.app.demo

import com.hivecore.app.HiveCoreGraph
import com.hivecore.core.ActivityFeed
import com.hivecore.core.ChunkState
import com.hivecore.core.FeedKind
import com.hivecore.core.JobProgress
import com.hivecore.core.Profile
import com.hivecore.ui.Format
import com.hivecore.ui.ports.RosterView
import com.hivecore.ui.vm.ChatTurn
import com.hivecore.ui.vm.SwarmViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** One feed line before it is posted. */
data class FeedLine(val kind: FeedKind, val text: String, val detail: String? = null)

/**
 * The pure diffs behind the feed (JVM-tested): what changed between two snapshots of a job, of the peers, of the
 * conversation. The engine stays untouched; the feed reads the same flows the screens do.
 */
object FeedDiff {
    /** Job lines: started, chunk to a phone, chunk done / duplicated / failed, merged, stopped. */
    fun job(prev: JobProgress?, next: JobProgress): List<FeedLine> = buildList {
        if (prev == null) add(FeedLine(FeedKind.JOB, "Started ${next.title}" + if (next.chunks.isNotEmpty()) ": ${Format.plural(next.chunks.size, "chunk")}" else ""))
        else if (prev.chunks.isEmpty() && next.chunks.isNotEmpty()) add(FeedLine(FeedKind.JOB, "Split ${next.title} into ${Format.plural(next.chunks.size, "chunk")}"))
        val before = prev?.chunks?.associateBy { it.index }.orEmpty()
        for (c in next.chunks) {
            val was = before[c.index]?.state
            if (was == c.state) continue
            val n = c.index + 1
            val where = c.node?.let { if (it.startsWith("cloud:")) "the cloud" else it } ?: "?"
            when (c.state) {
                ChunkState.RUNNING -> if (was == null || was == ChunkState.QUEUED) add(FeedLine(FeedKind.CHUNK, "Chunk $n → $where", c.label.ifBlank { null }))
                ChunkState.DONE -> add(FeedLine(FeedKind.CHUNK, "Chunk $n done on $where" + (c.ms?.let { " in ${Format.duration(it)}" } ?: ""), c.label.ifBlank { null }))
                ChunkState.DUPLICATED -> add(FeedLine(FeedKind.CHUNK, "Chunk $n is slow; running a second copy on $where", c.label.ifBlank { null }))
                ChunkState.FAILED -> add(FeedLine(FeedKind.WARN, "Chunk $n failed on $where", c.label.ifBlank { null }))
                ChunkState.QUEUED -> if (was != null) add(FeedLine(FeedKind.CHUNK, "Chunk $n back in the queue (its phone left)"))
            }
        }
        if (next.done && prev?.done != true) {
            val speed = next.speedup?.takeIf { it > 0 }?.let { String.format(Locale.US, " · %.1f× faster", it) } ?: ""
            add(FeedLine(FeedKind.MERGE, "Merged ${Format.plural(next.chunks.size, "chunk")} from ${Format.plural(next.nodes.size, "phone")}$speed",
                next.resultRef?.removePrefix("split:")?.take(8)?.let { "hash $it" }))
        }
        if (next.error != null && prev?.error == null) add(FeedLine(FeedKind.WARN, "${next.title} stopped: ${next.error}"))
    }

    /** Peer lines: who came online (and how), who went quiet. */
    fun peers(prevOnline: Map<String, String>, peers: List<Profile>, roster: RosterView, now: Long): Pair<Map<String, String>, List<FeedLine>> {
        val online = peers.filter { SwarmViewModel.isOnline(it, now) }.associate { it.nodeId to Format.linkWord(it.link?.kind) }
        val lines = buildList {
            for ((id, link) in online) if (id !in prevOnline) add(FeedLine(FeedKind.PEER, "${roster.nameOf(id)} online via $link"))
            for (id in prevOnline.keys) if (id !in online) add(FeedLine(FeedKind.PEER, "${roster.nameOf(id)} went offline"))
        }
        return online to lines
    }

    /** Conversation lines: planned, running, answered, refused. */
    fun chat(prev: ChatTurn?, next: ChatTurn?): List<FeedLine> {
        if (next == null || next.key == prev?.key && next::class == prev::class && (next as? ChatTurn.Plan)?.running?.first == (prev as? ChatTurn.Plan)?.running?.first) return emptyList()
        return when (next) {
            is ChatTurn.Plan -> { val run = next.running; if (run == null) listOf(FeedLine(FeedKind.PLAN, "Planned ${Format.plural(next.options.plan.steps.size, "step")} (${next.options.plan.tier})",
                next.options.plan.steps.joinToString(" → ") { "${it.op}@${it.phone}" }))
                else listOf(FeedLine(if (run.first.name == "CLOUD") FeedKind.CLOUD else FeedKind.PLAN, "Running ${run.first.name.lowercase()}")) }
            is ChatTurn.Answer -> listOf(FeedLine(FeedKind.PLAN, "Answered (${next.answer.badge.name.lowercase()})", "plan ${next.answer.planHash.take(8)}"))
            is ChatTurn.Refused -> listOf(FeedLine(FeedKind.WARN, "Refused: ${next.refusal.sentence}"))
            else -> emptyList()
        }
    }
}

/** Starts the feed's listeners over [graph]: swarm, pairing, roster, warnings, jobs, approvals, models, consent, chat. */
object FeedBridges {
    fun start(graph: HiveCoreGraph, feed: ActivityFeed, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis) {
        fun post(l: FeedLine) = feed.post(l.kind, l.text, l.detail)
        val swarm = graph.swarmPort

        scope.launch {
            var online = emptyMap<String, String>()
            var first = true
            while (true) {
                val (next, lines) = FeedDiff.peers(online, swarm.peers.value, swarm.roster.value, clock())
                if (!first) lines.forEach(::post)
                online = next; first = false
                delay(PEER_POLL_MS)
            }
        }
        scope.launch {
            var version = -1L
            swarm.roster.collect { r -> if (version >= 0 && r.version > version) feed.post(FeedKind.ROSTER, "Roster v${r.version} · ${Format.plural(r.members.size, "member")} · admin ${r.nameOf(r.adminId)}"); version = r.version }
        }
        scope.launch {
            val seen = HashSet<String>()
            swarm.sasPrompts.collect { list -> list.filter { seen.add(it.handshakeId) }.forEach { feed.post(FeedKind.PAIR, "Pairing with ${it.peerName}: compare ${it.digits}") } }
        }
        scope.launch {
            var last: String? = null
            swarm.warnings.collect { w -> w.firstOrNull()?.takeIf { it != last }?.let { feed.post(FeedKind.WARN, it); last = it } }
        }
        scope.launch {
            val prev = HashMap<String, JobProgress>()
            graph.jobRunner.jobs.collect { jobs ->
                for (j in jobs.asReversed()) { FeedDiff.job(prev[j.jobId], j).forEach(::post); prev[j.jobId] = j }
            }
        }
        graph.ports.approval?.let { store ->
            scope.launch {
                var ids = emptySet<String>()
                store.pending.collect { list ->
                    list.filter { it.jobId !in ids }.forEach { feed.post(FeedKind.APPROVAL, "${it.requester} asks for figures from this phone", "${(it.deadlineAt - clock()).coerceAtLeast(0) / 1000} s to answer") }
                    (ids - list.map { it.jobId }.toSet()).forEach { _ -> feed.post(FeedKind.APPROVAL, "Approval request answered") }
                    ids = list.map { it.jobId }.toSet()
                }
            }
        }
        scope.launch {
            var big: String? = null
            graph.models.state.collect { m -> if (m.big != big) { if (big != null && m.big != "none") feed.post(FeedKind.MODEL, "${m.big} loaded in the big slot"); big = m.big } }
        }
        graph.consent?.let { c ->
            scope.launch { c.request.collect { r -> if (r != null) feed.post(FeedKind.CLOUD, "Asking before ${r.slug} runs step ${r.stepId}", "about ${com.hivecore.cloud.SpendCaps.inr(r.estimateInr)}") } }
        }
        scope.launch {
            var last: ChatTurn? = null
            graph.ask.state.collect { s -> val t = s.thread.lastOrNull(); FeedDiff.chat(last, t).forEach(::post); last = t }
        }
    }

    private const val PEER_POLL_MS = 4_000L
}
