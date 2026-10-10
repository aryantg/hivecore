package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Bluetooth-only transport over plain RFCOMM sockets between phones bonded in Settings (plan 10, 2.3).
 * No Wi-Fi, no router: works in airplane mode with Bluetooth on.
 *
 * - A server loop accepts incoming links; a client loop tries every bonded address every [connectIntervalMs]
 *   until a link exists (refusals from bonded laptops or earbuds are normal and stay at debug level).
 * - Framing: 4-byte big-endian length + bytes, at most [MAX_FRAME]. An oversize header closes that link only.
 * - One reader per link emits [Inbound] with `PeerEndpoint("bt", address, name)`; [send] writes under a per-link lock.
 * - Both phones may connect to each other at once; both links are kept and [send] uses the newest live one.
 * - A dropped link is retried after [backoffMinMs], doubling to [backoffMaxMs].
 * - [pairingRequests] never emits: RFCOMM has no auth digits, MeshRouter raises pairing on the first HELLO.
 *
 * Everything but the sockets is testable on the JVM through [BtEndpoint] / [BtLink] (see Bt*Test in src/test).
 */
class BtRfcommTransport(
    private val endpoint: BtEndpoint,
    private val scope: CoroutineScope,
    localName: String = "",
    private val connectIntervalMs: Long = 5_000,
    private val backoffMinMs: Long = 1_000,
    private val backoffMaxMs: Long = 5_000,
) : Transport, Disconnectable {
    companion object {
        const val NAME = "bt"
        /** Largest frame we send or accept (bytes of payload, header excluded); matches MeshRouter.MAX_ENVELOPE. */
        const val MAX_FRAME = 64 * 1024
        private const val TAG = "BtRfcomm"
        private const val HEADER = 4
    }

    override val name: String = NAME
    var localName: String = localName
        private set

    private val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 16)
    override val pairingRequests: SharedFlow<PairingRequest> = pairingFlow
    private val statusState = MutableStateFlow("stopped")
    override val status: StateFlow<String> = statusState

    private inner class Link(val bt: BtLink, val outbound: Boolean) {
        val id: Long = seq.incrementAndGet()
        val address: String = bt.remoteAddress
        val peer = PeerEndpoint(NAME, address, bt.remoteName)
        val writeLock = Any()
        @Volatile var dead = false
        @Volatile var reader: Job? = null
    }

    private val seq = AtomicLong()
    private val lock = Any()
    private val links = LinkedHashMap<Long, Link>()            // guarded by lock
    private val backoff = HashMap<String, Long>()              // address → current reconnect delay (only after a drop)
    private val nextAttempt = HashMap<String, Long>()          // address → earliest time for the next connect
    private val jobs = ArrayList<Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var running = false
    @Volatile private var pairingMode = false
    @Volatile private var bondedCount = 0

    override suspend fun start(localName: String) {
        if (localName.isNotBlank()) this.localName = localName
        synchronized(lock) {
            if (running) return
            running = true
            jobs += scope.launch(Dispatchers.IO) { acceptLoop() }
            jobs += scope.launch(Dispatchers.Default) { connectLoop() }
        }
        SLog.d(TAG, "started as ${this.localName}")
        refresh()
    }

    override suspend fun stop() {
        val toClose: List<Link>
        val toCancel: List<Job>
        synchronized(lock) {
            if (!running) return
            running = false
            toClose = links.values.toList(); links.clear()
            toCancel = jobs.toList(); jobs.clear()
            backoff.clear(); nextAttempt.clear()
        }
        toCancel.forEach { it.cancel() }
        endpoint.close()
        toClose.forEach { it.dead = true; it.bt.close(); it.reader?.cancel() }
        connectedState.value = emptyList()
        statusState.value = "stopped"
        SLog.d(TAG, "stopped")
    }

    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        if (bytes.size > MAX_FRAME) return Result.failure(IllegalArgumentException("frame of ${bytes.size} B exceeds $MAX_FRAME"))
        val candidates = synchronized(lock) { links.values.filter { !it.dead && it.address == endpoint.endpointId }.sortedByDescending { it.id } }
        if (candidates.isEmpty()) return Result.failure(IOException("no Bluetooth link to ${endpoint.displayName} (${endpoint.endpointId})"))
        val frame = ByteArray(HEADER + bytes.size)
        val n = bytes.size
        frame[0] = (n ushr 24).toByte(); frame[1] = (n ushr 16).toByte(); frame[2] = (n ushr 8).toByte(); frame[3] = n.toByte()
        System.arraycopy(bytes, 0, frame, HEADER, n)
        var last: Throwable? = null
        for (link in candidates) {
            try {
                withContext(Dispatchers.IO) {
                    synchronized(link.writeLock) {
                        link.bt.output.write(frame)
                        link.bt.output.flush()
                    }
                }
                return Result.success(Unit)
            } catch (e: IOException) {
                SLog.w(TAG, "write to ${link.address} failed (${e.message}), dropping link #${link.id}")
                last = e
                removeLink(link)
            }
        }
        return Result.failure(last ?: IOException("send failed"))
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) {
        // Pairing over RFCOMM is decided by MeshRouter on the first HELLO; nothing to accept at the socket level.
        SLog.d(TAG, "acceptPairing(${req.endpoint.endpointId}, $accept) ignored: pairing happens at the router")
    }

    override fun setPairingMode(on: Boolean) {
        pairingMode = on
        SLog.d(TAG, "pairing mode ${if (on) "on" else "off"} (router decides over Bluetooth)")
    }

    /** Closes every link to [endpoint] (the router saw an unpinned HELLO outside pairing mode on it). */
    override fun disconnect(endpoint: PeerEndpoint) {
        val drop = synchronized(lock) { links.values.filter { it.address == endpoint.endpointId } }
        drop.forEach { removeLink(it) }
        SLog.d(TAG, "closed ${drop.size} link(s) to ${endpoint.displayName} (${endpoint.endpointId})")
    }

    // ---- server side -------------------------------------------------------------------------------------------

    private suspend fun acceptLoop() {
        var wait = backoffMinMs
        while (currentCoroutineIsActive() && running) {
            try {
                val link = endpoint.accept()
                wait = backoffMinMs
                addLink(link, outbound = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!running) return
                SLog.d(TAG, "accept failed (${e.message}); retry in $wait ms")
                refresh()
                kotlinx.coroutines.delay(wait)
                wait = (wait * 2).coerceAtMost(backoffMaxMs)
            }
        }
    }

    // ---- client side -------------------------------------------------------------------------------------------

    private suspend fun connectLoop() {
        while (currentCoroutineIsActive() && running) {
            var earliest = now() + connectIntervalMs
            if (endpoint.available()) {
                val bonded = try { endpoint.bonded() } catch (e: Exception) { SLog.w(TAG, "bonded(): $e"); emptyList() }
                bondedCount = bonded.size
                for ((address, _) in bonded) {
                    if (!running) return
                    if (hasLink(address)) continue
                    val due = synchronized(lock) { nextAttempt[address] ?: 0L }
                    if (due > now()) { earliest = minOf(earliest, due); continue }
                    val link = try {
                        endpoint.connect(address)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SLog.d(TAG, "connect $address threw $e"); null
                    }
                    if (link != null) {
                        synchronized(lock) { backoff.remove(address); nextAttempt.remove(address) }
                        addLink(link, outbound = true)
                    } else {
                        val delay = synchronized(lock) {
                            val b = backoff[address]
                            val next = if (b == null) connectIntervalMs else (b * 2).coerceAtMost(backoffMaxMs).also { backoff[address] = it }
                            nextAttempt[address] = now() + next
                            next
                        }
                        earliest = minOf(earliest, now() + delay)
                    }
                }
            }
            refresh()
            val sleep = (earliest - now()).coerceAtLeast(50L)
            withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    // ---- links -------------------------------------------------------------------------------------------------

    private fun hasLink(address: String): Boolean = synchronized(lock) { links.values.any { !it.dead && it.address == address } }

    private fun addLink(bt: BtLink, outbound: Boolean) {
        val link = Link(bt, outbound)
        synchronized(lock) {
            if (!running) { bt.close(); return }
            links[link.id] = link
            link.reader = scope.launch(Dispatchers.IO) { readLoop(link) }
        }
        SLog.d(TAG, "link #${link.id} ${if (outbound) "to" else "from"} ${link.peer.displayName} (${link.address}); ${liveCount()} live")
        refresh()
    }

    private fun removeLink(link: Link) {
        val wasLive: Boolean
        synchronized(lock) {
            wasLive = links.remove(link.id) != null && !link.dead
            link.dead = true
            if (running && wasLive && links.values.none { !it.dead && it.address == link.address }) {
                backoff[link.address] = backoffMinMs
                nextAttempt[link.address] = now() + backoffMinMs
            }
        }
        link.bt.close()
        if (wasLive) {
            SLog.d(TAG, "link #${link.id} to ${link.address} closed; ${liveCount()} live")
            wake.trySend(Unit)
            refresh()
        }
    }

    private suspend fun readLoop(link: Link) {
        val input = DataInputStream(link.bt.input)
        try {
            while (!link.dead && running) {
                val len = input.readInt()
                if (len < 0 || len > MAX_FRAME) {
                    SLog.w(TAG, "oversize frame header $len B from ${link.address} on link #${link.id}; closing that link")
                    break
                }
                val buf = ByteArray(len)
                input.readFully(buf)
                inboundFlow.emit(Inbound(link.peer, buf))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!link.dead && running) SLog.d(TAG, "link #${link.id} to ${link.address} dropped: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            removeLink(link)
        }
    }

    private fun liveCount(): Int = synchronized(lock) { links.values.count { !it.dead } }

    private fun refresh() {
        val live = synchronized(lock) { links.values.filter { !it.dead }.sortedByDescending { it.id } }
        connectedState.value = live.map { it.peer }.distinctBy { it.endpointId }
        statusState.value = when {
            !running -> "stopped"
            !endpoint.available() -> "Bluetooth off"
            live.isEmpty() -> "Bluetooth · 0 links · $bondedCount bonded"
            else -> "Bluetooth · ${live.size} link${if (live.size == 1) "" else "s"}"
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private suspend fun currentCoroutineIsActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive
}
