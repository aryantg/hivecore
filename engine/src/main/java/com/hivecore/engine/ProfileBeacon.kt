package com.hivecore.engine

import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.WorkerPolicy
import com.hivecore.mesh.BeaconCadence
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
 * Spec 4.6 / 5.5 / 20.2: broadcasts this phone's PROFILE on the [BeaconCadence] of the links its peers are reached
 * over (every 15 s while every peer is on a LAN-class link, every 30 s once one is reached through the relay or a
 * WebRTC channel) and right away whenever the [WorkerPolicy] profile changes, and keeps the latest profile per peer.
 * A peer profile counts as fresh for the stale window of its own link (45 s on LAN, 90 s on WAN: three missed
 * heartbeats) after it was *received* (local clock, so a skewed peer clock cannot keep a dead phone alive). The
 * received profile's `link` is filled from [linkOf] (the session layer's measurement, spec 5.6), so the scheduler and
 * the work queue see how each phone is reached from here. [snapshot] = this phone's own profile + every fresh peer
 * profile: the scheduler's input.
 *
 * @param broadcast  sends one PROFILE body to every connected peer (MeshRouterPort.broadcast)
 * @param queueDepth the local TaskRunner's depth, merged into the sent profile
 * @param intervalMs / staleMs  fixed overrides of the cadence (tests); null = per link class
 * @param linkOf     the measured link to a peer (`MeshRouter.secure(name).link(peer)`); null = the peer's own claim
 */
class ProfileBeacon(
    private val selfId: String,
    private val policy: WorkerPolicy,
    private val broadcast: suspend (MsgType, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val queueDepth: StateFlow<Int>? = null,
    val intervalMs: Long? = null,
    val staleMs: Long? = null,
    /** Last touch on the sent profile (the integrator adds dataVersion and BENCH p50 here). */
    private val decorate: (Profile) -> Profile = { it },
    private val linkOf: (String) -> Link? = { null },
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
     * Starts the change collector and, when [periodic], the heartbeat. The collector sends the current profile
     * at once (StateFlow replays it). Tests that drain the scheduler pass `periodic = false` and call [announce].
     */
    fun start(scope: CoroutineScope, periodic: Boolean = true) {
        synchronized(lock) { if (jobs.isNotEmpty()) return }
        val collector = scope.launch { policy.profile.collect { announce() } }
        val ticker = if (periodic) scope.launch {
            while (isActive) { delay(currentIntervalMs()); announce() }
        } else null
        synchronized(lock) { jobs += listOfNotNull(collector, ticker) }
    }

    fun stop() = synchronized(lock) { jobs.forEach { it.cancel() }; jobs.clear() }

    /** The heartbeat period now: the slowest cadence among the fresh peers' link classes (15 s with no WAN peer). */
    fun currentIntervalMs(): Long = intervalMs ?: BeaconCadence.intervalFor(peerKinds())

    /** The stale window of [nodeId]'s profile: by the link it is reached over (45 s LAN-class, 90 s WAN). */
    fun staleMsOf(nodeId: String): Long = staleMs ?: BeaconCadence.staleFor(kindOf(nodeId))

    private fun peerKinds(): List<LinkKind> = synchronized(lock) {
        latest.values.filter { it.profile.nodeId != selfId && fresh(it) }.map { kindOf(it.profile) }
    }

    private fun kindOf(nodeId: String): LinkKind = synchronized(lock) { latest[nodeId]?.profile }?.let { kindOf(it) } ?: LinkKind.LAN

    private fun kindOf(p: Profile): LinkKind = p.link?.kind ?: LinkKind.LAN

    /** Sends this phone's profile now. */
    suspend fun announce() {
        try {
            broadcast(MsgType.PROFILE, MeshJson.encode(ProfileBody(self())))
        } catch (e: Exception) {
            SLog.w(TAG, "PROFILE broadcast failed: ${e.message}")
        }
    }

    /** A verified PROFILE from [from] (the router already checked profile.nodeId == from); `link` is filled from [linkOf]. */
    fun onProfile(from: String, body: String) {
        val p = try { MeshJson.decode<ProfileBody>(body).profile } catch (e: Exception) {
            SLog.w(TAG, "PROFILE from ${from.take(8)} does not parse: ${e.message}"); return
        }
        if (p.nodeId != from) return
        val link = try { linkOf(from) } catch (e: Exception) { SLog.w(TAG, "link of ${from.take(8)}: ${e.message}"); null }
        put(if (link != null) p.copy(link = link) else p)
    }

    /** A peer the relay reports offline (spec 5.5): its profile leaves the snapshot at once instead of after the stale window. */
    fun forget(nodeId: String) { synchronized(lock) { latest.remove(nodeId) } }

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

    /** This phone's profile plus every peer profile received within its stale window, sorted by nodeId. */
    fun snapshot(): List<Profile> {
        val peers = synchronized(lock) { latest.values.filter { fresh(it) && it.profile.nodeId != selfId }.map { it.profile } }
        return (peers + self()).sortedBy { it.nodeId }
    }

    private fun fresh(s: Seen): Boolean = clock() - s.receivedAt <= (staleMs ?: BeaconCadence.staleFor(kindOf(s.profile)))

    companion object { private const val TAG = "ProfileBeacon" }
}
