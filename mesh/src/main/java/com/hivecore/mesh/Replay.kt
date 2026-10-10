package com.hivecore.mesh

/**
 * Plan 10 replay guard: a seen-set of `(requester, nonce)` (last [capacity]) and `|ts - now| <= windowMs`
 * (no NTP in airplane mode, so the window is generous). [check] returns null when the message is fresh and
 * records it; otherwise a short reason for the log.
 */
class ReplayGuard(
    private val capacity: Int = 1000,
    private val windowMs: Long = 10 * 60_000L,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun check(requester: String, nonce: String, ts: Long): String? {
        val skew = ts - now()
        if (skew > windowMs || skew < -windowMs) return "ts outside window (${skew / 1000}s)"
        val key = "$requester|$nonce"
        if (!seen.add(key)) return "replayed nonce"
        while (seen.size > capacity) {
            val oldest = seen.iterator().next()
            seen.remove(oldest)
        }
        return null
    }

    @Synchronized fun size(): Int = seen.size
}
