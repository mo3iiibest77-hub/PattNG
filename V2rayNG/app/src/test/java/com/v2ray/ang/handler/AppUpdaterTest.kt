package com.v2ray.ang.handler

import com.v2ray.ang.dto.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
