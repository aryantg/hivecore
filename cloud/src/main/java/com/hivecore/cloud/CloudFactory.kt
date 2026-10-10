package com.hivecore.cloud

import com.hivecore.core.LedgerStore
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.JobJournal
import com.hivecore.planner.PlanCompilerImpl

/**
 * Spec 18.2: the OpenRouter client's constructor is `internal`, so `:app` never builds it. These two factories are the
 * only way out of this module: the orchestrator's escalation gate and the T2 planner, each over a client of its own.
 * `:app` calls them only inside the orchestrator's commander factories (nodes keep `Escalator.Off`).
 */
object CloudFactory {
    /** The escalation gate (spec 15) for [selfId] over a fresh client reading [keys] and [config]. */
    fun gate(
        selfId: String,
        keys: CloudKeyStore,
        config: ModelsConfig,
        caps: SpendCaps,
        consent: ConsentSheet,
        consentStore: ConsentStore,
        ledger: LedgerStore,
        journal: JobJournal? = null,
        memo: ChunkMemo = ChunkMemo.None,
        triggers: () -> TriggerSettings = { TriggerSettings() },
        clock: () -> Long = System::currentTimeMillis,
    ): EscalationGate = EscalationGate(selfId, OpenRouterClient.create(keys, config, clock = clock), config, caps, consent, consentStore,
        LedgerStoreSink(ledger, journal), memo, triggers, clock = clock)

    /** The T2 planner (spec 7.2) over a fresh client; [schemaSharing] is the Settings switch. */
    fun planner(
        keys: CloudKeyStore,
        config: ModelsConfig,
        compiler: PlanCompilerImpl,
        caps: SpendCaps,
        selfId: String,
        schemaSharing: () -> Boolean = { false },
        modelHash: () -> String = { "" },
        clock: () -> Long = System::currentTimeMillis,
    ): CloudPlannerImpl = CloudPlannerImpl(OpenRouterClient.create(keys, config, clock = clock), config, compiler, schemaSharing, selfId, modelHash, caps)

    /** Consent remembered in the app's prefs (spec 15.2 step 6): per job id and per month. */
    class PrefsConsentStore(private val prefs: com.hivecore.core.KeyValuePrefs) : ConsentStore {
        override fun allowed(jobId: String, month: String): Boolean = prefs.getBoolean(JOB + jobId, false) || prefs.getBoolean(MONTH + month, false)
        override fun allowJob(jobId: String) = prefs.putBoolean(JOB + jobId, true)
        override fun allowMonth(month: String) = prefs.putBoolean(MONTH + month, true)
        companion object { const val JOB = "cloud_consent_job:"; const val MONTH = "cloud_consent_month:" }
    }
}
