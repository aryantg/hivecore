package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec 5.1 rank 1: LAN TCP between phones on one Wi-Fi, discovered by NSD/mDNS, `kind = LAN`.
 *
 * - [LanDiscovery] lists the advertised peers; a connect loop opens one link per peer. The phone whose nodeId prefix
 *   sorts first connects (as the Nearby transport does, so two phones do not cross-connect); the other side still
 *   connects after [fallbackMs] in case discovery is one-sided. Both links are kept when it happens; [send] uses the
 *   newest live one.
 * - Framing: 4-byte big-endian length + bytes, at most [MAX_FRAME] (a 64 KB envelope plus the session record
 *   overhead). An oversize header closes that link only.
 * - The first frame on every link is a plaintext preamble carrying the sender's `<name>|<nodeId prefix>` (the same
 *   string mDNS already advertises), so the accepting side knows which peer connected; the endpoint id is
 *   `<prefix>@<ip>` so both directions of one pair collapse into one [PeerEndpoint]. Frames tagged [TAG_CONTROL] are
 *   the transport's own (preamble, 2 KB echo) and never reach [inbound]; everything else is handed up unchanged,
 *   which for a wired router means session frames only.
 * - A dropped link is retried after [backoffMinMs], doubling to [backoffMaxMs] (spec 20.2: 1 s to 30 s).
 * - [measureLink] is a 2 KB echo on the link (spec 5.6); the ladder default when there is no link or no answer.
 * - [pairingRequests] never emits: pairing happens at the router (HELLO digits) or in the trust layer (SAS).
 *
 * Everything but discovery runs unchanged on the JVM over loopback sockets ([SocketLanEndpoint]).
 */
class LanTransport(
    private val endpoint: LanEndpoint,
    private val discovery: LanDiscovery,
    private val scope: CoroutineScope,
    localName: String = "",
    private val connectIntervalMs: Long = 5_000,
    private val backoffMinMs: Long = 1_000,
    private val backoffMaxMs: Long = 30_000,
    private val fallbackMs: Long = 8_000,
    private val echoTimeoutMs: Long = 3_000,
) : Transport, Disconnectable {
    companion object {
        const val NAME = "lan"
        /** Largest frame on the wire: a MeshRouter.MAX_ENVELOPE envelope inside one sealed session record. */
        const val MAX_FRAME = 64 * 1024 + Session.OVERHEAD
        /** First byte of a transport-level frame (never a session tag 0xA1..0xB0 nor '{'). */
        const val TAG_CONTROL: Byte = 0xC0.toByte()
        const val CTL_PREAMBLE: Byte = 1
        const val CTL_PING: Byte = 2
        const val CTL_PONG: Byte = 3
        const val ECHO_BYTES = 2048
        private const val TAG = "LanTcp"
        private const val HEADER = 4
        private const val MAX_KBPS = 1_000_000

        /** `HR|a1b2c3d4` -> `a1b2c3d4`; a name without the separator yields the whole name. */
        fun prefixOf(serviceName: String): String = serviceName.substringAfter('|', serviceName)
        fun displayNameOf(serviceName: String): String = serviceName.substringBefore('|')
        /** The lower prefix opens the connection. */
        fun initiates(localName: String, remoteName: String): Boolean = prefixOf(localName) < prefixOf(remoteName)
        fun endpointId(serviceName: String, host: String): String = "${prefixOf(serviceName)}@$host"
    }

    override val name: String = NAME
    override val kind: LinkKind = LinkKind.LAN
    var localName: String = localName
        private set
    /** The listening port after [start] (-1 before). */
    val port: Int get() = endpoint.localPort

    private val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 16)
    override val pairingRequests: SharedFlow<PairingRequest> = pairingFlow
    private val statusState = MutableStateFlow("stopped")
    override val status: StateFlow<String> = statusState

    private inner class LinkState(val lan: LanLink, val outbound: Boolean) {
        val id: Long = seq.incrementAndGet()
        val host: String = lan.remoteHost
        /** Set by the preamble; null until the peer named itself. */
        @Volatile var peer: PeerEndpoint? = null
        @Volatile var serviceName: String = ""
        val writeLock = Any()
        @Volatile var dead = false
        @Volatile var reader: Job? = null
        val pongs = HashMap<Long, CompletableDeferred<Long>>()
    }

    private val seq = AtomicLong()
    private val lock = Any()
    private val links = LinkedHashMap<Long, LinkState>()        // guarded by lock
    private val backoff = HashMap<String, Long>()               // endpoint id → current reconnect delay
    private val nextAttempt = HashMap<String, Long>()           // endpoint id → earliest time for the next connect
    private val firstSeen = HashMap<String, Long>()             // endpoint id → when discovery first listed it
    private val jobs = ArrayList<Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var running = false

    override suspend fun start(localName: String) {
        if (localName.isNotBlank()) this.localName = localName
        val port: Int
        synchronized(lock) {
            if (running) return
            running = true
            port = try { endpoint.listen() } catch (e: IOException) { SLog.w(TAG, "listen failed: $e"); -1 }
            jobs += scope.launch(Dispatchers.IO) { acceptLoop() }
            jobs += scope.launch(Dispatchers.Default) { connectLoop() }
        }
        discovery.start(this.localName, port)
        SLog.d(TAG, "started as ${this.localName} on port $port")
        refresh()
    }

    override suspend fun stop() {
        val toClose: List<LinkState>
        val toCancel: List<Job>
        synchronized(lock) {
            if (!running) return
            running = false
            toClose = links.values.toList(); links.clear()
            toCancel = jobs.toList(); jobs.clear()
            backoff.clear(); nextAttempt.clear(); firstSeen.clear()
        }
        discovery.stop()
        toCancel.forEach { it.cancel() }
        endpoint.close()
        toClose.forEach { it.dead = true; it.lan.close(); it.reader?.cancel() }
        connectedState.value = emptyList()
        statusState.value = "stopped"
        SLog.d(TAG, "stopped")
    }

    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        if (bytes.size > MAX_FRAME) return Result.failure(IllegalArgumentException("frame of ${bytes.size} B exceeds $MAX_FRAME"))
        val candidates = linksTo(endpoint.endpointId)
        if (candidates.isEmpty()) return Result.failure(IOException("no LAN link to ${endpoint.displayName} (${endpoint.endpointId})"))
        var last: Throwable? = null
        for (link in candidates) {
            try {
                write(link, bytes)
                return Result.success(Unit)
            } catch (e: IOException) {
                SLog.w(TAG, "write to ${link.host} failed (${e.message}), dropping link #${link.id}")
                last = e
                removeLink(link)
            }
        }
        return Result.failure(last ?: IOException("send failed"))
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) {
        SLog.d(TAG, "acceptPairing(${req.endpoint.endpointId}, $accept) ignored: pairing happens above the transport")
    }

    override fun setPairingMode(on: Boolean) { SLog.d(TAG, "pairing mode ${if (on) "on" else "off"} (decided above the transport)") }

    /** Spec 5.6: a 2 KB echo on the newest live link to [endpoint]; the LAN default when there is none or it does not answer. */
    override suspend fun measureLink(endpoint: PeerEndpoint): Link {
        val link = linksTo(endpoint.endpointId).firstOrNull() ?: return Link.defaultFor(kind)
        val id = System.nanoTime()
        val d = CompletableDeferred<Long>()
        synchronized(link.pongs) { link.pongs[id] = d; if (link.pongs.size > 8) link.pongs.remove(link.pongs.keys.first()) }
        val frame = ByteArray(ECHO_BYTES).also { it[0] = TAG_CONTROL; it[1] = CTL_PING; ByteBuffer.wrap(it, 2, 8).putLong(id) }
        val t0 = System.nanoTime()
        try { write(link, frame) } catch (e: IOException) { removeLink(link); return Link.defaultFor(kind) }
        val t1 = withTimeoutOrNull(echoTimeoutMs) { d.await() } ?: run { synchronized(link.pongs) { link.pongs.remove(id) }; return Link.defaultFor(kind) }
        val rttMs = maxOf(1L, (t1 - t0) / 1_000_000L)
        val kbps = (2L * ECHO_BYTES * 8 / rttMs).coerceIn(1L, MAX_KBPS.toLong()).toInt()
        return Link(kind, kbps, rttMs.toInt(), metered = false)
    }

    /**
     * Closes every link to [endpoint] (the session layer refused its handshake, or the router closed it) and holds
     * off redialling it for [backoffMaxMs]: phone gate finding, an unpaired neighbour redialled every second had each
     * refusal close every link to it, including the one a pairing handshake was crossing on. [dialNow] lifts the hold.
     */
    override fun disconnect(endpoint: PeerEndpoint) {
        val drop = synchronized(lock) { links.values.filter { it.peer?.endpointId == endpoint.endpointId } }
        drop.forEach { removeLink(it) }
        synchronized(lock) { backoff[endpoint.endpointId] = backoffMaxMs; nextAttempt[endpoint.endpointId] = now() + backoffMaxMs }   // after removeLink's own 1 s retry
        SLog.d(TAG, "closed ${drop.size} link(s) to ${endpoint.displayName} (${endpoint.endpointId}); redial in ${backoffMaxMs / 1000} s")
    }

    /** Dials every discovered peer whose node id starts with [prefix] at once (a pairing is starting with it), hold or not. */
    fun dialNow(prefix: String) {
        synchronized(lock) {
            val ids = discovery.peers.value.filter { prefixOf(it.serviceName) == prefix.take(8) }.map { endpointId(it.serviceName, it.host) }
            for (id in ids) { nextAttempt.remove(id); backoff.remove(id); firstSeen[id] = 0L }
        }
        wake.trySend(Unit)
    }

    // ---- server side -------------------------------------------------------------------------------------------

    private suspend fun acceptLoop() {
        var wait = backoffMinMs
        while (currentCoroutineContext().isActive && running) {
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
        while (currentCoroutineContext().isActive && running) {
            var earliest = now() + connectIntervalMs
            val peers = discovery.peers.value.filter { it.serviceName != localName && prefixOf(it.serviceName) != prefixOf(localName) }
            for (p in peers) {
                if (!running) return
                val id = endpointId(p.serviceName, p.host)
                if (hasLink(id)) continue
                val seen = synchronized(lock) { firstSeen.getOrPut(id) { now() } }
                // The lower prefix connects; the other side waits [fallbackMs] for it before trying itself.
                if (!initiates(localName, p.serviceName) && now() - seen < fallbackMs) { earliest = minOf(earliest, seen + fallbackMs); continue }
                val due = synchronized(lock) { nextAttempt[id] ?: 0L }
                if (due > now()) { earliest = minOf(earliest, due); continue }
                val link = try {
                    endpoint.connect(p.host, p.port)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SLog.d(TAG, "connect ${p.host}:${p.port} threw $e"); null
                }
                if (link != null) {
                    synchronized(lock) { backoff.remove(id); nextAttempt.remove(id) }
                    addLink(link, outbound = true, expected = p.serviceName)
                } else {
                    val delay = synchronized(lock) {
                        val b = backoff[id]
                        val next = if (b == null) backoffMinMs.also { backoff[id] = it } else (b * 2).coerceAtMost(backoffMaxMs).also { backoff[id] = it }
                        nextAttempt[id] = now() + next
                        next
                    }
                    earliest = minOf(earliest, now() + delay)
                }
            }
            refresh()
            val sleep = (earliest - now()).coerceAtLeast(50L)
            withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    // ---- links -------------------------------------------------------------------------------------------------

    private fun linksTo(endpointId: String): List<LinkState> =
        synchronized(lock) { links.values.filter { !it.dead && it.peer?.endpointId == endpointId }.sortedByDescending { it.id } }

    private fun hasLink(endpointId: String): Boolean = linksTo(endpointId).isNotEmpty()

    private fun addLink(lan: LanLink, outbound: Boolean, expected: String? = null) {
        val link = LinkState(lan, outbound)
        synchronized(lock) {
            if (!running) { lan.close(); return }
            links[link.id] = link
            link.reader = scope.launch(Dispatchers.IO) { readLoop(link, expected) }
        }
        scope.launch(Dispatchers.IO) {
            try { write(link, byteArrayOf(TAG_CONTROL, CTL_PREAMBLE) + localName.toByteArray(Charsets.UTF_8)) }
            catch (e: IOException) { SLog.d(TAG, "preamble to ${link.host} failed: ${e.message}"); removeLink(link) }
        }
        SLog.d(TAG, "link #${link.id} ${if (outbound) "to" else "from"} ${link.host}; ${liveCount()} live")
    }

    private fun removeLink(link: LinkState) {
        val wasLive: Boolean
        synchronized(lock) {
            wasLive = links.remove(link.id) != null && !link.dead
            link.dead = true
            val id = link.peer?.endpointId
            if (running && wasLive && id != null && links.values.none { !it.dead && it.peer?.endpointId == id }) {
                backoff[id] = backoffMinMs
                nextAttempt[id] = now() + backoffMinMs
            }
        }
        link.lan.close()
        synchronized(link.pongs) { link.pongs.values.forEach { it.cancel() }; link.pongs.clear() }
        if (wasLive) {
            SLog.d(TAG, "link #${link.id} to ${link.host} closed; ${liveCount()} live")
            wake.trySend(Unit)
            refresh()
        }
    }

    private suspend fun readLoop(link: LinkState, expected: String?) {
        val input = DataInputStream(link.lan.input)
        try {
            while (!link.dead && running) {
                val len = input.readInt()
                if (len < 0 || len > MAX_FRAME) {
                    SLog.w(TAG, "oversize frame header $len B from ${link.host} on link #${link.id}; closing that link")
                    break
                }
                val buf = ByteArray(len)
                input.readFully(buf)
                if (buf.size >= 2 && buf[0] == TAG_CONTROL) { if (!onControl(link, buf, expected)) break; continue }
                val peer = link.peer
                if (peer == null) { SLog.w(TAG, "frame before the preamble from ${link.host}; closing link #${link.id}"); break }
                inboundFlow.emit(Inbound(peer, buf))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!link.dead && running) SLog.d(TAG, "link #${link.id} to ${link.host} dropped: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            removeLink(link)
        }
    }

    /** Handles a transport-level frame; false closes the link. */
    private suspend fun onControl(link: LinkState, buf: ByteArray, expected: String?): Boolean {
        when (buf[1]) {
            CTL_PREAMBLE -> {
                val serviceName = String(buf, 2, buf.size - 2, Charsets.UTF_8)
                if (serviceName.isBlank() || prefixOf(serviceName) == prefixOf(localName)) { SLog.w(TAG, "bad preamble '$serviceName' from ${link.host}"); return false }
                if (expected != null && prefixOf(expected) != prefixOf(serviceName)) { SLog.w(TAG, "${link.host} is $serviceName, expected $expected"); return false }
                link.serviceName = serviceName
                link.peer = PeerEndpoint(NAME, endpointId(serviceName, link.host), displayNameOf(serviceName))
                SLog.d(TAG, "link #${link.id} is ${link.peer!!.displayName} (${link.peer!!.endpointId})")
                refresh()
            }
            CTL_PING -> { buf[1] = CTL_PONG; try { write(link, buf) } catch (e: IOException) { return false } }
            CTL_PONG -> if (buf.size >= 10) {
                val id = ByteBuffer.wrap(buf, 2, 8).long
                synchronized(link.pongs) { link.pongs.remove(id) }?.complete(System.nanoTime())
            }
            else -> SLog.d(TAG, "unknown control ${buf[1]} from ${link.host}")
        }
        return true
    }

    private suspend fun write(link: LinkState, bytes: ByteArray) {
        val frame = ByteArray(HEADER + bytes.size)
        val n = bytes.size
        frame[0] = (n ushr 24).toByte(); frame[1] = (n ushr 16).toByte(); frame[2] = (n ushr 8).toByte(); frame[3] = n.toByte()
        System.arraycopy(bytes, 0, frame, HEADER, n)
        withContext(Dispatchers.IO) {
            synchronized(link.writeLock) {
                link.lan.output.write(frame)
                link.lan.output.flush()
            }
        }
    }

    private fun liveCount(): Int = synchronized(lock) { links.values.count { !it.dead } }

    private fun refresh() {
        val live = synchronized(lock) { links.values.filter { !it.dead && it.peer != null }.sortedByDescending { it.id } }
        connectedState.value = live.mapNotNull { it.peer }.distinctBy { it.endpointId }
        val found = discovery.peers.value.size
        statusState.value = when {
            !running -> "stopped"
            live.isEmpty() -> "LAN · 0 links · $found found"
            else -> "LAN · ${live.size} link${if (live.size == 1) "" else "s"}"
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
