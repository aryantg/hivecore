package com.hivecore.node.docai

import com.hivecore.core.OcrLine
import com.hivecore.core.fakes.FakeOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowGrouperTest {
    // 10 px per character, 20 px tall boxes.
    private fun box(text: String, left: Int, top: Int, height: Int = 20) = OcrLine(text, left, top, left + text.length * 10, top + height)

    @Test fun twoRowsThreeCellsFromShuffledBoxes() {
        val lines = listOf(
            box("Amount", 400, 102), box("Item", 0, 100), box("Qty", 200, 98),      // row 1, jittered y, out of order
            box("45,600", 400, 140), box("Paper", 0, 142), box("20", 200, 141),    // row 2 (40 px lower)
        )
        val rows = RowGrouper.rows(lines)
        assertEquals(listOf(listOf("Item", "Qty", "Amount"), listOf("Paper", "20", "45,600")), rows)
        assertEquals(listOf("Item  Qty  Amount", "Paper  20  45,600"), RowGrouper.rowsText(lines))
    }

    @Test fun adjacentBoxesMergeIntoOneCellAndNoiseGetsItsOwnRow() {
        val lines = listOf(
            box("Mehta", 0, 0), box("Stationers", 60, 1),         // gap 10 px < 1.5 x 10 -> one cell
            box("Total:", 0, 60), box("45,600.00", 100, 60),       // gap 40 px -> two cells
            box(".", 700, 300),                                    // noise: far below, alone
            OcrLine("   ", 0, 30, 10, 50),                         // blank: dropped
        )
        val rows = RowGrouper.rows(lines)
        assertEquals(listOf(listOf("Mehta Stationers"), listOf("Total:", "45,600.00"), listOf(".")), rows)
    }

    @Test fun rowBreakUsesMedianLineHeight() {
        // Lines 20 px tall: a 10 px shift stays in the row (<= 12), a 13 px shift starts a new one.
        val same = RowGrouper.rows(listOf(box("a", 0, 0), box("b", 100, 10)))
        assertEquals(1, same.size)
        val split = RowGrouper.rows(listOf(box("a", 0, 0), box("b", 100, 13)))
        assertEquals(2, split.size)
    }

    @Test fun doubleSpacesInsideOneBoxSplitCells() {
        val lines = FakeOcr.fromText("Copier paper A4 x 20  ₹ 19,322.00\nTotal: ₹ 45,600.00\n")
        val rows = RowGrouper.rows(lines)
        assertEquals(listOf(listOf("Copier paper A4 x 20", "₹ 19,322.00"), listOf("Total: ₹ 45,600.00")), rows)
    }

    @Test fun fakeOcrLinesOfAnInvoiceComeOutOnePerRowInOrder() {
        val text = "Mehta Stationers\nTAX INVOICE\nInvoice No: 1007\nDate: 12/10/2026\nTotal: ₹ 45,600.00\nStatus: PAID"
        val rows = RowGrouper.rowsText(FakeOcr.fromText(text))
        assertEquals(text.lines(), rows)
        assertTrue(RowGrouper.rows(emptyList()).isEmpty())
    }
}
