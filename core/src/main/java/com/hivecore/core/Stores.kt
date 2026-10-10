package com.hivecore.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// Plan 6 "core/Stores.kt" — EVERY cross-package seam lives here, each with a fake in core/fakes, so any package
// compiles and tests alone. Frozen after Wave 0.
interface SchemaSource { fun schema(): Schema }
interface DataAdmin { suspend fun seed(role: NodeRole); fun deleteTable(name: String); fun deleteDoc(id: String) }
data class Chunk(val docId: String, val docName: String, val ordinal: Int, val locator: String, val text: String, val hash: String)
data class ScoredChunk(val chunk: Chunk, val score: Double, val matchedTerms: Int)
interface DocIndex { fun search(query: String, k: Int = 3): List<ScoredChunk>
                     fun relevant(query: String, k: Int = 3): List<ScoredChunk>   // already thresholded (plan 9); empty = not relevant
                     fun chunkAt(docName: String, locator: String): Chunk?; fun rebuild() }
data class PlanResult(val intents: IntentSet, val chip: AnswerChip, val raw: String, val ms: Long)
interface IntentPlanner { suspend fun plan(question: String, schema: Schema): Result<PlanResult> }        // llm: LLM → parse → retry → template
interface IntentCompiler { fun compile(intent: Intent, schema: Schema): Compiled }                        // data
interface QueryExecutor { fun run(q: Compiled.Query, table: TableDef, node: String): NodeOutcome }         // data: Tables or Refused(floor)
interface Joiner { fun join(tables: List<AggTable>): AggTable? }                                          // data: one table per node, chained USING
interface AnswerRenderer { fun render(tables: List<AggTable>, joined: AggTable?, docs: List<DocAnswer>): String
                           fun refusal(reasons: List<Pair<String, String>>): String; fun noNode(holdings: List<Pair<String, List<String>>>): String }
interface DocAnswerer { suspend fun answer(question: String, chunks: List<ScoredChunk>): Result<DocAnswer?> }  // docs: RAG + LLM; null = NOT_FOUND
interface Asker { fun ask(question: String, viaVoice: Boolean): Flow<AskProgress>; val pendingApprovals: StateFlow<List<PendingApproval>> }
data class PendingApproval(val jobId: String, val requester: String, val description: String, val intentHash: String, val deadlineAt: Long)
@Serializable data class LedgerEntry(val seq: Long, val jobId: String, val kind: String, val entry: String, val entryHash: String, val prevHash: String,
    val sig: String, val peerNode: String? = null, val peerEntryHash: String? = null, val peerSig: String? = null, val ts: Long)
interface LedgerStore { fun append(jobId: String, kind: String, entryJson: String): LedgerEntry
                        fun countersign(jobId: String, peerNode: String, peerEntryHash: String, peerSig: String)
                        /** Countersigns this node's entry of [kind] for the job (an asker also holds its own `answer` entry). */
                        fun countersign(jobId: String, kind: String?, peerNode: String, peerEntryHash: String, peerSig: String) =
                            countersign(jobId, peerNode, peerEntryHash, peerSig)
                        fun entries(limit: Int = 200): List<LedgerEntry>; fun verifyChain(): Boolean; fun retain(days: Int); fun markReset() }
sealed interface IngestSource { data class File(val path: String, val displayName: String) : IngestSource; data class Text(val text: String, val name: String) : IngestSource }
data class ErrorCard(val what: String, val advice: String)
data class IngestPreview(val token: String, val kind: String, val name: String, val summary: String, val appendTo: String?, val error: ErrorCard?)
data class IngestReceipt(val target: String, val rows: Long, val chunks: Int)
interface IngestPort { suspend fun preview(src: IngestSource): IngestPreview
                       suspend fun commit(preview: IngestPreview, choice: String /* append|new|queue|ocr */): Result<IngestReceipt> }
data class Peer(val nodeId: String, val name: String, val pinned: Boolean, val connected: Boolean, val transport: String?, val holds: List<String>)
interface PeerManager { val peers: StateFlow<List<Peer>>; val pairing: SharedFlow<PairingRequest>; val transportName: StateFlow<String>
                        val bonded: StateFlow<List<Pair<String, String>>>   // (address, name) of phones bonded in Settings
                        fun setPairingMode(on: Boolean); fun accept(req: PairingRequest, ok: Boolean)
                        fun forget(nodeId: String); suspend fun selectTransport(name: String /* "nearby" | "bt" */); fun connectBonded(address: String) }
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
interface Ocr { suspend fun lines(imagePath: String): Result<List<OcrLine>> }
data class CloseState(val running: Boolean, val optIn: Boolean, val backlog: Int, val blockedReason: String?, val lastLine: String?)
interface CloseController { val state: StateFlow<CloseState>; val workLog: StateFlow<List<String>>; fun setOptIn(on: Boolean)
                            fun runNow(): String? /* null = started, else the unmet condition */ }
interface Stt {
    val engineName: String; val partial: StateFlow<String>; suspend fun listen(): Result<String>; fun stop()
    /** Every hypothesis of the last [listen], best first (the returned text is the first); empty when there is only one. */
    val guesses: List<String> get() = emptyList()
}
interface Tts { fun speak(text: String, flush: Boolean = false); fun stop() }
interface DeviceState { val charging: StateFlow<Boolean>; val batteryTempC: StateFlow<Float?>; val thermal: StateFlow<Int>
                        val noInternet: StateFlow<Boolean>; val airplane: StateFlow<Boolean> }
interface ApprovalGate { val publicKeyB64: String?   // null = no biometric enrolled → HELLO caps omit "approval"
                         suspend fun request(jobId: String, requester: String, description: String, intentHash: String): ApprovalResult
                         /** Swarm plan §7: one fingerprint for every figure a plan asks of this owner; the approval key signs
                          *  `Canonical.planConsent(planId, planHash, intentHashes)`. Default: unsupported → declined. */
                         suspend fun requestPlan(planId: String, requester: String, description: String, planHash: String,
                                                 intentHashes: List<String>): ApprovalResult = ApprovalResult.Declined }
sealed interface ApprovalResult { data class Approved(val sig: ByteArray, val signedBy: String /* approval|node(debug only) */) : ApprovalResult
                                  data object Declined : ApprovalResult; data object TimedOut : ApprovalResult }
