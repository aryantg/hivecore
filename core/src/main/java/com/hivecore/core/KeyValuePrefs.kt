package com.hivecore.core

import android.content.Context

/** The few UI settings that persist (SharedPreferences "hivecore", the file [com.hivecore.core.NodePrefs] uses). */
interface KeyValuePrefs {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)

    companion object {
        const val FILE = "hivecore"
        /** Settings "LLM phrasing" toggle (plan 11 6a). The ask pipeline reads this key; default off. */
        const val KEY_LLM_PHRASING = "llm_phrasing"
        /** Ledger retention in days. */
        const val KEY_LEDGER_RETAIN_DAYS = "ledger_retain_days"
    }
}

class SharedPrefsKv(context: Context) : KeyValuePrefs {
    private val prefs = context.applicationContext.getSharedPreferences(KeyValuePrefs.FILE, Context.MODE_PRIVATE)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getString(key: String, default: String?) = prefs.getString(key, default)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
}

/** In-memory prefs for JVM tests and previews. */
class MemoryPrefs : KeyValuePrefs {
    val map = HashMap<String, Any?>()
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getString(key: String, default: String?) = map[key] as? String ?: default
    override fun putString(key: String, value: String?) { map[key] = value }
}
