package com.hivecore.ui.vm

import com.hivecore.core.MemoryPrefs
import com.hivecore.core.WorkerActivityLog
import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.ui.settle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The points rule, which jobs count as settled, paying once, spending, and the balance surviving a restart. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComputeCreditsTest {
    private val t0 = 1_700_000_000_000L
    private val names = { id: String -> when (id) { "sales" -> "Sales"; "hr" -> "HR"; else -> id } }

    private fun ev(plan: String, kind: WorkerEventKind, ts: Long, commander: String = "sales", task: String? = null, ms: Long? = null) =
        WorkerEvent(plan, kind, "self", commander, ts, taskId = task, ms = ms, op = "EXTRACT")

    /** Two chunks for Sales: 12.3 s and 30 s of work (one with ms, one timed from CHUNK_RUNNING), then the result goes back. */
    private fun salesJob(start: Long) = listOf(
        ev("j1#1", WorkerEventKind.PLAN_RECEIVED, start),
        ev("j1#1", WorkerEventKind.CHUNK_RECEIVED, start + 100, task = "t1"),
        ev("j1#1", WorkerEventKind.CHUNK_RUNNING, start + 200, task = "t1"),
        ev("j1#1", WorkerEventKind.RESULT_SENT, start + 12_500, task = "t1", ms = 12_300),
        ev("j1#1", WorkerEventKind.CHUNK_RECEIVED, start + 13_000, task = "t2"),
        ev("j1#1", WorkerEventKind.CHUNK_RUNNING, start + 13_000, task = "t2"),
        ev("j1#1", WorkerEventKind.RESULT_SENT, start + 43_000, task = "t2"),
    )

    @Test fun pointsRule() {
        assertEquals(0, ComputeCredits.pointsFor(0, chunks = 0))
        assertEquals(1, ComputeCredits.pointsFor(0, chunks = 1))
        assertEquals(1, ComputeCredits.pointsFor(400))
        assertEquals(13, ComputeCredits.pointsFor(12_300))
        assertEquals(60, ComputeCredits.pointsFor(60_000))
    }

    @Test fun aJobSettlesAfterTheQuietWindowOrOnTheReceiptAndOwnJobsNeverPay() {
        val events = salesJob(t0) +
            // HR's job: the last chunk is still running
            listOf(ev("j2#1", WorkerEventKind.CHUNK_RECEIVED, t0 + 1_000, commander = "hr", task = "h1"), ev("j2#1", WorkerEventKind.CHUNK_RUNNING, t0 + 1_000, commander = "hr", task = "h1")) +
            // this phone's own job: never paid
            listOf(ev("j3#1", WorkerEventKind.RESULT_SENT, t0 + 1_000, commander = "self", task = "s1", ms = 5_000))
        // 1. too soon after the last result: nothing settles
        assertTrue(ComputeCredits.settled(events, "self", names, t0 + 43_000 + 1_000).isEmpty())
        // 2. after the quiet window Sales' job pays 42.3 s → 43 points over 2 chunks
        val paid = ComputeCredits.settled(events, "self", names, t0 + 43_000 + ComputeCredits.SETTLE_MS)
        assertEquals(1, paid.size)
        val a = paid.single()
        assertEquals("j1#1", a.planId); assertEquals("Sales", a.commanderName); assertEquals(42_300L, a.computeMs); assertEquals(2, a.chunks); assertEquals(43, a.points)
        assertEquals("+43 · 42.3 s of compute for Sales", a.line)
        // 3. the countersigned receipt settles it at once
        val withReceipt = events + ev("j1", WorkerEventKind.RECEIPT_COUNTERSIGNED, t0 + 43_100)
        assertEquals(listOf("j1#1"), ComputeCredits.settled(withReceipt, "self", names, t0 + 43_200).map { it.planId })
    }

    @Test fun paysOncePersistsAndSpends() = runTest {
        val prefs = MemoryPrefs()
        val log = WorkerActivityLog()
        var now = t0
        val awards = mutableListOf<ComputeCredits.Award>()
        val vm = CreditsViewModel(prefs, log, "self", names, backgroundScope, clock = { now }, pollMs = 1_000)
        vm.notifyAward = { awards += it }
        settle()
        assertEquals(ComputeCredits.WELCOME, vm.state.value.balance)
        assertEquals("50 points", vm.state.value.balanceLine)
        assertFalse(vm.state.value.low)

        salesJob(t0).forEach { log.emit(it) }
        now = t0 + 43_000 + ComputeCredits.SETTLE_MS
        settle(5_000)
        assertEquals(listOf(43), awards.map { it.points })
        val s = vm.state.value
        assertEquals(93, s.balance); assertEquals(43, s.earned); assertEquals(1, s.jobs); assertEquals(42_300L, s.computeMs)
        assertEquals("Sales", s.lastAward?.commanderName)

        // the same events again never pay twice
        now += 60_000; settle(5_000)
        assertEquals(1, awards.size); assertEquals(93, vm.state.value.balance)

        // spending: 5 a request, never below zero
        assertTrue(vm.spend())
        assertEquals(88, vm.state.value.balance); assertEquals(5, vm.state.value.spent)
        repeat(17) { vm.spend() }
        assertEquals(3, vm.state.value.balance)
        assertFalse(vm.spend())
        assertEquals(0, vm.state.value.balance); assertEquals(93, vm.state.value.spent)
        assertTrue(vm.state.value.low)

        // a restart reads the balance, the totals, the last award and the paid plans back
        val again = CreditsViewModel(prefs, log, "self", names, backgroundScope, clock = { now }, pollMs = 1_000)
        again.notifyAward = { awards += it }
        settle(5_000)
        assertEquals(0, again.state.value.balance); assertEquals(43, again.state.value.earned); assertEquals(93, again.state.value.spent)
        assertEquals("+43 · 42.3 s of compute for Sales", again.state.value.lastAward?.line)
        assertEquals(1, awards.size)

        again.reset()
        assertEquals(ComputeCredits.WELCOME, again.state.value.balance)
        assertEquals(ComputeCredits.WELCOME, prefs.getInt(ComputeCredits.KEY_BALANCE, -1))
    }

    @Test fun withoutTheWorkerTimelineTheBalanceStillSpends() = runTest {
        val vm = CreditsViewModel(MemoryPrefs(), null, "self", names, backgroundScope)
        assertTrue(vm.spend())
        assertEquals(45, vm.state.value.balance)
        assertEquals("Enough for 9 requests at 5 points each.", vm.state.value.detail)
    }
}
