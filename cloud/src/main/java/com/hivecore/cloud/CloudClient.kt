package com.hivecore.cloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One chat message for a cloud model; [images] are raw bytes of released media sent once as data URLs (multimodal role only). */
data class Msg(val role: String, val content: String, val images: List<ByteArray> = emptyList())

/**
 * A cloud answer (spec 15.3): the JSON value the model returned (already validated against the request schema), the
 * slug that answered, token counts, the cost OpenRouter reported in USD (null when it reported none), the bytes sent
 * and how many HTTP attempts it took.
 */
data class CloudReply(
    val output: JsonElement,
    val slug: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double?,
    val bytesSent: Long,
    val attempts: Int,
)

/** Why a cloud call failed; the gate turns [kind] into a refusal or a failure. */
class CloudException(message: String, val kind: Kind, val attempts: Int = 0) : Exception(message) {
    enum class Kind {
        /** no endpoint satisfies zdr + require_parameters: there is no fallback to a non-ZDR endpoint (spec 15.6) */
        NO_COMPLIANT_ENDPOINT,
        /** the API key is missing, invalid or out of credit */
        AUTH,
        /** every model in the chain failed over HTTP after its retry */
        HTTP,
        /** the 90 s total budget ran out */
        BUDGET,
        /** the model's reply is not one JSON value satisfying the schema */
        INVALID_REPLY,
    }
}

/** Spec 18.3: the one way to a cloud model. Only `:cloud` builds an implementation; `:app` creates it for the orchestrator's commander objects only. */
interface CloudClient {
    /**
     * One strict-schema completion for [role]: [schema] is the JSON Schema (draft 2020-12 subset, no `$ref`) the reply
     * must satisfy; [maxTokens] caps the output. The failure is a [CloudException].
     */
    suspend fun complete(role: CloudRole, messages: List<Msg>, schema: JsonObject, maxTokens: Int): Result<CloudReply>
}
