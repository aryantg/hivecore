package com.hivecore.ui

import com.hivecore.ui.qr.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing QR encoder: Reed-Solomon codewords verify, the version table matches the module count, the symbol shape is right. */
class QrCodeTest {
    private val invite = "swarm://join?g=7c1f2e9a4b3d&k=MCowBQYDK2VwAyEA9fZk3u2kJx1QwL8pN0vR5tY6uI7oP8aS9dF0gH1jK2l&t=3f8a1c9e2b7d4a6f"

    @Test fun inviteLinkFitsAtLevelMAndRoundTripsThroughTheTable() {
        val s = QrCode.encode(invite)!!
        assertEquals(QrCode.Ecl.M, s.ecl)
        assertTrue("version ${s.version}", s.version in 7..9)
        assertEquals(17 + 4 * s.version, s.size)
        assertTrue(s.mask in 0..7)
        // three finder patterns: centre dark, ring light, outer ring dark, separator light
        for ((r, c) in listOf(3 to 3, 3 to s.size - 4, s.size - 4 to 3)) {
            assertTrue(s.get(r, c)); assertTrue(!s.get(r - 2, c)); assertTrue(s.get(r - 3, c))
            assertTrue("separator", !s.get(if (r == 3) r + 4 else r - 4, c))
        }
        assertTrue("dark module", s.get(s.size - 8, 8))
        // timing patterns alternate between the finders
        for (i in 8 until s.size - 8) { assertEquals(i % 2 == 0, s.get(6, i)); assertEquals(i % 2 == 0, s.get(i, 6)) }
    }

    @Test fun reedSolomonCodewordsEvaluateToZeroAtEveryRootOfTheGenerator() {
        for (ec in listOf(7, 10, 18, 26)) {
            val gen = QrCode.rsGenerator(ec)
            assertEquals(ec, gen.size)
            val data = ByteArray(40) { (it * 37 + 11).toByte() }
            val codeword = data + QrCode.rsRemainder(data, gen)
            for (i in 0 until ec) assertEquals("root α^$i", 0, QrCode.evaluate(codeword, i))
        }
        // ISO 18004 annex: the generator for 7 codewords is x^7 + α^87 x^6 + … ; its constant term is α^21
        val g7 = QrCode.rsGenerator(7)
        assertEquals(0x75, g7[6])   // α^21 = 0x75
    }

    @Test fun versionTableMatchesTheModuleBudgetOfEverySymbol() {
        // data modules = 8 × total codewords + remainder bits (0 for v1, 7 for v2–6, 0 for v7–10)
        for (v in 1..QrCode.MAX_VERSION) {
            val size = QrCode.sizeOf(v)
            val functionModules = functionModuleCount(v)
            val dataModules = size * size - functionModules
            val remainder = if (v in 2..6) 7 else 0
            for (ecl in QrCode.Ecl.entries) assertEquals("version $v $ecl", dataModules, 8 * QrCode.totalCodewords(v, ecl) + remainder)
        }
        assertEquals(listOf(6, 22, 38), QrCode.alignmentPositions(7))
        assertEquals(listOf(6, 28, 50), QrCode.alignmentPositions(10))
        assertEquals(listOf(6, 18), QrCode.alignmentPositions(2))
    }

    @Test fun paddingAndCapacity() {
        val cw = QrCode.dataCodewords("hi".toByteArray(), 1, 16)
        assertEquals(16, cw.size)
        assertEquals(0x40, cw[0].toInt() and 0xF0)                     // byte mode
        assertEquals(0xEC, cw[cw.size - 2].toInt() and 0xFF); assertEquals(0x11, cw[cw.size - 1].toInt() and 0xFF)
        assertEquals(1, QrCode.encode("hello")!!.version)
        assertNotNull(QrCode.encode("x".repeat(271)))
        assertNull(QrCode.encode("x".repeat(272)))
    }

    /** Counts finder+separator, timing, alignment, format, version and dark-module modules the way the standard lays them out. */
    private fun functionModuleCount(v: Int): Int {
        val size = QrCode.sizeOf(v)
        val f = Array(size) { BooleanArray(size) }
        fun mark(r: Int, c: Int) { if (r in 0 until size && c in 0 until size) f[r][c] = true }
        for (i in 0 until size) { mark(6, i); mark(i, 6) }
        for ((r0, c0) in listOf(3 to 3, 3 to size - 4, size - 4 to 3)) for (dr in -4..4) for (dc in -4..4) mark(r0 + dr, c0 + dc)
        val al = QrCode.alignmentPositions(v)
        for (i in al.indices) for (j in al.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == al.size - 1) || (i == al.size - 1 && j == 0)) continue
            for (dr in -2..2) for (dc in -2..2) mark(al[i] + dr, al[j] + dc)
        }
        for (i in 0..8) { mark(8, i); mark(i, 8) }
        for (i in 0 until 8) { mark(size - 1 - i, 8); mark(8, size - 1 - i) }
        if (v >= 7) for (i in 0 until 18) { mark(i / 3, size - 11 + i % 3); mark(size - 11 + i % 3, i / 3) }
        return f.sumOf { row -> row.count { it } }
    }
}
