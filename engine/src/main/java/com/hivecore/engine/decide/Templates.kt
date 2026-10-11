package com.hivecore.engine.decide

import com.hivecore.core.Question
import com.hivecore.core.SplitAxis

/**
 * The question templates of the decision points (docs/plans/decision-chunking.md §4). [VERSION] is folded into the
 * decider tag of every decided job, so a reworded template changes chunk ids like a new model build would.
 */
object Templates {
    const val VERSION = "t1"
    const val MODEL = "hivecore-decide"

    /** Decision point names as journaled. */
    const val BOUNDARY = "A.boundary"; const val DIFFICULTY = "B.difficulty"; const val VERDICT = "E.verdict"
    const val AXIS = "C.axis"; const val CAPABILITY = "D.capability"; const val PERSONAL = "F.personal"

    val DIFFICULTY_LEVELS = listOf("trivial", "easy", "moderate", "hard", "very hard")

    /** A: the topic changes after sentence [i] (between s<i> and s<i+1>); grouped per window [group]. */
    fun boundary(i: Int, group: String) = Question.Noul("b$i", "The topic changes between sentence s$i and sentence s${i + 1}.", group)

    /** B: how demanding chunk [index] is for [op]. */
    fun difficulty(index: Int, op: String) = Question.Score("d$index", "How demanding is this chunk for the $op step?", DIFFICULTY_LEVELS)

    /** E: the output of chunk [index] from [node] is consistent with its input and schema. */
    fun verdict(index: Int, node: String) = Question.Noul("v$index.$node", "This output is consistent with its input and with the required schema.")

    /** C (planned): which axis to split along. */
    fun axis(op: String, axes: Map<SplitAxis, String>) =
        Question.Choice("axis", "Along which axis should this input be split for the $op step?", axes.entries.associate { it.key.name.lowercase() to it.value })

    /** D (planned): which capability runs chunk [index]. */
    fun capability(index: Int, options: Map<String, String>) = Question.Choice("cap$index", "Which capability should run this chunk?", options)

    /** F (planned, off by default): the item holds personal data. */
    fun personal(item: String) = Question.Noul("pd$item", "This item contains personal data.")

    /** The decider tag of a build under these templates: `<sha256>:<calibrationVersion>+t<VERSION>`. */
    fun tag(buildTag: String): String = "$buildTag+t$VERSION"
}
