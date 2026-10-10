package com.hivecore.mesh

import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Link class (spec 5.6): measures every connected peer of a [Transport] with its 2 KB echo at session start and
 * every [intervalMs] (5 min), overlays `metered` from the device ([metered]: mobile data right now?) and reports
 * [com.hivecore.core.Link] to [onLink]. The profile layer puts the result in `Profile.link`; the transfer rule,
 * deadlines, the candidate filter and the metered policy read it from there.
 *
 * Works for any transport: those that can measure override `measureLink` ([RelayTransport], [DataChannelTransport],
 * [SecureTransport]); the others return the ladder's planning figure for their kind.
 */
class LinkProbe(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val metered: () -> Boolean,
    private val intervalMs: Long = RelayProtocol.LINK_MEASURE_INTERVAL_MS,
    private val onLink: (PeerEndpoint, Link) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "LinkProbe"
        const val MAX_KBPS = 1_000_000

        /** A [Link] from one echo: [bytes] went there and back in [rttMs]. */
        fun fromEcho(kind: LinkKind, bytes: Int, rttMs: Long, metered: Boolean): Link {
            val rtt = maxOf(1L, rttMs)
            return Link(kind, (2L * bytes * 8 / rtt).coerceIn(1L, MAX_KBPS.toLong()).toInt(), rtt.toInt(), metered)
        }
    }

    private val linksState = MutableStateFlow<Map<String, Link>>(emptyMap())
    /** Latest measurement per endpoint key (`transport/endpointId`). */
    val links: StateFlow<Map<String, Link>> = linksState
    private val jobs = HashMap<String, Job>()
    private var collector: Job? = null

    fun start() {
        if (collector != null) return
        collector = scope.launch { transport.connected.collect { sync(it) } }
    }

    fun stop() {
        collector?.cancel(); collector = null
        synchronized(jobs) { jobs.values.forEach { it.cancel() }; jobs.clear() }
    }

    fun link(endpoint: PeerEndpoint): Link? = linksState.value[key(endpoint)]

    /** Measures now (outside the schedule) and records the result. */
    suspend fun probeNow(endpoint: PeerEndpoint): Link {
        val measured = try { transport.measureLink(endpoint) } catch (e: Exception) {
            SLog.w(TAG, "measure ${endpoint.displayName}: ${e.message}"); Link.defaultFor(transport.kind)
        }
        val link = measured.copy(metered = runCatching { metered() }.getOrDefault(false))
        linksState.value = linksState.value + (key(endpoint) to link)
        runCatching { onLink(endpoint, link) }.onFailure { SLog.w(TAG, "onLink: ${it.message}") }
        return link
    }

    private fun key(e: PeerEndpoint) = "${e.transport}/${e.endpointId}"

    private fun sync(eps: List<PeerEndpoint>) {
        synchronized(jobs) {
            val now = eps.associateBy { key(it) }
            for (k in jobs.keys.toList()) if (k !in now) { jobs.remove(k)?.cancel(); linksState.value = linksState.value - k }
            for ((k, ep) in now) if (k !in jobs) jobs[k] = scope.launch {
                while (isActive) {
                    probeNow(ep)
                    if (intervalMs <= 0) break
                    delay(intervalMs)
                }
            }
        }
    }
}
