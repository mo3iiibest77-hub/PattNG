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
 * Input classification for the onboarding / quick-connect field: either a direct
 * subscription URL or a future panel license key.
 */
sealed class ActivationInput {
    data class DirectSubscriptionUrl(val url: String) : ActivationInput()
    data class LicenseKey(val key: String) : ActivationInput()
    data object Invalid : ActivationInput()
}

data class LicenseRedeemResponse(
    @SerializedName("subscription_url") val subscriptionUrl: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("remarks") val remarks: String? = null,
    @SerializedName("plan_name") val planName: String? = null,
    @SerializedName("upload_bytes") val uploadBytes: Long = 0L,
    @SerializedName("download_bytes") val downloadBytes: Long = 0L,
    @SerializedName("total_bytes") val totalBytes: Long = 0L,
    @SerializedName("expire_at") val expireAtSeconds: Long = 0L,
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
        val quota: SubscriptionQuotaInfo? = null,
    ) : LicenseResolutionResult()

    data class BackendEndpointRequired(val licenseKey: String) : LicenseResolutionResult()
    data class Failure(val reason: String) : LicenseResolutionResult()
}

/**
 * Backend-agnostic client supporting both direct subscription URLs right now and HTTP JSON
 * license redemption once a panel base URL (e.g. Trixie panel) is configured.
 */
object LicenseBackendClient {

    private const val PREF_BACKEND_BASE_URL = "qsn_backend_base_url"
    private const val PREF_SAVED_LICENSE_KEY = "qsn_saved_license_key"
    private val gson = Gson()

    fun getBackendBaseUrl(): String =
        MmkvManager.decodeSettingsString(PREF_BACKEND_BASE_URL, "").orEmpty().trim()

    fun setBackendBaseUrl(url: String) {
        MmkvManager.encodeSettings(PREF_BACKEND_BASE_URL, url.trim().trimEnd('/'))
    }

    fun getSavedLicenseKey(): String =
        MmkvManager.decodeSettingsString(PREF_SAVED_LICENSE_KEY, "").orEmpty().trim()

    fun setSavedLicenseKey(key: String) {
        MmkvManager.encodeSettings(PREF_SAVED_LICENSE_KEY, key.trim())
    }

    fun classifyInput(raw: String): ActivationInput {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ActivationInput.Invalid
        val lower = trimmed.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return ActivationInput.DirectSubscriptionUrl(trimmed)
        }
        if (trimmed.length >= 4 && !trimmed.any { it.isWhitespace() }) {
            return ActivationInput.LicenseKey(trimmed)
        }
        return ActivationInput.Invalid
    }

    fun parseRedeemJson(json: String): LicenseRedeemResponse? =
        runCatching { gson.fromJson(json, LicenseRedeemResponse::class.java) }.getOrNull()

    fun redeem(
        rawInput: String,
        remarksHint: String = "QuietStorm",
        deviceId: String = "",
        backendBaseUrlOverride: String? = null,
        client: OkHttpClient = defaultClient(),
    ): LicenseResolutionResult {
        return when (val input = classifyInput(rawInput)) {
            is ActivationInput.Invalid ->
                LicenseResolutionResult.Failure("Empty or invalid input")

            is ActivationInput.DirectSubscriptionUrl ->
                LicenseResolutionResult.ReadySubscription(
                    subscriptionUrl = input.url,
                    remarks = remarksHint.ifBlank { "QuietStorm" }
                )

            is ActivationInput.LicenseKey -> {
                val baseUrl = (backendBaseUrlOverride ?: getBackendBaseUrl()).trim().trimEnd('/')
                if (baseUrl.isEmpty()) {
                    setSavedLicenseKey(input.key)
                    return LicenseResolutionResult.BackendEndpointRequired(input.key)
                }
                val payload = gson.toJson(
                    mapOf(
                        "license_key" to input.key,
                        "device_id" to deviceId,
                    )
                )
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/license/redeem")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                runCatching {
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body.string()
                        if (!resp.isSuccessful) {
                            val parsedErr = parseRedeemJson(body)?.message
                            return LicenseResolutionResult.Failure(
                                parsedErr ?: "Backend HTTP ${resp.code}"
                            )
                        }
                        val parsed = parseRedeemJson(body)
                            ?: return LicenseResolutionResult.Failure("Invalid backend JSON")
                        val subUrl = parsed.resolvedSubscriptionUrl()
                            ?: return LicenseResolutionResult.Failure(
                                parsed.message ?: "Backend did not return subscription_url"
                            )
                        setSavedLicenseKey(input.key)
                        val quota = if (parsed.totalBytes > 0L || parsed.expireAtSeconds > 0L) {
                            SubscriptionQuotaInfo(
                                uploadBytes = parsed.uploadBytes,
                                downloadBytes = parsed.downloadBytes,
                                totalBytes = parsed.totalBytes,
                                expireAtSeconds = parsed.expireAtSeconds,
                            )
                        } else null
                        LicenseResolutionResult.ReadySubscription(
                            subscriptionUrl = subUrl,
                            remarks = parsed.planName?.takeIf { it.isNotBlank() }
                                ?: parsed.remarks?.takeIf { it.isNotBlank() }
                                ?: remarksHint,
                            quota = quota,
                        )
                    }
                }.getOrElse { err ->
                    LicenseResolutionResult.Failure(err.message ?: "License request failed")
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
