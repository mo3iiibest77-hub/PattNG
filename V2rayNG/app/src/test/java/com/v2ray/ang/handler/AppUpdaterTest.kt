package com.v2ray.ang.handler

import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUpdaterTest {

    private fun sampleRelease(
        tag: String = "v2.5.0",
        versionCode: Int = 4000750,
    ): GitHubRelease {
        return GitHubRelease(
            tagName = tag,
            name = "NovaPulse $tag",
            body = "Performance & UI upgrade",
            versionCode = versionCode,
            assets = listOf(
                GitHubRelease.Asset(
                    name = "NovaPulse_2.5.0-fdroid_arm64-v8a.apk",
                    browserDownloadUrl = "https://example.invalid/fdroid-arm64.apk"
                ),
                GitHubRelease.Asset(
                    name = "NovaPulse_2.5.0_armeabi-v7a.apk",
                    browserDownloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_armeabi-v7a.apk"
                ),
                GitHubRelease.Asset(
                    name = "NovaPulse_2.5.0_arm64-v8a.apk",
                    browserDownloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_arm64-v8a.apk"
                ),
                GitHubRelease.Asset(
                    name = "NovaPulse_2.5.0_universal.apk",
                    browserDownloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_universal.apk"
                ),
                GitHubRelease.Asset(
                    name = "SHA256SUMS.txt",
                    browserDownloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/SHA256SUMS.txt"
                ),
            )
        )
    }

    @Test
    fun `matches exact device abi and excludes fdroid assets completely`() {
        val result = UpdateCheckerManager.evaluateReleaseUpdate(
            release = sampleRelease(),
            currentVersionName = "2.4.0",
            currentVersionCode = 4000748,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertTrue(result.hasUpdate)
        assertEquals("2.5.0", result.latestVersion)
        assertEquals("arm64-v8a", result.recommendedAbi)
        assertEquals(
            "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_arm64-v8a.apk",
            result.downloadUrl
        )
        assertEquals(
            "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_universal.apk",
            result.universalDownloadUrl
        )
        assertTrue(result.availableAssets.none { it.fileName.contains("fdroid", ignoreCase = true) })
        assertTrue(result.availableAssets.none { !it.fileName.endsWith(".apk") })
        assertEquals(3, result.availableAssets.size)
        assertTrue(result.availableAssets.first().isDeviceMatch)
    }

    @Test
    fun `falls back to universal apk when device abi not in release assets`() {
        val result = UpdateCheckerManager.evaluateReleaseUpdate(
            release = sampleRelease(),
            currentVersionName = "2.4.0",
            currentVersionCode = 4000748,
            supportedAbis = listOf("mips64")
        )

        assertTrue(result.hasUpdate)
        assertEquals("universal", result.recommendedAbi)
        assertEquals(
            "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_universal.apk",
            result.downloadUrl
        )
    }

    @Test
    fun `detects versionCode upgrade even when versionName matches`() {
        val result = UpdateCheckerManager.evaluateReleaseUpdate(
            release = sampleRelease(tag = "v2.4.0", versionCode = 4000749),
            currentVersionName = "2.4.0",
            currentVersionCode = 4000748,
            supportedAbis = listOf("arm64-v8a")
        )
        assertTrue(result.hasUpdate)

        val same = UpdateCheckerManager.evaluateReleaseUpdate(
            release = sampleRelease(tag = "v2.4.0", versionCode = 4000748),
            currentVersionName = "2.4.0",
            currentVersionCode = 4000748,
            supportedAbis = listOf("arm64-v8a")
        )
        assertFalse(same.hasUpdate)
    }

    @Test
    fun `validates apk zip magic header and rejects truncated or html payloads`() {
        val dir = Files.createTempDirectory("np-apk-check").toFile()
        try {
            val htmlFile = File(dir, "bad.apk").apply {
                writeText("<html><body>502 Bad Gateway</body></html>")
            }
            assertFalse(NovaPulseApkDownloader.isValidApkFile(htmlFile))

            val validStub = File(dir, "good.apk")
            val bytes = ByteArray(70_000)
            bytes[0] = 0x50
            bytes[1] = 0x4B
            bytes[2] = 0x03
            bytes[3] = 0x04
            validStub.writeBytes(bytes)
            assertTrue(NovaPulseApkDownloader.isValidApkFile(validStub))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `detects universal vs single-abi installed apk and selects matching update variant`() {
        val universalVariant = UpdateCheckerManager.detectApkAbiFromLibEntries(
            sequenceOf(
                "AndroidManifest.xml",
                "classes.dex",
                "lib/arm64-v8a/libtun2socks.so",
                "lib/armeabi-v7a/libtun2socks.so",
                "lib/x86_64/libtun2socks.so",
            )
        )
        assertEquals("universal", universalVariant)

        val arm64Variant = UpdateCheckerManager.detectApkAbiFromLibEntries(
            sequenceOf(
                "AndroidManifest.xml",
                "classes.dex",
                "lib/arm64-v8a/libtun2socks.so",
                "lib/arm64-v8a/libmmkv.so",
            )
        )
        assertEquals("arm64-v8a", arm64Variant)

        // When Universal APK is installed on an arm64-v8a device, updater MUST recommend universal!
        val universalResult = UpdateCheckerManager.evaluateReleaseUpdate(
            release = sampleRelease(),
            currentVersionName = "2.4.1",
            currentVersionCode = 4000749,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            installedApkAbi = universalVariant,
        )
        assertTrue(universalResult.hasUpdate)
        assertEquals("universal", universalResult.recommendedAbi)
        assertEquals(
            "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_universal.apk",
            universalResult.downloadUrl
        )
        assertTrue(universalResult.availableAssets.first().isUniversal)
        assertTrue(universalResult.availableAssets.first().isDeviceMatch)

        // When arm64-v8a split APK is installed on the same device, updater MUST recommend arm64-v8a!
        val arm64Result = UpdateCheckerManager.evaluateReleaseUpdate(
            release = sampleRelease(),
            currentVersionName = "2.4.1",
            currentVersionCode = 4000749,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            installedApkAbi = arm64Variant,
        )
        assertTrue(arm64Result.hasUpdate)
        assertEquals("arm64-v8a", arm64Result.recommendedAbi)
        assertEquals(
            "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.5.0_arm64-v8a.apk",
            arm64Result.downloadUrl
        )
    }

    @Test
    fun `pickNewestRelease always chooses strictly highest version across sources`() {
        val older = sampleRelease(tag = "v2.4.3", versionCode = 4000751)
        val newest = sampleRelease(tag = "v2.4.5", versionCode = 4000753)

        assertEquals("v2.4.5", UpdateCheckerManager.pickNewestRelease(older, newest)?.tagName)
        assertEquals("v2.4.5", UpdateCheckerManager.pickNewestRelease(newest, older)?.tagName)

        // Same version tag -> higher versionCode wins
        val sameTagNewCode = sampleRelease(tag = "v2.4.4", versionCode = 4000753)
        val sameTagOldCode = sampleRelease(tag = "v2.4.4", versionCode = 4000752)
        assertEquals(4000753, UpdateCheckerManager.pickNewestRelease(sameTagOldCode, sameTagNewCode)?.versionCode)

        // Skipping multiple versions (e.g. 2.4.2 -> 2.4.5) still reports update available
        val skippedResult = UpdateCheckerManager.evaluateReleaseUpdate(
            release = newest,
            currentVersionName = "2.4.2",
            currentVersionCode = 4000750,
            supportedAbis = listOf("arm64-v8a")
        )
        assertTrue(skippedResult.hasUpdate)
        assertEquals("2.4.5", skippedResult.latestVersion)
    }

    @Test
    fun `buildDownloadCandidates prioritizes self-hosted gateway and deduplicates identical mirror`() {
        val ghCandidates = NovaPulseApkDownloader.buildDownloadCandidates(
            downloadUrl = "https://github.com/mo3iiibest77-hub/PattNG/releases/download/v2.4.5/NovaPulse_2.4.5_arm64-v8a.apk",
            fileName = "NovaPulse_2.4.5_arm64-v8a.apk",
            serverLatestUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/latest",
        )
        assertEquals(
            listOf(
                "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.4.5_arm64-v8a.apk",
                "https://github.com/mo3iiibest77-hub/PattNG/releases/download/v2.4.5/NovaPulse_2.4.5_arm64-v8a.apk",
            ),
            ghCandidates
        )

        val gatewayOnly = NovaPulseApkDownloader.buildDownloadCandidates(
            downloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.4.5_arm64-v8a.apk",
            fileName = "NovaPulse_2.4.5_arm64-v8a.apk",
            serverLatestUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/latest",
        )
        assertEquals(1, gatewayOnly.size)
    }

    @Test
    fun `parses separated campaign_notice and preserves it even when no app update is needed`() {
        val rawJson = """
            {
              "tag_name": "v2.6.2",
              "version_code": 4000758,
              "name": "NovaPulse v2.6.2",
              "body": "Release notes v2.6.2",
              "prerelease": false,
              "assets": [
                {
                  "name": "NovaPulse_2.6.2_arm64-v8a.apk",
                  "browser_download_url": "https://gr.arpras.ir/npdl/NovaPulse_2.6.2_arm64-v8a.apk",
                  "size": 25377915
                }
              ],
              "campaign_notice": {
                "id": "202611_novapulse_campaign_v1_gift",
                "segment": "gift",
                "title": "هدیه‌ای از طرف NovaPulse برای شما ❤️",
                "body": "پیشنهاد ویژه ۱۰۰ گیگابایت ۳۰۰,۰۰۰ تومان\n@Mo3i_Best",
                "severity": "info",
                "expire_at_ms": 1795312024044,
                "server_now_ms": 1795104000000,
                "has_valid_expiry": true,
                "notify_enabled": true,
                "notify_title": "هدیه NovaPulse",
                "notify_body": "مشاهده پیشنهادهای ویژه",
                "min_notify_interval_sec": 21600,
                "min_dialog_interval_sec": 43200
              }
            }
        """.trimIndent()

        val release = JsonUtil.fromJsonSafe(rawJson, GitHubRelease::class.java)
        assertNotNull(release)
        assertNotNull(release!!.campaignNotice)

        val noUpdateRes = UpdateCheckerManager.evaluateReleaseUpdate(
            release = release,
            currentVersionName = "2.6.2",
            currentVersionCode = 4000758,
            supportedAbis = listOf("arm64-v8a"),
        )
        assertFalse(noUpdateRes.hasUpdate)
        assertEquals("gift", noUpdateRes.campaignNotice?.segment)
        assertEquals(1795312024044L, noUpdateRes.campaignNotice?.expireAtMs)
        assertTrue(noUpdateRes.campaignNotice?.hasValidExpiry == true)
        assertEquals(21600L, noUpdateRes.campaignNotice?.minNotifyIntervalSec)

        val withUpdateRes = UpdateCheckerManager.evaluateReleaseUpdate(
            release = release,
            currentVersionName = "2.5.0",
            currentVersionCode = 4000755,
            supportedAbis = listOf("arm64-v8a"),
        )
        assertTrue(withUpdateRes.hasUpdate)
        assertEquals("2.6.2", withUpdateRes.latestVersion)
        assertEquals("gift", withUpdateRes.campaignNotice?.segment)
    }
}
