package com.hivecore.runtime.llm

import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import java.util.Locale

/** One-line, human-readable token statistics for logs, the STATE hook and the Settings screen. */
object TokStats {
    fun format(s: GenStats): String =
        "prefill ${s.promptTokens} tok in ${s.prefillMs} ms · ${s.genTokens} tok in ${s.decodeMs} ms · ${tokPerSec(s.tokPerSec)} tok/s"

    fun format(backend: LlmBackend?, s: GenStats): String = "${backend?.name ?: "no backend"}: ${format(s)}"

    fun tokPerSec(v: Double): String = String.format(Locale.US, "%.1f", v)
}
