package com.hivecore.core

import kotlinx.coroutines.flow.StateFlow

// Plan 6 "core/LlmEngine.kt" — frozen after Wave 0.
data class ChatMsg(val role: String, val content: String)
data class GenParams(val maxTokens: Int = 192, val stopOnBalancedJson: Boolean = false, val stopWords: List<String> = listOf("<|im_end|>"),
    /** GBNF grammar (swarm plan §5.3); engines apply it only on llama.cpp backends — it breaks qairt NPU generation */
    val grammar: String? = null)
data class GenStats(val promptTokens: Int, val genTokens: Int, val prefillMs: Long, val decodeMs: Long, val tokPerSec: Double)
data class GenResult(val text: String, val stats: GenStats)
interface LlmEngine {
    val backend: StateFlow<LlmBackend?>; val lastStats: StateFlow<GenStats?>; val status: StateFlow<String>
    fun available(): Set<LlmBackend>
    suspend fun load(backend: LlmBackend): Result<Unit>
    suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)? = null): Result<GenResult>
    suspend fun cancel()
}
