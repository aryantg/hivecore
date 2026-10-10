package com.hivecore.node.vision

import java.io.File
import java.util.zip.GZIPInputStream

// Swarm plan §4A.3 TAG: OpenAI CLIP (AI Hub v0.64.0 openai_clip float) zero-shot against an owner-defined vocabulary.
// AI Hub's CLIP is ONE graph: inputs image [1,224,224,3] float32 (0..1) and text [1,5,77] int32 token ids; outputs
// image_features [1,512] and text_features [5,512]. So text is encoded 5 prompts per call, alongside the image.
// The token ids come from CLIP's byte-level BPE. AI Hub does not ship the merges file with the asset: the model
// folder must also hold OpenAI's `bpe_simple_vocab_16e6.txt(.gz)` (the vocabulary is derived from it).

/** CLIP's byte-level BPE tokenizer (port of openai/CLIP simple_tokenizer.py, without ftfy/html unescaping). */
class ClipBpe(mergesFile: List<String>) {
    private val byteEncoder: Array<String>
    private val ranks = HashMap<Pair<String, String>, Int>()
    private val encoder = HashMap<String, Int>()
    private val cache = HashMap<String, List<String>>()
    val sot: Int
    val eot: Int

    init {
        val bs = ArrayList<Int>()
        bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        val enc = arrayOfNulls<String>(256)
        for (i in bs.indices) enc[bs[i]] = String(Character.toChars(cs[i]))
        byteEncoder = enc.requireNoNulls()
        val vocab = ArrayList<String>()
        for (b in bs) vocab += byteEncoder[b]
        for (b in bs) vocab += byteEncoder[b] + "</w>"
        val merges = mergesFile.drop(1).take(49152 - 256 - 2).map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
            val p = l.split(' '); require(p.size == 2) { "bad merge line: $l" }; p[0] to p[1]
        }
        merges.forEachIndexed { i, m -> ranks[m] = i; vocab += m.first + m.second }
        vocab += "<|startoftext|>"; vocab += "<|endoftext|>"
        vocab.forEachIndexed { i, s -> encoder[s] = i }
        sot = encoder.getValue("<|startoftext|>"); eot = encoder.getValue("<|endoftext|>")
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        val cps = token.codePoints().toArray().map { String(Character.toChars(it)) }
        var word: List<String> = cps.dropLast(1) + (cps.last() + "</w>")
        while (word.size > 1) {
            var best: Pair<String, String>? = null; var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] to word[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = word[i] to word[i + 1] }
            }
            val (a, b) = best ?: break
            val nw = ArrayList<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == a && word[i + 1] == b) { nw += a + b; i += 2 } else { nw += word[i]; i++ }
            }
            word = nw
        }
        cache[token] = word
        return word
    }

    fun encode(text: String): List<Int> {
        val clean = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val out = ArrayList<Int>()
        for (m in PATTERN.findAll(clean)) {
            val t = m.value.toByteArray(Charsets.UTF_8).joinToString("") { byteEncoder[it.toInt() and 255] }
            for (piece in bpe(t)) out += encoder[piece] ?: continue
        }
        return out
    }

    /** [sot] + ids + [eot], truncated (eot kept) and zero-padded to [context]. */
    fun tokenize(text: String, context: Int = 77): IntArray {
        val ids = ArrayList<Int>(); ids += sot; ids += encode(text)
        if (ids.size > context - 1) { while (ids.size > context - 1) ids.removeAt(ids.size - 1) }
        ids += eot
        return IntArray(context) { if (it < ids.size) ids[it] else 0 }
    }

    companion object {
        private val PATTERN = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+", RegexOption.IGNORE_CASE)
        val MERGES_FILES = listOf("bpe_simple_vocab_16e6.txt.gz", "bpe_simple_vocab_16e6.txt", "merges.txt")

        fun load(file: File): ClipBpe {
            val text = if (file.name.endsWith(".gz")) GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            else file.readText(Charsets.UTF_8)
            return ClipBpe(text.split('\n'))
        }

        /** The merges file in [dir], if shipped. */
        fun find(dir: File): File? = MERGES_FILES.map { File(dir, it) }.firstOrNull { it.isFile }
    }
}

data class TagScore(val tag: String, val score: Double, val cosine: Double)

object ClipTagger {
    const val THRESHOLD = 0.15
    const val LOGIT_SCALE = 100.0
    const val TEMPLATE = "a photo of {}."

    fun prompt(tag: String, template: String = TEMPLATE) = if ("{}" in template) template.replace("{}", tag) else "$template $tag"

    fun normalize(v: FloatArray): DoubleArray {
        var s = 0.0
        for (x in v) s += x.toDouble() * x
        val n = StrictMath.sqrt(s)
        return DoubleArray(v.size) { if (n == 0.0) 0.0 else v[it] / n }
    }

    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * Scores every tag: "softmax" (default, CLIP zero-shot: softmax of 100·cos over the vocabulary) or "cosine".
     * Returns tags with score ≥ [threshold], score desc then vocabulary order.
     */
    fun score(image: FloatArray, texts: List<FloatArray>, tags: List<String>, mode: String = "softmax", threshold: Double = THRESHOLD): List<TagScore> {
        require(texts.size == tags.size)
        val iv = normalize(image)
        val cos = texts.map { cosine(iv, normalize(it)) }
        val scores = if (mode == "cosine") cos else {
            val m = cos.maxOrNull() ?: 0.0
            val e = cos.map { StrictMath.exp(LOGIT_SCALE * (it - m)) }
            val s = e.sum()
            e.map { it / s }
        }
        return tags.indices.filter { scores[it] >= threshold }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .map { TagScore(tags[it], scores[it], cos[it]) }
    }
}
