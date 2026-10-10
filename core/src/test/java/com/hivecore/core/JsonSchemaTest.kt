package com.hivecore.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The draft 2020-12 subset of [JsonSchema] (spec 7.3, 7.4, 15.3). */
class JsonSchemaTest {
    private fun s(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun v(text: String) = Json.parseToJsonElement(text)

    private val invoice = s("""{"type":"object","properties":{"vendor":{"type":"string","minLength":1},"total":{"type":"number","minimum":0},
        "lines":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"},"qty":{"type":"integer"}},"required":["sku"]},"maxItems":3},
        "status":{"enum":["paid","unpaid"]},"note":{"type":["string","null"]}},"required":["vendor","total"],"additionalProperties":false}""")

    @Test fun validSchemasAreAccepted() {
        assertNull(JsonSchema.schemaError(invoice))
        assertTrue(JsonSchema.isValidSchema(s("""{"type":"string","pattern":"^[a-z]+$"}""")))
        assertTrue(JsonSchema.isValidSchema(s("""{"anyOf":[{"type":"string"},{"type":"null"}],"title":"t","${'$'}schema":"x"}""")))
    }

    @Test fun refsAndUnknownKeywordsAreRejected() {
        assertEquals("properties.x.${'$'}ref: references are not allowed", JsonSchema.schemaError(s("""{"properties":{"x":{"${'$'}ref":"#/a"}}}""")))
        assertTrue(JsonSchema.schemaError(s("""{"${'$'}defs":{}}"""))!!.contains("references"))
        assertEquals("foo: unsupported keyword", JsonSchema.schemaError(s("""{"foo":1}""")))
        assertEquals("type: unknown type thing", JsonSchema.schemaError(s("""{"type":"thing"}""")))
        assertEquals("schema is not an object", JsonSchema.schemaError(v("true")))
        assertEquals("no schema", JsonSchema.schemaError(null))
        assertNotNull(JsonSchema.schemaError(s("""{"pattern":"("}""")))
        assertNotNull(JsonSchema.schemaError(s("""{"minItems":2,"maxItems":1}""")))
        assertNotNull(JsonSchema.schemaError(s("""{"enum":[]}""")))
        assertNotNull(JsonSchema.schemaError(s("""{"required":["a","a"]}""")))
    }

    @Test fun deepNestingIsBounded() {
        var sch = """{"type":"string"}"""
        repeat(JsonSchema.MAX_DEPTH + 2) { sch = """{"type":"object","properties":{"n":$sch}}""" }
        assertTrue(JsonSchema.schemaError(s(sch))!!.contains("nested deeper"))
    }

    @Test fun valuesValidate() {
        assertNull(JsonSchema.validate(v("""{"vendor":"Acme","total":12.5,"lines":[{"sku":"a","qty":2}],"status":"paid","note":null}"""), invoice))
        assertEquals("$: missing total", JsonSchema.validate(v("""{"vendor":"Acme"}"""), invoice))
        assertEquals("$.total: not number", JsonSchema.validate(v("""{"vendor":"Acme","total":"12"}"""), invoice))
        assertEquals("$.total: below 0", JsonSchema.validate(v("""{"vendor":"Acme","total":-1}"""), invoice))
        assertEquals("$.vendor: shorter than 1", JsonSchema.validate(v("""{"vendor":"","total":1}"""), invoice))
        assertEquals("$: unknown key extra", JsonSchema.validate(v("""{"vendor":"A","total":1,"extra":1}"""), invoice))
        assertEquals("$.lines[0]: missing sku", JsonSchema.validate(v("""{"vendor":"A","total":1,"lines":[{"qty":1}]}"""), invoice))
        assertEquals("$.lines[0].qty: not integer", JsonSchema.validate(v("""{"vendor":"A","total":1,"lines":[{"sku":"s","qty":1.5}]}"""), invoice))
        assertNull(JsonSchema.validate(v("""{"vendor":"A","total":1,"lines":[{"sku":"s","qty":2.0}]}"""), invoice))   // 2.0 is an integer
        assertTrue(JsonSchema.validate(v("""{"vendor":"A","total":1,"status":"late"}"""), invoice)!!.startsWith("$.status: not one of"))
        assertTrue(JsonSchema.validate(v("""{"vendor":"A","total":1,"lines":[{"sku":"1"},{"sku":"2"},{"sku":"3"},{"sku":"4"}]}"""), invoice)!!.contains("more than 3"))
        assertEquals("$.note: not string or null", JsonSchema.validate(v("""{"vendor":"A","total":1,"note":4}"""), invoice))
    }

    @Test fun combinatorsConstAndBounds() {
        val one = s("""{"oneOf":[{"type":"string"},{"type":"string","maxLength":2}]}""")
        assertNull(JsonSchema.validate(v("\"abc\""), one))
        assertEquals("$: matches 2 of oneOf (exactly 1 required)", JsonSchema.validate(v("\"ab\""), one))
        val any = s("""{"anyOf":[{"const":1},{"type":"boolean"}]}""")
        assertNull(JsonSchema.validate(v("1.0"), any)); assertNull(JsonSchema.validate(v("true"), any))
        assertEquals("$: matches none of anyOf", JsonSchema.validate(v("2"), any))
        val all = s("""{"allOf":[{"type":"number","exclusiveMinimum":0},{"maximum":10}]}""")
        assertNull(JsonSchema.validate(v("5"), all))
        assertEquals("$: not above 0", JsonSchema.validate(v("0"), all))
        assertEquals("$: above 10", JsonSchema.validate(v("11"), all))
        assertTrue(JsonSchema.validate(v("\"x\""), s("""{"pattern":"^[0-9]+$"}"""))!!.contains("does not match"))
    }

    @Test fun invalidSchemaFailsValidationNotTheValue() {
        val r = JsonSchema.validate(v("1"), s("""{"type":"int"}"""))
        assertTrue(r!!.startsWith("invalid schema:"))
        assertFalse(JsonSchema.isValidSchema(s("""{"type":"int"}""")))
    }
}
