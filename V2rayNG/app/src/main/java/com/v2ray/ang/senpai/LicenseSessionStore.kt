package com.v2ray.ang.senpai

import com.tencent.mmkv.MMKV

/**
 * Multi-process, Keystore-encrypted local session store for license activation,
 * subscription auth headers, and pending offline logout synchronization.
 *
 * Uses MMKV MULTI_PROCESS_MODE so both the UI process and `:RunSoLibV2RayDaemon`
 * observe session state & revocation immediately.
 */
object LicenseSessionStore {
    private const val MMKV_ID = "np_lic_secure_v2"
    private const val K_SESSION_TOKEN_ENC = "sess_tok_enc"
    private const val K_LICENSE_KEY_ENC = "lic_key_enc"
    private const val K_SESSION_ID = "sess_id"
    private const val K_SUB_URL = "sub_url"
    private const val K_ACTIVE = "sess_active"
    private const val K_EXPIRES_AT = "sess_expires_at"
    private const val K_LAST_VERIFIED = "sess_last_verified"
    private const val K_LAST_REFRESHED = "sess_last_refreshed"
    private const val K_PENDING_LOGOUT_TOK_ENC = "pending_logout_tok_enc"
    private const val K_PENDING_LOGOUT_DEV = "pending_logout_dev"

    interface BackingStore {
        fun getString(key: String, default: String = ""): String
        fun putString(key: String, value: String)
        fun getBool(key: String, default: Boolean = false): Boolean
        fun putBool(key: String, value: Boolean)
        fun getLong(key: String, default: Long = 0L): Long
        fun putLong(key: String, value: Long)
    }

    private object MmkvBackingStore : BackingStore {
        private fun mmkvOrNull(): MMKV? = runCatching { MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE) }.getOrNull()
        override fun getString(key: String, default: String): String =
            runCatching { mmkvOrNull()?.decodeString(key, default) }.getOrNull() ?: default
        override fun putString(key: String, value: String) {
            runCatching { mmkvOrNull()?.encode(key, value) }
        }
        override fun getBool(key: String, default: Boolean): Boolean =
            runCatching { mmkvOrNull()?.decodeBool(key, default) }.getOrNull() ?: default
        override fun putBool(key: String, value: Boolean) {
            runCatching { mmkvOrNull()?.encode(key, value) }
        }
        override fun getLong(key: String, default: Long): Long =
            runCatching { mmkvOrNull()?.decodeLong(key, default) }.getOrNull() ?: default
        override fun putLong(key: String, value: Long) {
            runCatching { mmkvOrNull()?.encode(key, value) }
        }
    }

    data class SessionSnapshot(
        val active: Boolean,
        val sessionId: String,
        val sessionToken: String,
        val licenseKey: String,
        val subscriptionUrl: String,
        val expiresAtSec: Long,
        val lastVerifiedAtSec: Long,
        val lastRefreshedAtSec: Long,
    )

    data class PendingLogout(
        val sessionToken: String,
        val deviceId: String,
    )

    private val lock = Any()

    @Volatile
    private var overrideStore: BackingStore? = null

    @Volatile
    private var overrideCrypto: DeviceCryptoProvider? = null

    fun configureForTest(store: BackingStore, crypto: DeviceCryptoProvider) {
        synchronized(lock) {
            overrideStore = store
            overrideCrypto = crypto
        }
    }

    fun resetForTest() {
        synchronized(lock) {
            overrideStore = null
            overrideCrypto = null
        }
    }

    private fun store(): BackingStore = overrideStore ?: MmkvBackingStore
    private fun crypto(): DeviceCryptoProvider = overrideCrypto ?: AndroidKeystoreCryptoProvider

    fun saveSession(
        licenseKey: String,
        sessionId: String,
        sessionToken: String,
        subscriptionUrl: String,
        expiresAtSec: Long,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        synchronized(lock) {
            val s = store()
            val c = crypto()
            s.putString(K_LICENSE_KEY_ENC, c.encryptString(licenseKey.trim().uppercase()))
            s.putString(K_SESSION_TOKEN_ENC, c.encryptString(sessionToken.trim()))
            s.putString(K_SESSION_ID, sessionId.trim())
            s.putString(K_SUB_URL, subscriptionUrl.trim())
            s.putLong(K_EXPIRES_AT, expiresAtSec)
            s.putLong(K_LAST_VERIFIED, nowSec)
            s.putLong(K_LAST_REFRESHED, nowSec)
            s.putBool(K_ACTIVE, licenseKey.isNotBlank() && sessionToken.isNotBlank())
        }
    }

    fun updateRotatedToken(
        sessionToken: String,
        expiresAtSec: Long,
        subscriptionUrl: String? = null,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        synchronized(lock) {
            val s = store()
            val c = crypto()
            s.putString(K_SESSION_TOKEN_ENC, c.encryptString(sessionToken.trim()))
            if (expiresAtSec > 0L) s.putLong(K_EXPIRES_AT, expiresAtSec)
            if (!subscriptionUrl.isNullOrBlank()) s.putString(K_SUB_URL, subscriptionUrl.trim())
            s.putLong(K_LAST_VERIFIED, nowSec)
            s.putLong(K_LAST_REFRESHED, nowSec)
            s.putBool(K_ACTIVE, true)
        }
    }

    fun touchVerified(
        expiresAtSec: Long = 0L,
        subscriptionUrl: String? = null,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        synchronized(lock) {
            val s = store()
            if (expiresAtSec > 0L) s.putLong(K_EXPIRES_AT, expiresAtSec)
            if (!subscriptionUrl.isNullOrBlank()) s.putString(K_SUB_URL, subscriptionUrl.trim())
            s.putLong(K_LAST_VERIFIED, nowSec)
        }
    }

    fun getSnapshot(): SessionSnapshot {
        synchronized(lock) {
            val s = store()
            val c = crypto()
            val active = s.getBool(K_ACTIVE, false)
            val encTok = s.getString(K_SESSION_TOKEN_ENC, "")
            val encKey = s.getString(K_LICENSE_KEY_ENC, "")
            val tok = if (encTok.isNotBlank()) c.decryptString(encTok).orEmpty() else ""
            val key = if (encKey.isNotBlank()) c.decryptString(encKey).orEmpty() else ""
            val sid = s.getString(K_SESSION_ID, "")
            val subUrl = s.getString(K_SUB_URL, "")
            val exp = s.getLong(K_EXPIRES_AT, 0L)
            val lastVer = s.getLong(K_LAST_VERIFIED, 0L)
            val lastRef = s.getLong(K_LAST_REFRESHED, 0L)
            val effectiveActive = active && tok.isNotBlank() && key.isNotBlank()
            return SessionSnapshot(
                active = effectiveActive,
                sessionId = sid,
                sessionToken = tok,
                licenseKey = key,
                subscriptionUrl = subUrl,
                expiresAtSec = exp,
                lastVerifiedAtSec = lastVer,
                lastRefreshedAtSec = lastRef,
            )
        }
    }

    fun hasActiveSession(): Boolean {
        val snap = getSnapshot()
        if (!snap.active) return false
        val nowSec = System.currentTimeMillis() / 1000L
        return snap.expiresAtSec <= 0L || snap.expiresAtSec > nowSec
    }

    fun clearActiveSession() {
        synchronized(lock) {
            val s = store()
            s.putBool(K_ACTIVE, false)
            s.putString(K_SESSION_TOKEN_ENC, "")
            s.putString(K_LICENSE_KEY_ENC, "")
            s.putString(K_SESSION_ID, "")
            s.putString(K_SUB_URL, "")
            s.putLong(K_EXPIRES_AT, 0L)
            s.putLong(K_LAST_VERIFIED, 0L)
            s.putLong(K_LAST_REFRESHED, 0L)
        }
    }

    fun enqueuePendingLogout(sessionToken: String, deviceId: String) {
        if (sessionToken.isBlank() || deviceId.isBlank()) return
        synchronized(lock) {
            val s = store()
            val c = crypto()
            s.putString(K_PENDING_LOGOUT_TOK_ENC, c.encryptString(sessionToken.trim()))
            s.putString(K_PENDING_LOGOUT_DEV, deviceId.trim())
        }
    }

    fun getPendingLogout(): PendingLogout? {
        synchronized(lock) {
            val s = store()
            val c = crypto()
            val enc = s.getString(K_PENDING_LOGOUT_TOK_ENC, "")
            val dev = s.getString(K_PENDING_LOGOUT_DEV, "")
            if (enc.isBlank() || dev.isBlank()) return null
            val tok = c.decryptString(enc).orEmpty()
            if (tok.isBlank()) return null
            return PendingLogout(tok, dev)
        }
    }

    fun clearPendingLogout() {
        synchronized(lock) {
            val s = store()
            s.putString(K_PENDING_LOGOUT_TOK_ENC, "")
            s.putString(K_PENDING_LOGOUT_DEV, "")
        }
    }
}
