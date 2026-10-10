package com.hivecore.trust

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import com.hivecore.core.SLog
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The approval (biometric) key, plan 12: Android Keystore alias `hivecore_approval_key`, P-256, SHA256withECDSA,
 * user authentication required per use with BIOMETRIC_STRONG, invalidated when the fingerprint enrolment changes.
 *
 * Creating a user-auth-required key with no biometric enrolled throws, so [ensure] first checks [canEnroll] and
 * returns null when no fingerprint is enrolled; the Setup screen then shows "Enroll a fingerprint" with
 * [enrollIntent]. The exported public key (X.509 SubjectPublicKeyInfo, base64) travels in HELLO as
 * `approvalPubkey`, the same encoding as the node key in `mesh/KeystoreSigner`.
 *
 * Signing never happens here: the private key is per-use and must be wrapped in a `BiometricPrompt.CryptoObject`
 * (see [ApprovalActivity]); calling `sign()` outside the prompt throws `UserNotAuthenticatedException`.
 */
class ApprovalKeys(context: Context) {
    private val ctx = context.applicationContext

    /** True when a BIOMETRIC_STRONG credential is enrolled and usable right now. */
    fun canEnroll(): Boolean = try {
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        SLog.w(TAG, "canAuthenticate failed: $e"); false
    }

    /** True when the alias exists in the Keystore (it may still be invalidated; that only shows at signing time). */
    fun exists(): Boolean = try { keyStore().containsAlias(ALIAS) } catch (e: Exception) { SLog.w(TAG, "containsAlias failed: $e"); false }

    /**
     * Creates the key when a biometric is enrolled and it does not exist yet. Returns the base64 public key, or null
     * when no biometric is enrolled (HELLO then omits the "approval" cap) or the Keystore refused.
     */
    fun ensure(): String? {
        if (!canEnroll()) {
            SLog.w(TAG, "no BIOMETRIC_STRONG credential enrolled; approval key not created")
            return null
        }
        return try {
            val ks = keyStore()
            if (!ks.containsAlias(ALIAS)) {
                SLog.d(TAG, "creating approval key $ALIAS")
                val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                gen.initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        .setInvalidatedByBiometricEnrollment(true)
                        .build()
                )
                gen.generateKeyPair()
            }
            publicKeyB64().also { SLog.d(TAG, "approval key ready; pubkey=${it?.take(16)}...") }
        } catch (e: Exception) {
            SLog.e(TAG, "approval key creation failed", e)
            null
        }
    }

    /** Base64 X.509 SPKI of the approval key, or null when it does not exist. Never throws. */
    fun publicKeyB64(): String? = try {
        keyStore().getCertificate(ALIAS)?.publicKey?.encoded?.let { Base64.getEncoder().encodeToString(it) }
    } catch (e: Exception) {
        SLog.w(TAG, "publicKeyB64 failed: $e"); null
    }

    /**
     * The private key handle for `Signature.initSign` inside a CryptoObject. `initSign` throws
     * `KeyPermanentlyInvalidatedException` after a fingerprint re-enrolment; callers then [recreate].
     */
    fun privateKey(): PrivateKey =
        (keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey
            ?: throw IllegalStateException("approval key $ALIAS does not exist; call ensure() first")

    /** Deletes the (invalidated) key and creates a fresh one. Returns the new public key or null (see [ensure]). */
    fun recreate(): String? {
        try {
            val ks = keyStore()
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
            SLog.w(TAG, "approval key $ALIAS deleted (biometric enrolment changed); re-creating")
        } catch (e: Exception) {
            SLog.e(TAG, "deleteEntry failed", e)
        }
        return ensure()
    }

    /** Deep link into the system fingerprint enrolment, limited to BIOMETRIC_STRONG credentials. */
    fun enrollIntent(): Intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG)

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        const val ALIAS = "hivecore_approval_key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TAG = "ApprovalKeys"
    }
}
