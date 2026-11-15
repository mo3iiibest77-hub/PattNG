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
    val versionCode: Int = 0
) {
    data class Asset(
        @SerializedName("name")
        val name: String,
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String,
        @SerializedName("size")
        val size: Long = 0L,
        @SerializedName("sha256")
        val sha256: String = ""
    )
}
