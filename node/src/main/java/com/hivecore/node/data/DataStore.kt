package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.DataAdmin
import com.hivecore.core.DocDef
import com.hivecore.core.NodeRole
import com.hivecore.core.SLog
import com.hivecore.core.Schema
import com.hivecore.core.SchemaSource
import com.hivecore.core.SqlDb
import com.hivecore.core.TableDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/** Reads an asset by its path under `assets/` (on Android `AssetManager.open`; in tests the fixtures folder). */
fun interface AssetReader { fun open(path: String): InputStream }

/**
 * Private mode (swarm plan §7, optional, default off): tables and documents the owner marked "never leaves this
 * phone". The splitter reads these before chunking work out to idle phones; unmarked data may be split freely.
 * Stored in DataStore's `_private` table (kind = "table" | "doc", id = table name | doc id).
 */
interface PrivateMarks {
    fun privateTables(): Set<String>
    fun privateDocs(): Set<String>
    fun setTablePrivate(table: String, on: Boolean)
    fun setDocPrivate(docId: String, on: Boolean)
}

/** What an import did. */
data class ImportResult(val table: String, val rows: Long, val appended: Boolean, val sourceId: Long, val columns: List<String>)

/**
 * The node's tables (plan 7): system tables, imports with type inference and the append rule, the derived
 * `month` column, automatic flags for dropped sheets, the seed manifest, the schema, deletes, the intent log and
 * the demo reset. Documents live in the `docs` package; the hooks below let it plug in without a dependency.
 *
 * User tables are `t_<name>` with the file's (normalised) columns plus `_src INTEGER` (the `_sources.id` each row
 * came from). `_columns.derived = 1` marks columns computed at import (`month` from `date`); they are ignored when
 * matching a new file's column set against existing tables.
 */
class DataStore(
    val db: SqlDb,
    private val assets: AssetReader = AssetReader { throw IllegalStateException("no assets: $it") },
    private val roleProvider: () -> NodeRole = { NodeRole.HR },
) : DataAdmin, SchemaSource, PrivateMarks {

    /** Set by the docs package: delete one document (id) — a no-op until then. */
    var docDeleter: (String) -> Unit = { }
    /** Set by the docs package: seed the role's documents (asset file names under `seed/`). */
    var docSeeder: (NodeRole, List<String>) -> Unit = { _, _ -> }
    /** Set by the docs package: the documents for the schema. */
    var docLister: () -> List<DocDef> = { emptyList() }
    /** Set by the docs package: delete every non-seed document (demo reset). */
    var docResetter: () -> Unit = { }

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- migrate

    fun migrate() {
        db.exec("CREATE TABLE IF NOT EXISTS _tables (name TEXT PRIMARY KEY, aliases TEXT NOT NULL DEFAULT '[]', confidential INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)")
        db.exec("CREATE TABLE IF NOT EXISTS _columns (tbl TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, confidential INTEGER NOT NULL DEFAULT 0, identifier INTEGER NOT NULL DEFAULT 0, groupable INTEGER NOT NULL DEFAULT 0, aliases TEXT NOT NULL DEFAULT '[]', ord INTEGER NOT NULL, derived INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (tbl, name))")
        db.exec("CREATE TABLE IF NOT EXISTS _sources (id INTEGER PRIMARY KEY AUTOINCREMENT, tbl TEXT NOT NULL, fileName TEXT NOT NULL, rows INTEGER NOT NULL, importedAt INTEGER NOT NULL, seed INTEGER NOT NULL DEFAULT 0)")
        db.exec("CREATE TABLE IF NOT EXISTS _intent_log (id INTEGER PRIMARY KEY AUTOINCREMENT, jobId TEXT, question TEXT, role TEXT, rawOutput TEXT, parsedOk INTEGER, sql TEXT, validation TEXT, ms INTEGER, backend TEXT, ts INTEGER NOT NULL)")
        db.exec("CREATE TABLE IF NOT EXISTS _rollups (name TEXT PRIMARY KEY, tbl TEXT NOT NULL, signature TEXT NOT NULL, builtAt INTEGER NOT NULL)")
        db.exec("CREATE TABLE IF NOT EXISTS _meta (key TEXT PRIMARY KEY, value TEXT)")
        db.exec("CREATE TABLE IF NOT EXISTS _private (kind TEXT NOT NULL, id TEXT NOT NULL, markedAt INTEGER NOT NULL, PRIMARY KEY (kind, id))")
        SLog.d(TAG, "migrated")
    }

    // ---------------------------------------------------------------- import

    /**
     * Imports one sheet. Columns are normalised; types inferred (INTEGER if every non-empty value parses as Long,
     * REAL if as Double, else TEXT). If a table exists whose non-derived column set equals the file's, the rows
     * are appended under a new `_src`; else `t_<normalised name>` is created (suffixed `_2`… on a clash). A table
     * with a `date` column and no `month` gets `month = substr(date,1,7)`, registered groupable.
     */
    fun importTable(name: String, header: List<String>, rows: List<List<String>>, fileName: String, seed: Boolean): ImportResult {
        val cols = normaliseHeader(header)
        val types = cols.indices.map { i -> inferType(rows.asSequence().map { it.getOrNull(i) ?: "" }) }
        val target = appendTargetFor(cols)
        return db.tx {
            val now = System.currentTimeMillis()
            val table: String
            val appended: Boolean
            if (target != null) {
                table = target
                appended = true
                widenTypes(table, cols, types)
            } else {
                table = freshTableName(name)
                appended = false
                createTable(table, cols, types, now)
            }
            db.exec("INSERT INTO _sources (tbl, fileName, rows, importedAt, seed) VALUES (?, ?, ?, ?, ?)", listOf(table, fileName, rows.size.toLong(), now, if (seed) 1L else 0L))
            val srcId = (db.query("SELECT MAX(id) AS id FROM _sources")[0]["id"] as Long)
            insertRows(table, cols, types, rows, srcId)
            val derived = derivedColumns(table)
            if ("month" in derived) db.exec("UPDATE t_$table SET month = substr(date, 1, 7) WHERE _src = ?", listOf(srcId))
            if (!seed && !isSeedTable(table)) autoFlags(table)
            SLog.d(TAG, "import ${fileName}: ${rows.size} rows -> $table (${if (appended) "append" else "new"}) src=$srcId")
            ImportResult(table, rows.size.toLong(), appended, srcId, cols)
        }
    }

    /** The existing table whose non-derived column set equals this header's, or null (for ingest previews). */
    fun appendTargetFor(header: List<String>): String? {
        val want = normaliseHeader(header).toSet()
        for (t in tableNames()) {
            val have = db.query("SELECT name FROM _columns WHERE tbl = ? AND derived = 0", listOf(t)).map { it["name"] as String }.toSet()
            if (have == want) return t
        }
        return null
    }

    private fun createTable(table: String, cols: List<String>, types: List<ColType>, now: Long) {
        val defs = ArrayList<String>()
        val registry = ArrayList<Triple<String, ColType, Boolean>>()   // name, type, derived
        for (i in cols.indices) {
            defs.add("${q(cols[i])} ${types[i].name}")
            registry.add(Triple(cols[i], types[i], false))
            if (cols[i] == "date" && "month" !in cols) { defs.add("month TEXT"); registry.add(Triple("month", ColType.TEXT, true)) }
        }
        defs.add("_src INTEGER NOT NULL")
        db.exec("CREATE TABLE ${q("t_$table")} (${defs.joinToString(", ")})")
        db.exec("CREATE INDEX IF NOT EXISTS ${q("ix_t_${table}_src")} ON ${q("t_$table")} (_src)")
        db.exec("INSERT INTO _tables (name, aliases, confidential, createdAt) VALUES (?, '[]', 0, ?)", listOf(table, now))
        registry.forEachIndexed { ord, (n, t, derived) ->
            db.exec("INSERT INTO _columns (tbl, name, type, confidential, identifier, groupable, aliases, ord, derived) VALUES (?, ?, ?, 0, 0, ?, '[]', ?, ?)",
                listOf(table, n, t.name, if (derived) 1L else 0L, ord.toLong(), if (derived) 1L else 0L))
        }
    }

    private fun widenTypes(table: String, cols: List<String>, types: List<ColType>) {
        val have = db.query("SELECT name, type FROM _columns WHERE tbl = ?", listOf(table)).associate { it["name"] as String to ColType.valueOf(it["type"] as String) }
        for (i in cols.indices) {
            val old = have[cols[i]] ?: continue
            if (types[i].ordinal > old.ordinal) db.exec("UPDATE _columns SET type = ? WHERE tbl = ? AND name = ?", listOf(types[i].name, table, cols[i]))
        }
    }

    private fun insertRows(table: String, cols: List<String>, types: List<ColType>, rows: List<List<String>>, srcId: Long) {
        if (rows.isEmpty()) return
        val width = cols.size + 1
        val perStatement = maxOf(1, 900 / width)
        val colList = cols.joinToString(", ") { q(it) } + ", _src"
        val one = "(" + List(width) { "?" }.joinToString(", ") + ")"
        var i = 0
        while (i < rows.size) {
            val chunk = rows.subList(i, minOf(rows.size, i + perStatement))
            val args = ArrayList<Any?>(chunk.size * width)
            for (r in chunk) {
                for (c in cols.indices) args.add(convert(r.getOrNull(c) ?: "", types[c]))
                args.add(srcId)
            }
            db.exec("INSERT INTO ${q("t_$table")} ($colList) VALUES " + List(chunk.size) { one }.joinToString(", "), args)
            i += chunk.size
        }
    }

    /**
     * Plan 7 "automatic flags": a TEXT column is groupable when its distinct count <= max(20, 10 % of rows);
     * identifier when its distinct count >= 90 % of rows or its name has a token in name|id|email|phone|mobile;
     * numeric columns never groupable; confidential only when the owner marks it (kept as is).
     */
    fun autoFlags(table: String) {
        val rows = rowCount(table)
        val cols = db.query("SELECT name, type, derived FROM _columns WHERE tbl = ? ORDER BY ord", listOf(table))
        for (c in cols) {
            val name = c["name"] as String
            val type = c["type"] as String
            if (type != ColType.TEXT.name) {
                db.exec("UPDATE _columns SET groupable = 0, identifier = 0 WHERE tbl = ? AND name = ?", listOf(table, name))
                continue
            }
            val distinct = db.query("SELECT COUNT(DISTINCT ${q(name)}) AS d FROM ${q("t_$table")}")[0]["d"] as Long
            val groupable = (c["derived"] as Long) == 1L || distinct <= maxOf(20L, rows / 10)
            val identifier = (rows > 0 && distinct * 10 >= rows * 9) || name.split('_').any { it in IDENTIFIER_TOKENS }
            db.exec("UPDATE _columns SET groupable = ?, identifier = ? WHERE tbl = ? AND name = ?",
                listOf(if (groupable) 1L else 0L, if (identifier) 1L else 0L, table, name))
        }
    }

    // ---------------------------------------------------------------- seed

    override suspend fun seed(role: NodeRole) = seedNow(role)

    /** Applies `seed/seed_manifest.json` for the role: imports its tables (idempotent) and sets flags/aliases. */
    fun seedNow(role: NodeRole) {
        val manifest = assets.open("seed/seed_manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
        seedFromManifest(role, manifest)
    }

    fun seedFromManifest(role: NodeRole, manifestJson: String) {
        val root = json.parseToJsonElement(manifestJson).jsonObject
        val roleObj = root["roles"]?.jsonObject?.get(role.name)?.jsonObject
        if (roleObj == null) { SLog.w(TAG, "seed: no manifest entry for $role"); return }
        val tables = root["tables"]?.jsonObject ?: JsonObject(emptyMap())
        for (tn in roleObj["tables"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()) {
            val spec = tables[tn]?.jsonObject
            if (spec == null) { SLog.w(TAG, "seed: table $tn missing from manifest"); continue }
            val file = spec["file"]?.jsonPrimitive?.content ?: "$tn.csv"
            val already = db.query("SELECT COUNT(*) AS n FROM _sources WHERE tbl = ? AND seed = 1", listOf(tn))[0]["n"] as Long
            if (already == 0L) {
                val data = assets.open("seed/$file").use { CsvReader.read(it) }
                val r = importTable(tn, data.header, data.rows, file, seed = true)
                if (r.table != tn) SLog.w(TAG, "seed: $file landed in ${r.table}, expected $tn")
            }
            applyManifest(tn, spec)
        }
        val docs = roleObj["docs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        docSeeder(role, docs)
        db.exec("INSERT OR REPLACE INTO _meta (key, value) VALUES ('seeded_role', ?)", listOf(role.name))
        SLog.d(TAG, "seeded $role")
    }

    private fun applyManifest(table: String, spec: JsonObject) {
        if (!tableExists(table)) return
        val aliases = spec["aliases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        // The manifest's table-level "confidential" summarises the column flags (plan 7 lists only salary and
        // commission as confidential). Applying it to _tables would make every employees query — including the
        // Q1 headcount — need an approval, so only column flags are applied; the owner can still mark a whole
        // table on the Data screen (setTableConfidential).
        if (spec["confidential"]?.jsonPrimitive?.booleanOrNull == true) SLog.d(TAG, "manifest: table-level confidential on $table noted; column flags apply")
        db.exec("UPDATE _tables SET aliases = ? WHERE name = ?", listOf(toJsonArray(aliases), table))
        for (c in spec["columns"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = c.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val colAliases = o["aliases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            db.exec("UPDATE _columns SET confidential = ?, identifier = ?, groupable = ?, aliases = ? WHERE tbl = ? AND name = ?",
                listOf(flag(o, "confidential"), flag(o, "identifier"), flag(o, "groupable"), toJsonArray(colAliases), table, name))
        }
    }

    private fun flag(o: JsonObject, key: String): Long = if (o[key]?.jsonPrimitive?.booleanOrNull == true) 1L else 0L

    // ---------------------------------------------------------------- schema

    override fun schema(): Schema = Schema(roleProvider(), tables(), docLister())

    fun tables(): List<TableDef> = db.query("SELECT name, aliases, confidential FROM _tables ORDER BY createdAt, name").map { t ->
        val name = t["name"] as String
        val cols = db.query("SELECT name, type, confidential, identifier, groupable, aliases FROM _columns WHERE tbl = ? ORDER BY ord", listOf(name)).map { c ->
            ColumnDef(c["name"] as String, ColType.valueOf(c["type"] as String), (c["confidential"] as Long) == 1L,
                (c["identifier"] as Long) == 1L, (c["groupable"] as Long) == 1L, fromJsonArray(c["aliases"] as String))
        }
        TableDef(name, cols, rowCount(name), fromJsonArray(t["aliases"] as String), (t["confidential"] as Long) == 1L)
    }

    fun table(name: String): TableDef? = tables().firstOrNull { it.name == name }
    fun tableNames(): List<String> = db.query("SELECT name FROM _tables ORDER BY createdAt, name").map { it["name"] as String }
    fun tableExists(name: String): Boolean = db.query("SELECT 1 FROM _tables WHERE name = ?", listOf(name)).isNotEmpty()
    fun rowCount(table: String): Long = if (tableExists(table)) db.query("SELECT COUNT(*) AS n FROM ${q("t_$table")}")[0]["n"] as Long else 0L
    fun derivedColumns(table: String): List<String> = db.query("SELECT name FROM _columns WHERE tbl = ? AND derived = 1 ORDER BY ord", listOf(table)).map { it["name"] as String }
    fun isSeedTable(table: String): Boolean = (db.query("SELECT COUNT(*) AS n FROM _sources WHERE tbl = ? AND seed = 1", listOf(table))[0]["n"] as Long) > 0

    data class Source(val id: Long, val table: String, val fileName: String, val rows: Long, val importedAt: Long, val seed: Boolean)
    fun sources(table: String? = null): List<Source> =
        (if (table == null) db.query("SELECT * FROM _sources ORDER BY id") else db.query("SELECT * FROM _sources WHERE tbl = ? ORDER BY id", listOf(table)))
            .map { Source(it["id"] as Long, it["tbl"] as String, it["fileName"] as String, it["rows"] as Long, it["importedAt"] as Long, (it["seed"] as Long) == 1L) }

    // ---------------------------------------------------------------- flags, deletes, reset

    fun setConfidential(table: String, column: String, on: Boolean) {
        db.exec("UPDATE _columns SET confidential = ? WHERE tbl = ? AND name = ?", listOf(if (on) 1L else 0L, table, column))
        SLog.d(TAG, "confidential $table.$column = $on")
    }

    fun setTableConfidential(table: String, on: Boolean) {
        db.exec("UPDATE _tables SET confidential = ? WHERE name = ?", listOf(if (on) 1L else 0L, table))
    }

    // ---------------------------------------------------------------- private mode (swarm plan §7, optional)

    override fun privateTables(): Set<String> = privateIds(KIND_TABLE)
    override fun privateDocs(): Set<String> = privateIds(KIND_DOC)
    override fun setTablePrivate(table: String, on: Boolean) = setPrivate(KIND_TABLE, table, on)
    override fun setDocPrivate(docId: String, on: Boolean) = setPrivate(KIND_DOC, docId, on)
    /** True when the owner marked [table] private: its rows/chunks are never split out to other phones. */
    fun isTablePrivate(table: String): Boolean = table in privateTables()
    /** True when the owner marked document [docId] private. */
    fun isDocPrivate(docId: String): Boolean = docId in privateDocs()

    private fun privateIds(kind: String): Set<String> =
        db.query("SELECT id FROM _private WHERE kind = ?", listOf(kind)).map { it["id"] as String }.toSet()

    private fun setPrivate(kind: String, id: String, on: Boolean) {
        if (on) db.exec("INSERT OR REPLACE INTO _private (kind, id, markedAt) VALUES (?, ?, ?)", listOf(kind, id, System.currentTimeMillis()))
        else db.exec("DELETE FROM _private WHERE kind = ? AND id = ?", listOf(kind, id))
        SLog.d(TAG, "private $kind $id = $on")
    }

    override fun deleteTable(name: String) {
        db.tx { deleteTableNoTx(name) }
        SLog.d(TAG, "deleted table $name")
    }

    private fun deleteTableNoTx(name: String) {
        dropRollupsFor(name)
        if (tableExists(name)) db.exec("DROP TABLE IF EXISTS ${q("t_$name")}")
        db.exec("DELETE FROM _columns WHERE tbl = ?", listOf(name))
        db.exec("DELETE FROM _sources WHERE tbl = ?", listOf(name))
        db.exec("DELETE FROM _tables WHERE name = ?", listOf(name))
        db.exec("DELETE FROM _private WHERE kind = ? AND id = ?", listOf(KIND_TABLE, name))
    }

    override fun deleteDoc(id: String) {
        docDeleter(id)
        db.exec("DELETE FROM _private WHERE kind = ? AND id = ?", listOf(KIND_DOC, id))
    }

    /** Removes the rows of every non-seed source; tables left empty are dropped (plan 16.3). */
    fun deleteNonSeedSources() {
        db.tx {
            for (s in sources().filter { !it.seed }) {
                if (tableExists(s.table)) db.exec("DELETE FROM ${q("t_${s.table}")} WHERE _src = ?", listOf(s.id))
                db.exec("DELETE FROM _sources WHERE id = ?", listOf(s.id))
            }
            for (t in tableNames()) if (rowCount(t) == 0L && !isSeedTable(t)) deleteTableNoTx(t)
        }
        SLog.d(TAG, "non-seed sources removed")
    }

    /** Drops every rollup table and clears `_rollups`. */
    fun dropRollups() {
        db.tx {
            for (r in db.query("SELECT name FROM _rollups")) db.exec("DROP TABLE IF EXISTS ${q(r["name"] as String)}")
            db.exec("DELETE FROM _rollups")
        }
    }

    private fun dropRollupsFor(table: String) {
        for (r in db.query("SELECT name FROM _rollups WHERE tbl = ?", listOf(table))) db.exec("DROP TABLE IF EXISTS ${q(r["name"] as String)}")
        db.exec("DELETE FROM _rollups WHERE tbl = ?", listOf(table))
    }

    /** The table/source/rollup part of RESET_DEMO; documents, receipts and queues belong to other packages. */
    fun resetDemo() {
        deleteNonSeedSources()
        dropRollups()
        docResetter()
        SLog.d(TAG, "demo reset (tables)")
    }

    // ---------------------------------------------------------------- intent log

    fun logIntent(jobId: String, question: String, role: String, rawOutput: String?, parsedOk: Boolean, sql: String?, validation: String?, ms: Long, backend: String?) {
        db.exec("INSERT INTO _intent_log (jobId, question, role, rawOutput, parsedOk, sql, validation, ms, backend, ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(jobId, question, role, rawOutput, if (parsedOk) 1L else 0L, sql, validation, ms, backend, System.currentTimeMillis()))
    }

    // ---------------------------------------------------------------- helpers

    private fun freshTableName(name: String): String {
        val base = normaliseName(name.substringBeforeLast('.').ifBlank { "table" }).let { if (it.startsWith("t_")) it.substring(2) else it }.ifBlank { "table" }
        var candidate = base
        var i = 2
        while (tableExists(candidate)) { candidate = "${base}_${i++}" }
        return candidate
    }

    private fun toJsonArray(items: List<String>): String = JsonArray(items.map { JsonPrimitive(it) }).toString()
    private fun fromJsonArray(s: String): List<String> = try { json.parseToJsonElement(s).jsonArray.map { it.jsonPrimitive.content } } catch (_: Exception) { emptyList() }

    companion object {
        private const val TAG = "DataStore"
        const val KIND_TABLE = "table"
        const val KIND_DOC = "doc"
        private val IDENTIFIER_TOKENS = setOf("name", "id", "email", "phone", "mobile")
        private val RESERVED = setOf("_src", "rowid", "oid", "_rowid_")

        /** Lowercase, non-alphanumerics -> `_`, `c_` prefix when it starts with a digit. */
        fun normaliseName(raw: String): String {
            val sb = StringBuilder()
            for (ch in raw.trim().lowercase()) sb.append(if (ch in 'a'..'z' || ch in '0'..'9') ch else '_')
            var s = sb.toString().trim('_').replace(Regex("_+"), "_")
            if (s.isNotEmpty() && s[0] in '0'..'9') s = "c_$s"
            return s
        }

        /** Normalised, unique, non-empty column names in header order. */
        fun normaliseHeader(header: List<String>): List<String> {
            val out = ArrayList<String>(header.size)
            for ((i, h) in header.withIndex()) {
                var n = normaliseName(h).ifEmpty { "col_${i + 1}" }
                if (n in RESERVED) n = "c_$n"
                var candidate = n
                var k = 2
                while (candidate in out) candidate = "${n}_${k++}"
                out.add(candidate)
            }
            return out
        }

        fun inferType(values: Sequence<String>): ColType {
            var type = ColType.INTEGER
            for (v in values) {
                val t = v.trim()
                if (t.isEmpty()) continue
                if (type == ColType.INTEGER && t.toLongOrNull() != null) continue
                if (t.toDoubleOrNull() != null && !t.equals("nan", true) && !t.contains("inf", true)) { type = ColType.REAL; continue }
                return ColType.TEXT
            }
            return type
        }

        /** A cell to its bound value: empty -> null, by the column's inferred type (falls back to text). */
        fun convert(v: String, type: ColType): Any? {
            val t = v.trim()
            if (t.isEmpty()) return null
            return when (type) {
                ColType.INTEGER -> t.toLongOrNull() ?: t
                ColType.REAL -> t.toDoubleOrNull() ?: t
                ColType.TEXT -> t
            }
        }

        /** Quotes an identifier that came from the registry (never from user input). */
        fun q(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""
    }
}
