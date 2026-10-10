package com.hivecore.engine.text

/** A transcribed segment: its start in the source audio and its text. */
data class SegmentText(val startMs: Long, val endMs: Long, val text: String)

/**
 * Merges segment transcripts (VAD_SPLIT shards with 1 s overlap) in time order and removes the words the overlap
 * duplicated. Whisper gives no word timestamps here (timestamps are suppressed), so the duplicate is found by text:
 * the longest run of equal words (compared lower-case, punctuation stripped) between the last [window] words of the
 * text so far and the first [window] words of the next segment. Ties prefer the run closest to the boundary. With a
 * run of at least [minRun] words, the merged text keeps the earlier text up to the end of the run and the later
 * text after it, so a word half-cut at either side of the boundary is dropped too. A one-word run counts only when it
 * is the last word of one side and the first of the other. Otherwise the texts are joined with a space. Pure.
 */
object Stitch {
    fun merge(segments: List<SegmentText>, window: Int = 12, minRun: Int = 2): String {
        val sorted = segments.sortedWith(compareBy<SegmentText> { it.startMs }.thenBy { it.endMs })
        var words = ArrayList<String>()
        for (s in sorted) {
            val next = words(s.text)
            if (next.isEmpty()) continue
            words = ArrayList(join(words, next, window, minRun))
        }
        return words.joinToString(" ")
    }

    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun norm(w: String): String = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    fun join(a: List<String>, b: List<String>, window: Int = 12, minRun: Int = 2): List<String> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val aOff = maxOf(0, a.size - window)
        val an = a.subList(aOff, a.size).map(::norm)
        val bn = b.subList(0, minOf(window, b.size)).map(::norm)
        var bestLen = 0; var bestI = -1; var bestJ = -1; var bestDist = Int.MAX_VALUE
        for (i in an.indices) for (j in bn.indices) {
            var l = 0
            while (i + l < an.size && j + l < bn.size && an[i + l].isNotEmpty() && an[i + l] == bn[j + l]) l++
            if (l == 0) continue
            val dist = (an.size - (i + l)) + j          // words dropped around the boundary
            if (l > bestLen || (l == bestLen && dist < bestDist)) { bestLen = l; bestI = i; bestJ = j; bestDist = dist }
        }
        val ok = bestLen >= minRun || (bestLen == 1 && bestDist == 0)
        if (!ok) return a + b
        return a.subList(0, aOff + bestI + bestLen) + b.subList(bestJ + bestLen, b.size)
    }
}
