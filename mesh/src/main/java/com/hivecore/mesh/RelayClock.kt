package com.hivecore.mesh

/**
 * Relay time (spec 5.3 "Time"): every relay frame that carries `serverTs` refines an offset between this phone's
 * clock and the relay's, so the replay guard's ±10 min window (spec 20.2) holds across time zones and drifted
 * clocks. [now] is the provider the integration wave hands to `ReplayGuard(now = relayClock::now)` and to the
 * timestamps of nonce-bearing messages.
 *
 * A pong sample is RTT-corrected (`serverTs - (sentAt + receivedAt) / 2`) and always wins over a one-way sample
 * (challenge, auth_ok, presence), which can only be trusted to the RTT. Samples with an RTT above [maxRttMs] are
 * ignored unless nothing better exists yet.
 */
class RelayClock(private val local: () -> Long = { System.currentTimeMillis() }, private val maxRttMs: Long = 2_000) {
    @Volatile var offsetMs: Long = 0L
        private set
    /** True once any sample arrived; false means [now] is the local clock. */
    @Volatile var synced: Boolean = false
        private set
    /** RTT of the sample the offset came from, or -1 for a one-way sample. */
    @Volatile var sampleRttMs: Long = -1
        private set

    fun now(): Long = local() + offsetMs

    /** A one-way sample: the relay said `serverTs` and it arrived now. Used until an RTT-corrected one exists. */
    @Synchronized
    fun syncOneWay(serverTs: Long) {
        if (synced && sampleRttMs >= 0) return
        offsetMs = serverTs - local()
        synced = true
        sampleRttMs = -1
    }

    /** A round-trip sample from a pong: the ping left at [sentAt] (local), the pong carried [serverTs] and arrived at [receivedAt]. */
    @Synchronized
    fun syncRoundTrip(serverTs: Long, sentAt: Long, receivedAt: Long = local()) {
        val rtt = (receivedAt - sentAt).coerceAtLeast(0)
        if (rtt > maxRttMs && synced && sampleRttMs in 0..rtt) return
        offsetMs = serverTs - (sentAt + receivedAt) / 2
        synced = true
        sampleRttMs = rtt
    }

    @Synchronized
    fun reset() { offsetMs = 0; synced = false; sampleRttMs = -1 }
}
