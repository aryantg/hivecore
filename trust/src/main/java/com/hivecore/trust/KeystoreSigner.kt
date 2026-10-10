package com.hivecore.trust

import com.hivecore.mesh.Wire
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The node key (plan 12): Android Keystore alias `hivecore_node_key`, P-256, SHA256withECDSA, no user auth.
 * Created on first use; survives app restarts (not uninstalls). `publicKeyB64` is the X.509 SubjectPublicKeyInfo,
 * the same encoding as [com.hivecore.core.fakes.FakeSigner], so `nodeId = sha256Hex(decode(publicKeyB64))`.
 * All pure logic lives in [Wire]; this class only talks to the Keystore (device-tested in Wave 2).
 */
class KeystoreSigner(private val alias: String = ALIAS) : Signer {
    private val privateKey: PrivateKey
    override val publicKeyB64: String

    init {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!ks.containsAlias(alias)) {
            SLog.d("KeystoreSigner", "creating node key $alias")
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            gen.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            gen.generateKeyPair()
        }
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        privateKey = entry.privateKey
        publicKeyB64 = Base64.getEncoder().encodeToString(entry.certificate.publicKey.encoded)
        SLog.d("KeystoreSigner", "node key ready; nodeId=${Wire.nodeIdOf(publicKeyB64)?.take(8)}")
    }

    override fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(bytes)
        sign()
    }

    companion object {
        const val ALIAS = "hivecore_node_key"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
