package com.hivecore.engine

import com.hivecore.core.JsonSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The draft 2020-12 subset ([JsonSchema]) as the engines' [SchemaValidator] (v2 plan §6.2, §7.3): LLM_MAP items, node
 * results of ops whose TaskSpec carries `params.schema`, and cloud answers are all checked by this one function.
 */
object JsonSchemaValidator : SchemaValidator {
    override fun validate(output: JsonElement, schema: JsonObject): String? = JsonSchema.validate(output, schema)
}
