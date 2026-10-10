package com.hivecore.mesh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM stand-in for the Bluetooth stack: a world of fake phones with a symmetric bond graph. `connect(address)`
 * creates a pair of piped links and hands the far end to the peer's `accept()` queue, exactly as RFCOMM would.
 */
class FakeBtWorld {
    private val nodes = ConcurrentHashMap<String, FakeBtEndpoint>()
    private val bonds = CopyOnWriteArrayList<Pair<String, String>>()

    fun node(address: String, name: String = address): FakeBtEndpoint = nodes.getOrPut(address) { FakeBtEndpoint(this, address, name) }

    fun bond(a: String, b: String) { bonds += a to b }

    /** Bonded devices of [address] as (address, name); bonds are symmetric like in Settings. */
    fun bondedOf(address: String): List<Pair<String, String>> = bonds
        .mapNotNull { (x, y) -> if (x == address) y else if (y == address) x else null }
        .distinct()
        .map { it to (nodes[it]?.name ?: it) }

    fun find(address: String): FakeBtEndpoint? = nodes[address]
}

class FakeBtEndpoint(private val world: FakeBtWorld, val address: String, val name: String) : BtEndpoint {
    @Volatile private var incoming = Channel<BtLink>(Channel.UNLIMITED)
    /** Every link this side ever had (accepted or connected), oldest first; tests break or inspect them. */
    val links = CopyOnWriteArrayList<PipedBtLink>()
    val connectAttempts = AtomicInteger()
    /** True once the owning transport has called [accept]: before that there is no server socket to connect to. */
    @Volatile var listening = false
        private set
    @Volatile var reachable = true
    /** True between [close] and the next [accept], like a closed server socket. */
    val closed: Boolean get() = !listening

    fun liveLinksTo(address: String): List<PipedBtLink> = links.filter { !it.closed && it.remoteAddress == address }

    override suspend fun accept(): BtLink {
        val queue = synchronized(this) {
            if (incoming.isClosedForReceive) incoming = Channel(Channel.UNLIMITED)   // re-listen after close()
            listening = true
            incoming
        }
        return try {
            queue.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw IOException("server socket closed")
        }
    }

    override suspend fun connect(address: String): BtLink? {
        connectAttempts.incrementAndGet()
        val peer = world.find(address) ?: return null
        if (!reachable || !peer.listening || !peer.reachable) return null
        val (mine, theirs) = PipedBtLink.pair(this, peer)
        links += mine
        peer.links += theirs
        val sent = peer.incoming.trySend(theirs)
        if (!sent.isSuccess) { mine.close(); return null }
        return mine
    }

    override fun bonded(): List<Pair<String, String>> = world.bondedOf(address)

    override fun available(): Boolean = reachable

    override fun close() {
        synchronized(this) {
            listening = false
            incoming.close()
        }
    }
}

/** One end of a bidirectional pipe. Closing either end breaks both directions, as a dropped socket does. */
class PipedBtLink private constructor(override val remoteAddress: String, override val remoteName: String) : BtLink {
    override lateinit var input: PipedInputStream
        private set
    override lateinit var output: PipedOutputStream
        private set
    private lateinit var partner: PipedBtLink
    @Volatile var closed = false
        private set

    companion object {
        private const val BUFFER = 256 * 1024

        fun pair(a: FakeBtEndpoint, b: FakeBtEndpoint): Pair<PipedBtLink, PipedBtLink> {
            val atB = PipedBtLink(b.address, b.name)
            val btA = PipedBtLink(a.address, a.name)
            atB.input = PipedInputStream(BUFFER); btA.output = PipedOutputStream(atB.input)
            btA.input = PipedInputStream(BUFFER); atB.output = PipedOutputStream(btA.input)
            atB.partner = btA; btA.partner = atB
            return atB to btA
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { output.close() } catch (_: IOException) {}
        try { partner.output.close() } catch (_: IOException) {}   // my reader sees EOF, as it would on a dropped socket
        try { input.close() } catch (_: IOException) {}
        partner.close()
    }
}
