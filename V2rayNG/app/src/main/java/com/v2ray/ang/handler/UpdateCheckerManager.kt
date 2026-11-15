package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.ApkAssetOption
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")

    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val serverUrl = buildString {
            append(AppConfig.APP_UPDATE_SERVER_URL)
            if (includePreRelease) append("?prerelease=1")
        }

        val serverRelease = fetchReleaseFromUrl(serverUrl, expectList = false)
        val release = if (serverRelease != null && (serverRelease.assets.isNotEmpty() || isNewerRelease(serverRelease, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))) {
            serverRelease
        } else {
            val ghUrl = if (includePreRelease) {
                AppConfig.APP_API_URL
            } else {
                AppConfig.APP_API_URL.concatUrl("latest")
            }
            fetchReleaseFromUrl(ghUrl, expectList = includePreRelease) ?: serverRelease
        }

        if (release == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val result = evaluateReleaseUpdate(
            release = release,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            supportedAbis = supportedAbis,
        )
        LogUtil.i(
            AppConfig.TAG,
            "Update check: hasUpdate=${result.hasUpdate}, latest=${result.latestVersion}, abi=${result.recommendedAbi}"
        )
        result
    }

    private fun fetchReleaseFromUrl(url: String, expectList: Boolean): GitHubRelease? {
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = runCatching {
            HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 6000
                )
            )
        }.getOrNull()

        if (response.isNullOrBlank()) {
            val httpPort = SettingsManager.getHttpPort()
            response = runCatching {
                HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 6000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            }.getOrNull()
        }

        if (response.isNullOrBlank()) return null

        return if (expectList) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)?.firstOrNull()
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
    }

    fun evaluateReleaseUpdate(
        release: GitHubRelease,
        currentVersionName: String,
        currentVersionCode: Int,
        supportedAbis: List<String>,
    ): CheckUpdateResult {
        val cleanVersion = normalizeVersion(release.tagName)
        val hasNewer = isNewerRelease(release, currentVersionName, currentVersionCode)
        val devicePrimaryAbi = supportedAbis.firstOrNull { abi ->
            KNOWN_ABIS.any { it.equals(abi, ignoreCase = true) }
        } ?: supportedAbis.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }

        val selectableAssets = buildApkAssetOptions(release.assets, supportedAbis)
        val universalOption = selectableAssets.firstOrNull { it.isUniversal }
        val recommendedOption = selectableAssets.firstOrNull { it.isDeviceMatch }
            ?: universalOption
            ?: selectableAssets.firstOrNull()

        return if (hasNewer && recommendedOption != null) {
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = cleanVersion,
                latestVersionCode = release.versionCode,
                releaseNotes = release.body,
                downloadUrl = recommendedOption.downloadUrl,
                universalDownloadUrl = universalOption?.downloadUrl,
                recommendedAbi = recommendedOption.abi,
                recommendedFileName = recommendedOption.fileName,
                availableAssets = selectableAssets,
                isPreRelease = release.prerelease
            )
        } else if (hasNewer) {
            // Version bump published before assets finished uploading: still report non-downloadable info safely
            CheckUpdateResult(
                hasUpdate = false,
                latestVersion = cleanVersion,
                latestVersionCode = release.versionCode,
                recommendedAbi = devicePrimaryAbi,
            )
        } else {
            CheckUpdateResult(
                hasUpdate = false,
                latestVersion = cleanVersion,
                latestVersionCode = release.versionCode,
                recommendedAbi = devicePrimaryAbi,
                availableAssets = selectableAssets
            )
        }
    }

    fun buildApkAssetOptions(
        assets: List<GitHubRelease.Asset>,
        supportedAbis: List<String>,
    ): List<ApkAssetOption> {
        val cleanAssets = assets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") && !lower.contains("fdroid")
        }

        val normalizedDeviceAbis = supportedAbis.map { it.lowercase() }
        val matchedDeviceAbi = normalizedDeviceAbis.firstOrNull { devAbi ->
            cleanAssets.any { extractAbiTag(it.name).equals(devAbi, ignoreCase = true) }
        }

        return cleanAssets.map { asset ->
            val abiTag = extractAbiTag(asset.name)
            val isUniversal = abiTag.equals("universal", ignoreCase = true)
            val isMatch = matchedDeviceAbi != null && abiTag.equals(matchedDeviceAbi, ignoreCase = true)
            ApkAssetOption(
                abi = abiTag,
                fileName = asset.name,
                downloadUrl = asset.browserDownloadUrl,
                sizeBytes = asset.size,
                sha256 = asset.sha256,
                isDeviceMatch = isMatch,
                isUniversal = isUniversal,
            )
        }.sortedWith(
            compareByDescending<ApkAssetOption> { it.isDeviceMatch }
                .thenByDescending { it.isUniversal }
                .thenBy { it.abi }
        )
    }

    fun extractAbiTag(fileName: String): String {
        val lower = fileName.lowercase()
        return KNOWN_ABIS.firstOrNull { lower.contains(it) } ?: "universal"
    }

    fun isNewerRelease(
        release: GitHubRelease,
        currentVersionName: String,
        currentVersionCode: Int,
    ): Boolean {
        val latest = normalizeVersion(release.tagName)
        val semverCmp = compareVersions(latest, normalizeVersion(currentVersionName))
        if (semverCmp > 0) return true
        if (semverCmp < 0) return false
        return release.versionCode > 0 && currentVersionCode > 0 && release.versionCode > currentVersionCode
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
    }

    fun compareVersions(version1: String, version2: String): Int {
        val v1 = normalizeVersion(version1).split(".")
        val v2 = normalizeVersion(version2).split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val num2 = v2.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }
}
