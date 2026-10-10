package com.hivecore.cloud

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Spec 15.7: the OpenRouter key, entered once in Settings and read only by the cloud client in the orchestrator path.
 * No message, setting sync or backup path carries it; a node never holds an implementation with a key.
 */
interface CloudKeyStore {
    /** The key, or null when none is entered. */
    fun key(): String?
    fun set(key: String?)
    val present: Boolean get() = !key().isNullOrBlank()
}

/** The Android store: `EncryptedSharedPreferences` backed by a Keystore master key (AES-256-GCM). */
class EncryptedCloudKeyStore(context: Context) : CloudKeyStore {
    private val prefs by lazy {
        val app = context.applicationContext
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    override fun key(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
    override fun set(key: String?) { prefs.edit().apply { if (key.isNullOrBlank()) remove(KEY) else putString(KEY, key.trim()) }.apply() }

    companion object {
        const val FILE = "hivecore_cloud"
        const val KEY = "openrouter_key"
    }
}

/** In-memory key for JVM tests and previews; the value is a placeholder, never a real key. */
class MemoryCloudKeyStore(private var value: String? = null) : CloudKeyStore {
    override fun key(): String? = value
    override fun set(key: String?) { value = key }
}
