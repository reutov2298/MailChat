package ru.mailchat.app

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64


/** Ed25519 identity key. Private key bytes must remain local; only public key is sent. */
object McCrypto {
    internal val provider = BouncyCastleProvider()
    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519", provider).generateKeyPair()
    fun publicKey(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)
    fun sign(pair: KeyPair, canonicalPayload: ByteArray): String {
        val signer = Signature.getInstance("Ed25519", provider)
        signer.initSign(pair.private); signer.update(canonicalPayload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }
    fun verify(publicKey: java.security.PublicKey, payload: ByteArray, signature: String): Boolean = try {
        val verifier = Signature.getInstance("Ed25519", provider)
        verifier.initVerify(publicKey); verifier.update(payload)
        verifier.verify(Base64.getDecoder().decode(signature))
    } catch (_: Exception) { false }
}
