package com.hivecore.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Presence, sleep and wake over the relay (spec 5.5, 14). The relay socket itself is held by the worker foreground
 * service (another track); this class gives the orchestrator the pieces:
 *
 * - [members] mirrors the relay's presence per roster member;
 * - [ensureAwake] sends `wake {nodeId}` for an offline member and waits the 20 s grace for it to come back;
 * - [isAbsent] is the "node asleep" rule: offline, and the grace since the last wake has run out;
 * - [awaitRelay] is the "relay down" rule: WAN attempts wait up to 60 s for the socket, then fault
 *   ([RelayTransport.relayDown] is the flag the queue can poll).
 */
class RelayPresence(
    private val relay: RelayTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val graceMs: Long = RelayProtocol.WAKE_GRACE_MS,
) {
    /** [online] as the relay last said; [since] relay time of that change; [wakeSentAt] local time of our last wake. */
    data class State(val online: Boolean, val since: Long, val wakeSentAt: Long? = null)

    private val statesFlow = MutableStateFlow<Map<String, State>>(emptyMap())
    val members: StateFlow<Map<String, State>> = statesFlow
    /** True while the relay socket is gone (reconnecting with backoff). */
    val relayDown: StateFlow<Boolean> get() = relay.relayDown

    init {
        relay.onPresence { id, online, since -> update(id, online, since) }
        scope.launch {
            relay.members.collect { map ->
                map.forEach { (id, online) -> if (statesFlow.value[id]?.online != online) update(id, online, relay.since(id) ?: clock()) }
            }
        }
    }

    private fun update(id: String, online: Boolean, since: Long) {
        synchronized(this) {
            val prev = statesFlow.value[id]
            statesFlow.value = statesFlow.value + (id to State(online, since, if (online) null else prev?.wakeSentAt))
        }
    }

    /** Online as far as we can tell: the relay said so, and our own socket is up (while it is down nobody is reachable). */
    fun isOnline(nodeId: String): Boolean = relay.authenticated.value && statesFlow.value[nodeId]?.online == true

    /** Offline, and never woken or woken more than [graceMs] ago: treated as vanished (requeue its chunks). */
    fun isAbsent(nodeId: String): Boolean {
        val s = statesFlow.value[nodeId] ?: return true
        if (s.online) return false
        val w = s.wakeSentAt ?: return true
        return clock() - w >= graceMs
    }

    /** Offline with a wake in flight (less than [graceMs] old). */
    fun isWaking(nodeId: String): Boolean {
        val s = statesFlow.value[nodeId] ?: return false
        return !s.online && s.wakeSentAt != null && clock() - s.wakeSentAt < graceMs
    }

    /**
     * Online now, or after a wake within the grace. Waits for the relay socket first (up to the outage budget) when it
     * is down. False means the node is absent for this attempt.
     */
    suspend fun ensureAwake(nodeId: String, outageWaitMs: Long = RelayProtocol.OUTAGE_WAIT_MS): Boolean {
        if (isOnline(nodeId)) return true
        if (!relay.authenticated.value && !relay.awaitOnline(outageWaitMs)) return false
        if (isOnline(nodeId)) return true
        if (!relay.wake(nodeId)) return false
        synchronized(this) {
            val prev = statesFlow.value[nodeId] ?: State(false, clock())
            statesFlow.value = statesFlow.value + (nodeId to prev.copy(wakeSentAt = clock()))
        }
        return withTimeoutOrNull(graceMs) { statesFlow.first { it[nodeId]?.online == true } } != null
    }

    /** Waits for the relay socket up to [timeoutMs] (default 60 s); false means WAN attempts fault (spec 14). */
    suspend fun awaitRelay(timeoutMs: Long = RelayProtocol.OUTAGE_WAIT_MS): Boolean = relay.awaitOnline(timeoutMs)
}
