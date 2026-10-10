package com.hivecore.runtime.models

import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelPool
import com.hivecore.runtime.catalog.BigKind
import kotlinx.serialization.Serializable

/** Result of [CapabilityPool.ensure]: which model and runtime are loaded, how long the load took (0 when warm). */
@Serializable data class LoadInfo(val modelId: String, val runtime: String, val loadMs: Long, val warm: Boolean)

/**
 * The runtime's view of `core.ModelPool` for placement (spec 4.3, 8.3, 18.3). `core` is frozen, so the members the
 * spec's `ModelPool { ensure, warm(), big() }` adds live here with defaults over the existing flows; [ModelPoolImpl]
 * overrides them with the real residency rules.
 *
 *  - [ensure] loads a catalog entry or confirms it is warm: the qairt bundle first, on failure the `llama_cpp` build
 *    of the same capability once, then a [com.hivecore.runtime.catalog.ModelLoadException] with a class in
 *    {oom, model_load, thermal, unsupported, timeout}. Models stay loaded after the call (8.4).
 *  - [big] is the kind holding the one big slot (`llm` | `vlm` | `gen`), null when it is free; [bigModel] its model id.
 *  - [warm] is every resident model id, big slot included.
 */
interface CapabilityPool : ModelPool {
    /** The model id holding the big slot now (null = free or unknown). */
    val bigModel: String? get() = null

    suspend fun ensure(entry: ModelEntry): Result<LoadInfo>

    /** "llm" | "vlm" | "gen" | null (spec 4.3 `big = llm | vlm | gen | none`). */
    fun big(): String? = big.value.takeIf { it != BigKind.NONE }

    /** Model ids resident right now (small slots plus the big slot). */
    fun warm(): Set<String> = (warm.value + listOfNotNull(bigModel)).toSet()
}
