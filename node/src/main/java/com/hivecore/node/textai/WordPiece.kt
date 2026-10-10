package com.hivecore.node.textai

import java.io.File
import java.text.Normalizer

/**
 * BERT uncased tokenizer (HuggingFace `BertTokenizer` with do_lower_case = true): BasicTokenizer (clean control
 * characters, space around CJK ideographs, lowercase, strip accents, split on whitespace and punctuation) followed by
 * greedy longest-match-first WordPiece with the `##` continuation prefix; a word longer than [maxCharsPerWord] or with
 * no full cover becomes `[UNK]`.
 *
 * Both text-embedding models verified on the NPU use the bert-base-uncased vocabulary (30,522 entries):
 * Nomic-embed-text ships it as `vocab.txt` with the AI Hub asset; MiniLM-v2 ships none, so the same `vocab.txt` is
 * pushed into its model folder.
 */
class WordPiece(vocabLines: List<String>, private val maxCharsPerWord: Int = 100) {
    private val vocab = HashMap<String, Int>(vocabLines.size * 2)
    val unk: Int
    val cls: Int
    val sep: Int
    val pad: Int

    init {
        vocabLines.forEachIndexed { i, l -> val t = l.trimEnd('\r', '\n'); if (t.isNotEmpty() && t !in vocab) vocab[t] = i }
        unk = vocab["[UNK]"] ?: error("vocab has no [UNK]")
        cls = vocab["[CLS]"] ?: error("vocab has no [CLS]")
        sep = vocab["[SEP]"] ?: error("vocab has no [SEP]")
        pad = vocab["[PAD]"] ?: 0
    }

    val size: Int get() = vocab.size

    fun id(token: String): Int? = vocab[token]

    /** Word pieces (strings) of [text], without [CLS]/[SEP]. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in basic(text)) {
            if (word.codePointCount(0, word.length) > maxCharsPerWord) { out += UNK; continue }
            val pieces = ArrayList<String>()
            var start = 0
            var bad = false
            while (start < word.length) {
                var end = word.length
                var cur: String? = null
                while (start < end) {
                    val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                    if (sub in vocab) { cur = sub; break }
                    end = word.offsetByCodePoints(end, -1)
                }
                if (cur == null) { bad = true; break }
                pieces += cur
                start = end
            }
            if (bad) out += UNK else out += pieces
        }
        return out
    }

    data class Encoded(val ids: IntArray, val mask: IntArray, val tokens: Int, val truncated: Boolean)

    /** `[CLS] pieces [SEP]`, truncated to [maxLen] (keeping [SEP]) and padded with [PAD]; mask 1 on real tokens. */
    fun encode(text: String, maxLen: Int): Encoded {
        require(maxLen >= 2) { "maxLen must be ≥ 2" }
        val pieces = tokenize(text).map { vocab[it] ?: unk }
        val body = pieces.take(maxLen - 2)
        val ids = IntArray(maxLen) { pad }
        val mask = IntArray(maxLen)
        ids[0] = cls
        body.forEachIndexed { i, v -> ids[i + 1] = v }
        ids[body.size + 1] = sep
        for (i in 0 until body.size + 2) mask[i] = 1
        return Encoded(ids, mask, body.size + 2, pieces.size > body.size)
    }

    companion object {
        const val UNK = "[UNK]"
        const val VOCAB_FILE = "vocab.txt"

        fun load(file: File): WordPiece = WordPiece(file.readLines(Charsets.UTF_8))

        /** BasicTokenizer: clean, CJK spacing, lowercase, strip accents, split on whitespace and punctuation. */
        fun basic(text: String): List<String> {
            val sb = StringBuilder(text.length + 8)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
                when {
                    isWhitespace(cp) -> sb.append(' ')
                    isCjk(cp) -> sb.append(' ').appendCodePoint(cp).append(' ')
                    else -> sb.appendCodePoint(cp)
                }
            }
            val out = ArrayList<String>()
            for (tok in sb.toString().split(' ')) {
                if (tok.isEmpty()) continue
                val lowered = stripAccents(tok.lowercase())
                val cur = StringBuilder()
                var j = 0
                while (j < lowered.length) {
                    val cp = lowered.codePointAt(j)
                    j += Character.charCount(cp)
                    if (isPunctuation(cp)) {
                        if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                        out += String(Character.toChars(cp))
                    } else cur.appendCodePoint(cp)
                }
                if (cur.isNotEmpty()) out += cur.toString()
            }
            return out
        }

        private fun stripAccents(s: String): String {
            val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(nfd.length)
            var i = 0
            while (i < nfd.length) {
                val cp = nfd.codePointAt(i)
                i += Character.charCount(cp)
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
            }
            return sb.toString()
        }

        private fun isWhitespace(cp: Int): Boolean =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val t = Character.getType(cp)
            return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()
        }

        fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        private fun isCjk(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
