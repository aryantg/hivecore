package com.hivecore.node.docs

import com.hivecore.core.Chunk
import com.hivecore.core.DocDef
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb

/** A `_docs` row in full (the [DocDef] the schema shows plus the bookkeeping columns). */
data class DocRecord(val def: DocDef, val hash: String, val seed: Boolean, val importedAt: Long)

/** Result of [DocStore.addDocument]: [duplicate] is true when a document with the same file hash already existed. */
data class AddResult(val doc: DocDef, val duplicate: Boolean)

/**
 * Documents and their chunks in SQLite (plan 9): `_docs(id, name, kind, units, unitLabel, chunkCount, confidential,
 * importedAt, hash, seed)` and `_chunks(docId, ordinal, locator, text, hash)`. Chunking happens here through
 * [Chunker]; the in-memory [Bm25Index] reads [chunks] on `rebuild()`, which the caller runs after every add or
 * delete. Dedupe is on the file hash: re-sharing the same PDF returns the existing document.
 */
class DocStore(private val db: SqlDb, private val now: () -> Long = { System.currentTimeMillis() }) {

    init { migrate() }

    private fun migrate() {
        db.exec(
            "CREATE TABLE IF NOT EXISTS _docs (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "units INTEGER NOT NULL, unitLabel TEXT NOT NULL, chunkCount INTEGER NOT NULL, " +
                "confidential INTEGER NOT NULL DEFAULT 0, importedAt INTEGER NOT NULL, hash TEXT NOT NULL, " +
                "seed INTEGER NOT NULL DEFAULT 0)"
        )
        db.exec(
            "CREATE TABLE IF NOT EXISTS _chunks (docId TEXT NOT NULL, ordinal INTEGER NOT NULL, locator TEXT NOT NULL, " +
                "text TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY (docId, ordinal))"
        )
        db.exec("CREATE INDEX IF NOT EXISTS _docs_hash ON _docs(hash)")
    }

    /**
     * Stores [units] as one document. [hash] is the sha256 of the source file (or of the text for pasted text);
     * when a document with that hash exists it is returned unchanged with `duplicate = true`.
     */
    fun addDocument(
        name: String, kind: String, units: List<TextUnit>, unitLabel: String = DocKinds.unitLabel(kind),
        confidential: Boolean = false, seed: Boolean = false, hash: String
    ): AddResult {
        findByHash(hash)?.let { return AddResult(it.def, duplicate = true) }
        val id = idFor(hash)
        val chunks = Chunker.chunk(id, name, units)
        val def = DocDef(id, name, kind, units.size, unitLabel, chunks.size, confidential)
        db.tx {
            db.exec("DELETE FROM _chunks WHERE docId = ?", listOf(id))
            db.exec("DELETE FROM _docs WHERE id = ?", listOf(id))
            db.exec(
                "INSERT INTO _docs (id, name, kind, units, unitLabel, chunkCount, confidential, importedAt, hash, seed) VALUES (?,?,?,?,?,?,?,?,?,?)",
                listOf(id, name, kind, units.size.toLong(), unitLabel, chunks.size.toLong(), if (confidential) 1L else 0L, now(), hash, if (seed) 1L else 0L)
            )
            for (c in chunks) {
                db.exec("INSERT INTO _chunks (docId, ordinal, locator, text, hash) VALUES (?,?,?,?,?)",
                    listOf(id, c.ordinal.toLong(), c.locator, c.text, c.hash))
            }
        }
        SLog.d("DocStore", "added $name ($kind): ${units.size} ${unitLabel}s, ${chunks.size} chunks, seed=$seed")
        return AddResult(def, duplicate = false)
    }

    /** Marks a document confidential (its chunks then go through the consent gate, plan 11.3). */
    fun setConfidential(id: String, on: Boolean) {
        db.exec("UPDATE _docs SET confidential = ? WHERE id = ?", listOf(if (on) 1L else 0L, id))
        SLog.d("DocStore", "confidential $id = $on")
    }

    /** Removes every document (the hook's WIPE / role switch). */
    fun deleteAll() {
        db.tx { db.exec("DELETE FROM _chunks"); db.exec("DELETE FROM _docs") }
        SLog.d("DocStore", "all documents deleted")
    }

    fun delete(id: String) {
        db.tx {
            db.exec("DELETE FROM _chunks WHERE docId = ?", listOf(id))
            db.exec("DELETE FROM _docs WHERE id = ?", listOf(id))
        }
        SLog.d("DocStore", "deleted $id")
    }

    /** Removes every document that was not seeded (the demo reset). Returns the ids removed. */
    fun deleteNonSeed(): List<String> {
        val ids = db.query("SELECT id FROM _docs WHERE seed = 0").map { it["id"] as String }
        db.tx {
            db.exec("DELETE FROM _chunks WHERE docId IN (SELECT id FROM _docs WHERE seed = 0)")
            db.exec("DELETE FROM _docs WHERE seed = 0")
        }
        return ids
    }

    fun docs(): List<DocDef> = records().map { it.def }

    fun records(): List<DocRecord> =
        db.query("SELECT id, name, kind, units, unitLabel, chunkCount, confidential, importedAt, hash, seed FROM _docs ORDER BY importedAt, name").map { toRecord(it) }

    fun doc(id: String): DocRecord? =
        db.query("SELECT id, name, kind, units, unitLabel, chunkCount, confidential, importedAt, hash, seed FROM _docs WHERE id = ?", listOf(id)).firstOrNull()?.let { toRecord(it) }

    fun findByHash(hash: String): DocRecord? =
        db.query("SELECT id, name, kind, units, unitLabel, chunkCount, confidential, importedAt, hash, seed FROM _docs WHERE hash = ?", listOf(hash)).firstOrNull()?.let { toRecord(it) }

    fun findByName(name: String): DocRecord? =
        db.query("SELECT id, name, kind, units, unitLabel, chunkCount, confidential, importedAt, hash, seed FROM _docs WHERE name = ? ORDER BY importedAt DESC", listOf(name)).firstOrNull()?.let { toRecord(it) }

    /** Every chunk of every document, with the document name joined in, in (name, ordinal) order. */
    fun chunks(): List<Chunk> =
        db.query("SELECT c.docId, d.name, c.ordinal, c.locator, c.text, c.hash FROM _chunks c JOIN _docs d ON d.id = c.docId ORDER BY d.name, c.ordinal").map { toChunk(it) }

    fun chunksOf(docId: String): List<Chunk> =
        db.query("SELECT c.docId, d.name, c.ordinal, c.locator, c.text, c.hash FROM _chunks c JOIN _docs d ON d.id = c.docId WHERE c.docId = ? ORDER BY c.ordinal", listOf(docId)).map { toChunk(it) }

    private fun toRecord(r: Map<String, Any?>) = DocRecord(
        DocDef(
            id = r["id"] as String, name = r["name"] as String, kind = r["kind"] as String,
            units = (r["units"] as Long).toInt(), unitLabel = r["unitLabel"] as String,
            chunkCount = (r["chunkCount"] as Long).toInt(), confidential = (r["confidential"] as Long) != 0L
        ),
        hash = r["hash"] as String, seed = (r["seed"] as Long) != 0L, importedAt = r["importedAt"] as Long
    )

    private fun toChunk(r: Map<String, Any?>) = Chunk(
        docId = r["docId"] as String, docName = r["name"] as String, ordinal = (r["ordinal"] as Long).toInt(),
        locator = r["locator"] as String, text = r["text"] as String, hash = r["hash"] as String
    )

    companion object {
        /** Document ids derive from the file hash, so the same file always gets the same id. */
        fun idFor(hash: String): String = "doc_" + hash.take(16)
    }
}
