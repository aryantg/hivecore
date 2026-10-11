package com.hivecore.mesh

import com.hivecore.core.SLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64

/** A pair request another phone on this Wi-Fi sent: who, from where, and the invite it carries ([payload], an `Invite.qrPayload`). */
data class PairPing(val id: String, val fromName: String, val fromHost: String, val payload: String, val ts: Long)

/** What a pinged phone answered to one of our pings. */
data class PairPingReply(val id: String, val fromName: String, val approved: Boolean, val ts: Long)

/** One ping sent: its id and how many addresses it went to. */
data class PairPingSent(val id: String, val sentTo: Int)

/**
 * "Pair with everyone in this room": one phone broadcasts a pair request to every phone on the same Wi-Fi and each of
 * them shows Approve / Deny. The request carries the inviter's regular invite (spec 6.2: group, public key, a
 * single-use 10-minute token), so Approve is an ordinary join and the 6-digit check still guards the keys; the ping
 * only saves the QR scan. Like [LanBeacon] it rides UDP on its own port, to the limited and directed broadcasts and,
 * because access points that isolate clients forward unicast only, to every host of the interface's prefix.
 *
 * Wire (tab-separated UTF-8, no secrets beyond the invite token):
 *  - `HCP1 REQ <id> <name> <payload base64url>` — a request, repeated [rounds] times (receivers dedupe on the id)
 *  - `HCP1 REP <id> <name> YES|NO` — the answer, unicast back to the sender
 */
class LanPairPing(
    private val listenPort: Int = PORT,
    /** where requests and replies are sent (tests point two instances at each other); the phones use [listenPort] */
    private val sendPort: Int = listenPort,
    private val targets: () -> List<InetAddress> = { LanBeacon.defaultTargets() },
    /** also address every host of each interface's prefix (up to /22) */
    private val sweep: Boolean = true,
    private val rounds: Int = ROUNDS,
    private val roundGapMs: Long = ROUND_GAP_MS,
    /** a request nobody answered disappears after this */
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val requestsState = MutableStateFlow<List<PairPing>>(emptyList())
    /** Requests heard and not yet answered or expired, oldest first (never our own). */
    val requests: StateFlow<List<PairPing>> = requestsState
    private val repliesFlow = MutableSharedFlow<PairPingReply>(replay = 0, extraBufferCapacity = 64)
    /** Answers to pings this phone sent. */
    val replies: SharedFlow<PairPingReply> = repliesFlow

    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    @Volatile private var ownName = ""
    private val lock = Any()
    private val mine = HashMap<String, Long>()      // id → when we sent it (guarded by lock)
    private val answered = HashMap<String, Long>()  // ids we replied to or dismissed, so a repeated round does not bring them back

    /** Opens the port; the listener runs until [stop]. Without it [ping] and [reply] fail. */
    @Synchronized
    fun start(name: String) {
        stop()
        ownName = clean(name)
        val s = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1_000; bind(InetSocketAddress(listenPort)) }
        } catch (e: Exception) { SLog.w(TAG, "bind $listenPort: ${e.message}"); return }
        socket = s
        receiver = Thread({ receiveLoop(s) }, "lan-pairping-rx").apply { isDaemon = true; start() }
        SLog.d(TAG, "listening on $listenPort as $ownName")
    }

    @Synchronized
    fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null; receiver = null
        requestsState.value = emptyList()
        synchronized(lock) { mine.clear(); answered.clear() }
    }

    val running: Boolean get() = socket?.isClosed == false

    /**
     * Broadcasts one pair request carrying [payload] to every phone on the Wi-Fi; the later rounds go out in the
     * background. Fails when the port is not open or nothing is reachable.
     */
    fun ping(payload: String): Result<PairPingSent> {
        val s = socket?.takeIf { !it.isClosed } ?: return Result.failure(IllegalStateException("the pairing port is not open"))
        val all = allTargets()
        if (all.isEmpty()) return Result.failure(IllegalStateException("no Wi-Fi network to ping"))
        val bytes = ByteArray(9).also { random.nextBytes(it) }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val line = listOf(MAGIC, REQ, id, ownName, Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))).joinToString("\t")
        synchronized(lock) { prune(clock()); mine[id] = clock() }
        val data = line.toByteArray(Charsets.UTF_8)
        if (data.size > MAX_DATAGRAM) return Result.failure(IllegalArgumentException("the invite is too large to ping"))
        val sent = sendAll(s, data, all)
        if (rounds > 1) Thread({
            for (r in 2..rounds) {
                try { Thread.sleep(roundGapMs) } catch (_: InterruptedException) { return@Thread }
                if (socket !== s || s.isClosed) return@Thread
                sendAll(s, data, runCatching { allTargets() }.getOrDefault(all))
            }
        }, "lan-pairping-tx").apply { isDaemon = true; start() }
        SLog.d(TAG, "pinged $sent addresses (id $id)")
        return Result.success(PairPingSent(id, sent))
    }

    /** Answers [ping] (Approve = true) straight back to the phone that sent it and drops it from [requests]. */
    fun reply(ping: PairPing, approve: Boolean): Result<Unit> {
        val s = socket?.takeIf { !it.isClosed } ?: return Result.failure(IllegalStateException("the pairing port is not open"))
        dismiss(ping.id)
        val line = listOf(MAGIC, REP, ping.id, ownName, if (approve) YES else NO).joinToString("\t").toByteArray(Charsets.UTF_8)
        return runCatching { s.send(DatagramPacket(line, line.size, InetAddress.getByName(ping.fromHost), sendPort)) }
            .onFailure { SLog.w(TAG, "reply to ${ping.fromHost}: ${it.message}") }
    }

    /** Forgets a request without answering it. */
    fun dismiss(id: String) {
        synchronized(lock) { answered[id] = clock() }
        requestsState.value = requestsState.value.filterNot { it.id == id }
    }

    private fun allTargets(): List<InetAddress> =
        (runCatching { targets() }.getOrDefault(emptyList()) + (if (sweep) runCatching { LanBeacon.sweepTargets() }.getOrDefault(emptyList()) else emptyList())).distinct()

    private fun sendAll(s: DatagramSocket, data: ByteArray, to: List<InetAddress>): Int {
        var n = 0
        for (t in to) {
            if (socket !== s || s.isClosed) break
            try { s.send(DatagramPacket(data, data.size, t, sendPort)); n++ } catch (e: Exception) { SLog.d(TAG, "send to $t: ${e.message}") }
        }
        return n
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(MAX_DATAGRAM)
        while (socket === s && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: SocketTimeoutException) { expire(); continue } catch (e: Exception) { if (!s.isClosed) SLog.d(TAG, "receive: ${e.message}"); return }
            val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('\t')
            if (parts.size != 5 || parts[0] != MAGIC) continue
            val id = parts[2]; val name = parts[3]
            if (id.isBlank() || name.isBlank()) continue
            val host = p.address?.hostAddress ?: continue
            val now = clock()
            when (parts[1]) {
                REQ -> {
                    val own = synchronized(lock) { prune(now); id in mine || id in answered }
                    if (own || name == ownName) continue
                    val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[4]), Charsets.UTF_8) }.getOrNull() ?: continue
                    if (requestsState.value.any { it.id == id }) continue
                    requestsState.value = requestsState.value.filter { now - it.ts <= ttlMs } + PairPing(id, name, host, payload, now)
                    SLog.d(TAG, "pair request from $name at $host")
                }
                REP -> {
                    val ours = synchronized(lock) { prune(now); id in mine }
                    if (!ours) continue
                    val approved = when (parts[4]) { YES -> true; NO -> false; else -> continue }
                    SLog.d(TAG, "$name ${if (approved) "approved" else "denied"} our ping")
                    repliesFlow.tryEmit(PairPingReply(id, name, approved, now))
                }
            }
        }
    }

    private fun expire() {
        val now = clock()
        val live = requestsState.value.filter { now - it.ts <= ttlMs }
        if (live.size != requestsState.value.size) requestsState.value = live
        synchronized(lock) { prune(now) }
    }

    /** Guarded by [lock]: forgets our pings after the invite would have expired anyway, and old answers after the ttl. */
    private fun prune(now: Long) {
        mine.values.removeAll { now - it > MINE_TTL_MS }
        answered.values.removeAll { now - it > ttlMs }
    }

    private fun clean(name: String): String = name.replace('\t', ' ').replace('\n', ' ').trim().ifEmpty { "A phone" }.take(32)

    companion object {
        /** One above [LanBeacon.PORT]. */
        const val PORT = 47475
        const val ROUNDS = 3
        const val ROUND_GAP_MS = 400L
        /** How long an unanswered request stays on the other phones' screens. */
        const val TTL_MS = 2 * 60_000L
        /** Replies to a ping are accepted for as long as its invite token lives (spec 6.2: 10 minutes). */
        const val MINE_TTL_MS = 10 * 60_000L
        private const val MAX_DATAGRAM = 1_400
        private const val MAGIC = "HCP1"
        private const val REQ = "REQ"
        private const val REP = "REP"
        private const val YES = "YES"
        private const val NO = "NO"
        private const val TAG = "LanPairPing"
    }
}
