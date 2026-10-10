package com.hivecore.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.hivecore.core.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Spec 5.1 rank 1: LAN TCP on the same Wi-Fi, found by NSD/mDNS. [LanTransport] frames envelopes over [LanLink]s
 * that come from a [LanEndpoint] (accept / connect) and learns who to connect to from a [LanDiscovery]. Only the
 * discovery touches Android ([NsdLanDiscovery]); the sockets are plain `java.net`, so the framing, preamble and
 * reconnect logic run on the JVM over loopback sockets with a fake discovery (see LanTransportTest).
 */
interface LanLink {
    val input: InputStream
    val output: OutputStream
    /** The peer's IP address (part of the endpoint id; the ephemeral port is not, so both directions dedupe). */
    val remoteHost: String
    fun close()
}

/** Where links come from: the listening socket (accept) and discovered peers (connect). */
interface LanEndpoint {
    /** The port peers connect to (advertised by discovery); valid after the first [accept] or [listen]. */
    val localPort: Int
    /** Opens the listening socket if needed and returns its port. */
    fun listen(): Int
    /** Blocks until a peer connects. Throws [IOException] when the socket is closed or the network is gone. */
    suspend fun accept(): LanLink
    /** One connection attempt; null when refused or timed out. */
    suspend fun connect(host: String, port: Int): LanLink?
    /** Closes the listening socket; a pending [accept] fails. The next [listen] opens a new one. */
    fun close()
}

/** A peer advertised on the LAN: its service name (`<name>|<nodeId prefix>`, see [LanTransport]), host and port. */
data class LanPeer(val serviceName: String, val host: String, val port: Int)

/** NSD seam: advertises this phone and lists the resolved peers. Android: [NsdLanDiscovery]; tests: a fake. */
interface LanDiscovery {
    /** Peers currently resolved (self excluded). */
    val peers: StateFlow<List<LanPeer>>
    /** Advertises `serviceName` on [port] and starts discovering. */
    fun start(serviceName: String, port: Int)
    fun stop()
}

class SocketLanLink(private val socket: Socket) : LanLink {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override val remoteHost: String = socket.inetAddress?.hostAddress ?: "?"
    override fun close() { try { socket.close() } catch (e: IOException) { SLog.d("LanLink", "close $remoteHost: $e") } }
}

/**
 * [LanEndpoint] over `java.net`. Nagle is off (one envelope per write, the session layer wants it on the wire now),
 * keep-alive on; connects time out after [connectTimeoutMs].
 *
 * @param port 0 = any free port (the JVM tests), else the fixed port to advertise
 * @param bind the address to listen on (null = every interface; tests use loopback)
 */
class SocketLanEndpoint(private val port: Int = 0, private val bind: InetAddress? = null, private val connectTimeoutMs: Int = 3_000) : LanEndpoint {
    private val lock = Any()
    private var server: ServerSocket? = null

    override val localPort: Int get() = synchronized(lock) { server?.localPort ?: -1 }

    override fun listen(): Int = synchronized(lock) {
        server?.takeIf { !it.isClosed }?.localPort ?: ServerSocket().also {
            it.reuseAddress = true
            it.bind(InetSocketAddress(bind, port))
            server = it
            SLog.d("LanEndpoint", "listening on ${it.localPort}")
        }.localPort
    }

    override suspend fun accept(): LanLink = withContext(Dispatchers.IO) {
        listen()
        val ss = synchronized(lock) { server } ?: throw IOException("not listening")
        try {
            val s = ss.accept()
            s.tcpNoDelay = true; s.keepAlive = true
            SocketLanLink(s)
        } catch (e: IOException) {
            synchronized(lock) { if (server === ss) server = null }
            try { ss.close() } catch (_: IOException) {}
            throw e
        }
    }

    override suspend fun connect(host: String, port: Int): LanLink? = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.tcpNoDelay = true; s.keepAlive = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            SocketLanLink(s)
        } catch (e: IOException) {
            SLog.d("LanEndpoint", "connect $host:$port refused: ${e.message}")
            try { s.close() } catch (_: IOException) {}
            null
        }
    }

    override fun close() {
        val ss = synchronized(lock) { server.also { server = null } }
        try { ss?.close() } catch (e: IOException) { SLog.d("LanEndpoint", "close: $e") }
    }
}

/**
 * [LanDiscovery] over the framework `NsdManager`: registers `serviceName` as `_hivecore._tcp` and discovers the same
 * type. Resolves are serialised (the framework resolves one service at a time and fails the rest with
 * FAILURE_ALREADY_ACTIVE), and a lost service is removed from [peers]. Needs no permission on API 31+ for mDNS;
 * the Wi-Fi multicast lock is the caller's (worker foreground service) concern.
 */
class NsdLanDiscovery(context: Context) : LanDiscovery {
    companion object {
        const val SERVICE_TYPE = "_hivecore._tcp."
        private const val TAG = "LanNsd"
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val peersState = MutableStateFlow<List<LanPeer>>(emptyList())
    /** NSD finds first, then beacon-only peers (an access point that filters multicast delivers only the latter). */
    override val peers: StateFlow<List<LanPeer>> get() = merged
    private val beacon = LanBeacon()
    private val merged = MutableStateFlow<List<LanPeer>>(emptyList())
    private val mergeScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    init {
        mergeScope.launch {
            kotlinx.coroutines.flow.combine(peersState, beacon.peers) { n, b -> n + b.filter { p -> n.none { it.serviceName == p.serviceName } } }.collect { merged.value = it }
        }
    }
    private val lock = Any()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var ownName: String = ""

    override fun start(serviceName: String, port: Int) {
        stop()
        ownName = serviceName
        val info = NsdServiceInfo().apply { this.serviceName = serviceName; serviceType = SERVICE_TYPE; this.port = port }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { ownName = i.serviceName; SLog.d(TAG, "registered as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { SLog.w(TAG, "registration failed: $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { SLog.d(TAG, "discovery started") }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { SLog.w(TAG, "discovery failed: $code") }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                // Phone gate finding: the framework (mdnsd on these phones) reports the type with or without the
                // trailing dot, and a leading one on some builds; compare the bare type or every find is dropped.
                if (i.serviceType.trim('.') != SERVICE_TYPE.trim('.') || i.serviceName == ownName) return
                SLog.d(TAG, "found ${i.serviceName}")
                synchronized(lock) { queue.addLast(i) }
                resolveNext()
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                peersState.value = peersState.value.filter { it.serviceName != i.serviceName }
            }
        }
        synchronized(lock) { registration = reg; discovery = disc }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        } catch (e: Exception) {
            SLog.w(TAG, "start: $e")
        }
        beacon.start(serviceName, port)
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            if (resolving) return
            queue.removeFirstOrNull()?.also { resolving = true }
        } ?: return
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                SLog.d(TAG, "resolve ${i.serviceName} failed: $code")
                synchronized(lock) { resolving = false }
                resolveNext()
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host?.hostAddress
                if (host != null && i.port > 0) {
                    val peer = LanPeer(i.serviceName, host, i.port)
                    peersState.value = peersState.value.filter { it.serviceName != peer.serviceName } + peer
                    SLog.d(TAG, "resolved ${peer.serviceName} at ${peer.host}:${peer.port}")
                }
                synchronized(lock) { resolving = false }
                resolveNext()
            }
        })
    }

    override fun stop() {
        val (reg, disc) = synchronized(lock) { (registration to discovery).also { registration = null; discovery = null; queue.clear(); resolving = false } }
        try { reg?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { disc?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        beacon.stop()
        peersState.value = emptyList()
    }
}
