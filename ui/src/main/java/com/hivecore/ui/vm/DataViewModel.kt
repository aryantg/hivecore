package com.hivecore.ui.vm

import com.hivecore.core.DataAdmin
import com.hivecore.core.DocDef
import com.hivecore.core.SLog
import com.hivecore.core.SchemaSource
import com.hivecore.core.TableDef
import com.hivecore.ui.Format
import com.hivecore.ui.ports.DataPort
import com.hivecore.ui.ports.ItemKind
import com.hivecore.ui.ports.MediaItem
import com.hivecore.ui.ports.Release
import com.hivecore.ui.ports.Visibility
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data screen (spec section 17, section 7): the phone's tables, documents and media as the swarm catalog sees them,
 * the private / released state per item, the confidential column flags; mark private, release for a job or for a
 * month, set confidential columns, hide a table, delete. Codes against [SchemaSource], [DataAdmin] and [DataPort].
 */
class DataViewModel(
    private val schema: SchemaSource,
    private val admin: DataAdmin,
    private val port: DataPort,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One catalog item with its visibility. */
    data class Item(val kind: ItemKind, val id: String, val name: String, val detail: String, val visibility: Visibility, val table: TableDef? = null, val doc: DocDef? = null, val media: MediaItem? = null) {
        val private: Boolean get() = visibility.private
        val hidden: Boolean get() = visibility.hidden
        /** "private" / "released for job j-12" / "released until 09 Nov 14:00" / "shared" / "hidden from the catalog" */
        fun stateLine(now: Long): String = when {
            hidden -> "hidden from the catalog"
            private -> "private · never leaves this phone"
            else -> when (val r = visibility.release) {
                is Release.ForJob -> "released for job ${r.jobId}"
                is Release.Until -> if (r.untilTs > now) "released until ${Format.dateTime(r.untilTs)}" else "release expired"
                null -> "shared · schema only, no values"
            }
        }
        val confidentialColumns: Set<String> get() = (table?.columns?.filter { it.confidential }?.map { it.name }?.toSet() ?: emptySet()) + visibility.confidentialColumns
    }

    data class UiState(
        val loading: Boolean = true,
        val tables: List<Item> = emptyList(),
        val docs: List<Item> = emptyList(),
        val media: List<Item> = emptyList(),
        val error: String? = null,
        /** the table whose confidential columns are being edited */
        val editingColumns: String? = null,
        val message: String? = null,
        val now: Long = 0,
    ) {
        val all: List<Item> get() = tables + docs + media
        val privateCount: Int get() = all.count { it.private }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Called after any change so the node re-broadcasts its catalog. */
    var onDataChanged: () -> Unit = { SLog.d(TAG, "onDataChanged (no catalog re-broadcast wired)") }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val now = clock()
            val r = runCatching { withContext(io) {
                val s = schema.schema()
                val marks = port.visibility().associateBy { it.kind to it.id }
                fun vis(kind: ItemKind, id: String) = marks[kind to id] ?: Visibility(kind, id)
                Triple(
                    s.tables.map { t -> Item(ItemKind.TABLE, t.name, t.name, "${Format.count(t.rowCount)} rows · ${t.columns.size} columns" + (if (t.isSensitive) " · confidential" else ""), vis(ItemKind.TABLE, t.name), table = t) },
                    s.docs.map { d -> Item(ItemKind.DOC, d.id, d.name, "${d.kind} · ${d.units} ${d.unitLabel}s · ${d.chunkCount} chunks" + (if (d.confidential) " · confidential" else ""), vis(ItemKind.DOC, d.id), doc = d) },
                    port.media().map { m -> Item(ItemKind.MEDIA, m.id, m.name, "${m.kind} · ${Format.bytes(m.sizeBytes)}", vis(ItemKind.MEDIA, m.id), media = m) },
                )
            } }
            r.onSuccess { (t, d, m) -> _state.update { it.copy(loading = false, tables = t, docs = d, media = m, now = now) } }
                .onFailure { e ->
                    SLog.e(TAG, "schema() failed", e)
                    _state.update { it.copy(loading = false, error = "Could not read the data registry: ${e.message ?: e.javaClass.simpleName}") }
                }
        }
    }

    /** "Private (never leaves this phone)": flips the mark optimistically, then persists and re-reads. */
    fun setPrivate(item: Item, on: Boolean) {
        patch(item) { it.copy(private = on) }
        adminAction(if (on) "${item.name} stays on this phone" else "${item.name} may be shared with the swarm") { port.setPrivate(item.kind, item.id, on) }
    }

    /** Release for one job. */
    fun releaseForJob(item: Item, jobId: String) {
        if (jobId.isBlank()) { _state.update { it.copy(message = "Pick a job to release ${item.name} for") }; return }
        val rel = Release.ForJob(jobId.trim())
        patch(item) { it.copy(release = rel, private = false) }
        adminAction("${item.name} released for job ${rel.jobId}") { port.setPrivate(item.kind, item.id, false); port.release(item.kind, item.id, rel) }
    }

    /** Release for a month (30 days from now). */
    fun releaseForMonth(item: Item) {
        val rel = Release.Until(clock() + MONTH_MS)
        patch(item) { it.copy(release = rel, private = false) }
        adminAction("${item.name} released until ${Format.dateTime(rel.untilTs)}") { port.setPrivate(item.kind, item.id, false); port.release(item.kind, item.id, rel) }
    }

    fun clearRelease(item: Item) {
        patch(item) { it.copy(release = null) }
        adminAction("${item.name} is no longer released") { port.release(item.kind, item.id, null) }
    }

    fun hideTable(item: Item, hidden: Boolean) {
        if (item.kind != ItemKind.TABLE) return
        patch(item) { it.copy(hidden = hidden) }
        adminAction(if (hidden) "${item.name} is hidden from the catalog" else "${item.name} is back in the catalog") { port.hideTable(item.id, hidden) }
    }

    fun editColumns(table: String?) { _state.update { it.copy(editingColumns = table) } }

    fun setConfidentialColumns(item: Item, columns: Set<String>) {
        if (item.kind != ItemKind.TABLE) return
        patch(item) { it.copy(confidentialColumns = columns) }
        _state.update { it.copy(editingColumns = null) }
        adminAction("${item.name}: ${if (columns.isEmpty()) "no confidential columns" else "confidential " + columns.sorted().joinToString(", ")}") { port.setConfidentialColumns(item.id, columns) }
    }

    fun deleteTable(name: String) = adminAction("Deleted table $name") { admin.deleteTable(name) }
    fun deleteDoc(doc: DocDef) = adminAction("Deleted ${doc.name}") { admin.deleteDoc(doc.id) }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun patch(item: Item, f: (Visibility) -> Visibility) {
        fun List<Item>.upd() = map { if (it.kind == item.kind && it.id == item.id) it.copy(visibility = f(it.visibility)) else it }
        _state.update { it.copy(tables = it.tables.upd(), docs = it.docs.upd(), media = it.media.upd()) }
    }

    private fun adminAction(message: String?, block: () -> Unit) {
        scope.launch {
            val r = runCatching { withContext(io) { block() } }
            r.onSuccess { if (message != null) _state.update { it.copy(message = message) }; onDataChanged(); refresh() }
                .onFailure { e -> SLog.e(TAG, "admin action failed", e); _state.update { it.copy(message = "Failed: ${e.message ?: e.javaClass.simpleName}") }; refresh() }
        }
    }

    companion object {
        private const val TAG = "DataVM"
        const val MONTH_MS = 30L * 24 * 3_600_000L
    }
}
