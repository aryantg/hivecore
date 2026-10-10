package com.hivecore.node.docai

import com.hivecore.core.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private fun kinds(text: String) = Redactor.find(text).map { it.kind }

    @Test fun verhoeff() {
        assertTrue(Redactor.verhoeffValid("2363"))                 // the textbook example: 236 -> check digit 3
        assertEquals(3, Redactor.verhoeffCheckDigit("236"))
        assertTrue(Redactor.verhoeffValid("234567890124"))
        assertFalse(Redactor.verhoeffValid("234567890125"))
        assertEquals(4, Redactor.verhoeffCheckDigit("23456789012"))
        assertTrue(Redactor.verhoeffValid("498765432102"))
        assertFalse(Redactor.verhoeffValid(""))
        assertFalse(Redactor.verhoeffValid("12a4"))
    }

    @Test fun aadhaarNeedsAValidCheckDigit() {
        assertEquals("Aadhaar: [AADHAAR]", Redactor.redact("Aadhaar: 2345 6789 0124").text)
        assertEquals("No. [AADHAAR].", Redactor.redact("No. 234567890124.").text)
        assertEquals(emptyList<String>(), kinds("Aadhaar: 2345 6789 0125"))      // Verhoeff-invalid: not an Aadhaar
        assertEquals(emptyList<String>(), kinds("2345 67890124"))                // mixed spacing
        assertEquals(emptyList<String>(), kinds("1234 5678 9012"))               // cannot start with 0 or 1 (and invalid)
        assertEquals(emptyList<String>(), kinds("92345 6789 0124"))              // part of a longer number
    }

    @Test fun panIfscEmailPhone() {
        assertEquals(listOf("PAN"), kinds("PAN: ABCDE1234F"))
        assertEquals(emptyList<String>(), kinds("GSTIN 27ABCDE1234F1Z5"))        // a GSTIN embeds a PAN but is not one
        assertEquals(emptyList<String>(), kinds("abcde1234f"))
        assertEquals(listOf("IFSC"), kinds("IFSC: HDFC0001234"))
        assertEquals(listOf("EMAIL"), kinds("write to accounts@mehta-stationers.in today"))
        assertEquals(listOf("PHONE"), kinds("Call 98765 43210"))
        assertEquals(listOf("PHONE"), kinds("Mob: +91 98765-43210"))
        assertEquals(listOf("PHONE"), kinds("Tel 09876543210"))
        assertEquals(listOf("PHONE"), kinds("Office: 080-41234567"))
        assertEquals(emptyList<String>(), kinds("Call 58765 43210"))             // mobiles start 6-9
        assertEquals(emptyList<String>(), kinds("Total: ₹ 1,12,000.00  Invoice No: 1007  Date: 12/10/2026"))
    }

    @Test fun accountNumbersOnlyNearTheKeyword() {
        assertEquals("A/c No: [ACCOUNT]  IFSC: [IFSC]", Redactor.redact("A/c No: 50100234567890  IFSC: HDFC0001234").text)
        assertEquals(listOf("ACCOUNT"), kinds("Account number 001234567890"))
        assertEquals(listOf("ACCOUNT"), kinds("Bank acct:\n123456789"))
        assertEquals(emptyList<String>(), kinds("Order 50100234567890 shipped"))                  // no keyword
        assertEquals(emptyList<String>(), kinds("Account manager will call on the 3rd. Ref 50100234567890"))  // too far away
    }

    @Test fun redactionLogHoldsSpansNotValues() {
        val text = "Name: Rahul, PAN ABCDE1234F, mail rahul@example.com, Aadhaar 2345 6789 0124"
        val r = Redactor.redact(text)
        assertEquals("Name: Rahul, PAN [PAN], mail [EMAIL], Aadhaar [AADHAAR]", r.text)
        assertEquals(listOf(Redaction("PAN", 17, 27), Redaction("EMAIL", 34, 51), Redaction("AADHAAR", 61, 75)), r.log)
        for (x in r.log) assertFalse(text.substring(x.start, x.end).isBlank())
        assertEquals(Redactor.redact(text), r)                                   // deterministic
        assertEquals(RedactedText("nothing here", emptyList()), Redactor.redact("nothing here"))
    }

    @Test fun overlappingMatchesKeepOneSpan() {
        // a 12-digit Verhoeff-valid number after "account" is both: the earlier/longer span wins once
        val r = Redactor.find("account 234567890124")
        assertEquals(1, r.size)
        assertEquals(8, r[0].start); assertEquals(20, r[0].end)
        assertEquals("AADHAAR", r[0].kind)                                       // same span: AADHAAR outranks ACCOUNT
    }

    @Test fun imageBoxesAreTheOcrLinesToBlur() {
        val lines = listOf(
            OcrLine("Mehta Stationers", 40, 40, 200, 56),
            OcrLine("Bank A/c No:", 40, 60, 160, 76),
            OcrLine("50100234567890", 40, 80, 180, 96),
            OcrLine("Phone 98765 43210  PAN ABCDE1234F", 40, 100, 380, 116),
        )
        assertEquals(listOf(
            RedactBox(2, 40, 80, 180, 96, listOf("ACCOUNT")),
            RedactBox(3, 40, 100, 380, 116, listOf("PHONE", "PAN")),
        ), Redactor.boxes(lines))
        assertEquals(emptyList<RedactBox>(), Redactor.boxes(lines.take(1)))
    }
}
