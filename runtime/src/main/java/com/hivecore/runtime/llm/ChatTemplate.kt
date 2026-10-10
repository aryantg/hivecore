package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg

/**
 * ChatML as Qwen expects it (plan 2.2). Used when the engine's own `applyChatTemplate` is unavailable or
 * returns an empty string (GenieX 0.8.0 bug #1432); `GenieXEngine` prefers the SDK's template.
 *
 *   <|im_start|>system\n…<|im_end|>\n<|im_start|>user\n…<|im_end|>\n<|im_start|>assistant\n
 *
 * Qwen3-4B-Instruct-2507 is a non-thinking model: no `/no_think`, no `enable_thinking`. Stop on `<|im_end|>`.
 */
object ChatTemplate {
    const val IM_START = "<|im_start|>"
    const val IM_END = "<|im_end|>"

    /** Renders [messages] and appends the open assistant turn so the model starts answering. */
    fun qwen(messages: List<ChatMsg>): String {
        val sb = StringBuilder()
        for (m in messages) {
            sb.append(IM_START).append(m.role.trim().lowercase()).append('\n')
            sb.append(m.content.trimEnd()).append(IM_END).append('\n')
        }
        sb.append(IM_START).append("assistant").append('\n')
        return sb.toString()
    }
}
