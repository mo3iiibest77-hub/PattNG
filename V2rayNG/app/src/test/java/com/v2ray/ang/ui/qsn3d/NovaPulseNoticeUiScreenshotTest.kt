package com.v2ray.ang.ui.qsn3d

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.v2ray.ang.dto.ApkAssetOption
import com.v2ray.ang.dto.CheckUpdateResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class NovaPulseNoticeUiScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleUpdateAssets(): List<ApkAssetOption> = listOf(
        ApkAssetOption(
            abi = "arm64-v8a",
            fileName = "NovaPulse_2.4.5_arm64-v8a.apk",
            downloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.4.5_arm64-v8a.apk",
            sizeBytes = 24_800_000L,
            isDeviceMatch = true,
            isUniversal = false,
        ),
        ApkAssetOption(
            abi = "universal",
            fileName = "NovaPulse_2.4.5_universal.apk",
            downloadUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse_2.4.5_universal.apk",
            sizeBytes = 48_900_000L,
            isDeviceMatch = false,
            isUniversal = true,
        ),
    )

    private fun writeScreenshotPng(fileName: String) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(350L)
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Captured bitmap width must be > 200px", bitmap.width > 200)
        assertTrue("Captured bitmap height must be > 300px", bitmap.height > 300)

        val outDir = File("build/outputs/screenshots").apply { mkdirs() }
        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue("Screenshot PNG should be non-empty: ${outFile.absolutePath}", outFile.length() > 5_000L)
    }

    @Test
    @Config(qualifiers = "fa-rIR-w412dp-h915dp-xxhdpi")
    fun renderMandatoryPre244UpdateNotice_persian412dp() {
        val mandatoryNotes = """
            هشدار امنیتی و ارتقای اجباری (نسخه‌های پایین‌تر از 2.4.4)
            • دسترسی نسخه‌های قدیمی‌تر از 2.4.4 به‌زودی به‌طور کامل قطع خواهد شد.
            • لطفاً همین حالا دکمه «بروزرسانی» را بزنید و به نسخه 2.4.5 ارتقا دهید.
            • پس از نصب نسخه جدید، دوباره با کد لایسنس خود وارد شوید.

            پشتیبانی و دریافت نسخه رسمی:
            • کانال رسمی NOVAPULSE در تلگرام: @NovaPulseVPN
            • لینک مستقیم کانال: https://t.me/NovaPulseVPN
            • پشتیبانی مستقیم و دریافت/تمدید لایسنس: @Mo3i_Best
        """.trimIndent()

        val result = CheckUpdateResult(
            hasUpdate = true,
            latestVersion = "2.4.5",
            latestVersionCode = 4000755,
            releaseNotes = mandatoryNotes,
            recommendedAbi = "arm64-v8a",
            availableAssets = sampleUpdateAssets(),
        )

        composeTestRule.setContent {
            Qs3dTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF040916), Color(0xFF09132B)))
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NovaPulseUpdateDialogCard(
                        result = result,
                        currentVersionName = "2.4.3",
                        isCheckingUpdate = false,
                        downloadProgress = null,
                        onDownloadOption = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("@NovaPulseVPN", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("@Mo3i_Best", substring = true).assertIsDisplayed()
        writeScreenshotPng("notice_mandatory_pre244_fa_412dp.png")
    }

    @Test
    @Config(qualifiers = "fa-rIR-w412dp-h915dp-xxhdpi")
    fun renderOptional244UpdateNotice_withDownloadProgress_persian412dp() {
        val optionalNotes = """
            نسخه جدید 2.4.5 آماده دریافت است
            • وضعیت نسخه فعلی شما (2.4.4 و بالاتر): دسترسی کاملاً برقرار و ایمن است.
            • بروزرسانی به نسخه 2.4.5 در این مرحله اختیاری بوده و شامل بهبود رابط کاربری، امنیت و سرعت دریافت است.

            پشتیبانی و دریافت نسخه رسمی:
            • کانال رسمی NOVAPULSE در تلگرام: @NovaPulseVPN
            • پشتیبانی مستقیم و دریافت/تمدید لایسنس: @Mo3i_Best
        """.trimIndent()

        val result = CheckUpdateResult(
            hasUpdate = true,
            latestVersion = "2.4.5",
            latestVersionCode = 4000755,
            releaseNotes = optionalNotes,
            recommendedAbi = "arm64-v8a",
            availableAssets = sampleUpdateAssets(),
        )

        composeTestRule.setContent {
            Qs3dTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF040916), Color(0xFF09132B)))
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NovaPulseUpdateDialogCard(
                        result = result,
                        currentVersionName = "2.4.4",
                        isCheckingUpdate = false,
                        downloadProgress = 68,
                        onDownloadOption = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("68%").assertIsDisplayed()
        composeTestRule.onNodeWithText("@Mo3i_Best", substring = true).assertIsDisplayed()
        writeScreenshotPng("notice_optional_244_progress_fa_412dp.png")
    }

    @Test
    @Config(qualifiers = "fa-rIR-w320dp-h640dp-xhdpi")
    fun renderSecurityLegacyNotice_narrowScreen320dp() {
        val legacyMsg = """
            هشدار امنیتی و ارتقای اجباری (نسخه‌های پایین‌تر از 2.4.4)
            • دسترسی نسخه‌های پایین‌تر از 2.4.4 به‌زودی به‌طور کامل قطع خواهد شد.
            • لطفاً همین حالا از بخش «بروزرسانی» اپلیکیشن خود را به نسخه 2.4.5 ارتقا دهید.

            پشتیبانی و دریافت نسخه رسمی:
            • کانال رسمی NOVAPULSE در تلگرام: @NovaPulseVPN
            • پشتیبانی مستقیم و دریافت/تمدید لایسنس: @Mo3i_Best
        """.trimIndent()

        composeTestRule.setContent {
            Qs3dTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF040916), Color(0xFF09132B)))
                        )
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NovaPulseSecurityNoticeCard(
                        message = legacyMsg,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("@Mo3i_Best", substring = true).assertIsDisplayed()
        writeScreenshotPng("notice_security_legacy_fa_320dp.png")
    }

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h915dp-xxhdpi")
    fun renderMixedEnglishPersianNotice_412dp() {
        val notes = """
            NovaPulse 2.4.5 Security & UI Update
            • Improved TLS license validation and APK certificate rotation support.
            • Official Telegram channel: https://t.me/NovaPulseVPN
            • Direct support: @Mo3i_Best
        """.trimIndent()

        val result = CheckUpdateResult(
            hasUpdate = true,
            latestVersion = "2.4.5",
            latestVersionCode = 4000755,
            releaseNotes = notes,
            recommendedAbi = "arm64-v8a",
            availableAssets = sampleUpdateAssets(),
        )

        composeTestRule.setContent {
            Qs3dTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF040916), Color(0xFF09132B)))
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NovaPulseUpdateDialogCard(
                        result = result,
                        currentVersionName = "2.4.4",
                        isCheckingUpdate = false,
                        downloadProgress = null,
                        onDownloadOption = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("@NovaPulseVPN", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("@Mo3i_Best", substring = true).assertIsDisplayed()
        writeScreenshotPng("notice_optional_en_412dp.png")
    }
}
