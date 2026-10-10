package com.hivecore.cloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import java.io.File

/** Shared fixtures for the cloud tests: the real `models.json`, a placeholder key store and canned OpenRouter replies. */
object CloudTestSupport {
    /** Gradle runs unit tests with the module dir as cwd. */
    fun config(): ModelsConfig = ModelsConfig.parse(File("src/main/assets/cloud/models.json").readText())

    /** A placeholder, never a real key. */
    fun keys(): CloudKeyStore = MemoryCloudKeyStore("sk-or-test-placeholder")

    /** An OpenRouter chat completion whose content is [content] (a JSON text), answered by [slug]. */
    fun completion(content: String, slug: String = "anthropic/claude-sonnet-5", tokensIn: Int = 120, tokensOut: Int = 20, cost: Double? = 0.00044): MockResponse {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val usage = """{"prompt_tokens":$tokensIn,"completion_tokens":$tokensOut${cost?.let { ",\"cost\":$it" } ?: ""}}"""
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"id":"gen-1","model":"$slug","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"}}],"usage":$usage}""")
    }

    fun error(code: Int, message: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("""{"error":{"code":$code,"message":"$message"}}""")

    val noEndpoints: MockResponse get() = error(404, "No endpoints found that support the requested parameters (zdr, structured outputs)")
}

/** A scripted [CloudClient]: answers from [replies] in order (or fails with [failure]) and records every call. */
class FakeCloudClient : CloudClient {
    data class Call(val role: CloudRole, val messages: List<Msg>, val schema: JsonObject, val maxTokens: Int)
    val calls = ArrayList<Call>()
    val replies = ArrayDeque<JsonElement>()
    var slug = "anthropic/claude-sonnet-5"
    var failure: CloudException? = null
    var costUsd: Double? = 0.001

    override suspend fun complete(role: CloudRole, messages: List<Msg>, schema: JsonObject, maxTokens: Int): Result<CloudReply> {
        calls += Call(role, messages, schema, maxTokens)
        failure?.let { return Result.failure(it) }
        val out = replies.removeFirstOrNull() ?: return Result.failure(CloudException("no scripted reply", CloudException.Kind.HTTP))
        val bytes = messages.sumOf { it.content.length }.toLong()
        return Result.success(CloudReply(out, slug, (bytes / 4).toInt(), 30, costUsd, bytes, 1))
    }
}
