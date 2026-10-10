package com.hivecore.planner

import com.hivecore.core.DocIndex
import com.hivecore.core.SLog
import com.hivecore.core.Schema
import com.hivecore.core.ScoredChunk

/**
 * Plan 11 NodeAnswerer step 1. A question is a SQL candidate for this node when its words overlap the node's
 * vocabulary: table names and aliases plus the names and aliases of the non-groupable, non-identifier columns, minus the generic
 * names ([GENERIC]). Dimension-only overlap ("which region?") or generic-only overlap ("what is the total?") does
 * does not qualify; a table alias alone does ("how many invoices?"). The doc candidate is `docIndex.relevant(question)`,
 * already thresholded by the index. Neither -> the node answers NOT_RELEVANT without an LLM call.
 *
 * A match that lies only in confidential documents is dropped: the CONSENT_REQ it would trigger tells the asker
 * that the confidential document contains the words of the question before the owner has approved anything.
 * Confidential chunks ride along only when a table or a non-confidential document also matches.
 *
 * Matching is on lightly normalised words (lowercase, `ies -> y`, a trailing `s` dropped) so "salaries" meets the
 * alias "salaries" and "invoice" meets "invoices"; multi-word aliases ("rep id") match as phrases.
 */
object Relevance {
    val GENERIC: Set<String> = setOf("total", "date", "status", "source", "name", "id", "month", "value", "amount", "count")

    data class Candidates(val sql: Boolean, val docChunks: List<ScoredChunk>, val matchedTerms: List<String> = emptyList())

    fun check(question: String, schema: Schema, docIndex: DocIndex): Candidates {
        val matched = sqlMatches(question, schema)
        val found = try {
            docIndex.relevant(question)
        } catch (e: Exception) {
            SLog.w(TAG, "docIndex.relevant failed: ${e.message}"); emptyList()
        }
        val confidentialDocs = schema.docs.filter { it.confidential }.map { it.name }.toSet()
        val open = found.filter { it.chunk.docName !in confidentialDocs }
        val docs = if (matched.isNotEmpty() || open.isNotEmpty()) found else {
            if (found.isNotEmpty()) SLog.d(TAG, "${schema.node}: only confidential documents match; not a candidate")
            emptyList()
        }
        SLog.d(TAG, "${schema.node}: sql=${matched.isNotEmpty()} $matched docs=${docs.map { it.chunk.docName + " " + it.chunk.locator }}")
        return Candidates(matched.isNotEmpty(), docs, matched)
    }

    /** The vocabulary phrases of [schema] found in [question] (normalised form), in vocabulary order. */
    fun sqlMatches(question: String, schema: Schema): List<String> = matches(question, vocabulary(schema))

    /** The phrases of [vocab] (already normalised, as [vocabulary] builds them) found in [question]. */
    fun matches(question: String, vocab: List<String>): List<String> {
        val haystack = " " + tokens(question).joinToString(" ") + " "
        if (haystack.isBlank()) return emptyList()
        return vocab.filter { " $it " in haystack }
    }

    /** Normalised phrases that make a question a SQL candidate for this schema. */
    fun vocabulary(schema: Schema): List<String> {
        val out = LinkedHashSet<String>()
        val generic = GENERIC.map { norm(it) }.toSet()
        fun add(raw: String) {
            val phrase = tokens(raw).joinToString(" ")
            if (phrase.isNotEmpty() && phrase !in generic) out.add(phrase)
        }
        for (t in schema.tables) {
            add(t.name)
            t.aliases.forEach(::add)
            for (c in t.columns) {
                if (c.groupable || c.identifier) continue   // identifiers only filter; "reps named Rahul" must not make Sales relevant
                add(c.name)
                c.aliases.forEach(::add)
            }
        }
        return out.toList()
    }

    private val WORD = Regex("[\\p{L}\\p{Nd}]+")

    /** Lowercase words (underscores split), each normalised by [norm]. */
    fun tokens(text: String): List<String> =
        WORD.findAll(text.lowercase().replace('_', ' ')).map { norm(it.value) }.filter { it.isNotEmpty() }.toList()

    fun norm(w: String): String {
        val l = w.lowercase()
        if (l.length <= 3) return l
        if (l.endsWith("ies")) return l.dropLast(3) + "y"
        if (l.endsWith("s") && !l.endsWith("ss")) return l.dropLast(1)
        return l
    }

    private const val TAG = "Relevance"
}
