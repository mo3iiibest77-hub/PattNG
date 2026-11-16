package com.v2ray.ang.senpai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class DeviceMetadata(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val osVersion: String,
    val apiLevel: Int,
    val appVersion: String,
)

/**
 * Generates and persists a stable, non-guessable per-installation cryptographic device identity
 * backed by Android Keystore encryption & ECDSA P-256 hardware proof keys.
 */
object DeviceIdentityManager {
    private const val PREFS_NAME = "np_dev_id_prefs_v2"
    private const val MMKV_ID = "np_dev_id_secure_v2"
    private const val LOCK_FILE_NAME = "np_dev_id_v2.lock"
    private const val KEY_INSTALL_ID_ENC = "install_id_enc"
    private const val KEY_DEVICE_ID_ENC = "device_id_enc"
    private const val INVALID_EMULATOR_ANDROID_ID = "9774d56d682e549c"
    private val LOW_ENTROPY_ANDROID_IDS = setOf(
        INVALID_EMULATOR_ANDROID_ID,
        "0000000000000000",
        "ffffffffffffffff",
        "0123456789abcdef",
        "fedcba9876543210",
        "1234567890abcdef",
        "android_id",
    )
    const val LEGACY_SHARED_ANDROID_ID = "YW5kcm9pZF9pZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    interface KeyValueStorage {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun clear()
    }

    /**
     * Multi-process identity storage backed by MMKV_MULTI_PROCESS_MODE so main UI,
     * `:RunSoLibV2RayDaemon`, and `:bg` always observe the exact same cryptographic device_id,
     * while transparently migrating legacy single-process SharedPreferences values once.
     */
    private class MmkvDeviceIdentityStorage(context: Context) : KeyValueStorage {
        private val appCtx = context.applicationContext
        private val legacyPrefs by lazy {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private fun mmkvOrNull(): MMKV? = runCatching {
            if (MMKV.getRootDir().isNullOrBlank()) {
                com.v2ray.ang.handler.MmkvManager.initialize(appCtx)
            }
            MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
        }.getOrNull()

        override fun getString(key: String): String? {
            val mmkv = mmkvOrNull()
            val fromMmkv = mmkv?.decodeString(key, null)?.takeIf { it.isNotBlank() }
            if (fromMmkv != null) return fromMmkv

            val fromLegacy = legacyPrefs.getString(key, null)?.takeIf { it.isNotBlank() }
            if (fromLegacy != null) {
                mmkv?.encode(key, fromLegacy)
            }
            return fromLegacy
        }

        override fun putString(key: String, value: String) {
            val mmkv = mmkvOrNull()
            if (mmkv != null) {
                mmkv.encode(key, value)
            } else {
                legacyPrefs.edit().putString(key, value).apply()
            }
        }

        override fun clear() {
            mmkvOrNull()?.clearAll()
            legacyPrefs.edit().clear().apply()
        }
    }

    private val lock = Any()
    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedDeviceId: String? = null

    @Volatile
    private var overrideCrypto: DeviceCryptoProvider? = null

    @Volatile
    private var overrideStorage: KeyValueStorage? = null

    @Volatile
    private var overrideAndroidId: String? = null

    @Volatile
    private var overrideMetadata: DeviceMetadata? = null

    fun configureForTest(
        crypto: DeviceCryptoProvider,
        storage: KeyValueStorage,
        androidIdAux: String = "",
        metadata: DeviceMetadata? = null,
    ) {
        synchronized(lock) {
            overrideCrypto = crypto
            overrideStorage = storage
            overrideAndroidId = androidIdAux
            overrideMetadata = metadata
            cachedDeviceId = null
        }
    }

    fun resetForTest() {
        synchronized(lock) {
            overrideCrypto = null
            overrideStorage = null
            overrideAndroidId = null
            overrideMetadata = null
            cachedDeviceId = null
        }
    }

    private fun cryptoProvider(): DeviceCryptoProvider = overrideCrypto ?: AndroidKeystoreCryptoProvider

    private fun storage(context: Context?): KeyValueStorage {
        overrideStorage?.let { return it }
        val appCtx = (context ?: AngApplication.application).applicationContext
        return MmkvDeviceIdentityStorage(appCtx)
    }

    private inline fun <T> withCrossProcessLock(context: Context?, block: () -> T): T {
        if (overrideStorage != null) return block()
        val dir = runCatching {
            val appCtx = (context ?: AngApplication.application).applicationContext
            appCtx.noBackupFilesDir ?: appCtx.filesDir
        }.getOrNull() ?: return block()

        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val lockFile = File(dir, LOCK_FILE_NAME)
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    block()
                }
            }
        }.getOrElse { block() }
    }

    @SuppressLint("HardwareIds")
    private fun readAndroidIdAuxiliary(context: Context?): String {
        overrideAndroidId?.let { return it }
        val appCtx = runCatching { (context ?: AngApplication.application).applicationContext }.getOrNull()
            ?: return ""
        val raw = runCatching {
            Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()?.lowercase(Locale.US).orEmpty()
        if (raw.length < 8 || raw == INVALID_EMULATOR_ANDROID_ID || raw == "android_id") {
            return ""
        }
        return raw
    }

    private fun randomHex256(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getDeviceId(context: Context? = null): String {
        cachedDeviceId?.let { if (it.isNotBlank() && it != LEGACY_SHARED_ANDROID_ID) return it }
        synchronized(lock) {
            cachedDeviceId?.let { if (it.isNotBlank() && it != LEGACY_SHARED_ANDROID_ID) return it }
            return withCrossProcessLock(context) {
                val store = storage(context)
                val crypto = cryptoProvider()

                val existingEnc = store.getString(KEY_DEVICE_ID_ENC)
                if (!existingEnc.isNullOrBlank()) {
                    val decrypted = crypto.decryptString(existingEnc)
                    if (!decrypted.isNullOrBlank() && decrypted != LEGACY_SHARED_ANDROID_ID) {
                        cachedDeviceId = decrypted
                        return@withCrossProcessLock decrypted
                    }
                }

                val installId = store.getString(KEY_INSTALL_ID_ENC)
                    ?.let { crypto.decryptString(it) }
                    ?.takeIf { it.length >= 32 }
                    ?: randomHex256().also { generated ->
                        store.putString(KEY_INSTALL_ID_ENC, crypto.encryptString(generated))
                    }

                val androidIdAux = readAndroidIdAuxiliary(context)
                val pkg = runCatching { (context ?: AngApplication.application).packageName }.getOrDefault(BuildConfig.APPLICATION_ID)
                val deviceId = sha256Hex("np_dev_v2|$installId|$androidIdAux|$pkg")
                store.putString(KEY_DEVICE_ID_ENC, crypto.encryptString(deviceId))
                cachedDeviceId = deviceId
                deviceId
            }
        }
    }

    /**
     * Produces an opaque SHA-256 device-slot continuity fingerprint when the OS reports a high-entropy
     * hardware Settings.Secure.ANDROID_ID. Returns "" for emulators or bogus/low-entropy OEM IDs so
     * different devices never share a continuity slot.
     */
    fun getDeviceSlotFingerprint(context: Context? = null): String {
        val androidIdAux = readAndroidIdAuxiliary(context)
        if (!isHighConfidenceAndroidId(androidIdAux)) return ""
        val meta = getDeviceInfo()
        val brand = meta.brand.trim().lowercase(Locale.US)
        val model = meta.model.trim().lowercase(Locale.US)
        return sha256Hex("np_slot_v1|$androidIdAux|$brand|$model")
    }

    private fun isHighConfidenceAndroidId(raw: String): Boolean {
        val clean = raw.trim().lowercase(Locale.US)
        if (clean.length < 12) return false
        if (clean in LOW_ENTROPY_ANDROID_IDS) return false
        if (clean.toSet().size < 4) return false
        return true
    }

    fun getPublicKeySpkiB64(): String = cryptoProvider().getOrCreateSigningPublicKeySpkiB64()

    fun signChallenge(nonce: String, purpose: String, context: Context? = null): String {
        val devId = getDeviceId(context)
        val msg = "v1|$nonce|$devId|$purpose".toByteArray(Charsets.UTF_8)
        return cryptoProvider().signProofMessage(msg)
    }

    /**
     * Generates a stable 32-byte URL-safe Base64 XUDP base key isolated from license auth identifiers.
     */
    fun getXudpBaseKey(context: Context? = null): String {
        val devId = getDeviceId(context)
        val digest = MessageDigest.getInstance("SHA-256").digest("np_xudp_v1|$devId".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.copyOf(32), Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun getDeviceInfo(): DeviceMetadata {
        overrideMetadata?.let { return it }
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val brand = Build.BRAND?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val prefix = manufacturer.ifBlank { brand }
        val deviceName = when {
            prefix.isBlank() && model.isBlank() -> "Android Device"
            model.startsWith(prefix, ignoreCase = true) -> model
            else -> "$prefix $model".trim()
        }.take(80)
        return DeviceMetadata(
            deviceName = deviceName,
            manufacturer = manufacturer.take(48),
            model = model.take(64),
            brand = brand.take(48),
            osVersion = (Build.VERSION.RELEASE ?: "").take(32),
            apiLevel = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME.take(32),
        )
    }
}
