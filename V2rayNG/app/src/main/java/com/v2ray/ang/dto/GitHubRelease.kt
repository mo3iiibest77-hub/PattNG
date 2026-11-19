package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String,
    @SerializedName("name")
    val name: String = "",
    @SerializedName("body")
    val body: String = "",
    @SerializedName("assets")
    val assets: List<Asset> = emptyList(),
    @SerializedName("prerelease")
    val prerelease: Boolean = false,
    @SerializedName("published_at")
    val publishedAt: String = "",
    @SerializedName("version_code")
    val versionCode: Int = 0,
    @SerializedName("campaign_notice")
    val campaignNotice: CampaignNoticePayload? = null,
) {
    data class CampaignNoticePayload(
        @SerializedName("id")
        val id: String = "",
        @SerializedName("segment")
        val segment: String = "personal",
        @SerializedName("title")
        val title: String = "",
        @SerializedName("body")
        val body: String = "",
        @SerializedName("severity")
        val severity: String = "info",
        @SerializedName("expire_at_ms")
        val expireAtMs: Long = 0L,
        @SerializedName("server_now_ms")
        val serverNowMs: Long = 0L,
        @SerializedName("has_valid_expiry")
        val hasValidExpiry: Boolean = false,
        @SerializedName("notify_enabled")
        val notifyEnabled: Boolean = true,
        @SerializedName("notify_title")
        val notifyTitle: String = "",
        @SerializedName("notify_body")
        val notifyBody: String = "",
        @SerializedName("min_notify_interval_sec")
        val minNotifyIntervalSec: Long = 21600L,
        @SerializedName("min_dialog_interval_sec")
        val minDialogIntervalSec: Long = 43200L,
    )

    data class Asset(
        @SerializedName("name")
        val name: String,
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String,
        @SerializedName("size")
        val size: Long = 0L,
        @SerializedName("sha256")
        val sha256: String? = ""
    )
}
