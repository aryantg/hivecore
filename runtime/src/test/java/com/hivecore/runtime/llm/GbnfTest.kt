package com.hivecore.runtime.llm

import com.hivecore.runtime.llm.Gbnf.Slot
import com.hivecore.runtime.llm.Gbnf.SlotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GbnfTest {
    @Test fun choiceAndNumbers() {
        assertEquals("root ::= \"yes\" | \"no\" | \"unclear\"", Gbnf.choice(listOf("yes", "no", "unclear", "no")))
        assertEquals("root ::= \"1\" | \"2\"", Gbnf.numbers(2))
        assertThrows(IllegalArgumentException::class.java) { Gbnf.choice(emptyList()) }
    }

    @Test fun literalEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Gbnf.literal("a\"b\\c\n"))
    }

    @Test fun jsonObjectWithEnumAndBoundedInt() {
        val g = Gbnf.jsonObject(listOf(Slot("dept", SlotType.Enum(listOf("HR", "Sales"))), Slot("k", SlotType.Integer(1, 3))))
        assertEquals(
            """
            root ::= "{" ws "\"dept\"" ws ":" ws s0 ws "," ws "\"k\"" ws ":" ws s1 ws "}"
            s0 ::= "\"HR\"" | "\"Sales\""
            s1 ::= "1" | "2" | "3"
            ws ::= " "?
            """.trimIndent(), g)
    }

    @Test fun unboundedIntsUseADigitRule() {
        val g = Gbnf.jsonObject(listOf(Slot("n", SlotType.Integer(min = 0)), Slot("d", SlotType.Integer())))
        assertEquals(
            """
            root ::= "{" ws "\"n\"" ws ":" ws s0 ws "," ws "\"d\"" ws ":" ws s1 ws "}"
            s0 ::= uint
            s1 ::= "-"? uint
            uint ::= "0" | [1-9] [0-9]*
            ws ::= " "?
            """.trimIndent(), g)
    }

    @Test fun enumValuesAreJsonThenGbnfEscaped() {
        val g = Gbnf.jsonObject(listOf(Slot("v", SlotType.Enum(listOf("say \"hi\"")))))
        assertEquals("s0 ::= \"\\\"say \\\\\\\"hi\\\\\\\"\\\"\"", g.lines()[1])
    }
}
