package com.hivecore.cloud

/** What the consent sheet shows (spec 15.2 step 6): "Send 12 KB of OCR text from 3 released items to <model>? Nothing private is included." */
data class ConsentRequest(
    val jobId: String,
    val stepId: String,
    val slug: String,
    val bytes: Long,
    val items: Int,
    /** "public" | "released" (private never reaches the sheet) */
    val lineage: String,
    val trigger: String,
    val estimateInr: Double,
) {
    val sentence: String get() = "Send ${kb(bytes)} of ${if (lineage == "released") "text from $items released item${if (items == 1) "" else "s"}" else "public text"} to $slug? Nothing private is included."
    private fun kb(b: Long) = if (b < 1024) "$b B" else "${(b + 512) / 1024} KB"
}

/** The user's answer: allow once (remembered for this job), allow this month, or keep local. */
enum class ConsentDecision { ALLOW_ONCE, ALLOW_MONTH, KEEP_LOCAL }

/** The UI seam; `:app` shows a bottom sheet, tests script the answer. */
fun interface ConsentSheet {
    suspend fun ask(req: ConsentRequest): ConsentDecision
}

/** Remembers consent per job or per month (spec 15.2 step 6). */
interface ConsentStore {
    fun allowed(jobId: String, month: String): Boolean
    fun allowJob(jobId: String)
    fun allowMonth(month: String)
}

class MemoryConsentStore : ConsentStore {
    val jobs = HashSet<String>()
    val months = HashSet<String>()
    @Synchronized override fun allowed(jobId: String, month: String): Boolean = jobId in jobs || month in months
    @Synchronized override fun allowJob(jobId: String) { jobs += jobId }
    @Synchronized override fun allowMonth(month: String) { months += month }
}
