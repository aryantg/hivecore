package com.hivecore.cloud

import com.hivecore.core.KeyValuePrefs
import java.time.Instant
import java.time.ZoneOffset

/** Persisted cloud spend in INR per scope (`job:<jobId>`, `month:<yyyy-MM>`); the small seam [SpendCaps] needs. */
interface SpendStore {
    fun spent(scope: String): Double
    fun add(scope: String, inr: Double)
}

/** In-memory spend for tests and previews. */
class MemorySpendStore : SpendStore {
    val map = HashMap<String, Double>()
    @Synchronized override fun spent(scope: String): Double = map[scope] ?: 0.0
    @Synchronized override fun add(scope: String, inr: Double) { map[scope] = (map[scope] ?: 0.0) + inr }
}

/** Spend kept in the app's prefs under `cloud_spend:<scope>`. */
class PrefsSpendStore(private val prefs: KeyValuePrefs) : SpendStore {
    override fun spent(scope: String): Double = prefs.getString(KEY + scope, null)?.toDoubleOrNull() ?: 0.0
    override fun add(scope: String, inr: Double) { prefs.putString(KEY + scope, (spent(scope) + inr).toString()) }
    companion object { const val KEY = "cloud_spend:" }
}

/** Per-role caps (spec 15.5 `jobCapInr`, `monthCapInr`). */
data class RoleCaps(val jobCapInr: Double, val monthCapInr: Double)

/**
 * Spec 15.2 step 5: `jobSpent + estimate ≤ jobCap` and `monthSpent + estimate ≤ monthCap`, with the caps of the role
 * that would answer ([caps], from `models.json` by default) and the month on the orchestrator's clock (UTC). The month
 * scope is shared by every role (one monthly cloud bill); the job scope is per job id.
 */
class SpendCaps(
    private val store: SpendStore,
    private val caps: (CloudRole) -> RoleCaps,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(store: SpendStore, config: ModelsConfig, clock: () -> Long = System::currentTimeMillis) :
        this(store, { r -> config.role(r).let { RoleCaps(it.jobCapInr, it.monthCapInr) } }, clock)

    fun jobSpent(jobId: String): Double = store.spent(JOB + jobId)
    fun monthSpent(): Double = store.spent(MONTH + month())

    /** Null when [estimateInr] fits both caps, else the plain-words reason. */
    fun check(role: CloudRole, jobId: String, estimateInr: Double): String? {
        val c = caps(role)
        val job = jobSpent(jobId); val month = monthSpent()
        if (job + estimateInr > c.jobCapInr) return "this job's cloud cap of ${inr(c.jobCapInr)} would be exceeded (spent ${inr(job)}, this call about ${inr(estimateInr)})"
        if (month + estimateInr > c.monthCapInr) return "this month's cloud cap of ${inr(c.monthCapInr)} would be exceeded (spent ${inr(month)}, this call about ${inr(estimateInr)})"
        return null
    }

    /** Books a call's cost against the job and the month. */
    fun record(jobId: String, costInr: Double) {
        if (costInr <= 0.0) return
        store.add(JOB + jobId, costInr)
        store.add(MONTH + month(), costInr)
    }

    fun month(): String = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    companion object {
        const val JOB = "job:"
        const val MONTH = "month:"
        fun inr(v: Double): String = "₹" + if (v >= 100) "%.0f".format(v) else "%.2f".format(v)
    }
}
