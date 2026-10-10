package com.hivecore.core

import com.hivecore.core.fakes.FakeDataChannelNetwork
import com.hivecore.core.fakes.FakeIceCandidate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDataChannelTest {
    @Test fun offerAnswerAndCandidatesOpenAnOrderedChannel() {
        val net = FakeDataChannelNetwork()
        val a = net.peer("a"); val b = net.peer("b")
        val aCands = ArrayList<FakeIceCandidate>(); val bCands = ArrayList<FakeIceCandidate>()
        a.onIceCandidate = { aCands += it }; b.onIceCandidate = { bCands += it }
        var opens = 0
        a.onOpen = { opens++ }; b.onOpen = { opens++ }
        val got = ArrayList<ByteArray>()
        b.onMessage = { got += it }

        val offer = a.createOffer()
        assertEquals(1, aCands.size)
        assertFalse(a.open)
        val answer = b.acceptOffer(offer)
        a.acceptAnswer(answer)
        assertFalse(a.open)              // no remote candidates yet
        bCands.forEach { a.addIceCandidate(it) }
        aCands.forEach { b.addIceCandidate(it) }
        assertTrue(a.open && b.open)
        assertEquals(2, opens)
        assertEquals(1, net.opened)

        a.send(byteArrayOf(1)); a.send(byteArrayOf(2, 2))
        assertEquals(2, got.size)
        assertArrayEquals(byteArrayOf(2, 2), got[1])
        assertEquals(2, b.received.size)
    }

    @Test fun blockedPairsNeverOpenAndStalledNodesLoseBytes() {
        val net = FakeDataChannelNetwork()
        val a = net.peer("a"); val b = net.peer("b")
        net.block("a", "b")
        a.onIceCandidate = { b.addIceCandidate(it) }; b.onIceCandidate = { a.addIceCandidate(it) }
        a.acceptAnswer(b.acceptOffer(a.createOffer()))
        assertFalse(a.open)
        net.unblock("a", "b")
        a.addIceCandidate(FakeIceCandidate("cand:b:9"))
        assertTrue(a.open && b.open)

        var delivered = 0
        b.onMessage = { delivered++ }
        net.stall("a")
        assertTrue(a.send(byteArrayOf(1)))   // accepted, lost
        assertEquals(0, delivered)
        net.unstall("a")
        assertTrue(a.send(byteArrayOf(2)))
        assertEquals(1, delivered)
    }

    @Test fun closeReachesBothSides() {
        val net = FakeDataChannelNetwork()
        val a = net.peer("a"); val b = net.peer("b")
        a.onIceCandidate = { b.addIceCandidate(it) }; b.onIceCandidate = { a.addIceCandidate(it) }
        a.acceptAnswer(b.acceptOffer(a.createOffer()))
        assertTrue(a.open)
        var bClosed: String? = null
        b.onClosed = { bClosed = it }
        a.close()
        assertEquals("peer closed", bClosed)
        assertFalse(a.open || b.open)
        assertFalse(a.send(byteArrayOf(1)))
        assertTrue(runCatching { a.createOffer() }.isFailure)
    }
}
