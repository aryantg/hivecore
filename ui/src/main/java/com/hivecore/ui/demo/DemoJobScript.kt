package com.hivecore.ui.demo

import com.hivecore.core.ChunkState
import com.hivecore.core.ChunkView
import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.NodeLoad
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

/**
 * The scripted split job (Demo mode): a pull queue over three phones with different speeds, one phone leaving
 * mid-job so its chunk is copied to another (the straggler badge), then a merge with the speed-up. Pure and
 * deterministic per seed: [frames] is the whole timeline, which the runner replays on a clock.
 */
object DemoJobScript {
    data class Frame(val atMs: Long, val progress: JobProgress)
    data class Phone(val id: String, val name: String, val itemsPerSec: Double, val link: String?, val leavesAtMs: Long? = null)

    val PHONES = listOf(
        Phone("self", "This phone", 1.0, null),
        Phone("sales", "Sales", 1.35, "LAN"),
        Phone("accounts", "Accounts", 0.9, "LAN", leavesAtMs = 20_000),
    )
    const val ITEMS_PER_CHUNK = 5
    const val OVERHEAD_MS = 350L
    const val PLACING_MS = 700L
    const val DEFAULT_ITEMS = 120
    const val NON_PHOTO_ITEMS = 40
    private const val BYTES_PER_REMOTE_CHUNK = 180_000L
    /** seconds of LLM work per table-recipe item on the reference phone (a letter or review of ~150 tokens) */
    const val LLM_SEC_PER_ITEM = 3.0

    private fun kaviraWords(recipeId: String?): Pair<String, String>? = when (recipeId) {
        "stockist_letters" -> "letter" to "letters"; "brand_reviews" -> "review" to "reviews"
        "branch_review_packs", "launch_messages" -> "branch" to "branches"; "launch_kit" -> "seed" to "seeds"; else -> null
    }

    fun frames(request: JobRequest, jobId: String, seed: Long = 7): List<Frame> {
        val photos = request.recipeId == "photo_text" || request.recipeId == null && request.inputs.isNotEmpty()
        val kavira = KaviraDemoJobs.items(request.recipeId)
        val n = when { request.inputs.isNotEmpty() -> request.inputs.size; photos -> DEFAULT_ITEMS; kavira != null -> kavira; else -> NON_PHOTO_ITEMS }
        // a table recipe's items are slow LLM work: fewer per chunk, so even 3 branches spread over 3 phones
        val perChunk = if (kavira != null) maxOf(1, minOf(ITEMS_PER_CHUNK, (n + 5) / 6)) else ITEMS_PER_CHUNK
        val secPerItem = if (kavira != null) LLM_SEC_PER_ITEM else 1.0
        val chunks = (n + perChunk - 1) / perChunk
        val phones = if (request.private) PHONES.take(1) else PHONES
        val itemsOf = IntArray(chunks) { minOf(perChunk, n - it * perChunk) }
        val (one, many) = if (photos) "photo" to "photos" else kaviraWords(request.recipeId) ?: ("item" to "items")
        val label = { i: Int -> if (itemsOf[i] == 1) "$one ${i * perChunk + 1}" else "$many ${i * perChunk + 1}–${i * perChunk + itemsOf[i]}" }

        val state = Array(chunks) { ChunkState.QUEUED }
        val node = arrayOfNulls<String>(chunks)
        val ms = arrayOfNulls<Long>(chunks)
        val queue = ArrayDeque((0 until chunks).toList())
        val busyChunk = arrayOfNulls<Int>(phones.size)
        val startAt = LongArray(phones.size)
        val finishAt = LongArray(phones.size) { Long.MAX_VALUE }
        val left = BooleanArray(phones.size)
        val done = IntArray(phones.size)
        val itemsDone = IntArray(phones.size)
        val busyMs = LongArray(phones.size)
        var remoteChunks = 0L
        var rnd = seed * 2862933555777941757L + 3037000493L
        fun jitter(): Double { rnd = rnd * 6364136223846793005L + 1442695040888963407L; return 0.85 + ((rnd ushr 11) % 1000) / 1000.0 * 0.3 }
        val singleMs = (n * secPerItem / phones.first().itemsPerSec * 1000).toLong() + chunks * OVERHEAD_MS

        val frames = ArrayList<Frame>()
        fun snapshot(t: Long, finished: Boolean = false): JobProgress {
            val elapsedS = maxOf(1L, t) / 1000.0
            return JobProgress(
                jobId = jobId, title = request.title, axis = "shard", merge = "concat",
                chunks = (0 until chunks).map { i -> ChunkView(i, "$jobId/c$i", state[i], node[i], ms[i], label(i), duplicated = state[i] == ChunkState.DUPLICATED) },
                nodes = phones.indices.filter { done[it] > 0 || busyChunk[it] != null }.map { p ->
                    NodeLoad(phones[p].id, phones[p].name, done[p], busyMs[p], Math.round(itemsDone[p] / elapsedS * 100.0) / 100.0, phones[p].link)
                },
                bytesMoved = remoteChunks * BYTES_PER_REMOTE_CHUNK,
                elapsedMs = t,
                singlePhoneMs = singleMs,
                speedup = if (finished) Math.round(singleMs.toDouble() / t * 100.0) / 100.0 else null,
                done = finished,
                resultSummary = if (finished) KaviraDemoJobs.summary(request.recipeId, phones.size) ?: summary(n, photos, phones.size) else null,
                resultRef = if (finished) "split:${resultHash(n, seed)}" else null,
                result = if (finished && photos) ocrResult(request, n, seed) else if (finished) KaviraDemoJobs.result(request.recipeId) else null,
            )
        }

        frames += Frame(0, snapshot(0))
        var t = PLACING_MS
        while (true) {
            // hand the queue to every free phone that is still here
            var assigned = false
            for (p in phones.indices) {
                if (busyChunk[p] != null || left[p] || queue.isEmpty()) continue
                val c = queue.removeFirst()
                busyChunk[p] = c; startAt[p] = t
                finishAt[p] = t + (itemsOf[c] * secPerItem / phones[p].itemsPerSec * 1000 * jitter()).toLong() + OVERHEAD_MS
                node[c] = phones[p].name
                if (state[c] != ChunkState.DUPLICATED) state[c] = ChunkState.RUNNING
                assigned = true
            }
            if (assigned) frames += Frame(t, snapshot(t))
            if (queue.isEmpty() && busyChunk.all { it == null }) break

            // the next event: a phone leaves, or the earliest chunk finishes
            val leave = phones.indices.filter { !left[it] && phones[it].leavesAtMs != null && phones[it].leavesAtMs!! > t }.minByOrNull { phones[it].leavesAtMs!! }
            val finish = phones.indices.filter { busyChunk[it] != null }.minByOrNull { finishAt[it] }
            val leaveAt = leave?.let { phones[it].leavesAtMs!! } ?: Long.MAX_VALUE
            val finishTime = finish?.let { finishAt[it] } ?: Long.MAX_VALUE
            if (leave != null && leaveAt < finishTime) {
                t = leaveAt
                left[leave] = true
                busyChunk[leave]?.let { c -> state[c] = ChunkState.DUPLICATED; queue.addFirst(c) }
                busyChunk[leave] = null; finishAt[leave] = Long.MAX_VALUE
                frames += Frame(t, snapshot(t))
            } else if (finish != null) {
                t = finishTime
                val c = busyChunk[finish]!!
                state[c] = ChunkState.DONE; node[c] = phones[finish].name; ms[c] = t - startAt[finish]
                done[finish]++; itemsDone[finish] += itemsOf[c]; busyMs[finish] += t - startAt[finish]
                if (finish != 0) remoteChunks++
                busyChunk[finish] = null; finishAt[finish] = Long.MAX_VALUE
                frames += Frame(t, snapshot(t))
            } else break
        }
        t += OVERHEAD_MS // the merge on this phone
        frames += Frame(t, snapshot(t, finished = true))
        return frames
    }

    private fun summary(n: Int, photos: Boolean, phones: Int): String =
        if (photos) "$n photos → $n pages of text on ${if (phones == 1) "1 phone" else "$phones phones"}, merged in order"
        else "$n items merged in order on ${if (phones == 1) "1 phone" else "$phones phones"}"

    /** The same items give the same hash, whichever phones ran them (the "identical to one phone" check). */
    fun resultHash(n: Int, seed: Long): String = String.format(Locale.US, "%016x", (n * 0x9E3779B97F4A7C15uL.toLong()) xor (seed * 0xC2B2AE3D27D4EB4FuL.toLong()))

    private fun ocrResult(request: JobRequest, n: Int, seed: Long): JsonObject {
        val items = JsonArray((0 until n).map { i ->
            val name = request.inputs.getOrNull(i)?.let { File(it).name } ?: InvoiceTexts.fileName(i)
            JsonObject(mapOf(
                "item" to JsonPrimitive(name),
                "lines" to JsonArray(InvoiceTexts.text(i, seed).lines.map { JsonObject(mapOf("text" to JsonPrimitive(it))) }),
            ))
        })
        return JsonObject(mapOf("stages" to JsonObject(mapOf("ocr" to JsonObject(mapOf("op" to JsonPrimitive("OCR"), "items" to items))))))
    }
}
