package com.hivecore.cloud

import com.hivecore.core.Canon
import com.hivecore.core.JsonSchema
import com.hivecore.core.SLog
import com.hivecore.engine.JsonSchemaValidator
import com.hivecore.engine.SchemaValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The OpenRouter [CloudClient] (spec 15.4-15.6). One request per attempt, exactly the 15.6 configuration: the role's
 * `models` fallback chain, `temperature 0`, `max_tokens`, `response_format json_schema strict` and
 * `provider {zdr, require_parameters, data_collection: deny, sort: latency}`. 30 s per attempt, one retry on a 5xx
 * (or a transport failure), then the chain advances to the next model; 90 s total budget. A 404 "no endpoints" means
 * no endpoint satisfies `zdr` + `require_parameters`: the call fails with "no compliant endpoint" and there is no
 * fallback to a non-ZDR endpoint. The reply must be exactly one JSON value satisfying the schema; it is validated here
 * and never repaired. Every request carries `HTTP-Referer` and `X-OpenRouter-Title`.
 *
 * The constructor is `internal` (spec 18.2): only this module's [EscalationGate] and [CloudPlannerImpl] build it,
 * through [create]; `:app` creates those inside the orchestrator's commander factories only.
 */
class OpenRouterClient internal constructor(
    private val keys: CloudKeyStore,
    private val config: ModelsConfig,
    private val baseUrl: String = DEFAULT_BASE_URL,
    http: OkHttpClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
    private val totalBudgetMs: Long = TOTAL_BUDGET_MS,
    private val referer: String = DEFAULT_REFERER,
    private val title: String = DEFAULT_TITLE,
    private val validator: SchemaValidator = JsonSchemaValidator,
) : CloudClient {

    private val http: OkHttpClient = (http ?: OkHttpClient()).newBuilder()
        .callTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun complete(role: CloudRole, messages: List<Msg>, schema: JsonObject, maxTokens: Int): Result<CloudReply> = runCatching {
        JsonSchema.schemaError(schema)?.let { throw CloudException("schema is not valid: $it", CloudException.Kind.INVALID_REPLY) }
        val key = keys.key()?.takeIf { it.isNotBlank() } ?: throw CloudException("no OpenRouter key is entered in Settings", CloudException.Kind.AUTH)
        val chain = config.role(role).models
        val tokens = maxTokens.coerceIn(1, config.role(role).maxTokens)
        val t0 = clock()
        var attempts = 0
        var lastError: String? = null
        var bytesSent = 0L
        for (start in chain.indices) {
            val models = chain.drop(start)
            val body = requestBody(models, tokens, schema, messages)
            bytesSent = body.toByteArray(Charsets.UTF_8).size.toLong()
            var retried = false
            while (true) {
                if (clock() - t0 >= totalBudgetMs) throw CloudException("cloud budget of ${totalBudgetMs / 1000} s exhausted (${lastError ?: "no reply"})", CloudException.Kind.BUDGET, attempts)
                attempts++
                val r = try {
                    post(key, body)
                } catch (e: IOException) {
                    lastError = "${models.first()}: ${e.message ?: e::class.simpleName}"
                    SLog.w(TAG, "attempt $attempts failed: $lastError")
                    if (!retried) { retried = true; continue } else break
                }
                when {
                    r.code == 200 -> {
                        val parsed = parseReply(r.body, schema) ?: throw CloudException("the model's reply is not one JSON value satisfying the schema", CloudException.Kind.INVALID_REPLY, attempts)
                        return@runCatching parsed.copy(bytesSent = bytesSent, attempts = attempts)
                    }
                    r.code == 404 -> throw CloudException("no compliant endpoint: ${errorMessage(r.body) ?: "no endpoint offers zero data retention with strict schemas for ${models.first()}"}", CloudException.Kind.NO_COMPLIANT_ENDPOINT, attempts)
                    r.code == 401 || r.code == 403 || r.code == 402 -> throw CloudException("OpenRouter refused the key: ${errorMessage(r.body) ?: "HTTP ${r.code}"}", CloudException.Kind.AUTH, attempts)
                    r.code >= 500 -> {
                        lastError = "${models.first()}: HTTP ${r.code}"
                        SLog.w(TAG, "attempt $attempts: $lastError")
                        if (!retried) { retried = true; continue } else break
                    }
                    r.code == 429 -> { lastError = "${models.first()}: rate limited"; break }
                    else -> throw CloudException("OpenRouter HTTP ${r.code}: ${errorMessage(r.body) ?: r.body.take(200)}", CloudException.Kind.HTTP, attempts)
                }
            }
        }
        throw CloudException("every cloud model failed: ${lastError ?: "no reply"}", CloudException.Kind.HTTP, attempts)
    }

    private class Http(val code: Int, val body: String)

    private suspend fun post(key: String, body: String): Http = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", referer)
            .header("X-OpenRouter-Title", title)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { Http(it.code, it.body?.string() ?: "") }
    }

    /** `choices[0].message.content` parsed as one JSON value and validated; null when it is not. */
    private fun parseReply(body: String, schema: JsonObject): CloudReply? {
        val o = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        (o["error"] as? JsonObject)?.let { e ->
            throw CloudException("OpenRouter error: ${(e["message"] as? JsonPrimitive)?.content ?: e}", CloudException.Kind.HTTP)
        }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val value = runCatching { STRICT.parseToJsonElement(content.trim()) }.getOrNull() ?: return null
        validator.validate(value, schema)?.let { SLog.w(TAG, "reply failed the schema: $it"); return null }
        val usage = o["usage"] as? JsonObject
        val slug = (o["model"] as? JsonPrimitive)?.content ?: "unknown"
        return CloudReply(
            output = value, slug = slug,
            tokensIn = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            tokensOut = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            costUsd = (usage?.get("cost") as? JsonPrimitive)?.doubleOrNull,
            bytesSent = 0, attempts = 0,
        )
    }

    private fun errorMessage(body: String): String? {
        val o = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return ((o["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
    }

    companion object {
        const val TAG = "OpenRouter"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_REFERER = "https://hivecore.app"
        const val DEFAULT_TITLE = "HiveCore"
        const val ATTEMPT_TIMEOUT_MS = 30_000L
        const val TOTAL_BUDGET_MS = 90_000L
        const val SCHEMA_NAME = "step_output"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val STRICT = Json { isLenient = false; allowSpecialFloatingPointValues = false }

        /** The module-internal factory the gate and the planner use. */
        internal fun create(keys: CloudKeyStore, config: ModelsConfig, baseUrl: String = DEFAULT_BASE_URL, http: OkHttpClient? = null,
                            clock: () -> Long = System::currentTimeMillis, attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS, totalBudgetMs: Long = TOTAL_BUDGET_MS): OpenRouterClient =
            OpenRouterClient(keys, config, baseUrl, http, clock, attemptTimeoutMs, totalBudgetMs)

        /** The spec 15.6 request body, as canonical JSON (sorted keys, so tests and the ledger see one byte form). */
        fun requestBody(models: List<String>, maxTokens: Int, schema: JsonObject, messages: List<Msg>): String = Canon.canonical(buildJsonObject {
            put("models", JsonArray(models.map { JsonPrimitive(it) }))
            put("temperature", 0)
            put("max_tokens", maxTokens)
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", SCHEMA_NAME); put("strict", true); put("schema", schema) }
            }
            putJsonObject("provider") { put("zdr", true); put("require_parameters", true); put("data_collection", "deny"); put("sort", "latency") }
            put("messages", JsonArray(messages.map { m ->
                buildJsonObject {
                    put("role", m.role)
                    if (m.images.isEmpty()) put("content", m.content)
                    else put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", m.content) })
                        for (img in m.images) add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(img)) }
                        })
                    })
                }
            }))
        })
    }
}
