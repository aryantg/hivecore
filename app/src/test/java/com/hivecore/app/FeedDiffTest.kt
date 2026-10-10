package com.hivecore.app

import com.hivecore.app.demo.FeedDiff
import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.FeedKind
import com.hivecore.core.JobProgress
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.NodeLoad
import com.hivecore.core.Profile
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.RosterView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The activity feed's wording: what a presenter reads aloud while the swarm works. */
class FeedDiffTest {
    private fun job(states: List<ChunkState>, done: Boolean = false) = JobProgress("j", "Photo text (OCR)", "shard", "concat",
        states.mapIndexed { i, s -> ChunkView(i, "c$i", s, if (s == ChunkState.QUEUED) null else "Sales", if (s == ChunkState.DONE) 3_100 else null, "invoices ${i * 5 + 1}–${i * 5 + 5}") },
        listOf(NodeLoad("s", "Sales", 1, 100, 1.0), NodeLoad("a", "Accounts", 1, 100, 1.0)),
        done = done, speedup = if (done) 2.6 else null, resultRef = if (done) "split:9f2c41ab77e0d3c5" else null)

    @Test fun aJobNarratesItself() {
        val q = ChunkState.QUEUED; val r = ChunkState.RUNNING; val d = ChunkState.DONE
        assertEquals(listOf("Started Photo text (OCR): 2 chunks"), FeedDiff.job(null, job(listOf(q, q))).map { it.text })
        assertEquals(listOf("Chunk 1 → Sales"), FeedDiff.job(job(listOf(q, q)), job(listOf(r, q))).map { it.text })
        assertEquals(listOf("Chunk 1 done on Sales in 3.1 s"), FeedDiff.job(job(listOf(r, q)), job(listOf(d, q))).map { it.text })
        val merged = FeedDiff.job(job(listOf(d, r)), job(listOf(d, d), done = true))
        assertEquals(FeedKind.MERGE, merged.last().kind)
        assertEquals("Merged 2 chunks from 2 phones · 2.6× faster", merged.last().text)
        assertEquals("hash 9f2c41ab", merged.last().detail)
        assertTrue("an unchanged snapshot says nothing", FeedDiff.job(job(listOf(d, d), true), job(listOf(d, d), true)).isEmpty())
    }

    @Test fun stragglersAndRequeuesAreExplained() {
        val lines = FeedDiff.job(job(listOf(ChunkState.RUNNING, ChunkState.RUNNING)), job(listOf(ChunkState.DUPLICATED, ChunkState.QUEUED))).map { it.text }
        assertEquals(listOf("Chunk 1 is slow; running a second copy on Sales", "Chunk 2 back in the queue (its phone left)"), lines)
    }

    @Test fun peersComeAndGo() {
        val roster = RosterView("g", 2, "self", listOf(MemberView("self", "This phone", true), MemberView("sales", "Sales")))
        val now = 100_000L
        val sales = Profile("sales", now - 1_000, link = Link.defaultFor(LinkKind.LAN))
        val (online, lines) = FeedDiff.peers(emptyMap(), listOf(sales), roster, now)
        assertEquals(listOf("Sales online via LAN"), lines.map { it.text })
        val (_, gone) = FeedDiff.peers(online, listOf(sales.copy(ts = now - 60_000)), roster, now)
        assertEquals(listOf("Sales went offline"), gone.map { it.text })
    }
}
