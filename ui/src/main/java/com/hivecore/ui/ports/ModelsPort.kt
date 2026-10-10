package com.hivecore.ui.ports

import com.hivecore.core.ModelEntry
import kotlinx.coroutines.flow.StateFlow

// Spec section 17 (Models) and section 4.3 (capability catalog): the catalog, the per-phone state of each build and
// the actions. The runtime+placement track implements [ModelsPort] over the catalog, the model pool and the BENCH
// recipe; [FakeModelsPort] drives the screen and the JVM tests until then. This phone's installed and loaded state
// also comes straight from `core.ModelRegistry` and `core.ModelPool`, which the view model prefers when they disagree.

/** One phone's state for one catalog build (from its PROFILE `capabilities[]` and `p50`, spec section 4.6). */
data class PhoneModelState(
    val nodeId: String,
    val phoneName: String,
    val modelId: String,
    val installed: Boolean,
    val loaded: Boolean,
    /** BENCH p50 per unit (ms); null when never benchmarked on that phone */
    val benchP50Ms: Double? = null,
    /** when the last BENCH ran on that phone (ms since epoch) */
    val lastBenchTs: Long? = null,
)

data class BenchResult(val modelId: String, val p50Ms: Double, val ts: Long)

interface ModelsPort {
    /** The capability catalog (`models/catalog.json`), every build. */
    val catalog: StateFlow<List<ModelEntry>>
    /** Per phone and build: installed / loaded / BENCH, this phone included. */
    val phones: StateFlow<List<PhoneModelState>>
    /** The pinned default build per capability (spec section 17: "pin the default build per capability"). */
    val pinned: StateFlow<Map<String, String>>

    suspend fun download(modelId: String): Result<Unit>
    suspend fun load(modelId: String): Result<Unit>
    suspend fun unload(modelId: String): Result<Unit>
    /** Runs BENCH for one build on this phone and records the p50. */
    suspend fun bench(modelId: String): Result<BenchResult>
    suspend fun pin(capability: String, modelId: String): Result<Unit>
}
