package com.hivecore.node.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Whisper token ids → text (GPT-2 byte-level BPE decode). Decoding needs only the id → token-string table, not
 * the merges. The AI Hub asset ships no tokenizer, so the Hugging Face `tokenizer.json` of `openai/whisper-tiny`
 * (multilingual vocabulary, 51 865 ids; 2.4 MB) is placed next to the model as `<model dir>/tokenizer.json`.
 * Its `model.vocab` (50 257 BPE tokens + `<|endoftext|>`) and `added_tokens` (50 257…51 864: specials, languages,
 * tasks, timestamps) were checked against the constants below (2026-10-10):
 * `<|endoftext|>` 50257, `<|startoftranscript|>` 50258, `<|en|>` 50259, `<|transcribe|>` 50359,
 * `<|startoflm|>` 50360, `<|startofprev|>` 50361, `<|nocaptions|>` 50362, `<|notimestamps|>` 50363,
 * `<|0.00|>` 50364 … `<|30.00|>` 51864.
 *
 * Byte-level decode: each character of a vocab token stands for one byte through GPT-2's `bytes_to_unicode`
 * table ([BYTE_DECODER]); the bytes of all tokens are concatenated and decoded as UTF-8 (malformed → U+FFFD).
 */
class WhisperTokenizer(private val tokens: Map<Int, String>, private val special: Set<Int>) {

    val size: Int get() = tokens.size

    fun token(id: Int): String? = tokens[id]

    /** Text of [ids]; with [skipSpecial] every id ≥ [EOT] (specials and timestamps) is dropped. Not trimmed. */
    fun decode(ids: List<Int>, skipSpecial: Boolean = true): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            val t = tokens[id] ?: continue
            if (id >= EOT || id in special) {
                if (!skipSpecial) out.write(t.toByteArray(Charsets.UTF_8))
                continue
            }
            for (ch in t) {
                val b = BYTE_DECODER[ch] ?: continue
                out.write(b)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val EOT = 50257
        const val SOT = 50258
        const val EN = 50259
        const val TRANSCRIBE = 50359
        const val NO_TIMESTAMPS = 50363
        const val TIMESTAMP_BEGIN = 50364
        const val FILE = "tokenizer.json"

        /** GPT-2 `bytes_to_unicode`: printable bytes map to themselves, the rest to U+0100 + n in byte order. */
        val BYTE_DECODER: Map<Char, Int> by lazy {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += (0xA1..0xAC); bs += (0xAE..0xFF)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }

        fun load(file: File): WhisperTokenizer = parse(file.readText(Charsets.UTF_8))

        /** A Hugging Face `tokenizer.json` (BPE `model.vocab` + `added_tokens`). */
        fun parse(json: String): WhisperTokenizer {
            val root = Json.parseToJsonElement(json) as JsonObject
            val vocab = (root["model"] as? JsonObject)?.get("vocab") as? JsonObject ?: error("tokenizer.json has no model.vocab")
            val map = HashMap<Int, String>(vocab.size * 2)
            for ((tok, id) in vocab) map[(id as JsonPrimitive).intOrNull ?: continue] = tok
            val special = HashSet<Int>()
            (root["added_tokens"] as? JsonArray)?.forEach { e ->
                val o = e as JsonObject
                val id = (o["id"] as JsonPrimitive).intOrNull ?: return@forEach
                map[id] = (o["content"] as JsonPrimitive).content
                if ((o["special"] as? JsonPrimitive)?.booleanOrNull == true) special += id
            }
            return WhisperTokenizer(map, special)
        }
    }
}
