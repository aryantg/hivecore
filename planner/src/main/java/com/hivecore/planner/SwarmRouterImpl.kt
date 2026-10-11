package com.hivecore.planner

import com.hivecore.planner.Relevance
import com.hivecore.core.Catalog
import com.hivecore.core.ColType
import com.hivecore.core.Cue
import com.hivecore.core.RouteDecision
import com.hivecore.core.SwarmRouter

/**
 * Swarm plan §2.1: decides whether a question is composite (T2) or stays on the existing T0/T1 path. Pure Kotlin,
 * deterministic, no LLM.
 *
 *  - **Owners:** catalogs whose vocabulary occurs in the question, with the [Relevance] token rules: table names and
 *    aliases, plus names and aliases of the non-groupable, non-identifier columns, minus [Relevance.GENERIC]. A
 *    document whose name (without extension) occurs as a phrase also makes its catalog an owner.
 *  - **Cues:** [CueLexicon] phrases in order of appearance, with the lexicon's gates for words that double as
 *    aggregate words (`per`, superlatives, `flag`).
 *  - **Composite** when there is a cue and an owner, or when more than one node owns a match and some owner has more
 *    than one measure named (more than one aggregate on one node, which one T1 JOB per node cannot carry), or when a
 *    cue names an owner-free op ([CueLexicon.OWNER_FREE_OPS]: a picture drawn from the words needs no phone's data).
 *    Measures are the numeric, non-groupable, non-identifier, non-generic columns and their aliases.
 *  - No owner and no owner-free cue: not composite; the existing pipeline gives its broadcast / NoNode outcome.
 */
class SwarmRouterImpl : SwarmRouter {

    override fun route(question: String, selfId: String, catalogs: List<Catalog>): RouteDecision {
        val owners = ArrayList<String>()
        val measuresByOwner = LinkedHashMap<String, List<String>>()
        val matchedByOwner = LinkedHashMap<String, List<String>>()
        for (c in catalogs) {
            val m = Relevance.matches(question, vocabulary(c))
            val d = docMatches(question, c)
            if (m.isEmpty() && d.isEmpty()) continue
            if (c.node !in owners) owners += c.node
            matchedByOwner[c.node] = m + d
            measuresByOwner[c.node] = Relevance.matches(question, measures(c))
        }
        val cues = cues(question, catalogs)
        val multiMeasureOwner = measuresByOwner.entries.firstOrNull { it.value.size > 1 }
        val multi = owners.size > 1 && multiMeasureOwner != null
        // cues-3: a picture is drawn from the words, so it needs no owner
        val generative = cues.any { it.op in CueLexicon.OWNER_FREE_OPS }

        val holds = matchedByOwner.entries.joinToString("; ") { (n, m) -> "$n: ${m.joinToString(", ")}" }
        val cueText = cues.joinToString(", ") { "'${it.word}' → ${it.op}" }
        val reason = when {
            owners.isEmpty() && generative -> "T1 generative: cues $cueText need no phone's data"
            owners.isEmpty() && cues.isNotEmpty() -> "no catalog holds a term of the question (cues $cueText ignored); existing pipeline"
            owners.isEmpty() -> "no catalog holds a term of the question; existing pipeline"
            cues.isNotEmpty() -> "T2 composite: cues $cueText; owners $holds"
            multi -> "T2 composite: ${owners.size} owners and ${multiMeasureOwner!!.key} has ${multiMeasureOwner.value.size} measures " +
                "(${multiMeasureOwner.value.joinToString(", ")}); owners $holds"
            owners.all { it == selfId } -> "T0 local: no cue, only this phone holds the terms ($holds)"
            else -> "T1 direct: no cue, one aggregate per owner ($holds)"
        }
        val composite = generative || owners.isNotEmpty() && (cues.isNotEmpty() || multi)
        return RouteDecision(composite, owners, cues, "[${CueLexicon.VERSION}] $reason")
    }

    /** Lexicon cues in order of appearance; a matched phrase consumes its words. */
    fun cues(question: String, catalogs: List<Catalog>): List<Cue> {
        val words = rawWords(question)
        val normed = words.map { Relevance.norm(it) }
        val dims = dimensions(catalogs)
        val out = ArrayList<Cue>()
        var i = 0
        while (i < words.size) {
            val hit = CueLexicon.candidatesAt(words, i).firstOrNull { gateOk(it, words, normed, i, dims) }
            if (hit == null) { i++; continue }
            out += Cue(hit.phrase, hit.op)
            i += hit.words.size
        }
        return out
    }

    private fun gateOk(e: CueLexicon.Entry, words: List<String>, normed: List<String>, i: Int, dims: Set<String>): Boolean = when (e.gate) {
        CueLexicon.Gate.NONE -> true
        CueLexicon.Gate.NOT_LAST -> i + e.words.size < words.size
        CueLexicon.Gate.NEEDS_DIMENSION -> normed.any { it in dims }
        CueLexicon.Gate.PER -> {
            val next = words.getOrNull(i + 1)
            when {
                next == null -> false
                next in CueLexicon.PER_GROUPING || Relevance.norm(next) in dims -> false
                (0 until i).any { words[it] in CueLexicon.AVERAGE_WORDS } -> false
                else -> true
            }
        }
    }

    companion object {
        private val WORD = Regex("[\\p{L}\\p{Nd}]+")

        /** Lowercase words, underscores split, not normalised (cue phrases are matched verbatim). */
        fun rawWords(text: String): List<String> = WORD.findAll(text.lowercase().replace('_', ' ')).map { it.value }.toList()

        private fun phrase(raw: String): String = Relevance.tokens(raw).joinToString(" ")
        private val generic: Set<String> = Relevance.GENERIC.map { Relevance.norm(it) }.toSet()

        /** [Relevance.vocabulary] over a public catalog. */
        fun vocabulary(c: Catalog): List<String> {
            val out = LinkedHashSet<String>()
            fun add(raw: String) { val p = phrase(raw); if (p.isNotEmpty() && p !in generic) out += p }
            for (t in c.tables) {
                add(t.name); t.aliases.forEach(::add)
                for (col in t.columns) {
                    if (col.groupable || col.identifier) continue
                    add(col.name); col.aliases.forEach(::add)
                }
            }
            return out.toList()
        }

        /** Measure phrases: numeric, non-groupable, non-identifier, non-generic columns and their aliases. */
        fun measures(c: Catalog): List<String> {
            val out = LinkedHashSet<String>()
            for (t in c.tables) for (col in t.columns) {
                if (col.groupable || col.identifier || col.type == ColType.TEXT) continue
                (listOf(col.name) + col.aliases).map(::phrase).filter { it.isNotEmpty() && it !in generic }.forEach { out += it }
            }
            return out.toList()
        }

        /** Groupable column names and aliases of every catalog, as normalised single words or phrases. */
        fun dimensions(catalogs: List<Catalog>): Set<String> {
            val out = HashSet<String>()
            for (c in catalogs) for (t in c.tables) for (col in t.columns) if (col.groupable) {
                (listOf(col.name) + col.aliases).map(::phrase).filter { it.isNotEmpty() }.forEach { out += it }
            }
            return out
        }

        /** Document names (extension dropped) found as phrases in the question. */
        fun docMatches(question: String, c: Catalog): List<String> {
            val vocab = c.docs.map { phrase(it.substringBeforeLast('.', it)) }.filter { it.isNotEmpty() && it !in generic }.distinct()
            return Relevance.matches(question, vocab)
        }
    }
}
