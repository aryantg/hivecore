package com.hivecore.cloud

import android.content.res.AssetManager
import com.hivecore.planner.CloudPrices
import com.hivecore.planner.StepPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One role's slug chain and caps (spec 15.5 `cloud/models.json`): `{models[], maxTokens, jobCapInr, monthCapInr}`. */
@Serializable data class RoleConfig(
    val models: List<String>,
    val maxTokens: Int,
    val jobCapInr: Double,
    val monthCapInr: Double,
)

/** List price of a slug in USD per million tokens (input / output), as listed on the date in [ModelsConfig.version]. */
@Serializable data class SlugPrice(@SerialName("in") val input: Double, val out: Double)

/**
 * `cloud/models.json` (spec 15.5): the slugs per role, the output cap, the spend caps and the list prices that feed the
 * pre-flight estimate. Prices are USD; estimates are converted at [inrPerUsd], a documented reference rate
 * ([INR_PER_USD]) that only sizes estimates: the ledger records the cost OpenRouter reports. Swapping a model is an
 * edit of this file; the slugs must be verified against `GET /api/v1/models` before the cloud is enabled.
 */
@Serializable data class ModelsConfig(
    val version: String,
    val about: String = "",
    val roles: Map<String, RoleConfig>,
    val prices: Map<String, SlugPrice> = emptyMap(),
    val inrPerUsd: Double = INR_PER_USD,
) {
    init {
        for (r in CloudRole.entries) require(r.name in roles) { "models.json has no role ${r.name}" }
        for ((name, r) in roles) require(r.models.isNotEmpty()) { "role $name has no models" }
    }

    fun role(r: CloudRole): RoleConfig = roles.getValue(r.name)

    /** The slug that answers first for [r] (its list price sizes the estimate). */
    fun primary(r: CloudRole): String = role(r).models.first()

    fun price(slug: String): SlugPrice? = prices[slug]

    /** Estimated cost in INR of [tokensIn] prompt and [tokensOut] output tokens on [slug] at list price (0 when unknown). */
    fun estimateInr(slug: String, tokensIn: Long, tokensOut: Long): Double {
        val p = prices[slug] ?: return 0.0
        return (tokensIn * p.input + tokensOut * p.out) / 1_000_000.0 * inrPerUsd
    }

    /** The prices the plan panel's CLOUD estimate uses (spec 9.1). */
    fun cloudPrices(): CloudPrices {
        fun step(r: CloudRole): StepPrice { val s = primary(r); val p = prices[s]; return StepPrice(s, p?.input ?: 0.0, p?.out ?: 0.0) }
        return CloudPrices(step(CloudRole.ESCALATE_TEXT), step(CloudRole.ESCALATE_MULTIMODAL), inrPerUsd)
    }

    companion object {
        const val ASSET_PATH = "cloud/models.json"
        /**
         * Reference INR per USD used for every estimate (RBI reference rate rounded, 2026-10-10). A constant on purpose:
         * estimates must be reproducible in tests and across phones; the ledger holds the real cost.
         */
        const val INR_PER_USD = 88.0

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelsConfig = json.decodeFromString(serializer(), text)

        fun load(assets: AssetManager): ModelsConfig = assets.open(ASSET_PATH).bufferedReader().use { parse(it.readText()) }
    }
}
