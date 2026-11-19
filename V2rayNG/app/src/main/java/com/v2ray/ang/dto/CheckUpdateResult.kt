package com.v2ray.ang.dto

data class ApkAssetOption(
    val abi: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val isDeviceMatch: Boolean = false,
    val isUniversal: Boolean = false,
)

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val latestVersionCode: Int = 0,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val universalDownloadUrl: String? = null,
    val recommendedAbi: String = "arm64-v8a",
    val recommendedFileName: String? = null,
    val availableAssets: List<ApkAssetOption> = emptyList(),
    val error: String? = null,
    val isPreRelease: Boolean = false,
    val campaignNotice: GitHubRelease.CampaignNoticePayload? = null,
)
