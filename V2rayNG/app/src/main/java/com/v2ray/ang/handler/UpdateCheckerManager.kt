package com.v2ray.ang.handler

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.ApkAssetOption
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.senpai.LicenseBackendClient
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipFile

object UpdateCheckerManager {

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")
    private val NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun detectInstalledApkAbi(context: Context? = null): String? {
        val ctx = context ?: runCatching { AngApplication.application }.getOrNull() ?: return null
        val sourcePath = runCatching { ctx.applicationInfo?.sourceDir }.getOrNull().orEmpty()
        if (sourcePath.isBlank()) return null
        val apkFile = File(sourcePath)
        if (!apkFile.isFile) return null
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                val names = generateSequence { if (entries.hasMoreElements()) entries.nextElement().name else null }
                detectApkAbiFromLibEntries(names)
            }
        }.getOrNull()
    }

    fun detectApkAbiFromLibEntries(entryNames: Sequence<String>): String? {
        val foundAbis = linkedSetOf<String>()
        for (name in entryNames) {
            if (!name.startsWith("lib/")) continue
            val abi = name.removePrefix("lib/").substringBefore('/', "")
            if (abi in NATIVE_ABIS) {
                foundAbis.add(abi)
            }
        }
        return when {
            foundAbis.size > 1 -> "universal"
            foundAbis.size == 1 -> foundAbis.first()
            else -> null
        }
    }

    private fun buildServerUpdateHeaders(): Map<String, String> = runCatching {
        buildMap {
            put("X-App-Version", BuildConfig.VERSION_NAME)
            put("X-Campaign-Notice-V1", "1")
            if (LicenseBackendClient.isLicenseSessionActive()) {
                for ((k, v) in LicenseBackendClient.buildSubscriptionAuthHeaders()) {
                    if (k.isNotBlank() && v.isNotBlank()) put(k, v)
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun resolveInstalledVersionCode(context: Context?): Int {
        if (context == null) return BuildConfig.VERSION_CODE
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info).toInt().takeIf { it > 0 } ?: BuildConfig.VERSION_CODE
        }.getOrDefault(BuildConfig.VERSION_CODE)
    }

    suspend fun checkForUpdate(
        includePreRelease: Boolean = false,
        context: Context? = null,
    ): CheckUpdateResult = withContext(Dispatchers.IO) {
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val installedApkAbi = detectInstalledApkAbi(context)
        val ts = System.currentTimeMillis()
        val encodedVer = runCatching { URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8") }
            .getOrDefault(BuildConfig.VERSION_NAME)
        val serverUrl = buildString {
            append(AppConfig.APP_UPDATE_SERVER_URL)
            append("?force=1&campaign_v1=1&app_version=")
            append(encodedVer)
            append("&t=")
            append(ts)
            if (includePreRelease) append("&prerelease=1")
        }

        val ghUrl = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val serverRelease = fetchReleaseFromUrl(
            url = serverUrl,
            expectList = false,
            headers = buildServerUpdateHeaders(),
        )
        // Always cross-check GitHub when needed or compare both sources so the newest version ALWAYS wins.
        val ghRelease = fetchReleaseFromUrl(ghUrl, expectList = includePreRelease)
        val release = pickNewestRelease(serverRelease, ghRelease)
        val campaignNotice = serverRelease?.campaignNotice ?: release?.campaignNotice

        if (release == null) {
            return@withContext CheckUpdateResult(hasUpdate = false, campaignNotice = campaignNotice)
        }

        val effectiveVersionCode = resolveInstalledVersionCode(context)
        val result = evaluateReleaseUpdate(
            release = release,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = effectiveVersionCode,
            supportedAbis = supportedAbis,
            installedApkAbi = installedApkAbi,
            campaignNotice = campaignNotice,
        )
        LogUtil.i(
            AppConfig.TAG,
            "Update check: hasUpdate=${result.hasUpdate}, latest=${result.latestVersion}, campaignSegment=${campaignNotice?.segment ?: "none"}, recommended=${result.recommendedAbi}"
        )
        result
    }

    fun pickNewestRelease(
        first: GitHubRelease?,
        second: GitHubRelease?,
    ): GitHubRelease? {
        if (first == null) return second
        if (second == null) return first
        val firstHasApk = first.assets.any {
            val l = it.name.lowercase()
            l.endsWith(".apk") && !l.contains("fdroid")
        }
        val secondHasApk = second.assets.any {
            val l = it.name.lowercase()
            l.endsWith(".apk") && !l.contains("fdroid")
        }
        if (firstHasApk != secondHasApk) {
            return if (firstHasApk) first else second
        }
        val cmp = compareVersions(first.tagName, second.tagName)
        if (cmp != 0) {
            return if (cmp > 0) first else second
        }
        if (first.versionCode != second.versionCode) {
            return if (first.versionCode > second.versionCode) first else second
        }
        return first
    }

    private fun fetchReleaseFromUrl(
        url: String,
        expectList: Boolean,
        headers: Map<String, String> = emptyMap(),
    ): GitHubRelease? {
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = runCatching {
            HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 6000,
                    headers = headers,
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
                        proxyPassword = proxyPassword,
                        headers = headers,
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
        installedApkAbi: String? = null,
        campaignNotice: GitHubRelease.CampaignNoticePayload? = null,
    ): CheckUpdateResult {
        val cleanVersion = normalizeVersion(release.tagName)
        val hasNewer = isNewerRelease(release, currentVersionName, currentVersionCode)
        val effectiveCampaign = campaignNotice ?: release.campaignNotice
        val installedClean = installedApkAbi?.trim()?.lowercase()?.takeIf { it in KNOWN_ABIS }
        val devicePrimaryAbi = installedClean
            ?: supportedAbis.firstOrNull { abi ->
                KNOWN_ABIS.any { it.equals(abi, ignoreCase = true) }
            }
            ?: supportedAbis.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }

        val selectableAssets = buildApkAssetOptions(
            assets = release.assets,
            supportedAbis = supportedAbis,
            installedApkAbi = installedClean,
        )
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
                isPreRelease = release.prerelease,
                campaignNotice = effectiveCampaign,
            )
        } else if (hasNewer) {
            // Version bump published before assets finished uploading: still report non-downloadable info safely
            CheckUpdateResult(
                hasUpdate = false,
                latestVersion = cleanVersion,
                latestVersionCode = release.versionCode,
                recommendedAbi = devicePrimaryAbi,
                campaignNotice = effectiveCampaign,
            )
        } else {
            CheckUpdateResult(
                hasUpdate = false,
                latestVersion = cleanVersion,
                latestVersionCode = release.versionCode,
                recommendedAbi = devicePrimaryAbi,
                availableAssets = selectableAssets,
                campaignNotice = effectiveCampaign,
            )
        }
    }

    fun buildApkAssetOptions(
        assets: List<GitHubRelease.Asset>,
        supportedAbis: List<String>,
        installedApkAbi: String? = null,
    ): List<ApkAssetOption> {
        val cleanAssets = assets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") && !lower.contains("fdroid")
        }

        val installedNorm = installedApkAbi?.trim()?.lowercase()?.takeIf { it in KNOWN_ABIS }
        val normalizedDeviceAbis = supportedAbis.map { it.lowercase() }
        val matchedTargetAbi = if (installedNorm != null && cleanAssets.any { extractAbiTag(it.name).equals(installedNorm, ignoreCase = true) }) {
            installedNorm
        } else {
            normalizedDeviceAbis.firstOrNull { devAbi ->
                cleanAssets.any { extractAbiTag(it.name).equals(devAbi, ignoreCase = true) }
            }
        }

        return cleanAssets.map { asset ->
            val abiTag = extractAbiTag(asset.name)
            val isUniversal = abiTag.equals("universal", ignoreCase = true)
            val isMatch = matchedTargetAbi != null && abiTag.equals(matchedTargetAbi, ignoreCase = true)
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
