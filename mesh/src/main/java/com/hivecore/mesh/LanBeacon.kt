package com.hivecore.mesh

import com.hivecore.core.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Phone gate finding (spec 5.1 LAN): on access points that filter multicast between clients, mDNS never delivers a
 * find although unicast works, so the LAN transport also announces itself by UDP broadcast. Every [intervalMs] this
 * phone sends `HC1\t<serviceName>\t<servicePort>` to [beaconPort] on every target address ([targets]: the limited
 * broadcast and each interface's directed broadcast by default) and listens on the same port; a beacon from another
 * name becomes a [LanPeer] at the sender's address, dropped again after [staleMs] without a beacon. Beacons carry no
 * secret: the session handshake authenticates the peer, exactly as for an NSD find.
 */
class LanBeacon(
    private val beaconPort: Int = PORT,
    /** where beacons are sent (tests point it at a listener on another port); the phones use [beaconPort] */
    private val sendPort: Int = beaconPort,
    private val intervalMs: Long = INTERVAL_MS,
    private val staleMs: Long = STALE_MS,
    private val targets: () -> List<InetAddress> = { defaultTargets() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** also address every host of each interface's prefix (up to /22) every third beat */
    private val sweep: Boolean = true,
) {
    private val peersState = MutableStateFlow<List<LanPeer>>(emptyList())
    /** Peers heard recently (self excluded). */
    val peers: StateFlow<List<LanPeer>> = peersState
    private val lastSeen = HashMap<String, Long>()
    private var socket: DatagramSocket? = null
    private var sender: Thread? = null
    private var receiver: Thread? = null
    @Volatile private var ownName = ""
    @Volatile private var servicePort = 0

    @Synchronized
    fun start(serviceName: String, port: Int) {
        stop()
        ownName = serviceName; servicePort = port
        val s = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1_000; bind(InetSocketAddress(beaconPort)) }
        } catch (e: Exception) { SLog.w(TAG, "bind $beaconPort: ${e.message}"); return }
        socket = s
        receiver = Thread({ receiveLoop(s) }, "lan-beacon-rx").apply { isDaemon = true; start() }
        sender = Thread({ sendLoop(s) }, "lan-beacon-tx").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null; sender = null; receiver = null
        synchronized(lastSeen) { lastSeen.clear() }
        peersState.value = emptyList()
    }

    private fun sendLoop(s: DatagramSocket) {
        val payload = "$MAGIC\t$ownName\t$servicePort".toByteArray(Charsets.UTF_8)
        var beat = 0
        while (socket === s && !s.isClosed) {
            // Every beat: the broadcasts. Every third beat: one datagram to every host of the interface's prefix,
            // because access points that isolate clients drop broadcast and multicast but forward unicast (phone
            // gate finding); 20 bytes × a /23 every 9 s is nothing on the air.
            val all = runCatching { targets() }.getOrDefault(emptyList()) + (if (sweep && beat % SWEEP_EVERY == 0) runCatching { sweepTargets() }.getOrDefault(emptyList()) else emptyList())
            for (t in all) {
                if (socket !== s || s.isClosed) return
                try { s.send(DatagramPacket(payload, payload.size, t, sendPort)) } catch (e: Exception) { SLog.d(TAG, "send to $t: ${e.message}") }
            }
            beat++
            prune()
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return }
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(512)
        while (socket === s && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: SocketTimeoutException) { continue } catch (e: Exception) { if (!s.isClosed) SLog.d(TAG, "receive: ${e.message}"); return }
            val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('\t')
            if (parts.size != 3 || parts[0] != MAGIC) continue
            val name = parts[1]; val port = parts[2].toIntOrNull() ?: continue
            if (name.isBlank() || name == ownName || port !in 1..65535) continue
            val host = p.address?.hostAddress ?: continue
            val peer = LanPeer(name, host, port)
            val fresh = synchronized(lastSeen) { lastSeen.put(name, clock()) == null }
            peersState.value = peersState.value.filter { it.serviceName != name } + peer
            if (fresh) SLog.d(TAG, "heard $name at $host:$port")
        }
    }

    private fun prune() {
        val now = clock()
        val gone = synchronized(lastSeen) { lastSeen.filterValues { now - it > staleMs }.keys.toList().also { g -> g.forEach { lastSeen.remove(it) } } }
        if (gone.isNotEmpty()) { peersState.value = peersState.value.filter { it.serviceName !in gone }; SLog.d(TAG, "lost ${gone.joinToString()}") }
    }

    companion object {
        const val PORT = 47474
        const val INTERVAL_MS = 3_000L
        const val STALE_MS = 20_000L
        const val SWEEP_EVERY = 3
        /** the widest prefix swept (a /22 is 1,022 hosts) */
        const val SWEEP_MIN_PREFIX = 22
        private const val MAGIC = "HC1"
        private const val TAG = "LanBeacon"

        /** Every host address of each up, non-loopback IPv4 interface whose prefix is /[SWEEP_MIN_PREFIX] or narrower, own addresses excluded. */
        fun sweepTargets(): List<InetAddress> {
            val out = ArrayList<InetAddress>()
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (a in nif.interfaceAddresses) {
                        val addr = a.address.address
                        if (addr.size != 4 || a.networkPrefixLength < SWEEP_MIN_PREFIX || a.networkPrefixLength >= 31) continue
                        val own = ((addr[0].toInt() and 255) shl 24) or ((addr[1].toInt() and 255) shl 16) or ((addr[2].toInt() and 255) shl 8) or (addr[3].toInt() and 255)
                        val mask = (-1 shl (32 - a.networkPrefixLength.toInt()))
                        val base = own and mask
                        val last = base or mask.inv()
                        var h = base + 1
                        while (h < last) {
                            if (h != own) out += InetAddress.getByAddress(byteArrayOf((h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte()))
                            h++
                        }
                    }
                }
            }
            return out
        }

        /** The limited broadcast plus every up, non-loopback interface's directed broadcast. */
        fun defaultTargets(): List<InetAddress> {
            val out = LinkedHashSet<InetAddress>()
            runCatching { out += InetAddress.getByName("255.255.255.255") }
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (a in nif.interfaceAddresses) a.broadcast?.let { out += it }
                }
            }
            return out.toList()
        }
    }
}
