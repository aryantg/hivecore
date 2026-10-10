package com.hivecore.node.media

import com.hivecore.core.SLog
import java.io.File

/** Per-path cache of Whisper tokenizers for the ASR executor. */
object MediaModels {
    private val tokenizers = HashMap<String, WhisperTokenizer>()

    /** `<dir>/tokenizer.json`, cached per path; null when missing (then text is empty, tokens still returned). */
    fun tokenizer(dir: File): WhisperTokenizer? = synchronized(tokenizers) {
        val f = File(dir, WhisperTokenizer.FILE)
        tokenizers[f.path] ?: if (f.isFile) WhisperTokenizer.load(f).also { tokenizers[f.path] = it }
        else { SLog.w("MediaModels", "no ${f.path}: Whisper text will be empty"); null }
    }
}
