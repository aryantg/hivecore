package com.hivecore.engine

import com.hivecore.core.Canon
import com.hivecore.core.Op
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparatorImplTest {
    private val c = ComparatorImpl()
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun rule(s: String): JsonObject = j(s) as JsonObject

    @Test fun defaultAndHashUseCanonicalJson() {
        val a = j("""{"b":[1,2.0,{"y":1,"x":"s"}],"a":5.00}""")
        val b = j("""{"a":5,"b":[1,2,{"x":"s","y":1.0}]}""")
        assertTrue(c.agree(Op.FIELDS, a, b))
        assertTrue(c.agree(Op.FIELDS, a, b, rule("""{"kind":"hash"}""")))
        assertTrue(c.agree(Op.FIELDS, a, b, JsonObject(emptyMap())))
        assertFalse(c.agree(Op.FIELDS, a, j("""{"a":5,"b":[2,1,{"x":"s","y":1}]}""")))
        assertFalse(c.agree(Op.FIELDS, j("\"5\""), j("5")))
    }

    @Test fun canonicalFormIsSortedAndCompact() {
        assertEquals("""{"a":5,"b":[1.5,"x",null,true]}""", Canon.canonical(j("""{ "b": [1.50, "x", null, true], "a": 5.0 }""")))
        assertEquals("0", Canon.canonical(JsonPrimitive(-0.0)))
    }

    @Test fun cosine() {
        val r = rule("""{"kind":"cosine","min":0.99}""")
        assertTrue(c.agree(Op.EMBED, j("[1,2,3]"), j("[1.01,2,2.99]"), r))
        assertTrue(c.agree(Op.EMBED, j("[1,2,3]"), j("[2,4,6]"), r))              // scale-free
        assertFalse(c.agree(Op.EMBED, j("[1,0,0]"), j("[0,1,0]"), r))
        assertFalse(c.agree(Op.EMBED, j("[1,2]"), j("[1,2,3]"), r))               // length mismatch
        assertFalse(c.agree(Op.EMBED, j("[1,\"a\"]"), j("[1,2]"), r))
        assertTrue(c.agree(Op.EMBED, j("[0,0]"), j("[0,0]"), r))
        assertTrue(c.agree(Op.EMBED, j("""{"vector":[[1,2],[3,4]]}"""), j("[1,2,3,4]"), r))
        // default min 0.999 rejects what 0.99 accepts
        assertFalse(c.agree(Op.EMBED, j("[1,2,3]"), j("[1.3,2,2.7]"), rule("""{"kind":"cosine"}""")))
    }

    @Test fun iou() {
        val r = rule("""{"kind":"iou","min":0.5}""")
        val a = j("""[{"label":"helmet","box":[0,0,10,10],"score":0.9},{"label":"person","box":[20,20,40,40],"score":0.8}]""")
        val shifted = j("""[{"label":"person","box":[21,21,41,41],"score":0.7},{"label":"helmet","box":[1,0,11,10],"score":0.95}]""")
        assertTrue(c.agree(Op.DETECT, a, shifted, r))
        val wrongLabel = j("""[{"label":"helmet","box":[20,20,40,40]},{"label":"helmet","box":[0,0,10,10]}]""")
        assertFalse(c.agree(Op.DETECT, a, wrongLabel, r))
        val far = j("""[{"label":"helmet","box":[5,5,15,15]},{"label":"person","box":[20,20,40,40]}]""")
        assertFalse(c.agree(Op.DETECT, a, far, r))                              // IoU 25/175 < 0.5
        assertTrue(c.agree(Op.DETECT, a, far, rule("""{"kind":"iou","min":0.1}""")))
        assertFalse(c.agree(Op.DETECT, a, j("[]"), r))
        assertTrue(c.agree(Op.DETECT, j("[]"), j("""{"detections":[]}"""), r))
        assertEquals(1.0 / 3.0, ComparatorImpl.iouOf(doubleArrayOf(0.0, 0.0, 2.0, 1.0), doubleArrayOf(1.0, 0.0, 3.0, 1.0)), 1e-9)
    }

    @Test fun text() {
        val r = rule("""{"kind":"text"}""")
        assertTrue(c.agree(Op.PHRASE, j("\" Revenue is up \""), j("\"Revenue is up\""), r))
        assertFalse(c.agree(Op.PHRASE, j("\"Revenue is up\""), j("\"revenue is up\""), r))
        assertTrue(c.agree(Op.PHRASE, j("[1, 2]"), j("[1,2.0]"), r))
    }
}
