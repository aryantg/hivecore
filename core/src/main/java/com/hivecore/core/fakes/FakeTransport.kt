package com.hivecore.core.fakes

import com.hivecore.core.Inbound
import com.hivecore.core.PairingRequest
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.Transport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory message bus for N nodes. `bus.node("hr")` gives one [FakeTransport] per node; every started node is
 * "connected" to every other started node; `send` delivers synchronously to the target's [Transport.inbound]
 * (buffered, so late collectors still see up to 256 messages) and to its `received` list for assertions.
 * `offline` simulates a dead phone: sends to it fail and it drops out of everyone's `connected` list.
 */
class FakeBus {
    private val nodes = LinkedHashMap<String, FakeTransport>()
    val offline = HashSet<String>()

    fun node(id: String, displayName: String = id): FakeTransport =
        synchronized(nodes) { nodes.getOrPut(id) { FakeTransport(this, id, displayName) } }

    fun all(): List<FakeTransport> = synchronized(nodes) { nodes.values.toList() }

    fun setOffline(id: String, off: Boolean) {
        if (off) offline.add(id) else offline.remove(id)
        refreshConnected()
    }

    internal fun deliver(from: FakeTransport, toId: String, bytes: ByteArray): Result<Unit> {
        val to = synchronized(nodes) { nodes[toId] } ?: return Result.failure(IllegalStateException("unknown endpoint $toId"))
        if (!from.started || from.id in offline) return Result.failure(IllegalStateException("${from.id} is not started"))
        if (!to.started || toId in offline) return Result.failure(IllegalStateException("$toId is not reachable"))
        to.receive(Inbound(from.endpoint, bytes.copyOf()))
        return Result.success(Unit)
    }

    internal fun peersOf(id: String): List<PeerEndpoint> =
        all().filter { it.started && it.id != id && it.id !in offline && id !in offline }.map { it.endpoint }

    internal fun refreshConnected() = all().forEach { it.connectedState.value = peersOf(it.id) }
}

class FakeTransport internal constructor(val bus: FakeBus, val id: String, displayName: String) : Transport {
    override val name: String = "fake"
    var localName: String = displayName
        private set
    val endpoint: PeerEndpoint get() = PeerEndpoint(name, id, localName)

    internal val connectedState = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    override val connected: StateFlow<List<PeerEndpoint>> = connectedState
    private val inboundFlow = MutableSharedFlow<Inbound>(replay = 0, extraBufferCapacity = 256)
    override val inbound: SharedFlow<Inbound> = inboundFlow
    private val pairingFlow = MutableSharedFlow<PairingRequest>(replay = 0, extraBufferCapacity = 16)
    override val pairingRequests: SharedFlow<PairingRequest> = pairingFlow
    private val statusState = MutableStateFlow("stopped")
    override val status: StateFlow<String> = statusState

    @Volatile var started: Boolean = false; private set
    @Volatile var pairingMode: Boolean = false; private set
    val received: MutableList<Inbound> = java.util.Collections.synchronizedList(ArrayList())
    val sent: MutableList<Pair<PeerEndpoint, ByteArray>> = java.util.Collections.synchronizedList(ArrayList())
    val pairingDecisions: MutableList<Pair<PairingRequest, Boolean>> = java.util.Collections.synchronizedList(ArrayList())

    override suspend fun start(localName: String) {
        this.localName = localName
        started = true
        statusState.value = "started as $localName"
        bus.refreshConnected()
    }

    override suspend fun stop() {
        started = false
        statusState.value = "stopped"
        bus.refreshConnected()
    }

    override suspend fun send(endpoint: PeerEndpoint, bytes: ByteArray): Result<Unit> {
        sent.add(endpoint to bytes.copyOf())
        return bus.deliver(this, endpoint.endpointId, bytes)
    }

    internal fun receive(inb: Inbound) {
        received.add(inb)
        inboundFlow.tryEmit(inb)
    }

    override fun acceptPairing(req: PairingRequest, accept: Boolean) { pairingDecisions.add(req to accept) }
    override fun setPairingMode(on: Boolean) { pairingMode = on }

    /** Test helper: raise a pairing request as a real transport would on an unpinned connection. */
    fun raisePairing(req: PairingRequest): Boolean = pairingFlow.tryEmit(req)
}
