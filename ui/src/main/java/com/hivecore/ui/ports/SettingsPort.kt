package com.hivecore.ui.ports

// Spec section 17 (Settings), 15.7 (key handling), 13.6 (ledger). The plain settings persist through
// `core.KeyValuePrefs` under [SettingsKeys]; the OpenRouter key never touches prefs: it goes through [SettingsPort],
// which the cloud track implements over EncryptedSharedPreferences. The ledger export and "forget a peer" ride on the
// same port (state track and mesh+trust track respectively). [FakeSettingsPort] drives the screen and the tests.

/** Spec section 9.2 policy settings. */
enum class CloudPolicy(val key: String, val label: String, val blurb: String) {
    ASK("ask", "Ask every time", "Every plan shows LOCAL and CLOUD with estimates; you pick."),
    LOCAL_UNLESS_IMPOSSIBLE("local_unless_impossible", "Local unless impossible", "Runs on your phones; cloud only for a step no local model can do."),
    FASTEST("fastest", "Fastest", "Picks the quicker option within the caps; private steps stay local.");

    companion object {
        fun of(key: String?): CloudPolicy = entries.firstOrNull { it.key == key } ?: ASK
    }
}

/** The `KeyValuePrefs` keys the Settings screen writes and the engines read. */
object SettingsKeys {
    const val CLOUD_POLICY = "cloud_policy"
    const val CAP_JOB_INR = "cap_job_inr"
    const val CAP_MONTH_INR = "cap_month_inr"
    const val SCHEMA_SHARING = "schema_sharing"
    const val MOBILE_DATA_BLOBS = "mobile_data_blobs"
    const val RELAY_ADDRESS = "relay_address"
    const val HEARTBEAT_LAN_S = "heartbeat_lan_s"
    const val HEARTBEAT_WAN_S = "heartbeat_wan_s"
    const val DEADLINE_FLOOR_S = "deadline_floor_s"
    const val DEADLINE_FACTOR_X10 = "deadline_factor_x10"

    const val DEFAULT_CAP_JOB_INR = 20
    const val DEFAULT_CAP_MONTH_INR = 500
    /** Spec section 5.5: 15 s on LAN, 30 s on WAN. */
    const val DEFAULT_HEARTBEAT_LAN_S = 15
    const val DEFAULT_HEARTBEAT_WAN_S = 30
    /** Spec section 11.4: `max(4 s, 3 × expected + 2·rtt)`. */
    const val DEFAULT_DEADLINE_FLOOR_S = 4
    const val DEFAULT_DEADLINE_FACTOR_X10 = 30
}

interface SettingsPort {
    // ---- OpenRouter key (cloud track): write-only from the UI's point of view ----
    fun hasKey(): Boolean
    /** A hint that never reveals the key: the last four characters, or null when none is stored. */
    fun keyHint(): String?
    fun setKey(key: String): Result<Unit>
    fun clearKey()

    // ---- ledger (state track): writes the receipts to a file and returns its path ----
    suspend fun exportLedger(): Result<String>

    // ---- peers (mesh+trust track) ----
    fun knownPeers(): List<MemberView>
    /** Unpins the peer's key and drops it from this phone's view of the roster. */
    suspend fun forgetPeer(nodeId: String): Result<Unit>
}
