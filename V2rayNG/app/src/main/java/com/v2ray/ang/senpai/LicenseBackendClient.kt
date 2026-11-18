package com.v2ray.ang.senpai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

sealed class ActivationInput {
    data class LicenseKey(val key: String) : ActivationInput()
    data class RejectedUrl(val reason: String) : ActivationInput()
    data object Empty : ActivationInput()
}

data class LicenseRedeemResponse(
    val status: String? = null,
    @SerializedName("license_status") val licenseStatus: String? = null,
    @SerializedName("license_key") val licenseKey: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("session_token") val sessionToken: String? = null,
    @SerializedName("session_expires_at") val sessionExpiresAt: Long = 0L,
    @SerializedName("subscription_url") val subscriptionUrl: String? = null,
    @SerializedName("sub_url") val subUrl: String? = null,
    val url: String? = null,
    @SerializedName("plan_name") val planName: String? = null,
    val remarks: String? = null,
    @SerializedName("upload_bytes") val uploadBytes: Long = 0L,
    @SerializedName("download_bytes") val downloadBytes: Long = 0L,
    @SerializedName("total_bytes") val totalBytes: Long = 0L,
    @SerializedName("expire_at") val expireAtSeconds: Long = 0L,
    @SerializedName("created_at") val createdAtSeconds: Long = 0L,
    @SerializedName("max_devices") val maxDevices: Int = 1,
    @SerializedName("unlimited_devices") val unlimitedDevices: Boolean = false,
    @SerializedName("used_devices") val usedDevices: Int = 1,
    @SerializedName("revoke_reason") val revokeReason: String? = null,
    val message: String? = null,
    val detail: String? = null,
) {
    fun resolvedSubscriptionUrl(): String =
        sequenceOf(subscriptionUrl, subUrl, url)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            .orEmpty()

    fun resolvedRemarks(fallback: String): String =
        sequenceOf(planName, remarks)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: fallback

    fun resolvedStatusCode(): String =
        sequenceOf(status, licenseStatus)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.lowercase()
            .orEmpty()

    /**
     * Resolves subscription activation state cleanly from `license_status` (avoiding `"status": "ok"` false-inactive mismatch).
     */
    fun resolvedLicenseStatus(): String {
        val explicit = licenseStatus?.trim()?.lowercase().orEmpty()
        if (explicit.isNotBlank()) return explicit
        return when (status?.trim()?.lowercase().orEmpty()) {
            "inactive", "disabled", "expired", "revoked", "exhausted" -> status!!.trim().lowercase()
            else -> "active"
        }
    }

    fun toQuotaInfo(remarksFallback: String): SubscriptionQuotaInfo {
        val resolvedMax = if (unlimitedDevices || maxDevices < 0) -1 else maxDevices.coerceAtLeast(1)
        return SubscriptionQuotaInfo(
            uploadBytes = uploadBytes,
            downloadBytes = downloadBytes,
            totalBytes = totalBytes,
            expireAtSeconds = expireAtSeconds,
            createdAtSeconds = createdAtSeconds,
            planName = resolvedRemarks(remarksFallback),
            maxDevices = resolvedMax,
            usedDevices = usedDevices.coerceAtLeast(0),
            licenseStatus = resolvedLicenseStatus(),
        )
    }
}

sealed class LicenseResolutionResult {
    data object BackendEndpointRequired : LicenseResolutionResult()
    data class Failure(
        val reason: String,
        val isDefinitiveRejection: Boolean = false,
        val httpCode: Int = 0,
        val statusCode: String = "",
    ) : LicenseResolutionResult()

    data class ReadySubscription(
        val subscriptionUrl: String,
        val remarks: String,
        val quota: SubscriptionQuotaInfo? = null,
    ) : LicenseResolutionResult()
}

sealed class LogoutOutcome {
    data class Success(val usedDevices: Int = 0) : LogoutOutcome()
    data class AlreadyInvalid(val reason: String = "") : LogoutOutcome()
    data class NetworkFailure(val message: String) : LogoutOutcome()
}

object LicenseBackendClient {
    private const val PREF_LICENSE_SESSION_ACTIVE = "np_license_session_active"
    private const val PREF_LICENSE_SUB_ID = "np_license_sub_id"
    private const val PREF_LICENSE_KEY = "np_license_key"
    private const val SESSION_REFRESH_INTERVAL_SEC = 6L * 3600L

    private val KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{5,127}$")
    private val RAW_KEY_REDACT_REGEX = Regex("(\\b[A-Z0-9]{2,6}(?:-[A-Z0-9]{3,8}){2,5}\\b|\\bnps_[A-Za-z0-9_=-]{8,})")
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val refreshMutex = Any()

    private data class ChallengeResponsePayload(
        @SerializedName("nonce") val nonce: String? = null,
    )

    fun resolveBaseUrl(overrideUrl: String? = null): String {
        val raw = overrideUrl?.takeIf { it.isNotBlank() } ?: AppSecurityGuard.protectedBackendBaseUrl()
        return raw.trim().trimEnd('/')
    }

    val cleanSubscriptionEndpoint: String get() = "${resolveBaseUrl()}/api/v1/license/sub"

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun isLicenseSessionActive(): Boolean {
        if (LicenseSessionStore.hasActiveSession()) return true
        return runCatching { MmkvManager.decodeSettingsBool(PREF_LICENSE_SESSION_ACTIVE, false) }.getOrDefault(false) &&
            LicenseSessionStore.getSnapshot().sessionToken.isNotBlank()
    }

    fun getActiveLicenseSubId(): String =
        runCatching { MmkvManager.decodeSettingsString(PREF_LICENSE_SUB_ID, "").orEmpty() }.getOrDefault("")

    fun setActiveLicenseSubId(subId: String) {
        runCatching { MmkvManager.encodeSettings(PREF_LICENSE_SUB_ID, subId) }
    }

    fun getSavedLicenseKey(): String {
        val fromSecure = LicenseSessionStore.getSnapshot().licenseKey
        if (fromSecure.isNotBlank()) return fromSecure
        return runCatching { MmkvManager.decodeSettingsString(PREF_LICENSE_KEY, "").orEmpty().trim() }.getOrDefault("")
    }

    fun clearActiveLicenseSession() {
        LicenseSessionStore.clearActiveSession()
        runCatching {
            MmkvManager.encodeSettings(PREF_LICENSE_SESSION_ACTIVE, false)
            MmkvManager.encodeSettings(PREF_LICENSE_SUB_ID, "")
            MmkvManager.encodeSettings(PREF_LICENSE_KEY, "")
        }
    }

    fun isProtectedSubscriptionUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().trimEnd('/')
        if (path != "/api/v1/license/sub" && !path.startsWith("/api/v1/license/sub/")) return false
        val expectedHost = runCatching { URI(resolveBaseUrl()).host }.getOrNull()
        return expectedHost.isNullOrBlank() || uri.host.equals(expectedHost, ignoreCase = true)
    }

    fun sanitizeSubscriptionUrlForStorage(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return cleanSubscriptionEndpoint
        return if (isProtectedSubscriptionUrl(raw)) cleanSubscriptionEndpoint else raw
    }

    fun buildSubscriptionAuthHeaders(): Map<String, String> {
        val snap = LicenseSessionStore.getSnapshot()
        val devId = runCatching { DeviceIdentityManager.getDeviceId() }.getOrDefault("")
        if (!snap.active || snap.sessionToken.isBlank() || devId.isBlank()) {
            return emptyMap()
        }
        return mapOf(
            "Authorization" to "Bearer ${snap.sessionToken}",
            "X-Device-Id" to devId,
            "Cache-Control" to "no-store",
            "X-NovaPulse-Protocols" to "all",
        )
    }

    fun sanitizeForDisplay(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return RAW_KEY_REDACT_REGEX.replace(text, "[***]")
    }

    fun classifyInput(rawInput: String): ActivationInput {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ActivationInput.Empty
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("vless://") ||
            lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") ||
            lower.startsWith("ss://") ||
            lower.contains("://")
        ) {
            return ActivationInput.RejectedUrl(
                "ورودی نامعتبر است؛ فقط کد لایسنس معتبر را وارد کنید."
            )
        }
        return if (KEY_PATTERN.matches(trimmed)) {
            ActivationInput.LicenseKey(trimmed.uppercase())
        } else {
            ActivationInput.RejectedUrl("فرمت کد لایسنس نامعتبر است.")
        }
    }

    internal fun parseRedeemJson(rawJson: String?): LicenseRedeemResponse? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(rawJson, LicenseRedeemResponse::class.java)
        }.getOrNull()
    }

    internal fun isDefinitiveAuthRejection(httpCode: Int, statusCode: String): Boolean {
        if (httpCode == 429 || httpCode >= 500 || httpCode <= 0) return false
        if (httpCode == 401 || httpCode == 403) return true
        if (httpCode == 404) {
            return statusCode in setOf(
                "invalid_license",
                "session_invalid",
                "session_revoked",
                "session_logged_out",
                "session_expired",
                "not_found",
            )
        }
        return statusCode in setOf(
            "revoked",
            "disabled",
            "expired",
            "quota_exceeded",
            "device_limit",
            "device_mismatch",
            "device_key_mismatch",
            "session_required",
            "session_invalid",
            "session_revoked",
            "session_logged_out",
            "session_expired",
            "session_legacy_upgrade_required",
            "invalid_license",
        )
    }

    fun flushPendingLogoutIfNeeded(
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
    ) {
        flushPendingLogoutAndReport(backendBaseUrlOverride, client)
    }

    /**
     * Attempts to send any queued offline logout to the backend.
     * Returns true if there was no pending logout or it was cleanly cleared/invalidated on server.
     */
    fun flushPendingLogoutAndReport(
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
    ): Boolean {
        val pending = LicenseSessionStore.getPendingLogout() ?: return true
        val outcome = performLogoutCall(
            sessionToken = pending.sessionToken,
            deviceId = pending.deviceId,
            backendBaseUrlOverride = backendBaseUrlOverride,
            client = client,
        )
        return if (outcome is LogoutOutcome.Success || outcome is LogoutOutcome.AlreadyInvalid) {
            LicenseSessionStore.clearPendingLogout()
            true
        } else {
            false
        }
    }

    fun redeem(
        rawInput: String,
        remarksHint: String = "NovaPulse",
        deviceId: String? = null,
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
    ): LicenseResolutionResult {
        when (val classified = classifyInput(rawInput)) {
            ActivationInput.Empty ->
                return LicenseResolutionResult.Failure("کد لایسنس وارد نشده است.", isDefinitiveRejection = true)

            is ActivationInput.RejectedUrl ->
                return LicenseResolutionResult.Failure(classified.reason, isDefinitiveRejection = true)

            is ActivationInput.LicenseKey -> {
                val baseUrl = resolveBaseUrl(backendBaseUrlOverride)
                if (baseUrl.isBlank()) return LicenseResolutionResult.BackendEndpointRequired

                val http = client ?: defaultClient
                val devId = deviceId?.takeIf { it.isNotBlank() && it != DeviceIdentityManager.LEGACY_SHARED_ANDROID_ID }
                    ?: DeviceIdentityManager.getDeviceId()
                flushPendingLogoutIfNeeded(baseUrl, http)

                val previousSnap = LicenseSessionStore.getSnapshot()

                return try {
                    val pubKeyB64 = DeviceIdentityManager.getPublicKeySpkiB64()
                    val chReqJson = gson.toJson(
                        mapOf(
                            "device_id" to devId,
                            "purpose" to "redeem",
                            "public_key" to pubKeyB64,
                        )
                    )
                    val (chCode, chBody) = executeHttpJson(
                        client = http,
                        url = "$baseUrl/api/v1/license/challenge",
                        jsonBody = chReqJson,
                        headers = mapOf("X-Device-Id" to devId),
                    )
                    if (chCode !in 200..299) {
                        val parsedErr = parseRedeemJson(chBody)
                        val stCode = parsedErr?.resolvedStatusCode().orEmpty()
                        val errMsg = parsedErr?.message ?: parsedErr?.detail ?: "خطا در دریافت چالش امنیتی (کد $chCode)"
                        return LicenseResolutionResult.Failure(
                            reason = sanitizeForDisplay(errMsg),
                            isDefinitiveRejection = isDefinitiveAuthRejection(chCode, stCode),
                            httpCode = chCode,
                            statusCode = stCode,
                        )
                    }
                    val nonce = runCatching { gson.fromJson(chBody, ChallengeResponsePayload::class.java)?.nonce.orEmpty() }
                        .getOrDefault("")
                    if (nonce.isBlank()) {
                        return LicenseResolutionResult.Failure("پاسخ چالش امنیتی نامعتبر است.")
                    }

                    val signatureB64 = DeviceIdentityManager.signChallenge(nonce, "redeem")
                    val meta = DeviceIdentityManager.getDeviceInfo()
                    val slotFp = runCatching { DeviceIdentityManager.getDeviceSlotFingerprint() }.getOrDefault("")
                    val deviceInfoMap = buildMap<String, Any> {
                        put("device_name", meta.deviceName)
                        put("manufacturer", meta.manufacturer)
                        put("model", meta.model)
                        put("brand", meta.brand)
                        put("os_version", meta.osVersion)
                        put("api_level", meta.apiLevel)
                        put("app_version", meta.appVersion)
                        if (slotFp.isNotBlank()) put("device_slot_fp", slotFp)
                    }

                    val reqJsonStr = gson.toJson(
                        mapOf(
                            "license_key" to classified.key,
                            "device_id" to devId,
                            "public_key" to pubKeyB64,
                            "challenge_nonce" to nonce,
                            "device_signature" to signatureB64,
                            "device_info" to deviceInfoMap,
                        )
                    )

                    val (code, bodyStr) = executeHttpJson(
                        client = http,
                        url = "$baseUrl/api/v1/license/redeem",
                        jsonBody = reqJsonStr,
                        headers = mapOf("X-Device-Id" to devId),
                    )
                    val parsed = parseRedeemJson(bodyStr)
                    val stCode = parsed?.resolvedStatusCode().orEmpty()
                    if (code !in 200..299 || parsed == null) {
                        val errMsg = parsed?.message ?: parsed?.detail ?: when (code) {
                            401, 403 -> "دسترسی این لایسنس مسدود یا سقف دستگاه آن تکمیل شده است."
                            404 -> "کد لایسنس وارد شده معتبر نیست یا یافت نشد."
                            429 -> "تعداد تلاش‌ها بیش از حد مجاز است. لطفاً لحظاتی صبر کنید."
                            else -> "خطا در برقراری ارتباط با سرور لایسنس (کد $code)"
                        }
                        return LicenseResolutionResult.Failure(
                            reason = sanitizeForDisplay(errMsg),
                            isDefinitiveRejection = isDefinitiveAuthRejection(code, stCode),
                            httpCode = code,
                            statusCode = stCode,
                        )
                    }

                    val sessTok = parsed.sessionToken?.trim().orEmpty()
                    val sessId = parsed.sessionId?.trim().orEmpty()
                    if (sessTok.isBlank()) {
                        return LicenseResolutionResult.Failure("سرور توکن نشست معتبر صادر نکرد.")
                    }

                    val resolvedUrl = sanitizeSubscriptionUrlForStorage(
                        parsed.resolvedSubscriptionUrl().ifBlank { "$baseUrl/api/v1/license/sub" },
                    )
                    val activeKey = parsed.licenseKey?.trim()?.uppercase().orEmpty().ifBlank { classified.key }

                    // Release old license session cleanly if switching from another active license
                    if (previousSnap.active &&
                        previousSnap.sessionToken.isNotBlank() &&
                        previousSnap.sessionToken != sessTok &&
                        !previousSnap.licenseKey.equals(activeKey, ignoreCase = true)
                    ) {
                        val oldOut = performLogoutCall(previousSnap.sessionToken, devId, baseUrl, http)
                        if (oldOut is LogoutOutcome.NetworkFailure) {
                            LicenseSessionStore.enqueuePendingLogout(previousSnap.sessionToken, devId)
                        }
                    }

                    LicenseSessionStore.saveSession(
                        licenseKey = activeKey,
                        sessionId = sessId,
                        sessionToken = sessTok,
                        subscriptionUrl = resolvedUrl,
                        expiresAtSec = parsed.sessionExpiresAt,
                    )
                    runCatching {
                        MmkvManager.encodeSettings(PREF_LICENSE_KEY, "")
                        MmkvManager.encodeSettings(PREF_LICENSE_SESSION_ACTIVE, true)
                    }

                    LicenseResolutionResult.ReadySubscription(
                        subscriptionUrl = resolvedUrl,
                        remarks = parsed.resolvedRemarks(remarksHint),
                        quota = parsed.toQuotaInfo(remarksHint),
                    )
                } catch (e: IOException) {
                    LicenseResolutionResult.Failure(
                        reason = sanitizeForDisplay(e.message ?: "خطای اتصال به سرور لایسنس"),
                        isDefinitiveRejection = false,
                    )
                } catch (e: Exception) {
                    LicenseResolutionResult.Failure(
                        reason = sanitizeForDisplay(e.message ?: "خطای غیرمنتظره در فعال‌سازی لایسنس"),
                        isDefinitiveRejection = false,
                    )
                }
            }
        }
    }

    fun refreshStatus(
        licenseKey: String,
        deviceId: String? = null,
        remarksHint: String = "NovaPulse",
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
        sessionTokenOverride: String? = null,
    ): LicenseResolutionResult {
        val baseUrl = resolveBaseUrl(backendBaseUrlOverride)
        if (baseUrl.isBlank()) return LicenseResolutionResult.BackendEndpointRequired

        val snap = LicenseSessionStore.getSnapshot()
        val sessionToken = sessionTokenOverride?.takeIf { it.isNotBlank() } ?: snap.sessionToken
        val devId = deviceId?.takeIf { it.isNotBlank() && it != DeviceIdentityManager.LEGACY_SHARED_ANDROID_ID }
            ?: runCatching { DeviceIdentityManager.getDeviceId() }.getOrDefault("")
        val cleanKey = licenseKey.trim().uppercase().ifBlank { snap.licenseKey }

        if (sessionToken.isBlank() && client == null) {
            return LicenseResolutionResult.Failure(
                reason = "نشست فعال دستگاه یافت نشد؛ لطفاً دوباره وارد شوید.",
                isDefinitiveRejection = LicenseSessionStore.isExplicitlyCleared(),
                httpCode = 401,
                statusCode = "session_required",
            )
        }

        val http = client ?: defaultClient
        val initialResult = executeStatusRequest(
            baseUrl = baseUrl,
            http = http,
            cleanKey = cleanKey,
            devId = devId,
            sessionToken = sessionToken,
            remarksHint = remarksHint,
            snapActive = snap.active,
        )

        if (sessionTokenOverride == null &&
            initialResult is LicenseResolutionResult.Failure &&
            initialResult.httpCode == 401 &&
            initialResult.statusCode == "session_invalid"
        ) {
            // Another process (UI vs :RunSoLibV2RayDaemon) may have rotated the token in MMKV mid-flight.
            val latestSnap = LicenseSessionStore.getSnapshot()
            if (latestSnap.active &&
                latestSnap.sessionToken.isNotBlank() &&
                latestSnap.sessionToken != sessionToken
            ) {
                return executeStatusRequest(
                    baseUrl = baseUrl,
                    http = http,
                    cleanKey = cleanKey,
                    devId = devId,
                    sessionToken = latestSnap.sessionToken,
                    remarksHint = remarksHint,
                    snapActive = latestSnap.active,
                )
            }

            // Recover once via ECDSA redeem if the rotated token was lost in transit or in a killed peer process.
            if (cleanKey.isNotBlank()) {
                synchronized(refreshMutex) {
                    val lockedSnap = LicenseSessionStore.getSnapshot()
                    if (lockedSnap.active &&
                        lockedSnap.sessionToken.isNotBlank() &&
                        lockedSnap.sessionToken != sessionToken
                    ) {
                        return executeStatusRequest(
                            baseUrl = baseUrl,
                            http = http,
                            cleanKey = cleanKey,
                            devId = devId,
                            sessionToken = lockedSnap.sessionToken,
                            remarksHint = remarksHint,
                            snapActive = lockedSnap.active,
                        )
                    }
                    LicenseSessionStore.clearRefreshTransportFailure()
                    val recovered = redeem(
                        rawInput = cleanKey,
                        remarksHint = remarksHint,
                        deviceId = devId,
                        backendBaseUrlOverride = baseUrl,
                        client = http,
                    )
                    if (recovered is LicenseResolutionResult.ReadySubscription ||
                        (recovered is LicenseResolutionResult.Failure && recovered.isDefinitiveRejection)
                    ) {
                        return recovered
                    }
                }
            }
        }
        return initialResult
    }

    private fun executeStatusRequest(
        baseUrl: String,
        http: OkHttpClient,
        cleanKey: String,
        devId: String,
        sessionToken: String,
        remarksHint: String,
        snapActive: Boolean,
    ): LicenseResolutionResult {
        val reqBodyMap = buildMap<String, Any> {
            if (cleanKey.isNotBlank()) put("license_key", cleanKey)
            if (devId.isNotBlank()) put("device_id", devId)
        }
        val headers = buildMap {
            if (sessionToken.isNotBlank()) put("Authorization", "Bearer $sessionToken")
            if (devId.isNotBlank()) put("X-Device-Id", devId)
        }

        return try {
            val (code, bodyStr) = executeHttpJson(
                client = http,
                url = "$baseUrl/api/v1/license/status",
                jsonBody = gson.toJson(reqBodyMap),
                headers = headers,
            )
            val parsed = parseRedeemJson(bodyStr)
            val statusCode = parsed?.resolvedStatusCode().orEmpty()
            if (code in 200..299 && parsed != null) {
                val resolvedUrl = sanitizeSubscriptionUrlForStorage(
                    parsed.resolvedSubscriptionUrl().ifBlank { "$baseUrl/api/v1/license/sub" },
                )
                if (snapActive) {
                    LicenseSessionStore.touchVerified(
                        expiresAtSec = parsed.sessionExpiresAt,
                        subscriptionUrl = resolvedUrl,
                    )
                    maybeRefreshSessionTokenInBackground(devId, baseUrl, http)
                }
                LicenseResolutionResult.ReadySubscription(
                    subscriptionUrl = resolvedUrl,
                    remarks = parsed.resolvedRemarks(remarksHint),
                    quota = parsed.toQuotaInfo(remarksHint),
                )
            } else {
                val definitive = isDefinitiveAuthRejection(code, statusCode)
                val msg = sanitizeForDisplay(
                    parsed?.message
                        ?: parsed?.detail
                        ?: when (code) {
                            429 -> "محدودیت موقت تعداد درخواست‌ها (لطفاً کمی صبر کنید)"
                            else -> "اعتبار لایسنس یا نشست این دستگاه منقضی یا لغو شده است (کد $code)"
                        },
                )
                LicenseResolutionResult.Failure(
                    reason = msg,
                    isDefinitiveRejection = definitive,
                    httpCode = code,
                    statusCode = statusCode,
                )
            }
        } catch (e: Exception) {
            LicenseResolutionResult.Failure(
                reason = sanitizeForDisplay(e.message ?: "خطای ارتباط با سرور"),
                isDefinitiveRejection = false,
            )
        }
    }

    private fun maybeRefreshSessionTokenInBackground(
        devId: String,
        baseUrl: String,
        http: OkHttpClient,
    ) {
        val nowSec = System.currentTimeMillis() / 1000L
        val preSnap = LicenseSessionStore.getSnapshot()
        if (preSnap.lastRefreshedAtSec <= 0L || (nowSec - preSnap.lastRefreshedAtSec) < SESSION_REFRESH_INTERVAL_SEC) {
            return
        }
        synchronized(refreshMutex) {
            val leaseOwner = LicenseSessionStore.tryAcquireRefreshLease() ?: return
            try {
                val latestSnap = LicenseSessionStore.getSnapshot()
                val curSec = System.currentTimeMillis() / 1000L
                if (!latestSnap.active ||
                    latestSnap.sessionToken.isBlank() ||
                    latestSnap.lastRefreshedAtSec <= 0L ||
                    (curSec - latestSnap.lastRefreshedAtSec) < SESSION_REFRESH_INTERVAL_SEC
                ) {
                    return
                }
                runCatching { executeRefreshTokenLocked(devId, baseUrl, http, latestSnap) }
                    .onFailure { LogUtil.w(AppConfig.TAG, "Non-fatal session refresh failure: ${it.message}") }
            } finally {
                LicenseSessionStore.releaseRefreshLease(leaseOwner)
            }
        }
    }

    fun refreshSessionToken(
        deviceId: String? = null,
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
    ): Boolean {
        val baseUrl = resolveBaseUrl(backendBaseUrlOverride)
        if (baseUrl.isBlank()) return false
        val devId = deviceId?.takeIf { it.isNotBlank() } ?: DeviceIdentityManager.getDeviceId()
        val http = client ?: defaultClient
        synchronized(refreshMutex) {
            val leaseOwner = LicenseSessionStore.tryAcquireRefreshLease() ?: return false
            try {
                val snap = LicenseSessionStore.getSnapshot()
                if (!snap.active || snap.sessionToken.isBlank()) return false
                return executeRefreshTokenLocked(devId, baseUrl, http, snap)
            } finally {
                LicenseSessionStore.releaseRefreshLease(leaseOwner)
            }
        }
    }

    private fun executeRefreshTokenLocked(
        devId: String,
        baseUrl: String,
        http: OkHttpClient,
        snap: LicenseSessionStore.SessionSnapshot,
    ): Boolean {
        val chReqJson = gson.toJson(mapOf("device_id" to devId, "purpose" to "refresh"))
        val (chCode, chBody) = try {
            executeHttpJson(
                client = http,
                url = "$baseUrl/api/v1/license/challenge",
                jsonBody = chReqJson,
                headers = mapOf("X-Device-Id" to devId),
            )
        } catch (_: Exception) {
            return false
        }
        val nonce = if (chCode in 200..299) {
            runCatching { gson.fromJson(chBody, ChallengeResponsePayload::class.java)?.nonce.orEmpty() }.getOrDefault("")
        } else {
            ""
        }
        if (nonce.isBlank()) return false
        val sig = runCatching { DeviceIdentityManager.signChallenge(nonce, "refresh") }.getOrDefault("")
        if (sig.isBlank()) return false

        val reqBodyMap = mapOf(
            "device_id" to devId,
            "app_version" to BuildConfig.VERSION_NAME,
            "challenge_nonce" to nonce,
            "device_signature" to sig,
        )
        val (code, bodyStr) = try {
            executeHttpJson(
                client = http,
                url = "$baseUrl/api/v1/license/refresh",
                jsonBody = gson.toJson(reqBodyMap),
                headers = mapOf(
                    "Authorization" to "Bearer ${snap.sessionToken}",
                    "X-Device-Id" to devId,
                ),
            )
        } catch (_: IOException) {
            LicenseSessionStore.markRefreshTransportFailure()
            return false
        }
        if (code in 200..299) {
            val parsed = parseRedeemJson(bodyStr)
            val nextTok = parsed?.sessionToken?.trim().orEmpty()
            if (nextTok.isNotBlank()) {
                LicenseSessionStore.updateRotatedToken(
                    sessionToken = nextTok,
                    expiresAtSec = parsed?.sessionExpiresAt ?: 0L,
                    subscriptionUrl = sanitizeSubscriptionUrlForStorage(parsed?.resolvedSubscriptionUrl()),
                )
                return true
            }
        } else if (code == 409 || (code == 401 && parseRedeemJson(bodyStr)?.resolvedStatusCode() == "session_invalid")) {
            LicenseSessionStore.markRefreshTransportFailure()
        }
        return false
    }

    fun logoutFromServer(
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
    ): LogoutOutcome {
        val snap = LicenseSessionStore.getSnapshot()
        if (!snap.active || snap.sessionToken.isBlank()) {
            return LogoutOutcome.AlreadyInvalid("no_local_session")
        }
        val devId = runCatching { DeviceIdentityManager.getDeviceId() }.getOrDefault("")
        if (devId.isBlank()) {
            return LogoutOutcome.NetworkFailure("شناسه دستگاه در دسترس نیست.")
        }
        return performLogoutCall(snap.sessionToken, devId, backendBaseUrlOverride, client)
    }

    suspend fun logoutFromServerWithRetry(
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
        maxAttempts: Int = 3,
        retryDelayBaseMs: Long = 450L,
    ): LogoutOutcome {
        val snap = LicenseSessionStore.getSnapshot()
        if (!snap.active || snap.sessionToken.isBlank()) {
            return LogoutOutcome.AlreadyInvalid("no_local_session")
        }
        val devId = runCatching { DeviceIdentityManager.getDeviceId() }.getOrDefault("")
        if (devId.isBlank()) {
            return LogoutOutcome.NetworkFailure("شناسه دستگاه در دسترس نیست.")
        }
        val attempts = maxAttempts.coerceAtLeast(1)
        var lastOutcome: LogoutOutcome = LogoutOutcome.NetworkFailure("خطا در اتصال به سرور")
        for (attempt in 1..attempts) {
            val outcome = performLogoutCall(snap.sessionToken, devId, backendBaseUrlOverride, client)
            if (outcome is LogoutOutcome.Success || outcome is LogoutOutcome.AlreadyInvalid) {
                return outcome
            }
            lastOutcome = outcome
            if (attempt < attempts && retryDelayBaseMs > 0L) {
                delay(retryDelayBaseMs * attempt)
            }
        }
        return lastOutcome
    }

    private fun performLogoutCall(
        sessionToken: String,
        deviceId: String,
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient? = null,
    ): LogoutOutcome {
        val baseUrl = resolveBaseUrl(backendBaseUrlOverride)
        if (baseUrl.isBlank()) return LogoutOutcome.NetworkFailure("آدرس سرور تنظیم نشده است.")
        val http = client ?: defaultClient
        return try {
            val (code, bodyStr) = executeHttpJson(
                client = http,
                url = "$baseUrl/api/v1/license/logout",
                jsonBody = "{}",
                headers = mapOf(
                    "Authorization" to "Bearer $sessionToken",
                    "X-Device-Id" to deviceId,
                ),
            )
            val parsed = parseRedeemJson(bodyStr)
            when {
                code in 200..299 -> LogoutOutcome.Success(parsed?.usedDevices ?: 0)
                code == 401 || code == 403 || code == 404 ->
                    LogoutOutcome.AlreadyInvalid(parsed?.resolvedStatusCode().orEmpty())

                else -> LogoutOutcome.NetworkFailure(
                    sanitizeForDisplay(parsed?.message ?: "عدم پاسخ‌گویی سرور برای خروج (کد $code)"),
                )
            }
        } catch (e: Exception) {
            LogoutOutcome.NetworkFailure(sanitizeForDisplay(e.message ?: "خطا در اتصال به سرور"))
        }
    }

    private fun executeHttpJson(
        client: OkHttpClient,
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .header("User-Agent", "NovaPulse/${BuildConfig.VERSION_NAME}")
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        client.newCall(builder.build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }
}
