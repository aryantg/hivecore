package com.hivecore.node.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvReaderTest {

    @Test fun quotesEscapedQuotesAndEmbeddedNewlines() {
        val t = CsvReader.read("name,note,n\r\n\"Sharma, Rahul\",\"said \"\"hi\"\"\",1\r\n\"multi\nline\",plain,2\r\n")
        assertEquals(listOf("name", "note", "n"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("Sharma, Rahul", "said \"hi\"", "1"), t.rows[0])
        assertEquals(listOf("multi\nline", "plain", "2"), t.rows[1])
    }

    @Test fun bomLfAndNoTrailingNewline() {
        val t = CsvReader.read("﻿a,b\n1,2\n3,4")
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), t.rows)
    }

    @Test fun blankLinesSkippedRowsPaddedAndCut() {
        val t = CsvReader.read("a,b,c\n1,2\n\n4,5,6,7\n   \n")
        assertEquals(listOf(listOf("1", "2", ""), listOf("4", "5", "6")), t.rows)
    }

    @Test fun emptyQuotedFieldAndTrailingComma() {
        val t = CsvReader.read("a,b,c\n\"\",x,\n")
        assertEquals(listOf(listOf("", "x", "")), t.rows)
    }

    @Test fun tabAndSemicolonDelimitersDetected() {
        assertEquals(listOf("a", "b"), CsvReader.read("a\tb\n1\t2\n").header)
        assertEquals(listOf(listOf("1", "2")), CsvReader.read("a;b\n1;2\n").rows)
        assertEquals(listOf("a;b"), CsvReader.read("a;b\n1;2\n", delimiter = ',').header)
    }

    @Test fun emptyInput() {
        val t = CsvReader.read("")
        assertTrue(t.header.isEmpty()); assertTrue(t.rows.isEmpty())
    }

    @Test fun looksTabular() {
        assertTrue(CsvReader.looksTabular("a,b,c\n1,2,3\n4,5,6"))
        assertFalse(CsvReader.looksTabular("just a sentence, with a comma\nand another line"))
        assertFalse(CsvReader.looksTabular("one line only"))
    }

    @Test fun seedCsvFixturesParse() {
        val e = CsvReader.read(Fixtures.stream("employees.csv"))
        assertEquals(listOf("region", "rep_id", "name", "salary", "commission", "joined"), e.header)
        assertEquals(61, e.rows.size)
        assertEquals(1204, CsvReader.read(Fixtures.stream("sales.csv")).rows.size)
        assertEquals(40, CsvReader.read(Fixtures.stream("invoices.csv")).rows.size)
    }
}
