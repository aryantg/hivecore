package com.hivecore.engine.device

import com.hivecore.core.DeviceState
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.WorkerPolicy
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.CapabilityState
import com.hivecore.engine.placement.CooldownTable
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.models.CapabilityPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * [WorkerPolicy] (swarm plan §6.1): folds DeviceState, presence, the LLM engine, the model pool, the opt-in pref
 * and the TaskRunner queue depth into this phone's [Profile]. Recomputed on every input change and at least every
 * [tickMs] (the PROFILE heartbeat period), so a screen-off-for-60-s transition shows up without another event.
 *
 * The opt-in lives in SharedPreferences "hivecore" under [PREF_WORKER_OPT_IN] (default off).
 * NPU generation time seen on [LlmEngine.lastStats] is charged to [dutyCycle]; heavy executors can add their own
 * NPU spans with [DutyCycle.begin]/[DutyCycle.end] or [DutyCycle.record].
 *
 * Spec 4.6 decoration: with a [catalog] the profile carries `capabilities[]` (the catalog entries installed here,
 * warm-flagged from the pool), `cooldowns[]` from [cooldowns] (this phone's own failed loads), `p50` from [bench]
 * (the BENCH table), and `big` / `warm` from the [CapabilityPool] (`llm` | `vlm` | `gen` | `none`).
 */
class WorkerPolicyImpl(
    private val nodeId: String,
    private val device: DeviceState,
    private val presence: StateFlow<Presence>,
    private val engine: LlmEngine,
    private val prefs: KeyValuePrefs,
    private val queueDepth: StateFlow<Int>,
    scope: CoroutineScope,
    private val pool: ModelPool? = null,
    private val registry: ModelRegistry? = null,
    private val modelHash: () -> String? = { null },
    private val qairt: String? = null,
    val dutyCycle: DutyCycle = DutyCycle(),
    /** Runs before each periodic recompute (the integrator passes `presenceMonitor::refresh`). */
    private val onTick: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
    /** The capability catalog (spec 4.3); null = no `capabilities[]` in the profile. */
    private val catalog: () -> ModelCatalog? = { null },
    /** This phone's own capability cooldowns (ModelReqHandler marks them). */
    private val cooldowns: CooldownTable? = null,
    /** modelId → BENCH p50 ms (BenchTable.p50). */
    private val bench: () -> Map<String, Double> = { emptyMap() },
    /** Model ids installed outside the registry (the GenieX LLM builds); the registry's ids are added. */
    private val extraInstalled: () -> Set<String> = { emptySet() },
    /**
     * True while the task runner yields to the owner (OwnerYield: until the screen has been off for 60 s). Phone gate
     * finding: a charging phone with the app in the background counted as idle with its screen on, so it advertised
     * eligible while refusing every task, and each refusal cost the orchestrator a full photo transfer.
     */
    private val yielding: () -> Boolean = { false },
) : WorkerPolicy {

    private val optInState = MutableStateFlow(prefs.getBoolean(PREF_WORKER_OPT_IN, false))
    override val optIn: StateFlow<Boolean> = optInState

    private val tokWindow = ArrayDeque<Double>()
    private var lastSeenStats: GenStats? = null

    private val profileState = MutableStateFlow(compute())
    override val profile: StateFlow<Profile> = profileState

    init {
        val inputs: List<Flow<Unit>> = listOfNotNull(
            device.charging.map { }, device.batteryTempC.map { }, device.thermal.map { },
            presence.map { }, engine.backend.map { }, queueDepth.map { }, optInState.map { },
            engine.lastStats.map { onStats(it) },
            pool?.big?.map { }, pool?.warm?.map { },
        )
        scope.launch { merge(*inputs.toTypedArray()).collect { recompute() } }
        scope.launch {
            while (true) {
                delay(tickMs)
                runCatching(onTick).onFailure { SLog.e(TAG, "tick", it) }
                recompute()
            }
        }
    }

    override fun setOptIn(on: Boolean) {
        prefs.putBoolean(PREF_WORKER_OPT_IN, on)
        optInState.value = on
        recompute()
    }

    /** The eligibility inputs as of now (for the Settings "why not eligible" text and logs). */
    fun signals(): Signals {
        val p = presence.value
        return Signals(
            optIn = optInState.value,
            screenOffSinceMs = if (p.screenOn) null else p.screenOffSinceMs,
            charging = device.charging.value,
            appForeground = p.appForeground,
            batteryPct = p.batteryPct,
            batteryTempC = device.batteryTempC.value,
            thermal = device.thermal.value,
            inCall = p.inCall,
            nowMs = clock(),
        )
    }

    /** Recomputes the profile now. */
    fun recompute() {
        val next = compute()
        val prev = profileState.value
        if (prev.eligible != next.eligible || prev.heavyOk != next.heavyOk)
            SLog.d(TAG, "eligible=${next.eligible} heavyOk=${next.heavyOk} idle=${next.idle} optIn=${next.workerOptIn}")
        profileState.value = next
    }

    private fun compute(): Profile {
        val s = signals()
        val backend = engine.backend.value
        return Profile(
            nodeId = nodeId,
            ts = s.nowMs,
            backend = backend,
            tokPerSec = medianTokPerSec(),
            modelHash = runCatching(modelHash).getOrNull(),
            qairt = qairt,
            idle = idle(s),
            charging = s.charging,
            batteryPct = s.batteryPct,
            batteryTempC = s.batteryTempC,
            thermal = s.thermal,
            inCall = s.inCall,
            workerOptIn = s.optIn,
            eligible = eligible(s) && !yielding(),
            heavyOk = heavyOk(s, dutyCycle) && !yielding(),
            queueDepth = queueDepth.value,
            big = pool?.big?.value ?: if (backend != null) "llm" else "none",
            warm = (pool as? CapabilityPool)?.warm()?.sorted() ?: pool?.warm?.value ?: emptyList(),
            models = registry?.let { r -> runCatching { r.installed().map { "${it.id}@${it.sha256}" }.sorted() }.getOrDefault(emptyList()) }
                ?: emptyList(),
            p50 = runCatching(bench).getOrDefault(emptyMap()),
            capabilities = capabilityStates(),
            cooldowns = cooldowns?.of(nodeId) ?: emptyList(),
        )
    }

    /** Spec 4.6 `capabilities[]`: catalog entries installed here, warm-flagged from the pool's residency. */
    private fun capabilityStates(): List<CapabilityState> {
        val cat = runCatching(catalog).getOrNull() ?: return emptyList()
        val installed = (registry?.let { r -> runCatching { r.installed().map { it.id } }.getOrDefault(emptyList()) } ?: emptyList()).toSet() +
            runCatching(extraInstalled).getOrDefault(emptySet())
        val cp = pool as? CapabilityPool
        return cat.capabilitiesOf(installed, cp?.warm() ?: pool?.warm?.value?.toSet().orEmpty(), cp?.bigModel)
    }

    @Synchronized
    private fun onStats(stats: GenStats?) {
        if (stats == null || stats === lastSeenStats) return
        lastSeenStats = stats
        if (stats.tokPerSec > 0) {
            tokWindow.addLast(stats.tokPerSec)
            while (tokWindow.size > TOK_WINDOW) tokWindow.removeFirst()
        }
        if (engine.backend.value == LlmBackend.NPU) dutyCycle.record(stats.prefillMs + stats.decodeMs, clock())
    }

    @Synchronized
    private fun medianTokPerSec(): Double? {
        if (tokWindow.isEmpty()) return null
        val sorted = tokWindow.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    companion object {
        const val PREF_WORKER_OPT_IN = "worker_opt_in"
        const val TICK_MS = 15_000L
        const val TOK_WINDOW = 9
        private const val TAG = "WorkerPolicy"
    }
}
