package com.hivecore.engine.placement

import com.hivecore.core.SLog
import com.hivecore.core.WorkerActivityLog
import com.hivecore.core.WorkerEvent
import com.hivecore.core.WorkerEventKind
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.ModelFailBody
import com.hivecore.mesh.ModelReadyBody
import com.hivecore.mesh.ModelReqBody
import com.hivecore.mesh.MsgType
import com.hivecore.runtime.catalog.ErrorClasses
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.models.CapabilityPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Node side of placement (spec 8.3, 11.3), plugged into `SwarmNode.onModelReq`:
 *  1. resolve the catalog entry for (modelId, capability); a pinned sha256 that disagrees with the catalog is
 *     refused (P12), an unknown capability answers `unsupported`;
 *  2. `CapabilityPool.ensure(entry)` loads it: qairt first, the llama_cpp build once, then a classed failure;
 *     the reply is `MODEL_READY {loadMs, warm}` naming the build actually loaded, or `MODEL_FAIL {class}`;
 *  3. a load that outlives the request's `deadlineMs` answers `timeout`; a load that fails with oom / model_load /
 *     thermal starts this phone's own 10-minute cooldown for the capability, which its PROFILE publishes (a refusal
 *     before loading, such as a catalog mismatch, does not: the phone is fine, the request was not);
 *  4. models stay loaded after the job (8.4) until the next placement or the owner's own use evicts them.
 * [acceptWork] false (worker mode off) answers `unsupported` at once, except for this phone's own jobs.
 */
class ModelReqHandler(
    private val selfId: String,
    private val catalog: () -> ModelCatalog,
    private val pool: CapabilityPool,
    private val scope: CoroutineScope,
    private val cooldowns: CooldownTable? = null,
    private val acceptWork: () -> Boolean = { true },
    /** the worker timeline: the model chosen for the request's capability, then its load (display only) */
    private val activity: WorkerActivityLog? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(selfId: String, catalog: ModelCatalog, pool: CapabilityPool, scope: CoroutineScope, cooldowns: CooldownTable? = null,
                acceptWork: () -> Boolean = { true }) : this(selfId, { catalog }, pool, scope, cooldowns, acceptWork)

    /** The `SwarmNode.onModelReq` hook: loads off the dispatch thread and replies through [reply]. */
    fun handle(body: ModelReqBody, from: String, reply: suspend (MsgType, String) -> Result<Unit>) {
        scope.launch {
            val (type, out) = process(body, from)
            reply(type, out).onFailure { SLog.w(TAG, "$type for ${body.modelId} not sent: ${it.message}") }
        }
    }

    /** Loads and returns the reply (type, encoded body). */
    suspend fun process(body: ModelReqBody, from: String): Pair<MsgType, String> {
        fun fail(cls: String, msg: String?, cool: Boolean = false): Pair<MsgType, String> {
            if (cool && cls in ErrorClasses.COOLDOWN) cooldowns?.mark(selfId, body.capability)
            if (cool) emit(body, from, WorkerEventKind.MODEL_FAILED, body.modelId, error = "$cls ${msg.orEmpty()}".trim())
            SLog.w(TAG, "MODEL_REQ ${body.capability}/${body.modelId} from ${from.take(8)}: $cls ${msg.orEmpty()}")
            return MsgType.MODEL_FAIL to MeshJson.encode(ModelFailBody(body.planId, selfId, body.modelId, cls, msg))
        }
        if (body.commander != from) return fail(ErrorClasses.UNSUPPORTED, "commander is not the sender")
        if (from != selfId && !acceptWork()) return fail(ErrorClasses.UNSUPPORTED, "worker mode is off")
        val cat = catalog()
        val named = cat.entry(body.modelId, body.capability)
        val entry = named
            ?: cat.preferred(body.capability)
            ?: return fail(ErrorClasses.UNSUPPORTED, "capability ${body.capability} is not in this phone's catalog")
        val warmBefore = runCatching { entry.modelId in pool.warm() }.getOrNull()
        emit(body, from, WorkerEventKind.MODEL_SELECTED, entry.modelId, reason = if (named != null) "requested" else "preferred", warm = warmBefore)
        if (ModelCatalog.isHex64(body.sha256) && ModelCatalog.isHex64(entry.sha256) && !body.sha256.equals(entry.sha256, ignoreCase = true))
            return fail(ErrorClasses.MODEL_LOAD, "sha256 of ${entry.modelId} differs from the orchestrator's catalog")
        val deadline = body.deadlineMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        if (warmBefore != true) emit(body, from, WorkerEventKind.MODEL_LOADING, entry.modelId)
        val r = withTimeoutOrNull(deadline) { pool.ensure(entry) }
            ?: return fail(ErrorClasses.TIMEOUT, "load took longer than ${body.deadlineMs} ms")
        return r.fold(
            { li ->
                emit(body, from, WorkerEventKind.MODEL_READY, li.modelId, warm = li.warm, loadMs = li.loadMs)
                MsgType.MODEL_READY to MeshJson.encode(ModelReadyBody(body.planId, selfId, li.modelId, li.loadMs, li.warm))
            },
            { e -> fail(ErrorClasses.of(e).takeIf { it in ErrorClasses.MODEL_FAIL } ?: ErrorClasses.MODEL_LOAD, e.message, cool = true) })
    }

    private fun emit(body: ModelReqBody, from: String, kind: WorkerEventKind, modelId: String, reason: String? = null, warm: Boolean? = null,
                     loadMs: Long? = null, error: String? = null) {
        activity?.emit(WorkerEvent(body.planId, kind, selfId, from, clock(), capability = body.capability, modelId = modelId, reason = reason,
            warm = warm, loadMs = loadMs, error = error))
    }

    private companion object { const val TAG = "ModelReq" }
}
