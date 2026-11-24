package ru.mailchat.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class McIdentity(context: Context) {
    init { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "mc_identity", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    @Synchronized fun keyPair(): KeyPair {
        val factory = KeyFactory.getInstance("Ed25519", "BC")
        val priv = prefs.getString("private", null)
        val pub = prefs.getString("public", null)
        if (priv != null && pub != null) return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))),
            factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv)))
        )
        val pair = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        prefs.edit().putString("private", Base64.getEncoder().encodeToString(pair.private.encoded))
            .putString("public", Base64.getEncoder().encodeToString(pair.public.encoded)).apply()
        return pair
    }
    fun publicKey(): String = Base64.getEncoder().encodeToString(keyPair().public.encoded)
}
