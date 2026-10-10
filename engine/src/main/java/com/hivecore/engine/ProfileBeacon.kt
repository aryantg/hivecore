package com.hivecore.engine

import com.hivecore.mesh.MeshRouterPort
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.WorkerPolicy
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ProfileBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Swarm plan §6.1: broadcasts this phone's PROFILE every [intervalMs] (15 s) and right away whenever the
 * [WorkerPolicy] profile changes, and keeps the latest profile per peer. A peer profile counts as fresh for
 * [staleMs] (45 s, three missed heartbeats) after it was *received* (local clock, so a skewed peer clock cannot keep a
 * dead phone alive). [snapshot] = this phone's own profile + every fresh peer profile: the scheduler's input.
 *
 * @param broadcast  sends one PROFILE body to every connected peer (MeshRouterPort.broadcast)
 * @param queueDepth the local TaskRunner's depth, merged into the sent profile
 */
class ProfileBeacon(
    private val selfId: String,
    private val policy: WorkerPolicy,
    private val broadcast: suspend (MsgType, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val queueDepth: StateFlow<Int>? = null,
    val intervalMs: Long = 15_000L,
    val staleMs: Long = 45_000L,
    /** Last touch on the sent profile (the integrator adds dataVersion and BENCH p50 here). */
    private val decorate: (Profile) -> Profile = { it },
) {
    private class Seen(val profile: Profile, val receivedAt: Long)

    private val lock = Any()
    private val latest = HashMap<String, Seen>()
    private val jobs = ArrayList<Job>()

    /** This phone's profile as it is sent now (nodeId forced to self, current queue depth and time). */
    fun self(): Profile {
        val base = policy.profile.value
        val p = try { decorate(base) } catch (e: Exception) { SLog.w(TAG, "profile decorate failed: ${e.message}"); base }
        return p.copy(nodeId = selfId, ts = clock(), queueDepth = queueDepth?.value ?: base.queueDepth)
    }

    /**
     * Starts the change collector and, when [periodic], the 15 s heartbeat. The collector sends the current profile
     * at once (StateFlow replays it). Tests that drain the scheduler pass `periodic = false` and call [announce].
     */
    fun start(scope: CoroutineScope, periodic: Boolean = true) {
        synchronized(lock) { if (jobs.isNotEmpty()) return }
        val collector = scope.launch { policy.profile.collect { announce() } }
        val ticker = if (periodic) scope.launch {
            while (isActive) { delay(intervalMs); announce() }
        } else null
        synchronized(lock) { jobs += listOfNotNull(collector, ticker) }
    }

    fun stop() = synchronized(lock) { jobs.forEach { it.cancel() }; jobs.clear() }

    /** Sends this phone's profile now. */
    suspend fun announce() {
        try {
            broadcast(MsgType.PROFILE, MeshJson.encode(ProfileBody(self())))
        } catch (e: Exception) {
            SLog.w(TAG, "PROFILE broadcast failed: ${e.message}")
        }
    }

    /** A verified PROFILE from [from] (the router already checked profile.nodeId == from). */
    fun onProfile(from: String, body: String) {
        val p = try { MeshJson.decode<ProfileBody>(body).profile } catch (e: Exception) {
            SLog.w(TAG, "PROFILE from ${from.take(8)} does not parse: ${e.message}"); return
        }
        if (p.nodeId != from) return
        put(p)
    }

    /** Records [p] as received now; an older `ts` than the one held is ignored. */
    fun put(p: Profile) = synchronized(lock) {
        val prev = latest[p.nodeId]
        if (prev == null || p.ts >= prev.profile.ts) latest[p.nodeId] = Seen(p, clock())
    }

    /** The latest fresh profile of [nodeId] (this phone: [self]). */
    fun profileOf(nodeId: String): Profile? =
        if (nodeId == selfId) self() else synchronized(lock) { latest[nodeId]?.takeIf { fresh(it) }?.profile }

    /** The last profile received from [nodeId], however old (this phone: [self]); for data versions, not scheduling. */
    fun lastOf(nodeId: String): Profile? =
        if (nodeId == selfId) self() else synchronized(lock) { latest[nodeId]?.profile }

    /** This phone's profile plus every peer profile received within [staleMs], sorted by nodeId. */
    fun snapshot(): List<Profile> {
        val peers = synchronized(lock) { latest.values.filter { fresh(it) && it.profile.nodeId != selfId }.map { it.profile } }
        return (peers + self()).sortedBy { it.nodeId }
    }

    private fun fresh(s: Seen): Boolean = clock() - s.receivedAt <= staleMs

    companion object { private const val TAG = "ProfileBeacon" }
}
