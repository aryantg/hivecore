package com.hivecore.engine.journal

import com.hivecore.core.Canon
import com.hivecore.core.SLog
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouterPort
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ReclaimBody
import com.hivecore.mesh.ReclaimFound
import com.hivecore.mesh.ReclaimResBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Node side of RECLAIM (§13.3, §16.5): answers `RECLAIM {planId, chunkIds, commander, ts, nonce}` with
 * `RECLAIM_RES {planId, node, found: [{chunkId, outputHash}]}` from the chunk memo (and, on a deputy, from the
 * orchestrator's streamed outputs: [extra]). Always answers, with an empty `found` when nothing is held, so the
 * resuming orchestrator knows this phone is alive.
 *
 * The router already enforces `commander == from` and the replay guard on RECLAIM before the body reaches the
 * verified stream; [answer] checks the sender rule again so a body fed in directly is held to the same rule.
 *
 * Wiring: [start] subscribes to the port's verified stream (independent of SwarmNode, which leaves RECLAIM to its
 * `onReclaim` hook); [handler] is the same logic for that hook when the integration prefers one dispatcher. Use one
 * of the two, not both.
 */
class ReclaimResponder(
    private val port: MeshRouterPort,
    private val memo: ChunkMemo,
    /** (commander, chunkIds) -> outputs held for that commander outside the memo (the deputy store). */
    var extra: ((String, List<String>) -> List<ReclaimFound>)? = null,
) {
    private var job: Job? = null

    /** RECLAIMs answered (for tests and the Swarm screen). */
    @Volatile var answered: Int = 0
        private set

    /** Starts answering RECLAIMs from the verified stream. Subscribe before the router starts; call once. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            port.verified.collect { v ->
                if (v.type != MsgType.RECLAIM) return@collect
                val body = try { MeshJson.decode<ReclaimBody>(v.body) } catch (e: Exception) {
                    SLog.w(TAG, "RECLAIM from ${v.from.take(8)} does not parse: ${e.message}"); return@collect
                }
                val res = answer(body, v.from) ?: run { SLog.w(TAG, "RECLAIM from ${v.from.take(8)} names another commander; ignored"); return@collect }
                port.send(v.from, MsgType.RECLAIM_RES, MeshJson.encode(res))
                    .onFailure { SLog.w(TAG, "RECLAIM_RES to ${v.from.take(8)} not sent: ${it.message}") }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** The answer to [body] from [from]; null when the body names another commander. */
    fun answer(body: ReclaimBody, from: String): ReclaimResBody? {
        if (body.commander != from) return null
        val ids = body.chunkIds.distinct()
        val found = LinkedHashMap<String, ReclaimFound>()
        try {
            for (h in memo.holding(ids)) if (Canon.hash(h.outputJson) == h.outputHash) found.putIfAbsent(h.chunkId, ReclaimFound(h.chunkId, h.outputHash))
        } catch (e: Exception) { SLog.w(TAG, "memo lookup: ${e.message}") }
        try {
            extra?.invoke(from, ids.filter { it !in found })?.forEach { found.putIfAbsent(it.chunkId, it) }
        } catch (e: Exception) { SLog.w(TAG, "deputy lookup: ${e.message}") }
        answered++
        SLog.d(TAG, "RECLAIM ${body.planId} from ${from.take(8)}: ${found.size} of ${ids.size} held")
        return ReclaimResBody(body.planId, port.nodeId, ids.mapNotNull { found[it] })
    }

    /** `SwarmNode.onReclaim` form of the same answer (the hook's reply lambda sends RECLAIM_RES). */
    fun handler(scope: CoroutineScope): (ReclaimBody, String, suspend (MsgType, String) -> Result<Unit>) -> Unit = { body, from, reply ->
        val res = answer(body, from)
        if (res == null) SLog.w(TAG, "RECLAIM from ${from.take(8)} names commander ${body.commander.take(8)}; ignored")
        else scope.launch {
            reply(MsgType.RECLAIM_RES, MeshJson.encode(res)).onFailure { SLog.w(TAG, "RECLAIM_RES to ${from.take(8)} not sent: ${it.message}") }
        }
    }

    private companion object { const val TAG = "Reclaim" }
}
