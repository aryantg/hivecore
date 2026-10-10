package com.hivecore.ui.ports.fakes

import com.hivecore.core.ModelEntry
import com.hivecore.ui.ports.BenchResult
import com.hivecore.ui.ports.ModelsPort
import com.hivecore.ui.ports.PhoneModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** An in-memory [ModelsPort]: actions mutate [phonesState] for [selfId] and record themselves. */
class FakeModelsPort(
    val selfId: String = "self",
    val selfName: String = "This phone",
    catalog: List<ModelEntry> = SAMPLE_CATALOG,
    phones: List<PhoneModelState> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelsPort {
    val catalogState = MutableStateFlow(catalog)
    override val catalog: StateFlow<List<ModelEntry>> = catalogState
    val phonesState = MutableStateFlow(phones)
    override val phones: StateFlow<List<PhoneModelState>> = phonesState
    val pinnedState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val pinned: StateFlow<Map<String, String>> = pinnedState

    val actions = mutableListOf<String>()
    var failNext: Throwable? = null
    var benchP50 = 7.5

    private fun <T> maybeFail(block: () -> T): Result<T> {
        val f = failNext
        if (f != null) { failNext = null; return Result.failure(f) }
        return Result.success(block())
    }

    private fun setSelf(modelId: String, f: (PhoneModelState) -> PhoneModelState) = phonesState.update { list ->
        val cur = list.firstOrNull { it.nodeId == selfId && it.modelId == modelId } ?: PhoneModelState(selfId, selfName, modelId, installed = false, loaded = false)
        list.filterNot { it.nodeId == selfId && it.modelId == modelId } + f(cur)
    }

    override suspend fun download(modelId: String): Result<Unit> = maybeFail { actions += "download $modelId"; setSelf(modelId) { it.copy(installed = true) } }
    override suspend fun load(modelId: String): Result<Unit> = maybeFail { actions += "load $modelId"; setSelf(modelId) { it.copy(installed = true, loaded = true) } }
    override suspend fun unload(modelId: String): Result<Unit> = maybeFail { actions += "unload $modelId"; setSelf(modelId) { it.copy(loaded = false) } }
    override suspend fun bench(modelId: String): Result<BenchResult> = maybeFail {
        actions += "bench $modelId"
        val r = BenchResult(modelId, benchP50, clock())
        setSelf(modelId) { it.copy(installed = true, benchP50Ms = r.p50Ms, lastBenchTs = r.ts) }
        r
    }
    override suspend fun pin(capability: String, modelId: String): Result<Unit> = maybeFail { actions += "pin $capability=$modelId"; pinnedState.update { it + (capability to modelId) } }

    companion object {
        /** A slice of the spec section 4.3 catalog. */
        val SAMPLE_CATALOG = listOf(
            ModelEntry("qwen3-4b-q4", "llm.json", "qairt", "a1".repeat(32), 2_600_000_000L, 4_200, 38, 8_192, big = true),
            ModelEntry("qwen3-1.7b-q4", "llm.json", "qairt", "b2".repeat(32), 1_100_000_000L, 2_100, 21, 8_192, big = true),
            ModelEntry("gemma3-4b-vlm", "vlm", "llama_cpp", "c3".repeat(32), 2_900_000_000L, 5_800, 160, 4_096, big = true),
            ModelEntry("whisper-base", "asr", "qairt", "d4".repeat(32), 150_000_000L, 900, 210, 0, big = false),
            ModelEntry("yolo11n", "detect", "qairt", "e5".repeat(32), 12_000_000L, 300, 9, 0, big = false),
            ModelEntry("minilm-l6", "embed", "qairt", "f6".repeat(32), 23_000_000L, 200, 2, 512, big = false),
        )

        fun sample(now: Long = System.currentTimeMillis()): FakeModelsPort = FakeModelsPort(
            phones = listOf(
                PhoneModelState("self", "This phone", "qwen3-4b-q4", installed = true, loaded = true, benchP50Ms = 38.0, lastBenchTs = now - 3_600_000L),
                PhoneModelState("self", "This phone", "yolo11n", installed = true, loaded = true, benchP50Ms = 9.2, lastBenchTs = now - 3_600_000L),
                PhoneModelState("sales", "Sales", "qwen3-4b-q4", installed = true, loaded = false, benchP50Ms = 41.0, lastBenchTs = now - 86_400_000L),
                PhoneModelState("sales", "Sales", "whisper-base", installed = true, loaded = true),
                PhoneModelState("accounts", "Accounts", "qwen3-1.7b-q4", installed = true, loaded = true, benchP50Ms = 24.0, lastBenchTs = now - 7_200_000L),
            ),
            clock = { now },
        ).also { it.pinnedState.value = mapOf("llm.json" to "qwen3-4b-q4") }
    }
}
