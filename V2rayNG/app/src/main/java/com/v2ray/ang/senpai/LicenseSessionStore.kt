package com.v2ray.ang.senpai

import com.tencent.mmkv.MMKV
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

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
    private const val K_REFRESH_LEASE_UNTIL_MS = "sess_refresh_lease_until_ms"
    private const val K_REFRESH_LEASE_OWNER = "sess_refresh_lease_owner"
    private const val K_REFRESH_FAIL_UNTIL_MS = "sess_refresh_fail_until_ms"
    private const val K_PENDING_NOTICE_MSG = "pending_notice_msg"

    interface BackingStore {
        fun getString(key: String, default: String = ""): String
        fun putString(key: String, value: String)
        fun getBool(key: String, default: Boolean = false): Boolean
        fun putBool(key: String, value: Boolean)
        fun getLong(key: String, default: Long = 0L): Long
        fun putLong(key: String, value: Long)
    }

    private object MmkvBackingStore : BackingStore {
        private fun mmkvOrNull(): MMKV? = runCatching {
            if (MMKV.getRootDir().isNullOrBlank()) {
                runCatching { com.v2ray.ang.handler.MmkvManager.initialize(com.v2ray.ang.AngApplication.application) }
            }
            MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
        }.getOrNull()
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

    @Volatile
    private var lastKnownSnapshot: SessionSnapshot? = null

    fun configureForTest(store: BackingStore, crypto: DeviceCryptoProvider) {
        synchronized(lock) {
            overrideStore = store
            overrideCrypto = crypto
            lastKnownSnapshot = null
        }
    }

    fun resetForTest() {
        synchronized(lock) {
            overrideStore = null
            overrideCrypto = null
            lastKnownSnapshot = null
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
            val cleanKey = licenseKey.trim().uppercase()
            val cleanTok = sessionToken.trim()
            val cleanSid = sessionId.trim()
            val cleanSub = subscriptionUrl.trim()
            val isActive = cleanKey.isNotBlank() && cleanTok.isNotBlank()
            s.putString(K_LICENSE_KEY_ENC, c.encryptString(cleanKey))
            s.putString(K_SESSION_TOKEN_ENC, c.encryptString(cleanTok))
            s.putString(K_SESSION_ID, cleanSid)
            s.putString(K_SUB_URL, cleanSub)
            s.putLong(K_EXPIRES_AT, expiresAtSec)
            s.putLong(K_LAST_VERIFIED, nowSec)
            s.putLong(K_LAST_REFRESHED, nowSec)
            s.putLong(K_REFRESH_FAIL_UNTIL_MS, 0L)
            s.putBool(K_ACTIVE, isActive)
            lastKnownSnapshot = if (isActive) {
                SessionSnapshot(
                    active = true,
                    sessionId = cleanSid,
                    sessionToken = cleanTok,
                    licenseKey = cleanKey,
                    subscriptionUrl = cleanSub,
                    expiresAtSec = expiresAtSec,
                    lastVerifiedAtSec = nowSec,
                    lastRefreshedAtSec = nowSec,
                )
            } else null
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
            val cleanTok = sessionToken.trim()
            s.putString(K_SESSION_TOKEN_ENC, c.encryptString(cleanTok))
            if (expiresAtSec > 0L) s.putLong(K_EXPIRES_AT, expiresAtSec)
            if (!subscriptionUrl.isNullOrBlank()) s.putString(K_SUB_URL, subscriptionUrl.trim())
            s.putLong(K_LAST_VERIFIED, nowSec)
            s.putLong(K_LAST_REFRESHED, nowSec)
            s.putLong(K_REFRESH_FAIL_UNTIL_MS, 0L)
            s.putBool(K_ACTIVE, true)
            lastKnownSnapshot = lastKnownSnapshot?.copy(
                active = true,
                sessionToken = cleanTok,
                expiresAtSec = if (expiresAtSec > 0L) expiresAtSec else lastKnownSnapshot?.expiresAtSec ?: 0L,
                subscriptionUrl = if (!subscriptionUrl.isNullOrBlank()) subscriptionUrl.trim() else lastKnownSnapshot?.subscriptionUrl.orEmpty(),
                lastVerifiedAtSec = nowSec,
                lastRefreshedAtSec = nowSec,
            )
        }
    }

    fun touchVerified(
        expiresAtSec: Long = 0L,
        subscriptionUrl: String? = null,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        synchronized(lock) {
            val s = store()
            if (expiresAtSec > 0L && s.getLong(K_EXPIRES_AT, 0L) != expiresAtSec) {
                s.putLong(K_EXPIRES_AT, expiresAtSec)
            }
            if (!subscriptionUrl.isNullOrBlank()) {
                val cleanSub = subscriptionUrl.trim()
                if (s.getString(K_SUB_URL, "") != cleanSub) {
                    s.putString(K_SUB_URL, cleanSub)
                }
            }
            val prevVer = s.getLong(K_LAST_VERIFIED, 0L)
            if (nowSec - prevVer >= 5L || nowSec < prevVer) {
                s.putLong(K_LAST_VERIFIED, nowSec)
            }
            lastKnownSnapshot = lastKnownSnapshot?.copy(
                expiresAtSec = if (expiresAtSec > 0L) expiresAtSec else lastKnownSnapshot?.expiresAtSec ?: 0L,
                subscriptionUrl = if (!subscriptionUrl.isNullOrBlank()) subscriptionUrl.trim() else lastKnownSnapshot?.subscriptionUrl.orEmpty(),
                lastVerifiedAtSec = nowSec,
            )
        }
    }

    fun getSnapshot(): SessionSnapshot {
        synchronized(lock) {
            val s = store()
            val c = crypto()
            val active = s.getBool(K_ACTIVE, false)
            val encTok = s.getString(K_SESSION_TOKEN_ENC, "")
            val encKey = s.getString(K_LICENSE_KEY_ENC, "")
            val sid = s.getString(K_SESSION_ID, "")
            val subUrl = s.getString(K_SUB_URL, "")
            val exp = s.getLong(K_EXPIRES_AT, 0L)
            val lastVer = s.getLong(K_LAST_VERIFIED, 0L)
            val lastRef = s.getLong(K_LAST_REFRESHED, 0L)
            if (!active && encTok.isBlank() && encKey.isBlank()) {
                lastKnownSnapshot = null
                return SessionSnapshot(
                    active = false,
                    sessionId = sid,
                    sessionToken = "",
                    licenseKey = "",
                    subscriptionUrl = subUrl,
                    expiresAtSec = exp,
                    lastVerifiedAtSec = lastVer,
                    lastRefreshedAtSec = lastRef,
                )
            }
            var tok = if (encTok.isNotBlank()) c.decryptString(encTok).orEmpty() else ""
            var key = if (encKey.isNotBlank()) c.decryptString(encKey).orEmpty() else ""
            val cached = lastKnownSnapshot
            if (active && cached != null && cached.active && (sid.isBlank() || sid == cached.sessionId)) {
                if (tok.isBlank() && encTok.isNotBlank()) tok = cached.sessionToken
                if (key.isBlank() && encKey.isNotBlank()) key = cached.licenseKey
            }
            val effectiveActive = active && tok.isNotBlank() && key.isNotBlank()
            val snap = SessionSnapshot(
                active = effectiveActive,
                sessionId = sid.ifBlank { cached?.sessionId.orEmpty() },
                sessionToken = tok,
                licenseKey = key,
                subscriptionUrl = subUrl.ifBlank { cached?.subscriptionUrl.orEmpty() },
                expiresAtSec = if (exp > 0L) exp else (cached?.expiresAtSec ?: 0L),
                lastVerifiedAtSec = if (lastVer > 0L) lastVer else (cached?.lastVerifiedAtSec ?: 0L),
                lastRefreshedAtSec = if (lastRef > 0L) lastRef else (cached?.lastRefreshedAtSec ?: 0L),
            )
            if (effectiveActive) {
                lastKnownSnapshot = snap
            }
            return snap
        }
    }

    fun isExplicitlyCleared(): Boolean {
        synchronized(lock) {
            val s = store()
            return !s.getBool(K_ACTIVE, false) &&
                s.getString(K_SESSION_TOKEN_ENC, "").isBlank() &&
                s.getString(K_LICENSE_KEY_ENC, "").isBlank()
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
            lastKnownSnapshot = null
            val s = store()
            s.putBool(K_ACTIVE, false)
            s.putString(K_SESSION_TOKEN_ENC, "")
            s.putString(K_LICENSE_KEY_ENC, "")
            s.putString(K_SESSION_ID, "")
            s.putString(K_SUB_URL, "")
            s.putLong(K_EXPIRES_AT, 0L)
            s.putLong(K_LAST_VERIFIED, 0L)
            s.putLong(K_LAST_REFRESHED, 0L)
            s.putLong(K_REFRESH_FAIL_UNTIL_MS, 0L)
            s.putLong(K_REFRESH_LEASE_UNTIL_MS, 0L)
            s.putString(K_REFRESH_LEASE_OWNER, "")
        }
    }

    private inline fun <T> withRefreshLockFile(block: () -> T): T {
        if (overrideStore != null) return block()
        val rootDir = runCatching { MMKV.getRootDir() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return block()
        return runCatching {
            val dir = File(rootDir)
            if (!dir.exists()) dir.mkdirs()
            RandomAccessFile(File(dir, "np_lic_refresh.lock"), "rw").use { raf ->
                raf.channel.lock().use {
                    block()
                }
            }
        }.getOrElse { block() }
    }

    fun tryAcquireRefreshLease(
        leaseMs: Long = 30_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        synchronized(lock) {
            return withRefreshLockFile {
                val s = store()
                val until = s.getLong(K_REFRESH_LEASE_UNTIL_MS, 0L)
                val curOwner = s.getString(K_REFRESH_LEASE_OWNER, "")
                if (until > nowMs && curOwner.isNotBlank()) {
                    return@withRefreshLockFile null
                }
                val owner = UUID.randomUUID().toString()
                s.putLong(K_REFRESH_LEASE_UNTIL_MS, nowMs + leaseMs.coerceAtLeast(1_000L))
                s.putString(K_REFRESH_LEASE_OWNER, owner)
                owner
            }
        }
    }

    fun releaseRefreshLease(owner: String?) {
        if (owner.isNullOrBlank()) return
        synchronized(lock) {
            withRefreshLockFile {
                val s = store()
                if (s.getString(K_REFRESH_LEASE_OWNER, "") == owner) {
                    s.putLong(K_REFRESH_LEASE_UNTIL_MS, 0L)
                    s.putString(K_REFRESH_LEASE_OWNER, "")
                }
            }
        }
    }

    fun markRefreshTransportFailure(
        ttlMs: Long = 600_000L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            store().putLong(K_REFRESH_FAIL_UNTIL_MS, nowMs + ttlMs.coerceAtLeast(10_000L))
        }
    }

    fun hasRecentRefreshTransportFailure(nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            return store().getLong(K_REFRESH_FAIL_UNTIL_MS, 0L) > nowMs
        }
    }

    fun clearRefreshTransportFailure() {
        synchronized(lock) {
            store().putLong(K_REFRESH_FAIL_UNTIL_MS, 0L)
        }
    }

    fun setPendingNoticeMessage(message: String) {
        val clean = message.trim().take(240)
        if (clean.isBlank()) return
        synchronized(lock) {
            store().putString(K_PENDING_NOTICE_MSG, clean)
        }
    }

    fun consumePendingNoticeMessage(): String {
        synchronized(lock) {
            val s = store()
            val msg = s.getString(K_PENDING_NOTICE_MSG, "").trim()
            if (msg.isNotBlank()) {
                s.putString(K_PENDING_NOTICE_MSG, "")
            }
            return msg
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
