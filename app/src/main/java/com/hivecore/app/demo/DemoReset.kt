package com.hivecore.app.demo

import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.SLog
import com.hivecore.core.SqlDb
import com.hivecore.ui.ports.ResetOptions
import java.io.File

/**
 * "Reset demo state": clears what a demo run leaves behind so the next run starts clean, and nothing else. Never
 * touched: the Keystore identity, the ledger receipts, model files and caches, the OpenRouter key, the phone's name,
 * group, role and demo settings, the relay address, the owner's own media and the log.
 *
 * @param db `hivecore.db` (data, peers, ledger); [stateDb] `journal.db` (jobs, chunks, memo), null without the split flag
 */
class DemoReset(
    private val filesDir: File,
    private val cacheDir: File,
    private val prefs: KeyValuePrefs,
    private val db: SqlDb?,
    private val stateDb: SqlDb?,
) {
    /** Returns one line per thing cleared. Pairing is done by the caller (it needs the router). */
    fun run(o: ResetOptions): List<String> = buildList {
        if (o.jobs) {
            val tables = stateDb?.let { tables(it) }.orEmpty()
            tables.forEach { t -> runCatching { stateDb!!.exec("DELETE FROM \"$t\"") }.onFailure { SLog.w(TAG, "clear $t: ${it.message}") } }
            val files = listOf("blobs", "out", "exports").sumOf { clear(File(filesDir, it)) }
            add("jobs: ${tables.size} journal tables emptied, $files files removed")
        }
        if (o.pickedFiles) add("photos: ${clear(File(cacheDir, "picked")) + clear(File(cacheDir, "seed"))} files removed")
        if (o.askHistory) {
            val cleared = db?.let { d -> ASK_TABLES.count { t -> runCatching { d.exec("DELETE FROM $t") }.isSuccess } } ?: 0
            add("conversation: $cleared answer logs cleared")
        }
        if (o.importedData) {
            val keys = prefs.keys().filter { k -> DATA_PREFIXES.any { k.startsWith(it) } }
            keys.forEach { prefs.remove(it) }
            val priv = db?.let { d -> runCatching { d.exec("DELETE FROM _private") }.isSuccess } == true
            add("data marks: ${keys.size} releases and hidden flags cleared" + if (priv) ", private marks cleared" else "")
        }
    }.also { SLog.d(TAG, "reset: ${it.joinToString("; ")}") }

    private fun tables(d: SqlDb): List<String> = d.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        .mapNotNull { it["name"] as? String }.filter { !it.startsWith("sqlite_") && it != "android_metadata" }

    /** Deletes everything under [dir] (not [dir] itself); returns the number of files removed. */
    private fun clear(dir: File): Int {
        if (!dir.isDirectory) return 0
        var n = 0
        dir.walkBottomUp().filter { it != dir }.forEach { f -> if (f.isFile) { if (f.delete()) n++ } else f.delete() }
        return n
    }

    companion object {
        private const val TAG = "DemoReset"
        /** The differencing guard's answered log, the intent log and the authorised plans (else a repeated demo question is refused). */
        val ASK_TABLES = listOf("_answered", "_intent_log", "_plans")
        /** DataPortImpl's per-item marks. */
        val DATA_PREFIXES = listOf("release:", "hidden_table:", "private_media:")
    }
}
