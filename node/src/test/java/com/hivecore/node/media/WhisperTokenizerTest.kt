package com.hivecore.node.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTokenizerTest {
    // A tiny tokenizer.json in the Hugging Face layout: byte-level tokens ("Ġ" = space, "Ã©" = UTF-8 of "é").
    private val json = """
        {"version":"1.0","added_tokens":[
          {"id":50257,"content":"<|endoftext|>","special":true},
          {"id":50258,"content":"<|startoftranscript|>","special":true},
          {"id":50259,"content":"<|en|>","special":true},
          {"id":50359,"content":"<|transcribe|>","special":true},
          {"id":50363,"content":"<|notimestamps|>","special":true},
          {"id":50364,"content":"<|0.00|>","special":false}],
         "model":{"type":"BPE","vocab":{"Hello":0,"Ġworld":1,",":2,"Ġcaf":3,"Ã©":4,"!":5,"Ċ":6}}}
    """.trimIndent()

    private val tok = WhisperTokenizer.parse(json)

    @Test fun decodesByteLevelTokens() {
        assertEquals("Hello world, café!", tok.decode(listOf(0, 1, 2, 3, 4, 5)))
        assertEquals("Hello\n", tok.decode(listOf(0, 6)))
    }

    @Test fun skipsSpecialsAndTimestamps() {
        val ids = listOf(WhisperTokenizer.SOT, WhisperTokenizer.EN, WhisperTokenizer.TRANSCRIBE, WhisperTokenizer.NO_TIMESTAMPS,
            0, 50364, 1, WhisperTokenizer.EOT)
        assertEquals("Hello world", tok.decode(ids))
        assertEquals("<|startoftranscript|><|en|><|transcribe|><|notimestamps|>Hello<|0.00|> world<|endoftext|>", tok.decode(ids, skipSpecial = false))
    }

    @Test fun byteDecoderIsGpt2Table() {
        val d = WhisperTokenizer.BYTE_DECODER
        assertEquals(256, d.size)
        assertEquals(' '.code, d['Ġ'])        // U+0120
        assertEquals('\n'.code, d['Ċ'])       // U+010A
        assertEquals('A'.code, d['A'])
        assertEquals(0xAD, d['Ń'])            // U+0143: the 68th non-printable byte (0xAD)
    }
}
