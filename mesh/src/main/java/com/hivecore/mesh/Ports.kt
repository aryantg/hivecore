package com.hivecore.mesh

import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.Verified
import kotlinx.coroutines.flow.SharedFlow

// The seams the ask pipeline talks through (plan 11). They are small so the orchestrator and the node answerer
// can be driven by fakes in JVM tests; the adapters below are the only code that knows MeshRouter.

/** What the ask package needs from the mesh: identity, signed unicast/broadcast and the verified inbound stream. */
interface MeshRouterPort {
    val nodeId: String
    /** Every verified inbound message (replay 0: subscribe before the transport starts). */
    val verified: SharedFlow<Verified>
    suspend fun send(nodeId: String, type: MsgType, bodyJson: String): Result<Unit>
    /** Sends to every connected pinned node; returns the nodeIds reached (the job's participants besides self). */
    suspend fun broadcast(type: MsgType, bodyJson: String): List<String>
    /** The connected pinned nodes right now (the participants a JOB will be sent to). */
    fun connectedNodeIds(): List<String>
    /** Every pinned node, connected or not: one that is offline is a participant that cannot reply ("no reply from HR"). */
    fun pinnedNodeIds(): List<String> = connectedNodeIds()
}

class MeshRouterAdapter(val router: MeshRouter) : MeshRouterPort {
    override val nodeId: String get() = router.nodeId
    override val verified: SharedFlow<Verified> get() = router.verified
    override suspend fun send(nodeId: String, type: MsgType, bodyJson: String): Result<Unit> = router.send(nodeId, type, bodyJson)
    override suspend fun broadcast(type: MsgType, bodyJson: String): List<String> = router.broadcast(type, bodyJson)
    override fun connectedNodeIds(): List<String> = router.connectedNodeIds()
    override fun pinnedNodeIds(): List<String> = router.pinnedNodeIds()
}

