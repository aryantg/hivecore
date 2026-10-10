package com.hivecore.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a feed line is about; the UI picks an icon per kind. */
enum class FeedKind { PEER, PAIR, ROSTER, MODEL, JOB, CHUNK, MERGE, PLAN, APPROVAL, CLOUD, WARN, DEMO }

data class FeedEvent(val ts: Long, val kind: FeedKind, val text: String, val detail: String? = null)

/**
 * The live activity feed: a short, newest-first timeline of what the swarm is doing ("Sales online via LAN",
 * "Chunk 7 done on Sales in 3.1 s", "Merged 24 chunks, 2.6× faster"), for narrating a demo. In memory only.
 */
class ActivityFeed(private val capacity: Int = 200, private val clock: () -> Long = System::currentTimeMillis) {
    private val _events = MutableStateFlow<List<FeedEvent>>(emptyList())
    /** Newest first, at most [capacity]. */
    val events: StateFlow<List<FeedEvent>> = _events.asStateFlow()
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    fun post(kind: FeedKind, text: String, detail: String? = null) {
        val e = FeedEvent(clock(), kind, text, detail)
        _events.update { (listOf(e) + it).take(capacity) }
        _unread.update { it + 1 }
    }

    fun markRead() { _unread.value = 0 }
    fun clear() { _events.value = emptyList(); _unread.value = 0 }

    /**
     * Posts every warning and error [SLog] writes under one of [tags] as a [FeedKind.WARN] line, so problems the
     * engine only logs still reach the feed. Debug lines are not parsed: explicit posts carry the demo wording.
     */
    fun attachWarnings(tags: Set<String> = DEFAULT_WARN_TAGS) {
        SLog.sink = { level, tag, msg -> if ((level == "W" || level == "E") && tag in tags) post(FeedKind.WARN, msg.lineSequence().first().take(MAX_LINE), tag) }
    }

    companion object {
        val DEFAULT_WARN_TAGS = setOf("Wiring", "Router", "Pairing", "SwarmNode", "SplitCommander", "Commander", "ApprovalStore", "Main")
        private const val MAX_LINE = 160
    }
}

/** The process-wide feed, so engine-side code can post without a reference being threaded through. */
object Feed {
    @Volatile var current: ActivityFeed? = null
    fun post(kind: FeedKind, text: String, detail: String? = null) { current?.post(kind, text, detail) }
}
