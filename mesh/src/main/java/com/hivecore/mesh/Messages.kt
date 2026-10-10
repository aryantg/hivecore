package com.hivecore.mesh

import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.Hashing
import com.hivecore.core.NodeOutcome
import com.hivecore.core.Profile
import com.hivecore.core.TaskSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Plan 10: the seven message bodies, plus the swarm plan's (§8.1) at the end. Each travels as the `body` string of a signed envelope (Wire.kt); the
// signature covers the UTF-8 bytes of that string exactly as received, so the body JSON is never re-serialised
// by a verifier. Every body is signed by the node key; CONSENT_RES additionally carries `approvalSig`.

enum class MsgType {
    HELLO, JOB, CONSENT_REQ, CONSENT_RES, RESULT, RECEIPT, NOT_RELEVANT,
    // swarm plan §8.1
    PROFILE, PLAN, PLAN_CONSENT_REQ, PLAN_CONSENT_RES, TASK, TASK_RESULT, TASK_YIELD, TASK_CANCEL,
    BLOB, BLOB_DELETED, WORKLOAD, RELEASE,
    // v2 plan §12.1
    /** Noise XX handshake frames: plaintext framing only; never reach the router's verified stream */
    SESSION_INIT, SESSION_RESP, SESSION_FIN,
    /** admin-signed membership (§4.4), gossiped; REVOKE is a one-removal roster bump */
    ROSTER, REVOKE,
    /** WebRTC SDP / ICE inside the encrypted envelope, via the relay (§3.3) */
    SIGNAL,
    /** placement (§5.4) */
    MODEL_REQ, MODEL_READY, MODEL_FAIL,
    /** resume after an orchestrator restart (§11.3) */
    RECLAIM, RECLAIM_RES,
    /** orchestrator → deputy journal stream (§11.4) */
    JOURNAL,
    /** worker → orchestrator: one display-only worker timeline event (core.WorkerEvent) for a chunk of its split job */
    TASK_PROGRESS,
}

/** HELLO: sent on every connection and re-broadcast after ingest/delete. `pubkey` must hash to `nodeId`. */
@Serializable data class HelloBody(
    val nodeId: String,
    val name: String,
    val pubkey: String,
    val approvalPubkey: String? = null,
    val holds: List<String> = emptyList(),
    val caps: List<String> = emptyList(),   // "sql","docs","approval","npu"|"gpu"|"cpu"
    val ts: Long = 0L,
    /**
     * The table vocabulary the asker routes on (Relevance.vocabulary: table names and aliases, measure column names
     * and aliases; never values, identifiers or dimensions). Null from a node that predates routing: it is always asked.
     */
    val vocab: List<String>? = null,
    /** swarm plan §8.1: names and flags of the node's non-hidden tables and non-confidential docs (never values) */
    val catalog: Catalog? = null,
    /** sha256 of the loaded LLM bundle's genie_config + part sizes; replicas compare only with an equal hash */
    val modelHash: String? = null,
    val patternsVersion: String? = null,
    /** the packaged QAIRT build, e.g. "2.50.0.260828221209" (§4A.2) */
    val qairt: String? = null,
    /** v2 §12.2: the highest roster version this node holds (null = no roster yet) */
    val rosterVersion: Long? = null,
    /** v2 §12.2: this node can be reached through the relay */
    val relayCapable: Boolean = false,
)

/** JOB: asker → every participant. `nonce` + `ts` feed the replay guard. */
@Serializable data class JobBody(
    val jobId: String,
    val nonce: String,
    val ts: Long,
    val requester: String,
    val requesterName: String,
    val question: String
)

/** CONSENT_REQ: owner → asker, "awaiting approval"; the asker extends that node's deadline to 65 s. */
@Serializable data class ConsentReqBody(val jobId: String, val node: String, val intentHash: String)

/**
 * CONSENT_RES: owner → asker. `approvalSig` (base64 DER) is the biometric key over [Canonical.consent] and is
 * present iff `approved`. `signedBy` is "approval", or "node" for the debug adb hook only.
 */
@Serializable data class ConsentResBody(
    val jobId: String,
    val intentHash: String,
    val approved: Boolean,
    val node: String,
    val ts: Long,
    val signedBy: String = SIGNED_BY_APPROVAL,
    val approvalSig: String? = null
) {
    companion object { const val SIGNED_BY_APPROVAL = "approval"; const val SIGNED_BY_NODE = "node" }
}

@Serializable data class ResultTimings(val intentMs: Long? = null, val execMs: Long? = null)

/** RESULT: exactly one per node per job. `hash` = sha256Hex of the outcomes JSON ([hashOutcomes]). */
@Serializable data class ResultBody(
    val jobId: String,
    val node: String,
    val outcomes: List<NodeOutcome>,
    val rowsScanned: Long,
    val hash: String,
    val timings: ResultTimings = ResultTimings(),
    val backend: String = ""
) {
    companion object {
        fun hashOutcomes(outcomes: List<NodeOutcome>): String =
            Hashing.sha256Hex(MeshJson.json.encodeToString(ListSerializer(NodeOutcome.serializer()), outcomes))

        /** Builds a RESULT whose `hash` matches its `outcomes`. */
        fun of(jobId: String, node: String, outcomes: List<NodeOutcome>, rowsScanned: Long,
               timings: ResultTimings = ResultTimings(), backend: String = ""): ResultBody =
            ResultBody(jobId, node, outcomes, rowsScanned, hashOutcomes(outcomes), timings, backend)
    }

    fun hashMatches(): Boolean = hash == hashOutcomes(outcomes)
}

/**
 * RECEIPT: carries the sender's ledger entry for the job. `sig` is the sender's ledger signature (node key over
 * `entryHash`, base64 DER) so the receiver can store it as `peerSig` and verify it later with the pinned key.
 */
@Serializable data class ReceiptBody(
    val jobId: String,
    val node: String,
    val entryHash: String,
    val prevHash: String,
    val ts: Long,
    val sig: String? = null,
    /** The sender's entry kind ("ask" | "answer"): an `answer` receipt countersigns the receiver's `ask` entry and an
     *  `ask` receipt its `answer` entry. Null from older builds (then the newest open entry for the job is used). */
    val kind: String? = null
)

/** NOT_RELEVANT: the node holds nothing about the question; `holds` feeds the NoNode screen. */
@Serializable data class NotRelevantBody(val jobId: String, val nodeId: String, val holds: List<String> = emptyList())

/** Canonical strings that are signed outside the envelope. */
object Canonical {
    /** The bytes the approval (biometric) key signs for an approved consent. */
    fun consent(jobId: String, intentHash: String): String = "$jobId|$intentHash|true"
    /** The bytes the approval key signs for a plan-scoped consent (swarm plan §7): one fingerprint per plan. */
    fun planConsent(planId: String, planHash: String, intentHashes: List<String>): String =
        "$planId|$planHash|${intentHashes.sorted().joinToString(",")}|true"
}

/** The one Json instance for every body. Unknown keys are ignored so older peers can add fields. */
object MeshJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(s: String): T = json.decodeFromString(s)
}


// ---------------------------------------------------------------------------------------------------------------
// Swarm plan §8.1. Every body names its sender (`node`/`commander`/`owner`), which MeshRouter checks against the
// envelope's `from`; bodies with a nonce go through the replay guard like JOB.

/** PROFILE: capability heartbeat every 15 s and on eligibility changes (§6.1). */
@Serializable data class ProfileBody(val profile: Profile)

/** PLAN: commander → participants. `dag` is canonical JSON; `assignment` maps taskId → nodeId. */
@Serializable data class PlanBody(
    val planId: String,
    val planHash: String,
    val commander: String,
    val question: String,
    val dag: Dag,
    val assignment: Map<String, String>,
    val ts: Long,
    val nonce: String,
    /**
     * v2 §12.2 / C9: for a split job the dag holds one TaskSpec template per op and this is the ordered list of chunk
     * ids; owners and nodes authorise chunk TASKs against it. Empty for composite plans.
     */
    val chunkIds: List<String> = emptyList(),
)

/** PLAN_CONSENT_REQ: owner → commander, "awaiting approval" for every figure this plan asks of the owner (§7). */
@Serializable data class PlanConsentReqBody(val planId: String, val planHash: String, val node: String, val intentHashes: List<String>)

/**
 * PLAN_CONSENT_RES: owner → commander. `approvalSig` = biometric key over [Canonical.planConsent], present iff
 * approved; `refusal` carries the differencing guard's reason when the owner refused the plan outright.
 */
@Serializable data class PlanConsentResBody(
    val planId: String,
    val planHash: String,
    val intentHashes: List<String>,
    val approved: Boolean,
    val node: String,
    val ts: Long,
    val signedBy: String = ConsentResBody.SIGNED_BY_APPROVAL,
    val approvalSig: String? = null,
    val refusal: String? = null,
)

/** TASK: commander → executor. `inputs` inline (≤ 256 KB) keyed by upstream taskId; blobs by content hash. */
@Serializable data class TaskBody(
    val planId: String,
    val task: TaskSpec,
    val inputs: Map<String, JsonElement> = emptyMap(),
    val deadlineMs: Long,
    val modelHash: String? = null,
    val commander: String,
    val ts: Long,
    val nonce: String,
    /** split jobs, display only (the worker timeline): the chunk's 0-based index, the run's chunk count, its slice in words, the recipe stage */
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
    val label: String? = null,
    val stage: String? = null,
)

/** TASK_RESULT: executor → commander. `outputHash` = sha256 of canonical(output); `error` set on failure. */
@Serializable data class TaskResultBody(
    val planId: String,
    val taskId: String,
    val node: String,
    val output: JsonElement? = null,
    val outputHash: String? = null,
    val error: String? = null,
    val ms: Long = 0,
    val backend: String = "",
    val qairt: String? = null,
    /**
     * v2 §12.2: the failure class when `error` is set: oom | model_load | thermal | timeout | schema | yielded | other.
     * oom, model_load and thermal start a 10-minute capability cooldown on this node (§9.3 rule 4). Kept beside the
     * free-text `error` rather than replacing it so pre-v2 nodes and tests still parse.
     */
    val errorClass: String? = null,
    /**
     * A result too large for one envelope travels as a blob sent just before this message (BLOB chunks, owner = the
     * executor); `output` is then null and the receiving node restores it from the blob, checked against `outputHash`.
     */
    val outputBlob: String? = null,
)

/** TASK_YIELD (executor → commander, the owner picked the phone up) and TASK_CANCEL (commander → executor). */
@Serializable data class TaskSignalBody(val planId: String, val taskId: String, val node: String, val reason: String)

/** BLOB: one chunk of a released item; `blobId` = sha256 of the whole content; ≤ 900 KB of bytes per frame. */
@Serializable data class BlobBody(val blobId: String, val seq: Int, val total: Int, val bytesB64: String, val owner: String, val jobId: String)

/** BLOB_DELETED: worker → owner after the TASK_RESULT that used the blobs was countersigned (§4A.1). */
@Serializable data class BlobDeletedBody(val jobId: String, val node: String, val blobIds: List<String>)

/** WORKLOAD: submitter → participants; the plan itself still travels as a PLAN. */
@Serializable data class WorkloadBody(val jobId: String, val recipeId: String, val recipeVersion: String, val owner: String,
    val itemCount: Int, val release: String, val sink: String, val ts: Long, val nonce: String)

/** RELEASE: owner → workers: these blobs may be processed (only processed) until their results are countersigned. */
@Serializable data class ReleaseBody(val jobId: String, val owner: String, val blobIds: List<String>, val policy: String = "process_only", val ts: Long)

// ---------------------------------------------------------------------------------------------------------------
// v2 plan §12.1. Every body names its sender (`commander` / `node` / `from`), checked by MeshRouter against the
// envelope's `from`; MODEL_REQ and RECLAIM carry a nonce and go through the replay guard. Handshake frames
// (SESSION_*) have no body class here: they are consumed by the session layer below the router.

/** One roster member (§4.4). `pubkey` is the base64 DER P-256 key that hashes to `nodeId`. */
@Serializable data class RosterMember(val nodeId: String, val pubkey: String, val name: String, val addedTs: Long)

/**
 * ROSTER: admin → everyone, gossiped. `adminSig` is the admin's node key over the canonical JSON of the body without
 * `adminSig`; nodes keep the highest `version` whose signature verifies. `admin` names the admin's nodeId, so a roster
 * naming a successor is the admin handover.
 */
@Serializable data class RosterBody(
    val groupId: String,
    val version: Long,
    val admin: String,
    val members: List<RosterMember>,
    val revoked: List<String> = emptyList(),
    val adminSig: String = "",
)

/** REVOKE: admin → everyone; a roster bump with one removal. `adminSig` over the canonical body without `adminSig`. */
@Serializable data class RevokeBody(val groupId: String, val version: Long, val nodeId: String, val adminSig: String = "")

/** SIGNAL: WebRTC offer / answer / ICE candidate between two peers, carried inside the encrypted envelope via the relay (§3.3). */
@Serializable data class SignalBody(
    val sessionId: String,
    /** "offer" | "answer" | "candidate" | "close" */
    val kind: String,
    val from: String,
    val to: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

/** MODEL_REQ: orchestrator → node (§5.4): load `modelId` for `capability` (or confirm it is warm) before `deadlineMs`. */
@Serializable data class ModelReqBody(
    val planId: String,
    val capability: String,
    val modelId: String,
    val sha256: String,
    val deadlineMs: Long,
    val commander: String,
    val ts: Long,
    val nonce: String,
)

/** MODEL_READY: node → orchestrator. `warm` = the model was already loaded; `loadMs` = 0 then. */
@Serializable data class ModelReadyBody(val planId: String, val node: String, val modelId: String, val loadMs: Long, val warm: Boolean)

/** MODEL_FAIL: node → orchestrator. `errorClass` ∈ {oom, model_load, thermal, unsupported, timeout}; starts a 10-minute capability cooldown (§9.3). */
@Serializable data class ModelFailBody(
    val planId: String,
    val node: String,
    val modelId: String,
    @kotlinx.serialization.SerialName("class") val errorClass: String,
    val message: String? = null,
)

/** RECLAIM: a resuming orchestrator → every node in the plan's eligible set (§11.3): which of these chunks do you hold? */
@Serializable data class ReclaimBody(val planId: String, val chunkIds: List<String>, val commander: String, val ts: Long, val nonce: String)

/** One memoized chunk a node still holds. */
@Serializable data class ReclaimFound(val chunkId: String, val outputHash: String)

/** RECLAIM_RES: node → orchestrator; the orchestrator fetches each found output with a TASK carrying the same chunkId. */
@Serializable data class ReclaimResBody(val planId: String, val node: String, val found: List<ReclaimFound>)

/** JOURNAL: orchestrator → deputy (§11.4): journal rows `fromRow ..` as JSON, after each commit. */
@Serializable data class JournalBody(val jobId: String, val commander: String, val fromRow: Long, val rows: List<JsonElement>)
