package com.hivecore.engine.journal

import com.hivecore.core.SqlDb
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A [JobJournal] whose rows can be copied between phones (§13.4): the deputy stream reads them with [rowsAfterAll],
 * a returning orchestrator writes the deputy's newer rows back with [importRows]. Row JSON is [JournalRows.wrap]:
 * `{seq, table, key, row}`.
 */
interface ReplicatedJournal : JobJournal {
    /** Highest row sequence held for [jobId] (any job when null); 0 when there is none. */
    fun highWater(jobId: String? = null): Long
    /** Every row of every job newer than [afterRow], with the new high-water mark. */
    fun rowsAfterAll(afterRow: Long): Pair<Long, List<JsonElement>>
    /**
     * Writes rows produced by another copy of this journal, keeping their sequence numbers. A row replaces the held row
     * for its key only when its sequence is newer. Returns the number of rows applied.
     */
    fun importRows(rows: List<JsonElement>): Int
}

/** The wire form of one journal row (§16.5 JOURNAL `rows[]`). */
object JournalRows {
    const val JOBS = "jobs"; const val CHUNKS = "chunks"; const val ATTEMPTS = "attempts"
    const val OUTPUTS = "outputs"; const val PLACEMENTS = "placements"; const val ESCALATIONS = "escalations"
    const val DECISIONS = "decisions"
    val TABLES = listOf(JOBS, CHUNKS, ATTEMPTS, OUTPUTS, PLACEMENTS, ESCALATIONS, DECISIONS)

    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }

    /** `{seq, table, key, row}`; [key] identifies the row for replace semantics (append-only rows use their seq). */
    fun wrap(seq: Long, table: String, key: String, row: JsonElement): JsonElement = buildJsonObject {
        put("seq", seq); put("table", table); put("key", key); put("row", row)
    }

    fun seqOf(e: JsonElement): Long? = runCatching { e.jsonObject["seq"]!!.jsonPrimitive.long }.getOrNull()
    fun tableOf(e: JsonElement): String? = runCatching { e.jsonObject["table"]!!.jsonPrimitive.content }.getOrNull()
    fun keyOf(e: JsonElement): String? = runCatching { e.jsonObject["key"]!!.jsonPrimitive.content }.getOrNull()
    fun rowOf(e: JsonElement): JsonElement? = (e as? JsonObject)?.get("row")
    fun jobIdOf(e: JsonElement): String? = runCatching { (rowOf(e) as JsonObject)["jobId"]!!.jsonPrimitive.content }.getOrNull()

    /**
     * The end marker of a deputy's answer to a pull request (§13.4): `{end: true, highWater}`. A streamed batch never
     * carries one, which is how a JOURNAL answer is told apart from a stream.
     */
    fun endMarker(highWater: Long): JsonElement = buildJsonObject { put("end", true); put("highWater", highWater) }
    fun isEnd(e: JsonElement): Boolean = (e as? JsonObject)?.get("end")?.let { (it as? JsonPrimitive)?.content == "true" } == true
    fun endHighWater(e: JsonElement): Long = runCatching { e.jsonObject["highWater"]!!.jsonPrimitive.long }.getOrDefault(0L)

    fun key(table: String, row: Any, seq: Long): String = when (row) {
        is JobRow -> "$table|${row.jobId}"
        is ChunkRow -> "$table|${row.jobId}|${row.chunkId}"
        is AttemptRow -> "$table|${row.jobId}|${row.chunkId}|${row.attempt}"
        is PlacementRow -> "$table|${row.jobId}|${row.nodeId}|${row.capability}"
        else -> "$table|$seq"
    }
}

/**
 * The orchestrator's write-ahead journal on SQLite (§13.1): the six tables `jobs`, `chunks`, `attempts`, `outputs`,
 * `placements`, `escalations` with the §13.1 columns, plus `seq`, a row sequence shared by every table of this
 * database (monotonic: every write, including a replace, takes the next number), kept in `journal_meta`.
 *
 * - `jobs` keyed by jobId; `chunks` by (jobId, chunkId); `attempts` by (jobId, chunkId, attempt); `placements` by
 *   (jobId, nodeId, capability): a later write replaces the row;
 * - `outputs` and `escalations` are append-only (keyed by seq).
 *
 * Every write runs in its own transaction and is committed before the call returns (write-ahead: the caller sends the
 * message or runs the merge only afterwards). The schema is created on open and missing columns are added
 * ([migrate]), so an older database opens with a newer build. The journal expects its own database file (the user's
 * imported tables live in the data store and may carry any name).
 */
class SqliteJobJournal(private val db: SqlDb) : ReplicatedJournal {

    init { migrate() }

    // ------------------------------------------------------------------ writes

    override fun begin(job: JobRow) = job(job)

    @Synchronized override fun job(row: JobRow) = write { seq -> putJob(row, seq) }
    @Synchronized override fun chunk(row: ChunkRow) = write { seq -> putChunk(row, seq) }
    @Synchronized override fun attempt(row: AttemptRow) = write { seq -> putAttempt(row, seq) }
    @Synchronized override fun output(row: OutputRow) = write { seq -> putOutput(row, seq) }
    @Synchronized override fun placement(row: PlacementRow) = write { seq -> putPlacement(row, seq) }
    @Synchronized override fun escalation(row: EscalationRow) = write { seq -> putEscalation(row, seq) }
    @Synchronized override fun decisions(rows: List<DecisionRow>) { if (rows.isNotEmpty()) db.tx { for (r in rows) putDecision(r, nextSeq()) } }

    /** Several rows in one transaction (the cut of a job: one `chunks` row per chunk), committed before returning. */
    @Synchronized
    fun chunks(rows: List<ChunkRow>) { if (rows.isNotEmpty()) db.tx { for (r in rows) putChunk(r, nextSeq()) } }

    /** One row, one transaction, committed before returning. */
    private fun write(put: (Long) -> Unit) { db.tx { put(nextSeq()) } }

    private fun nextSeq(): Long {
        val cur = (db.query("SELECT v FROM journal_meta WHERE k = 'seq'").firstOrNull()?.get("v") as Number?)?.toLong() ?: 0L
        val next = cur + 1
        db.exec("INSERT OR REPLACE INTO journal_meta(k, v) VALUES ('seq', ?)", listOf(next))
        return next
    }

    private fun bumpSeq(atLeast: Long) {
        val cur = (db.query("SELECT v FROM journal_meta WHERE k = 'seq'").firstOrNull()?.get("v") as Number?)?.toLong() ?: 0L
        if (atLeast > cur) db.exec("INSERT OR REPLACE INTO journal_meta(k, v) VALUES ('seq', ?)", listOf(atLeast))
    }

    private fun putJob(r: JobRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO jobs(jobId, planId, planHash, plannerTier, mode, state, createdTs, updatedTs, privacy, capsSnapshot, spec, seq) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        listOf(r.jobId, r.planId, r.planHash, r.plannerTier, r.mode, r.state, r.createdTs, r.updatedTs, r.privacy, enc(r.capsSnapshot), enc(r.spec), seq))

    private fun putChunk(r: ChunkRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO chunks(jobId, chunkId, \"index\", params, owner, state, attempts, recutOf, units, replicas, build, seq) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        listOf(r.jobId, r.chunkId, r.index, enc(r.params), r.owner, r.state, r.attempts, r.recutOf, r.units, r.replicas, r.build, seq))

    private fun putAttempt(r: AttemptRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO attempts(jobId, chunkId, attempt, nodeId, dispatchedTs, deadlineTs, outcome, outputHash, errorClass, seq) VALUES (?,?,?,?,?,?,?,?,?,?)",
        listOf(r.jobId, r.chunkId, r.attempt, r.nodeId, r.dispatchedTs, r.deadlineTs, r.outcome, r.outputHash, r.errorClass, seq))

    private fun putOutput(r: OutputRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO outputs(seq, jobId, chunkId, outputHash, outputJson, source) VALUES (?,?,?,?,?,?)",
        listOf(seq, r.jobId, r.chunkId, r.outputHash, enc(r.outputJson), r.source))

    private fun putPlacement(r: PlacementRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO placements(jobId, nodeId, capability, modelId, sha256, state, loadMs, seq) VALUES (?,?,?,?,?,?,?,?)",
        listOf(r.jobId, r.nodeId, r.capability, r.modelId, r.sha256, r.state, r.loadMs, seq))

    private fun putEscalation(r: EscalationRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO escalations(seq, jobId, stepId, \"trigger\", slug, bytesSent, tokensIn, tokensOut, costInr, ts, outcome) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        listOf(seq, r.jobId, r.stepId, r.trigger, r.slug, r.bytesSent, r.tokensIn, r.tokensOut, r.costInr, r.ts, r.outcome))

    private fun putDecision(r: DecisionRow, seq: Long) = db.exec(
        "INSERT OR REPLACE INTO decisions(seq, jobId, point, questionId, requestHash, answerJson, probabilities, modelSha256, calibrationVersion, llmCalls, ts) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        listOf(seq, r.jobId, r.point, r.questionId, r.requestHash, enc(r.answerJson), enc(r.probabilities), r.modelSha256, r.calibrationVersion, r.llmCalls, r.ts))

    // ------------------------------------------------------------------ reads

    @Synchronized
    override fun resumable(): List<JobRow> {
        val states = JobState.RESUMABLE.toList()
        return db.query("SELECT * FROM jobs WHERE state IN (${states.joinToString(",") { "?" }}) ORDER BY createdTs, jobId", states)
            .map(::jobOf)
    }

    /** Every job row, newest first (the Jobs screen's history). */
    @Synchronized
    fun jobs(limit: Int = 200): List<JobRow> =
        db.query("SELECT * FROM jobs ORDER BY createdTs DESC, jobId LIMIT ?", listOf(limit)).map(::jobOf)

    @Synchronized
    override fun load(jobId: String): JobSnapshot? {
        val job = db.query("SELECT * FROM jobs WHERE jobId = ?", listOf(jobId)).firstOrNull()?.let(::jobOf) ?: return null
        return JobSnapshot(
            job = job,
            chunks = db.query("SELECT * FROM chunks WHERE jobId = ? ORDER BY \"index\", chunkId", listOf(jobId)).map(::chunkOf),
            attempts = db.query("SELECT * FROM attempts WHERE jobId = ? ORDER BY chunkId, attempt", listOf(jobId)).map(::attemptOf),
            outputs = db.query("SELECT * FROM outputs WHERE jobId = ? ORDER BY seq", listOf(jobId)).map(::outputOf),
            placements = db.query("SELECT * FROM placements WHERE jobId = ? ORDER BY nodeId, capability", listOf(jobId)).map(::placementOf),
            escalations = db.query("SELECT * FROM escalations WHERE jobId = ? ORDER BY seq", listOf(jobId)).map(::escalationOf),
            decisions = db.query("SELECT * FROM decisions WHERE jobId = ? ORDER BY seq", listOf(jobId)).map(::decisionOf),
        )
    }

    @Synchronized
    override fun rowsAfter(jobId: String, afterRow: Long): Pair<Long, List<JsonElement>> = rows("jobId = ? AND seq > ?", listOf(jobId, afterRow), afterRow)

    @Synchronized
    override fun rowsAfterAll(afterRow: Long): Pair<Long, List<JsonElement>> = rows("seq > ?", listOf(afterRow), afterRow)

    @Synchronized
    override fun highWater(jobId: String?): Long {
        if (jobId == null) return (db.query("SELECT v FROM journal_meta WHERE k = 'seq'").firstOrNull()?.get("v") as Number?)?.toLong() ?: 0L
        return JournalRows.TABLES.maxOf { t ->
            (db.query("SELECT MAX(seq) AS s FROM $t WHERE jobId = ?", listOf(jobId)).first()["s"] as Number?)?.toLong() ?: 0L
        }
    }

    private fun rows(where: String, args: List<Any?>, afterRow: Long): Pair<Long, List<JsonElement>> {
        val out = ArrayList<Pair<Long, JsonElement>>()
        for (table in JournalRows.TABLES) {
            for (r in db.query("SELECT * FROM $table WHERE $where", args)) {
                val seq = (r["seq"] as Number).toLong()
                val (row, ser) = decode(table, r)
                @Suppress("UNCHECKED_CAST")
                val el = JournalRows.json.encodeToJsonElement(ser as KSerializer<Any>, row)
                out += seq to JournalRows.wrap(seq, table, JournalRows.key(table, row, seq), el)
            }
        }
        out.sortBy { it.first }
        return (out.lastOrNull()?.first ?: afterRow) to out.map { it.second }
    }

    // ------------------------------------------------------------------ replication

    @Synchronized
    override fun importRows(rows: List<JsonElement>): Int = db.tx {
        var applied = 0
        for (e in rows) {
            if (JournalRows.isEnd(e)) continue
            val seq = JournalRows.seqOf(e) ?: continue
            val table = JournalRows.tableOf(e) ?: continue
            val row = JournalRows.rowOf(e) ?: continue
            val ok = try { importOne(table, row, seq) } catch (ex: Exception) { false }
            if (ok) { applied++; bumpSeq(seq) }
        }
        applied
    }

    private fun importOne(table: String, el: JsonElement, seq: Long): Boolean {
        val j = JournalRows.json
        fun newer(sql: String, args: List<Any?>): Boolean {
            val held = (db.query(sql, args).firstOrNull()?.get("seq") as Number?)?.toLong() ?: return true
            return seq > held
        }
        when (table) {
            JournalRows.JOBS -> { val r = j.decodeFromJsonElement(JobRow.serializer(), el)
                if (!newer("SELECT seq FROM jobs WHERE jobId = ?", listOf(r.jobId))) return false; putJob(r, seq) }
            JournalRows.CHUNKS -> { val r = j.decodeFromJsonElement(ChunkRow.serializer(), el)
                if (!newer("SELECT seq FROM chunks WHERE jobId = ? AND chunkId = ?", listOf(r.jobId, r.chunkId))) return false; putChunk(r, seq) }
            JournalRows.ATTEMPTS -> { val r = j.decodeFromJsonElement(AttemptRow.serializer(), el)
                if (!newer("SELECT seq FROM attempts WHERE jobId = ? AND chunkId = ? AND attempt = ?", listOf(r.jobId, r.chunkId, r.attempt))) return false; putAttempt(r, seq) }
            JournalRows.PLACEMENTS -> { val r = j.decodeFromJsonElement(PlacementRow.serializer(), el)
                if (!newer("SELECT seq FROM placements WHERE jobId = ? AND nodeId = ? AND capability = ?", listOf(r.jobId, r.nodeId, r.capability))) return false; putPlacement(r, seq) }
            JournalRows.OUTPUTS -> { val r = j.decodeFromJsonElement(OutputRow.serializer(), el)
                if (db.query("SELECT 1 FROM outputs WHERE seq = ?", listOf(seq)).isNotEmpty()) return false; putOutput(r, seq) }
            JournalRows.ESCALATIONS -> { val r = j.decodeFromJsonElement(EscalationRow.serializer(), el)
                if (db.query("SELECT 1 FROM escalations WHERE seq = ?", listOf(seq)).isNotEmpty()) return false; putEscalation(r, seq) }
            JournalRows.DECISIONS -> { val r = j.decodeFromJsonElement(DecisionRow.serializer(), el)
                if (db.query("SELECT 1 FROM decisions WHERE seq = ?", listOf(seq)).isNotEmpty()) return false; putDecision(r, seq) }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ row mapping

    private fun decode(table: String, r: Map<String, Any?>): Pair<Any, KSerializer<*>> = when (table) {
        JournalRows.JOBS -> jobOf(r) to JobRow.serializer()
        JournalRows.CHUNKS -> chunkOf(r) to ChunkRow.serializer()
        JournalRows.ATTEMPTS -> attemptOf(r) to AttemptRow.serializer()
        JournalRows.OUTPUTS -> outputOf(r) to OutputRow.serializer()
        JournalRows.PLACEMENTS -> placementOf(r) to PlacementRow.serializer()
        JournalRows.DECISIONS -> decisionOf(r) to DecisionRow.serializer()
        else -> escalationOf(r) to EscalationRow.serializer()
    }

    private fun jobOf(r: Map<String, Any?>) = JobRow(s(r, "jobId"), s(r, "planId"), s(r, "planHash"), s(r, "plannerTier"), s(r, "state"),
        l(r, "createdTs"), l(r, "updatedTs"), s(r, "privacy"), dec(r["capsSnapshot"]), dec(r["spec"]), (r["mode"] as String?) ?: JobMode.LOCAL)

    private fun chunkOf(r: Map<String, Any?>) = ChunkRow(s(r, "jobId"), s(r, "chunkId"), l(r, "index").toInt(), dec(r["params"]) ?: JsonNull,
        r["owner"] as String?, s(r, "state"), l(r, "attempts").toInt(), r["recutOf"] as String?, (r["units"] as Number?)?.toInt() ?: 1,
        (r["replicas"] as Number?)?.toInt(), r["build"] as String?)

    private fun attemptOf(r: Map<String, Any?>) = AttemptRow(s(r, "jobId"), s(r, "chunkId"), l(r, "attempt").toInt(), s(r, "nodeId"),
        l(r, "dispatchedTs"), l(r, "deadlineTs"), s(r, "outcome"), r["outputHash"] as String?, r["errorClass"] as String?)

    private fun outputOf(r: Map<String, Any?>) = OutputRow(s(r, "jobId"), s(r, "chunkId"), s(r, "outputHash"), dec(r["outputJson"]) ?: JsonNull, s(r, "source"))

    private fun placementOf(r: Map<String, Any?>) = PlacementRow(s(r, "jobId"), s(r, "nodeId"), s(r, "capability"), s(r, "modelId"), s(r, "sha256"),
        s(r, "state"), (r["loadMs"] as Number?)?.toLong())

    private fun escalationOf(r: Map<String, Any?>) = EscalationRow(s(r, "jobId"), s(r, "stepId"), s(r, "trigger"), s(r, "slug"), l(r, "bytesSent"),
        l(r, "tokensIn").toInt(), l(r, "tokensOut").toInt(), (r["costInr"] as Number).toDouble(), l(r, "ts"), (r["outcome"] as String?) ?: "ran")

    private fun decisionOf(r: Map<String, Any?>) = DecisionRow(s(r, "jobId"), s(r, "point"), s(r, "questionId"), s(r, "requestHash"),
        dec(r["answerJson"]) ?: JsonNull, dec(r["probabilities"]) ?: JsonNull, s(r, "modelSha256"), s(r, "calibrationVersion"), l(r, "llmCalls").toInt(), l(r, "ts"))

    private fun s(r: Map<String, Any?>, k: String): String = r[k] as String
    private fun l(r: Map<String, Any?>, k: String): Long = (r[k] as Number).toLong()
    private fun enc(e: JsonElement?): String? = e?.toString()
    private fun dec(v: Any?): JsonElement? = (v as String?)?.let { JournalRows.json.parseToJsonElement(it) }

    // ------------------------------------------------------------------ schema

    private fun migrate() {
        db.exec("CREATE TABLE IF NOT EXISTS journal_meta(k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
        for ((name, cols) in SCHEMA) {
            val pk = PRIMARY_KEYS.getValue(name)
            db.exec("CREATE TABLE IF NOT EXISTS $name(${cols.joinToString(", ") { (c, t) -> "${q(c)} $t" }}, PRIMARY KEY (${pk.joinToString(", ") { q(it) }}))")
            // Forward migration: a database created by an older build gains the columns added since.
            val have = db.query("PRAGMA table_info($name)").map { it["name"] as String }.toSet()
            for ((c, t) in cols) if (c !in have) db.exec("ALTER TABLE $name ADD COLUMN ${q(c)} ${t.replace("NOT NULL", "").trim()}")
        }
        for (name in JournalRows.TABLES) db.exec("CREATE INDEX IF NOT EXISTS ${name}_seq ON $name(jobId, seq)")
        db.exec("INSERT OR REPLACE INTO journal_meta(k, v) VALUES ('schema', ?)", listOf(SCHEMA_VERSION))
    }

    private fun q(c: String) = "\"$c\""

    companion object {
        const val SCHEMA_VERSION = 1L

        /** §13.1 columns (plus `seq`; `spec` on jobs, `units` on chunks, `jobId` on outputs and `outcome` on escalations from the seam rows). */
        val SCHEMA: Map<String, List<Pair<String, String>>> = linkedMapOf(
            JournalRows.JOBS to listOf("jobId" to "TEXT NOT NULL", "planId" to "TEXT NOT NULL", "planHash" to "TEXT NOT NULL",
                "plannerTier" to "TEXT NOT NULL", "mode" to "TEXT NOT NULL DEFAULT 'local'", "state" to "TEXT NOT NULL",
                "createdTs" to "INTEGER NOT NULL", "updatedTs" to "INTEGER NOT NULL",
                "privacy" to "TEXT NOT NULL", "capsSnapshot" to "TEXT", "spec" to "TEXT", "seq" to "INTEGER NOT NULL"),
            JournalRows.CHUNKS to listOf("jobId" to "TEXT NOT NULL", "chunkId" to "TEXT NOT NULL", "index" to "INTEGER NOT NULL",
                "params" to "TEXT", "owner" to "TEXT", "state" to "TEXT NOT NULL", "attempts" to "INTEGER NOT NULL", "recutOf" to "TEXT",
                "units" to "INTEGER NOT NULL DEFAULT 1", "replicas" to "INTEGER", "build" to "TEXT", "seq" to "INTEGER NOT NULL"),
            JournalRows.ATTEMPTS to listOf("jobId" to "TEXT NOT NULL", "chunkId" to "TEXT NOT NULL", "attempt" to "INTEGER NOT NULL",
                "nodeId" to "TEXT NOT NULL", "dispatchedTs" to "INTEGER NOT NULL", "deadlineTs" to "INTEGER NOT NULL", "outcome" to "TEXT NOT NULL",
                "outputHash" to "TEXT", "errorClass" to "TEXT", "seq" to "INTEGER NOT NULL"),
            JournalRows.OUTPUTS to listOf("seq" to "INTEGER NOT NULL", "jobId" to "TEXT NOT NULL", "chunkId" to "TEXT NOT NULL",
                "outputHash" to "TEXT NOT NULL", "outputJson" to "TEXT NOT NULL", "source" to "TEXT NOT NULL"),
            JournalRows.PLACEMENTS to listOf("jobId" to "TEXT NOT NULL", "nodeId" to "TEXT NOT NULL", "capability" to "TEXT NOT NULL",
                "modelId" to "TEXT NOT NULL", "sha256" to "TEXT NOT NULL", "state" to "TEXT NOT NULL", "loadMs" to "INTEGER", "seq" to "INTEGER NOT NULL"),
            JournalRows.ESCALATIONS to listOf("seq" to "INTEGER NOT NULL", "jobId" to "TEXT NOT NULL", "stepId" to "TEXT NOT NULL",
                "trigger" to "TEXT NOT NULL", "slug" to "TEXT NOT NULL", "bytesSent" to "INTEGER NOT NULL", "tokensIn" to "INTEGER NOT NULL",
                "tokensOut" to "INTEGER NOT NULL", "costInr" to "REAL NOT NULL", "ts" to "INTEGER NOT NULL", "outcome" to "TEXT NOT NULL DEFAULT 'ran'"),
            JournalRows.DECISIONS to listOf("seq" to "INTEGER NOT NULL", "jobId" to "TEXT NOT NULL", "point" to "TEXT NOT NULL",
                "questionId" to "TEXT NOT NULL", "requestHash" to "TEXT NOT NULL", "answerJson" to "TEXT NOT NULL", "probabilities" to "TEXT NOT NULL",
                "modelSha256" to "TEXT NOT NULL", "calibrationVersion" to "TEXT NOT NULL", "llmCalls" to "INTEGER NOT NULL", "ts" to "INTEGER NOT NULL"),
        )

        val PRIMARY_KEYS: Map<String, List<String>> = mapOf(
            JournalRows.JOBS to listOf("jobId"),
            JournalRows.CHUNKS to listOf("jobId", "chunkId"),
            JournalRows.ATTEMPTS to listOf("jobId", "chunkId", "attempt"),
            JournalRows.OUTPUTS to listOf("seq"),
            JournalRows.PLACEMENTS to listOf("jobId", "nodeId", "capability"),
            JournalRows.ESCALATIONS to listOf("seq"),
            JournalRows.DECISIONS to listOf("seq"),
        )
    }
}
