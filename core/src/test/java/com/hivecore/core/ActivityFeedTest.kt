package com.hivecore.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityFeedTest {
    @After fun detach() { SLog.sink = null }

    @Test fun newestFirstAndCapped() {
        var t = 0L
        val feed = ActivityFeed(capacity = 3) { ++t }
        listOf("a", "b", "c", "d").forEach { feed.post(FeedKind.JOB, it) }
        assertEquals(listOf("d", "c", "b"), feed.events.value.map { it.text })
        assertEquals(listOf(4L, 3L, 2L), feed.events.value.map { it.ts })
    }

    @Test fun unreadCountsUntilRead() {
        val feed = ActivityFeed()
        feed.post(FeedKind.PEER, "Sales online")
        feed.post(FeedKind.PEER, "Accounts online")
        assertEquals(2, feed.unread.value)
        feed.markRead()
        assertEquals(0, feed.unread.value)
        feed.clear()
        assertEquals(0, feed.events.value.size)
    }

    @Test fun warningsFromWatchedTagsOnly() {
        val feed = ActivityFeed()
        feed.attachWarnings(setOf("Router"))
        SLog.sink!!("W", "Router", "link to Sales lost\nstack")
        SLog.sink!!("D", "Router", "debug line")
        SLog.sink!!("E", "Other", "not watched")
        assertEquals(listOf("link to Sales lost"), feed.events.value.map { it.text })
        assertEquals(FeedKind.WARN, feed.events.value.single().kind)
    }
}
