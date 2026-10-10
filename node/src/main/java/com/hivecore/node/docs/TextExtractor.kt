package com.hivecore.node.docs

import java.io.File

/** `.txt` / `.md`: paragraphs are runs of non-blank lines; locator "¶ n" counts the paragraphs that are returned. */
object TextExtractor {
    private val BLANK_LINES = Regex("\\n[ \\t]*\\n")

    fun extract(file: File): List<TextUnit> = parse(file.readText(Charsets.UTF_8))

    fun parse(text: String): List<TextUnit> {
        val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<TextUnit>()
        for (block in body.split(BLANK_LINES)) {
            val t = Normalize.block(block)
            if (t.isNotEmpty()) out.add(TextUnit("¶ ${out.size + 1}", t))
        }
        return out
    }
}
