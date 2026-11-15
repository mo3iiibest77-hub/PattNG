package com.v2ray.ang.senpai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cryptographic operations bound to the device's Android Keystore when running on Android,
 * abstracted behind an interface for deterministic JVM unit testing.
 */
interface DeviceCryptoProvider {
    fun encryptString(plaintext: String): String
    fun decryptString(encoded: String): String?
    fun getOrCreateSigningPublicKeySpkiB64(): String
    fun signProofMessage(message: ByteArray): String
}

object AndroidKeystoreCryptoProvider : DeviceCryptoProvider {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_ALIAS = "np_sec_aes_v1"
    private const val ECDSA_ALIAS = "np_sec_ecdsa_v1"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX_V1 = "v1:"

    private val lock = Any()

    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            AES_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun getOrCreateSigningEntry(): KeyStore.PrivateKeyEntry {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(ECDSA_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ECDSA_ALIAS,
            KeyProperties.PURPOSE_SIGN,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()

        return (ks.getEntry(ECDSA_ALIAS, null) as? KeyStore.PrivateKeyEntry)
            ?: throw IllegalStateException("Keystore failed to persist ECDSA signing key")
    }

    override fun encryptString(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        synchronized(lock) {
            val key = getOrCreateAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
            return "$PREFIX_V1$ivB64:$ctB64"
        }
    }

    override fun decryptString(encoded: String): String? {
        if (encoded.isBlank()) return null
        if (!encoded.startsWith(PREFIX_V1)) return null
        return synchronized(lock) {
            runCatching {
                val payload = encoded.removePrefix(PREFIX_V1)
                val sep = payload.indexOf(':')
                if (sep <= 0) return@runCatching null
                val iv = Base64.decode(payload.substring(0, sep), Base64.DEFAULT)
                val ct = Base64.decode(payload.substring(sep + 1), Base64.DEFAULT)
                val key = getOrCreateAesKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            }.getOrNull()
        }
    }

    override fun getOrCreateSigningPublicKeySpkiB64(): String {
        synchronized(lock) {
            val entry = getOrCreateSigningEntry()
            val spki = entry.certificate.publicKey.encoded
            return Base64.encodeToString(spki, Base64.NO_WRAP)
        }
    }

    override fun signProofMessage(message: ByteArray): String {
        synchronized(lock) {
            val entry = getOrCreateSigningEntry()
            val priv: PrivateKey = entry.privateKey
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(priv)
            sig.update(message)
            return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        }
    }
}
