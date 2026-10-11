package com.hivecore.ui.demo

import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.JobRunner
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.LlmBackend
import com.hivecore.core.Profile
import com.hivecore.ui.ports.fakes.FakeSwarmPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The scripted phones' profiles over time (pure): batteries drift, and Accounts drops off for a while and returns. */
object DemoSwarm {
    const val OFFLINE_FROM_MS = 90_000L
    const val OFFLINE_UNTIL_MS = 125_000L

    fun profilesAt(elapsedMs: Long, now: Long): List<Profile> {
        val minutes = (elapsedMs / 60_000L).toInt()
        val accountsAway = elapsedMs in OFFLINE_FROM_MS until OFFLINE_UNTIL_MS
        return listOf(
            Profile("sales", now - 2_000, LlmBackend.NPU, 18.0, idle = true, charging = true, batteryPct = (64 - minutes).coerceAtLeast(55), batteryTempC = 31f, thermal = 0,
                workerOptIn = true, eligible = true, heavyOk = true, queueDepth = 1, big = "llm", warm = listOf("yolo11n"), link = Link.defaultFor(LinkKind.LAN)),
            Profile("accounts", if (accountsAway) now - 60_000 else now - 3_000, LlmBackend.NPU, 12.0, idle = true, charging = false,
                batteryPct = (71 - minutes).coerceAtLeast(60), batteryTempC = 33f, thermal = 1, workerOptIn = true, eligible = !accountsAway, queueDepth = 0,
                big = "llm", link = Link.defaultFor(LinkKind.LAN)),
        )
    }

    /** A swarm port over the scripted phones: the sample roster (with HR waiting to join) and profiles that tick. */
    fun port(scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, tickMs: Long = 2_000): FakeSwarmPort {
        val start = clock()
        val port = FakeSwarmPort.sample(start)
        port.peersState.value = profilesAt(0, start)
        scope.launch { while (true) { delay(tickMs); val now = clock(); port.peersState.value = profilesAt(now - start, now) } }
        return port
    }
}

/**
 * Replays [DemoJobScript] on a clock: the Jobs grid fills as on three phones. [timeScale] above 1 runs faster
 * (rehearsals). Elapsed times come from the script, not the wall clock, so JVM tests are exact.
 */
class DemoJobRunner(private val timeScale: () -> Double = { 1.0 }) : JobRunner {
    private val all = MutableStateFlow<List<JobProgress>>(emptyList())
    override val jobs: StateFlow<List<JobProgress>> = all
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var seq = 0

    override fun run(request: JobRequest): Flow<JobProgress> = flow {
        val id = "demo-job-${++seq}"
        var prev = 0L
        var last: JobProgress? = null
        for (f in DemoJobScript.frames(request, id)) {
            delay(((f.atMs - prev) / timeScale().coerceAtLeast(0.1)).toLong())
            prev = f.atMs
            if (id in cancelled) {
                val p = (last ?: f.progress).copy(error = "Job cancelled")
                publish(p); emit(p); return@flow
            }
            last = f.progress
            publish(f.progress); emit(f.progress)
        }
    }

    override fun cancel(jobId: String) { cancelled += jobId }

    private fun publish(p: JobProgress) { all.update { list -> listOf(p) + list.filter { it.jobId != p.jobId } } }
}
