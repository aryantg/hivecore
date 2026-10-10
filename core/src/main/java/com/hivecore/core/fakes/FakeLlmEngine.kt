package com.hivecore.core.fakes

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenParams
import com.hivecore.core.GenResult
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keyword → canned reply. The last user message is matched (case-insensitive `contains`) against [replies] in
 * insertion order; the first hit wins, otherwise [fallback]. [tour] preloads the tour's intents and doc answers.
 */
class FakeLlmEngine(initialBackend: LlmBackend? = LlmBackend.CPU) : LlmEngine {
    private val backendState = MutableStateFlow(initialBackend)
    override val backend: StateFlow<LlmBackend?> = backendState
    private val statsState = MutableStateFlow<GenStats?>(null)
    override val lastStats: StateFlow<GenStats?> = statsState
    private val statusState = MutableStateFlow("fake engine")
    override val status: StateFlow<String> = statusState

    val replies = LinkedHashMap<String, String>()
    var fallback: String = """{"not_relevant":true}"""
    val calls = mutableListOf<Pair<List<ChatMsg>, GenParams>>()
    var failNext: Throwable? = null
    var availableBackends: Set<LlmBackend> = setOf(LlmBackend.CPU)
    var cancelled = 0

    override fun available(): Set<LlmBackend> = availableBackends

    override suspend fun load(backend: LlmBackend): Result<Unit> {
        backendState.value = backend
        statusState.value = "loaded $backend (fake)"
        return Result.success(Unit)
    }

    override suspend fun generate(messages: List<ChatMsg>, params: GenParams, onToken: ((String) -> Unit)?): Result<GenResult> {
        calls.add(messages to params)
        failNext?.let { failNext = null; return Result.failure(it) }
        val user = messages.lastOrNull { it.role == "user" }?.content?.lowercase() ?: ""
        val text = replies.entries.firstOrNull { user.contains(it.key.lowercase()) }?.value ?: fallback
        onToken?.invoke(text)
        val stats = GenStats(
            promptTokens = messages.sumOf { it.content.length } / 4,
            genTokens = maxOf(1, text.length / 4),
            prefillMs = 1, decodeMs = 1, tokPerSec = 100.0
        )
        statsState.value = stats
        return Result.success(GenResult(text, stats))
    }

    override suspend fun cancel() { cancelled++ }

    companion object {
        /** Replies for the tour questions (plan 8 templates) plus the two doc answers. Order matters. */
        fun tour(): FakeLlmEngine = FakeLlmEngine().apply {
            replies["commission"] = """{"intents":[{"table":"employees","metric":"avg","column":"commission","filters":[{"column":"region","op":"eq","value":"West"}]}]}"""
            replies["salary"] = """{"intents":[{"table":"employees","metric":"avg","column":"salary","filters":[{"column":"name","op":"eq","value":"Rahul"}]}]}"""
            replies["invoiced"] = """{"intents":[{"table":"invoices","metric":"sum","column":"total","filters":[{"column":"month","op":"eq","value":"2026-10"}]}]}"""
            replies["unpaid"] = """{"intents":[{"table":"invoices","metric":"count","filters":[{"column":"status","op":"eq","value":"UNPAID"}]}]}"""
            replies["leave"] = "A new joiner is entitled to 18 days of paid leave in the first year, accrued monthly. [1]"
            replies["priorities"] = "The Q3 priorities are the three bullets on slide 3. [1]"
            replies["revenue"] = """{"intents":[{"table":"sales","metric":"sum","column":"revenue","group_by":["region"],"filters":[{"column":"month","op":"gte","value":"2026-01"},{"column":"month","op":"lte","value":"2026-12"}]},{"table":"employees","metric":"count","group_by":["region"]}]}"""
            replies["reps"] = """{"intents":[{"table":"employees","metric":"count","group_by":["region"]}]}"""
            replies["employees"] = """{"intents":[{"table":"employees","metric":"count","group_by":["region"]}]}"""
            replies["headcount"] = """{"intents":[{"table":"employees","metric":"count","group_by":["region"]}]}"""
        }
    }
}
