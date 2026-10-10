package com.hivecore.core.fakes

import com.hivecore.core.Signer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** In-memory P-256 key pair (plain JCA, no Keystore). Same algorithm and encodings as the Keystore signer. */
class FakeSigner(val keyPair: KeyPair = generate()) : Signer {
    override val publicKeyB64: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    override fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(bytes)
        sign()
    }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
