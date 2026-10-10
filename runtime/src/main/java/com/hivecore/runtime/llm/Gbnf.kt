package com.hivecore.runtime.llm

/**
 * GBNF grammars for closed questions (swarm plan §5.3). Pure functions. A grammar guarantees the format, never the
 * answer, and is only valid on the llama.cpp backend (GGUF on CPU/GPU): on the qairt NPU runtime GenieX 0.8.0 fails
 * with "generate() unknown exception" and the stream never completes, so callers must not send one there
 * ([ClosedChooserImpl.grammarFor] returns null for the NPU).
 */
object Gbnf {
    sealed interface SlotType {
        /** one of [values], emitted as a JSON string */
        data class Enum(val values: List<String>) : SlotType
        /** a JSON integer; when both bounds are set and the range is small, the values are enumerated */
        data class Integer(val min: Int? = null, val max: Int? = null) : SlotType
    }
    data class Slot(val name: String, val type: SlotType)

    /** Ranges up to this many values are spelled out as alternatives (exact bounds); larger ones use a digit rule. */
    const val MAX_ENUMERATED_INTS = 100

    /** `root ::= "a" | "b"`: the output is exactly one of [labels]. */
    fun choice(labels: List<String>): String {
        require(labels.isNotEmpty()) { "no options" }
        return "root ::= " + labels.distinct().joinToString(" | ") { literal(it) }
    }

    /** The option numbers 1..[n] (what [ClosedChooserImpl] asks for). */
    fun numbers(n: Int): String {
        require(n >= 1) { "no options" }
        return choice((1..n).map { it.toString() })
    }

    /** A single-line JSON object with exactly [slots] in order, e.g. `{"dept": "HR", "k": 3}`. */
    fun jsonObject(slots: List<Slot>): String {
        require(slots.isNotEmpty()) { "no slots" }
        val rules = ArrayList<String>()
        var needsInt = false
        val body = slots.mapIndexed { i, s ->
            val rule = "s$i"
            rules += "$rule ::= " + when (val t = s.type) {
                is SlotType.Enum -> {
                    require(t.values.isNotEmpty()) { "slot ${s.name}: empty enum" }
                    t.values.distinct().joinToString(" | ") { literal("\"" + jsonEscape(it) + "\"") }
                }
                is SlotType.Integer -> {
                    if (t.min != null && t.max != null) require(t.min <= t.max) { "slot ${s.name}: min > max" }
                    if (t.min != null && t.max != null && t.max.toLong() - t.min + 1 <= MAX_ENUMERATED_INTS) {
                        (t.min..t.max).joinToString(" | ") { literal(it.toString()) }
                    } else {
                        needsInt = true
                        if (t.min != null && t.min >= 0) "uint" else "\"-\"? uint"
                    }
                }
            }
            literal("\"" + jsonEscape(s.name) + "\"") + " ws \":\" ws " + rule
        }
        return buildString {
            append("root ::= \"{\" ws ")
            append(body.joinToString(" ws \",\" ws "))
            append(" ws \"}\"\n")
            for (r in rules) append(r).append('\n')
            if (needsInt) append("uint ::= \"0\" | [1-9] [0-9]*\n")
            append("ws ::= \" \"?")
        }
    }

    /** A GBNF string literal: `"` + escaped text + `"`. */
    fun literal(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    /** Escape for the inside of a JSON string (what the model must emit). */
    fun jsonEscape(s: String): String = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
