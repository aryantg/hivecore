package com.hivecore.ui.vm

import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.SLog
import com.hivecore.ui.Format
import com.hivecore.ui.ports.ModelsPort
import com.hivecore.ui.ports.PhoneModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Models screen (spec section 17, 4.3): the capability catalog with installed / available / loaded state per phone,
 * hashes, BENCH p50 and the last BENCH time; download, load, unload, BENCH and pin. Codes against [ModelsPort],
 * [ModelRegistry] (what this phone has on disk) and [ModelPool] (what this phone holds in memory); the registry and
 * the pool win over the port for this phone's own row.
 */
class ModelsViewModel(
    private val port: ModelsPort,
    private val registry: ModelRegistry,
    private val pool: ModelPool,
    private val selfId: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One phone's chip on a row: "Sales · loaded · p50 41 ms · BENCH 1 h ago". */
    data class PhoneChip(val nodeId: String, val name: String, val installed: Boolean, val loaded: Boolean, val p50Ms: Double?, val lastBenchTs: Long?) {
        fun label(now: Long): String = listOfNotNull(
            name,
            when { loaded -> "loaded"; installed -> "installed"; else -> "available" },
            p50Ms?.let { p50Text(it) },
            lastBenchTs?.let { "BENCH ${Format.ago(now - it)}" },
        ).joinToString(" · ")
    }

    data class Row(val entry: ModelEntry, val chips: List<PhoneChip>, val pinned: Boolean, val busy: Boolean) {
        val id: String get() = entry.modelId
        val self: PhoneChip? get() = chips.firstOrNull { it.nodeId == SELF_MARK }
        val installedHere: Boolean get() = self?.installed == true
        val loadedHere: Boolean get() = self?.loaded == true
        val sizeText: String get() = Format.bytes(entry.sizeBytes)
        val hashText: String get() = "sha256 ${entry.sha256.take(12)}"
        /** "qairt · big slot · load 4.2 s · 38 ms/unit · 8k context" */
        val specLine: String get() = listOfNotNull(
            entry.runtime, if (entry.big) "big slot" else "stays warm", "load ${Format.duration(entry.loadMsP50.toLong())}",
            "${entry.unitMsP50} ms/unit", entry.maxContext.takeIf { it > 0 }?.let { "${it / 1024}k context" },
        ).joinToString(" · ")
    }

    data class UiState(
        val loading: Boolean = true,
        val catalog: List<ModelEntry> = emptyList(),
        val phones: List<PhoneModelState> = emptyList(),
        val installedHere: Set<String> = emptySet(),
        val big: String = "none",
        val warm: List<String> = emptyList(),
        val pinned: Map<String, String> = emptyMap(),
        val busy: Set<String> = emptySet(),
        val error: String? = null,
        val message: String? = null,
        val now: Long = 0,
    ) {
        /** Rows grouped by capability, catalog order inside a group. */
        val groups: List<Pair<String, List<Row>>> get() = catalog.groupBy { it.capability }.map { (cap, entries) -> cap to entries.map { row(it) } }

        fun row(e: ModelEntry): Row {
            val others = phones.filter { it.modelId == e.modelId && it.nodeId != SELF_MARK }.map { PhoneChip(it.nodeId, it.phoneName, it.installed, it.loaded, it.benchP50Ms, it.lastBenchTs) }
            val selfPort = phones.firstOrNull { it.modelId == e.modelId && it.nodeId == SELF_MARK }
            val self = PhoneChip(SELF_MARK, "This phone", installed = e.modelId in installedHere || selfPort?.installed == true,
                loaded = e.modelId in warm || selfPort?.loaded == true,
                p50Ms = selfPort?.benchP50Ms, lastBenchTs = selfPort?.lastBenchTs)
            return Row(e, listOf(self) + others, pinned = pinned[e.capability] == e.modelId, busy = e.modelId in busy)
        }

        val bigText: String get() = when (big) { "none" -> "Big slot: empty"; else -> "Big slot: $big" }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        scope.launch { combine(pool.big, pool.warm) { b, w -> b to w }.collect { (b, w) -> _state.update { it.copy(big = b, warm = w) } } }
        scope.launch { port.catalog.collect { c -> _state.update { it.copy(catalog = c) } } }
        scope.launch { port.phones.collect { p -> _state.update { it.copy(phones = p.map { s -> if (s.nodeId == selfId) s.copy(nodeId = SELF_MARK) else s }) } } }
        scope.launch { port.pinned.collect { p -> _state.update { it.copy(pinned = p) } } }
    }

    /** Re-reads this phone's registry (the port flows are live). */
    fun refresh() {
        _state.update { it.copy(loading = true, error = null, now = clock()) }
        scope.launch {
            runCatching { withContext(io) { registry.installed().map { it.id }.toSet() } }
                .onSuccess { ids -> _state.update { it.copy(loading = false, installedHere = ids) } }
                .onFailure { e ->
                    SLog.e(TAG, "registry read failed", e)
                    _state.update { it.copy(loading = false, error = "Could not read the model registry: ${e.message ?: e.javaClass.simpleName}") }
                }
        }
    }

    fun download(modelId: String) = action(modelId, "download") { port.download(modelId).map { "Downloaded $modelId" } }
    fun load(modelId: String) = action(modelId, "load") { port.load(modelId).map { "Loaded $modelId" } }
    fun unload(modelId: String) = action(modelId, "unload") { port.unload(modelId).map { "Unloaded $modelId" } }
    fun bench(modelId: String) = action(modelId, "benchmark") { port.bench(modelId).map { "BENCH $modelId: ${p50Text(it.p50Ms)}" } }
    fun pin(capability: String, modelId: String) = action(modelId, "pin") { port.pin(capability, modelId).map { "$modelId is the default for $capability" } }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun action(modelId: String, what: String, block: suspend () -> Result<String>) {
        if (modelId in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + modelId, message = null) }
        scope.launch {
            val r = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            r.onSuccess { m -> SLog.d(TAG, "$what $modelId ok"); _state.update { it.copy(message = m, now = clock()) }; refresh() }
                .onFailure { e -> SLog.e(TAG, "$what $modelId failed", e); _state.update { it.copy(message = "Could not $what $modelId: ${e.message ?: e.javaClass.simpleName}") } }
            _state.update { it.copy(busy = it.busy - modelId) }
        }
    }

    companion object {
        private const val TAG = "ModelsVM"
        /** This phone's chips carry this nodeId so the screen needs no selfId. */
        const val SELF_MARK = "self"

        fun p50Text(ms: Double): String = String.format(Locale.US, if (ms < 10) "p50 %.2f ms" else "p50 %.0f ms", ms)
    }
}
