package com.v2ray.ang.senpai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.handler.MmkvManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Input classification for the onboarding / license activation field.
 * Direct subscription links and raw config URIs are strictly rejected; ONLY license codes
 * issued by the panel backend are accepted.
 */
sealed class ActivationInput {
    data class LicenseKey(val key: String) : ActivationInput()
    data class RejectedUrl(val reason: String) : ActivationInput()
    data object Invalid : ActivationInput()
}

data class LicenseRedeemResponse(
    @SerializedName("subscription_url") val subscriptionUrl: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("license_key") val licenseKey: String? = null,
    @SerializedName("remarks") val remarks: String? = null,
    @SerializedName("plan_name") val planName: String? = null,
    @SerializedName("upload_bytes") val uploadBytes: Long = 0L,
    @SerializedName("download_bytes") val downloadBytes: Long = 0L,
    @SerializedName("total_bytes") val totalBytes: Long = 0L,
    @SerializedName("expire_at") val expireAtSeconds: Long = 0L,
    @SerializedName("created_at") val createdAtSeconds: Long = 0L,
    @SerializedName("max_devices") val maxDevices: Int = 1,
    @SerializedName("used_devices") val usedDevices: Int = 1,
    @SerializedName("status") val status: String? = null,
    @SerializedName("message") val message: String? = null,
) {
    fun resolvedSubscriptionUrl(): String? =
        subscriptionUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: url?.trim()?.takeIf { it.isNotEmpty() }
}

sealed class LicenseResolutionResult {
    data class ReadySubscription(
        val subscriptionUrl: String,
        val remarks: String,
        val licenseKey: String,
        val quota: SubscriptionQuotaInfo? = null,
    ) : LicenseResolutionResult()

    data class BackendEndpointRequired(val licenseKey: String) : LicenseResolutionResult()
    data class Failure(val reason: String) : LicenseResolutionResult()
}

/**
 * Client for NovaPulse License API (`/api/v1/license/redeem` & `/api/v1/license/status`).
 * Enforces license-only activation bound to [deviceId]; direct URLs are never accepted.
 */
object LicenseBackendClient {

    const val DEFAULT_BACKEND_BASE_URL = "https://nova.dontbedumb.ir"
    private const val PREF_BACKEND_BASE_URL = "qsn_backend_base_url"
    private const val PREF_SAVED_LICENSE_KEY = "qsn_saved_license_key"
    private const val PREF_ACTIVE_LICENSE_SUB_ID = "qsn_active_license_sub_id"
    private const val PREF_LICENSE_SESSION_ACTIVE = "qsn_license_session_active"
    private val FORBIDDEN_URL_SCHEMES = listOf(
        "https://", "http://", "vless://", "vmess://", "trojan://", "ss://",
        "hy2://", "hysteria2://", "hysteria://", "tuic://", "socks://", "wireguard://"
    )
    private val LICENSE_TOKEN_REGEX = Regex("""\bNP-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,}){1,}\b""", RegexOption.IGNORE_CASE)
    private val gson = Gson()

    fun getBackendBaseUrl(): String {
        val saved = MmkvManager.decodeSettingsString(PREF_BACKEND_BASE_URL, "").orEmpty().trim().trimEnd('/')
        return saved.ifEmpty { DEFAULT_BACKEND_BASE_URL }
    }

    fun setBackendBaseUrl(url: String) {
        MmkvManager.encodeSettings(PREF_BACKEND_BASE_URL, url.trim().trimEnd('/'))
    }

    fun getSavedLicenseKey(): String =
        MmkvManager.decodeSettingsString(PREF_SAVED_LICENSE_KEY, "").orEmpty().trim()

    fun setSavedLicenseKey(key: String) {
        val normalized = key.trim().uppercase()
        MmkvManager.encodeSettings(PREF_SAVED_LICENSE_KEY, normalized)
        MmkvManager.encodeSettings(PREF_LICENSE_SESSION_ACTIVE, normalized.isNotEmpty())
    }

    fun getActiveLicenseSubId(): String =
        MmkvManager.decodeSettingsString(PREF_ACTIVE_LICENSE_SUB_ID, "").orEmpty().trim()

    fun setActiveLicenseSubId(subId: String) {
        MmkvManager.encodeSettings(PREF_ACTIVE_LICENSE_SUB_ID, subId.trim())
    }

    fun isLicenseSessionActive(): Boolean =
        MmkvManager.decodeSettingsBool(PREF_LICENSE_SESSION_ACTIVE, false) || getSavedLicenseKey().isNotEmpty()

    fun clearSavedLicenseKey() {
        MmkvManager.encodeSettings(PREF_SAVED_LICENSE_KEY, "")
        MmkvManager.encodeSettings(PREF_ACTIVE_LICENSE_SUB_ID, "")
        MmkvManager.encodeSettings(PREF_LICENSE_SESSION_ACTIVE, false)
    }

    fun clearActiveLicenseSession() {
        clearSavedLicenseKey()
    }

    fun sanitizeForDisplay(raw: String?): String {
        var text = raw.orEmpty()
        val saved = getSavedLicenseKey()
        if (saved.length >= 4) {
            text = text.replace(saved, "[***]", ignoreCase = true)
        }
        return text.replace(LICENSE_TOKEN_REGEX, "[***]")
    }

    fun classifyInput(raw: String): ActivationInput {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ActivationInput.Invalid
        val lower = trimmed.lowercase()
        if (FORBIDDEN_URL_SCHEMES.any { lower.startsWith(it) } || "://" in lower) {
            return ActivationInput.RejectedUrl(
                "این بخش فقط کد لایسنس معتبر قبول می‌کند؛ لطفاً کد لایسنس خود را وارد کنید."
            )
        }
        if (trimmed.length >= 4 && !trimmed.any { it.isWhitespace() }) {
            return ActivationInput.LicenseKey(trimmed.uppercase())
        }
        return ActivationInput.Invalid
    }

    fun parseRedeemJson(json: String): LicenseRedeemResponse? =
        runCatching { gson.fromJson(json, LicenseRedeemResponse::class.java) }.getOrNull()

    fun redeem(
        rawInput: String,
        remarksHint: String = "NovaPulse",
        deviceId: String = "",
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient = defaultClient(),
    ): LicenseResolutionResult = callLicenseEndpoint(
        endpointSuffix = "/api/v1/license/redeem",
        rawInput = rawInput,
        remarksHint = remarksHint,
        deviceId = deviceId,
        backendBaseUrlOverride = backendBaseUrlOverride,
        client = client,
    )

    fun refreshStatus(
        licenseKey: String,
        deviceId: String,
        remarksHint: String = "NovaPulse",
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient = defaultClient(),
    ): LicenseResolutionResult = callLicenseEndpoint(
        endpointSuffix = "/api/v1/license/status",
        rawInput = licenseKey,
        remarksHint = remarksHint,
        deviceId = deviceId,
        backendBaseUrlOverride = backendBaseUrlOverride,
        client = client,
    )

    private fun callLicenseEndpoint(
        endpointSuffix: String,
        rawInput: String,
        remarksHint: String,
        deviceId: String,
        backendBaseUrlOverride: String?,
        client: OkHttpClient,
    ): LicenseResolutionResult {
        return when (val input = classifyInput(rawInput)) {
            is ActivationInput.Invalid ->
                LicenseResolutionResult.Failure("کد لایسنس وارد شده نامعتبر است.")

            is ActivationInput.RejectedUrl ->
                LicenseResolutionResult.Failure(input.reason)

            is ActivationInput.LicenseKey -> {
                val baseUrl = (backendBaseUrlOverride ?: getBackendBaseUrl()).trim().trimEnd('/')
                if (baseUrl.isEmpty()) {
                    return LicenseResolutionResult.BackendEndpointRequired(input.key)
                }
                val payload = gson.toJson(
                    mapOf(
                        "license_key" to input.key,
                        "device_id" to deviceId,
                    )
                )
                val req = Request.Builder()
                    .url("$baseUrl$endpointSuffix")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                runCatching {
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body.string()
                        if (!resp.isSuccessful) {
                            val parsedErr = parseRedeemJson(body)?.message
                            return LicenseResolutionResult.Failure(
                                parsedErr ?: "خطای سرور لایسنس (${resp.code})"
                            )
                        }
                        val parsed = parseRedeemJson(body)
                            ?: return LicenseResolutionResult.Failure("پاسخ سرور لایسنس نامعتبر بود")
                        val subUrl = parsed.resolvedSubscriptionUrl()
                            ?: return LicenseResolutionResult.Failure(
                                sanitizeForDisplay(parsed.message)
                                    .ifBlank { "سرویس لایسنس اطلاعات اتصال را برنگرداند." }
                            )
                        val resolvedKey = parsed.licenseKey?.takeIf { it.isNotBlank() } ?: input.key
                        setSavedLicenseKey(resolvedKey)
                        val resolvedName = parsed.planName?.takeIf { it.isNotBlank() }
                            ?: parsed.remarks?.takeIf { it.isNotBlank() }
                            ?: remarksHint
                        val quota = SubscriptionQuotaInfo(
                            uploadBytes = parsed.uploadBytes,
                            downloadBytes = parsed.downloadBytes,
                            totalBytes = parsed.totalBytes,
                            expireAtSeconds = parsed.expireAtSeconds,
                            planName = resolvedName,
                            createdAtSeconds = parsed.createdAtSeconds,
                            maxDevices = parsed.maxDevices.coerceAtLeast(1),
                            usedDevices = parsed.usedDevices.coerceAtLeast(0),
                            licenseStatus = parsed.status ?: "active",
                        )
                        LicenseResolutionResult.ReadySubscription(
                            subscriptionUrl = subUrl,
                            remarks = resolvedName,
                            licenseKey = resolvedKey,
                            quota = quota,
                        )
                    }
                }.getOrElse { err ->
                    LicenseResolutionResult.Failure(err.message ?: "خطا در ارتباط با سرور لایسنس")
                }
            }
        }
    }

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
}
