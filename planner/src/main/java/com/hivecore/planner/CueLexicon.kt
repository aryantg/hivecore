package com.hivecore.planner

import com.hivecore.core.Op

/**
 * The fixed, versioned operator-cue lexicon of the swarm router (swarm plan §2.1). Each entry is a phrase of lowercase
 * words matched on word boundaries; at one position the longest phrase wins and its words are consumed. Bump
 * [VERSION] whenever an entry or a [Gate] changes, because routing decisions are logged against it.
 *
 * cues-2 added the TREND words of growth stories (increase, rise, fell …) and the RENDER words that ask for a deck.
 * cues-3 added the GEN_IMAGE words that ask for a picture (draw, image of, illustration, poster …); GEN_IMAGE is an
 * [OWNER_FREE_OPS] op, so a question carrying it is composite even when no catalog holds a term of it.
 *
 * Some cue words are also ordinary aggregate words, so a few entries carry a [Gate] that the router evaluates:
 *  - `per` is a ratio only when it is not "per <dimension>" ("revenue per region" is a group-by) and no average word
 *    precedes it ("average revenue per sale" is one AVG).
 *  - `highest`, `lowest`, `best`, `worst` rank groups only when the question names a dimension ("which region has the
 *    highest revenue"); "the highest salary in the company" is one MAX.
 *  - `flag` is an instruction only when something follows it ("flag reps below …"); "what did the review flag?" is not.
 */
object CueLexicon {
    const val VERSION = "cues-3"

    /** Ops that need no phone's data: a question cued with one is planned even without an owner (the picture is drawn from the words). */
    val OWNER_FREE_OPS: Set<Op> = setOf(Op.GEN_IMAGE)

    enum class Gate { NONE, PER, NEEDS_DIMENSION, NOT_LAST }

    data class Entry(val words: List<String>, val op: Op, val gate: Gate = Gate.NONE) {
        val phrase: String get() = words.joinToString(" ")
    }

    private fun e(phrase: String, op: Op, gate: Gate = Gate.NONE) = Entry(phrase.split(' '), op, gate)

    val ENTRIES: List<Entry> = listOf(
        // RATIO
        e("per", Op.RATIO, Gate.PER), e("ratio", Op.RATIO), e("ratios", Op.RATIO), e("share of", Op.RATIO),
        e("proportion of", Op.RATIO), e("percentage of", Op.RATIO),
        // DIFF
        e("vs", Op.DIFF), e("versus", Op.DIFF), e("compare", Op.DIFF), e("compared", Op.DIFF), e("comparing", Op.DIFF),
        e("comparison", Op.DIFF), e("difference", Op.DIFF), e("differences", Op.DIFF),
        // TREND
        e("growth", Op.TREND), e("grow", Op.TREND), e("grew", Op.TREND), e("trend", Op.TREND), e("trends", Op.TREND),
        e("month on month", Op.TREND), e("month over month", Op.TREND), e("year on year", Op.TREND),
        e("year over year", Op.TREND), e("change", Op.TREND), e("changed", Op.TREND), e("changes", Op.TREND),
        e("increase", Op.TREND), e("increased", Op.TREND), e("increasing", Op.TREND), e("decrease", Op.TREND),
        e("decreased", Op.TREND), e("rise", Op.TREND), e("rose", Op.TREND), e("fell", Op.TREND), e("decline", Op.TREND),
        // RENDER (cues-2): a deck or presentation assembled from the other steps
        e("pitch deck", Op.RENDER), e("slide deck", Op.RENDER), e("deck", Op.RENDER), e("slides", Op.RENDER),
        e("slideshow", Op.RENDER), e("presentation", Op.RENDER),
        // GEN_IMAGE (cues-3): a picture drawn from the words of the request
        e("image of", Op.GEN_IMAGE), e("images of", Op.GEN_IMAGE), e("picture of", Op.GEN_IMAGE), e("pictures of", Op.GEN_IMAGE),
        e("an image", Op.GEN_IMAGE), e("a picture", Op.GEN_IMAGE), e("an illustration", Op.GEN_IMAGE), e("images", Op.GEN_IMAGE),
        e("pictures", Op.GEN_IMAGE), e("illustration", Op.GEN_IMAGE), e("illustrations", Op.GEN_IMAGE), e("illustrate", Op.GEN_IMAGE),
        e("draw", Op.GEN_IMAGE), e("sketch", Op.GEN_IMAGE), e("logo", Op.GEN_IMAGE), e("poster", Op.GEN_IMAGE), e("artwork", Op.GEN_IMAGE),
        e("wallpaper", Op.GEN_IMAGE),
        // RANK
        e("top", Op.RANK), e("rank", Op.RANK), e("ranked", Op.RANK), e("ranking", Op.RANK),
        e("best", Op.RANK, Gate.NEEDS_DIMENSION), e("worst", Op.RANK, Gate.NEEDS_DIMENSION),
        e("highest", Op.RANK, Gate.NEEDS_DIMENSION), e("lowest", Op.RANK, Gate.NEEDS_DIMENSION),
        // THRESHOLD
        e("flag", Op.THRESHOLD, Gate.NOT_LAST), e("above", Op.THRESHOLD), e("below", Op.THRESHOLD),
        e("exceeds", Op.THRESHOLD), e("exceed", Op.THRESHOLD), e("exceeded", Op.THRESHOLD),
        e("more than the average", Op.THRESHOLD), e("less than the average", Op.THRESHOLD),
        e("above average", Op.THRESHOLD), e("below average", Op.THRESHOLD),
        // RECONCILE
        e("match", Op.RECONCILE), e("reconcile", Op.RECONCILE), e("reconciles", Op.RECONCILE),
        e("consistent", Op.RECONCILE), e("inconsistent", Op.RECONCILE),
        // EXTRACT
        e("summarise", Op.EXTRACT), e("summarize", Op.EXTRACT), e("key points", Op.EXTRACT), e("action items", Op.EXTRACT),
    )

    /** Words that make a following `per` an average ("average revenue per sale"). */
    val AVERAGE_WORDS: Set<String> = setOf("average", "avg", "mean")

    /** Words after `per` that are a time or unit grouping, never a denominator. */
    val PER_GROUPING: Set<String> = setOf("each", "every", "day", "week", "month", "quarter", "year", "cent", "annum")

    private val byFirst: Map<String, List<Entry>> = ENTRIES.groupBy { it.words.first() }
        .mapValues { (_, v) -> v.sortedByDescending { it.words.size } }

    /** Entries starting at word [i] of [words], longest first. */
    fun candidatesAt(words: List<String>, i: Int): List<Entry> =
        byFirst[words[i]].orEmpty().filter { en -> i + en.words.size <= words.size && en.words.indices.all { words[i + it] == en.words[it] } }
}
