package com.hivecore.cloud

import com.hivecore.core.Canon
import com.hivecore.core.LedgerStore
import com.hivecore.engine.journal.EscalationRow
import com.hivecore.engine.journal.JobJournal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The ledger `task` entry of a cloud-answered step (spec 15.3 step 3, 13.6). */
data class CloudTaskEntry(
    val jobId: String,
    val planId: String,
    val stepId: String,
    val chunkId: String?,
    val slug: String,
    val trigger: String,
    val bytesSent: Long,
    val tokensIn: Int,
    val tokensOut: Int,
    val costInr: Double,
    val costUsd: Double?,
    val attemptsReplaced: Int,
    val outputHash: String,
    val ts: Long,
) {
    val source: String get() = "cloud:$slug"

    fun toJson(): JsonObject = buildJsonObject {
        put("planId", planId); put("taskId", stepId); chunkId?.let { put("chunkId", it) }
        put("op", "escalation"); put("source", source); put("trigger", trigger)
        put("bytesSent", bytesSent); put("tokensIn", tokensIn); put("tokensOut", tokensOut)
        put("costInr", costInr); costUsd?.let { put("costUsd", it) }
        put("attemptsReplaced", attemptsReplaced); put("outputHash", outputHash); put("ts", ts)
    }
}

/** Every gate decision, including refusals (spec 13.1 `escalations`). */
data class GateDecision(
    val jobId: String,
    val stepId: String,
    val trigger: String,
    /** "ran" | "refused" | "failed" */
    val outcome: String,
    val reason: String?,
    val slug: String?,
    val bytesSent: Long,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val ts: Long,
)

/** Where the gate writes (spec 15.3 step 3). `:app` adapts the ledger and the journal; tests record. */
interface LedgerSink {
    fun task(entry: CloudTaskEntry)
    fun decision(d: GateDecision) {}
}

/** The real sink: a `task` entry on the hash chain and an `escalations` row in the journal. */
class LedgerStoreSink(private val ledger: LedgerStore, private val journal: JobJournal? = null) : LedgerSink {
    override fun task(entry: CloudTaskEntry) {
        ledger.append(entry.jobId, "task", Canon.canonical(entry.toJson()))
    }
    override fun decision(d: GateDecision) {
        journal?.escalation(EscalationRow(d.jobId, d.stepId, d.trigger, d.slug ?: "", d.bytesSent, d.tokensIn, d.tokensOut, d.costUsd, d.ts,
            outcome = if (d.outcome == "ran") "ran" else "${d.outcome}:${d.reason ?: ""}"))
    }
}

/** Records everything for tests. */
class RecordingLedgerSink : LedgerSink {
    val tasks = ArrayList<CloudTaskEntry>()
    val decisions = ArrayList<GateDecision>()
    override fun task(entry: CloudTaskEntry) { tasks += entry }
    override fun decision(d: GateDecision) { decisions += d }
    /** Convenience for assertions: the primitive fields of the ledger JSON. */
    fun lastJson(): Map<String, String> = tasks.last().toJson().mapValues { (it.value as JsonPrimitive).content }
}
