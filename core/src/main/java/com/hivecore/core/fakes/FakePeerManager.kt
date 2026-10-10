package com.hivecore.core.fakes

import com.hivecore.core.PairingRequest
import com.hivecore.core.Peer
import com.hivecore.core.PeerManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Mutable flows the tests and previews can drive directly. */
class FakePeerManager(initial: List<Peer> = emptyList()) : PeerManager {
    val peersState = MutableStateFlow(initial)
    override val peers: StateFlow<List<Peer>> = peersState
    val pairingFlow = MutableSharedFlow<PairingRequest>(extraBufferCapacity = 16)
    override val pairing: SharedFlow<PairingRequest> = pairingFlow
    val transportState = MutableStateFlow("nearby")
    override val transportName: StateFlow<String> = transportState
    val bondedState = MutableStateFlow<List<Pair<String, String>>>(listOf("00:11:22:33:44:55" to "iQOO 15 Sales", "66:77:88:99:AA:BB" to "iQOO 15 Accounts"))
    override val bonded: StateFlow<List<Pair<String, String>>> = bondedState

    var pairingMode = false; private set
    val decisions = mutableListOf<Pair<PairingRequest, Boolean>>()
    val forgotten = mutableListOf<String>()
    val connectRequests = mutableListOf<String>()

    override fun setPairingMode(on: Boolean) { pairingMode = on }
    override fun accept(req: PairingRequest, ok: Boolean) { decisions.add(req to ok) }
    override fun forget(nodeId: String) { forgotten.add(nodeId); peersState.value = peersState.value.filterNot { it.nodeId == nodeId } }
    override suspend fun selectTransport(name: String) { transportState.value = name }
    override fun connectBonded(address: String) { connectRequests.add(address) }

    fun raisePairing(req: PairingRequest): Boolean = pairingFlow.tryEmit(req)

    companion object {
        fun tour(): FakePeerManager = FakePeerManager(listOf(
            Peer("a1b2c3", "Sales", pinned = true, connected = true, transport = "nearby", holds = listOf("sales", "q3_review.pptx")),
            Peer("d4e5f6", "Accounts", pinned = true, connected = false, transport = null, holds = listOf("invoices"))
        ))
    }
}
