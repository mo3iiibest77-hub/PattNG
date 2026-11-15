package com.v2ray.ang.senpai

import com.v2ray.ang.ui.qsn3d.LicenseLogoutOrchestrator
import com.v2ray.ang.ui.qsn3d.LicenseLogoutStorage
import com.v2ray.ang.ui.qsn3d.Qs3dUiState
import com.v2ray.ang.ui.qsn3d.SupportLinks
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LicenseSecurityAndQuotaTest {

    @Test
    fun uiState_neverExposesSavedLicenseKeyField() {
        val fieldNames = Qs3dUiState::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(
            "Qs3dUiState must never expose savedLicenseKey or raw license code",
            "savedLicenseKey" in fieldNames || "licenseKey" in fieldNames
        )
    }

    @Test
    fun classifyInput_rejectsUrlsWithoutMentioningSubscriptionLinks() {
        val urls = listOf(
            "https://example.com/sub/123",
            "http://panel.local/api",
            "vless://uuid@example.com:443#test",
            "vmess://abc123"
        )
        for (url in urls) {
            val res = LicenseBackendClient.classifyInput(url)
            assertTrue("Expected RejectedUrl for $url", res is ActivationInput.RejectedUrl)
            val msg = (res as ActivationInput.RejectedUrl).reason
            assertFalse("Error message should not mention subscription link phrasing", msg.contains("لینک اشتراک"))
            assertFalse("Error message should not mention subscription link phrasing", msg.contains("سابسکریپشن"))
        }

        val valid = LicenseBackendClient.classifyInput("  np-ab12-cd34-ef56  ")
        assertTrue(valid is ActivationInput.LicenseKey)
        assertEquals("NP-AB12-CD34-EF56", (valid as ActivationInput.LicenseKey).key)
    }

    @Test
    fun sanitizeForDisplay_redactsLicenseTokens() {
        val redacted = LicenseBackendClient.sanitizeForDisplay("Error redeeming NP-ABCD-1234-WXYZ on server")
        assertFalse(redacted.contains("NP-ABCD-1234-WXYZ"))
        assertTrue(redacted.contains("[***]"))
    }

    @Test
    fun subscriptionQuotaInfo_calculatesAccuratePercentagesAndNeverFakesUnlimited() {
        val bounded = SubscriptionQuotaInfo(
            uploadBytes = 10L * 1024L * 1024L * 1024L,
            downloadBytes = 25L * 1024L * 1024L * 1024L,
            totalBytes = 100L * 1024L * 1024L * 1024L,
        )
        assertEquals(35, bounded.usagePercent)
        assertEquals(65, bounded.remainingPercent)
        assertEquals(0.35f, bounded.usageFraction ?: -1f, 0.001f)
        assertEquals(0.65f, bounded.remainingFraction ?: -1f, 0.001f)
        assertEquals(65L * 1024L * 1024L * 1024L, bounded.remainingBytes)

        val unlimited = SubscriptionQuotaInfo(
            uploadBytes = 5L * 1024L * 1024L * 1024L,
            downloadBytes = 12L * 1024L * 1024L * 1024L,
            totalBytes = 0L,
        )
        assertNull(unlimited.usagePercent)
        assertNull(unlimited.remainingPercent)
        assertNull(unlimited.usageFraction)
        assertNull(unlimited.remainingFraction)
        assertNull(unlimited.remainingBytes)
    }

    @Test
    fun logout_stopsRunningVpnBeforeClearingLicenseData() = runBlocking {
        var vpnRunning = true
        var backgroundCancelled = false
        var stopRequestedCount = 0

        val ok = LicenseLogoutOrchestrator.stopRuntimeAndConfirm(
            isCoreRunning = { vpnRunning },
            cancelBackgroundWork = { backgroundCancelled = true },
            requestStopVpn = {
                stopRequestedCount += 1
                vpnRunning = false
            },
            awaitStopEvent = { true },
            pollIntervalMs = 10L
        )

        assertTrue(ok)
        assertTrue(backgroundCancelled)
        assertEquals(1, stopRequestedCount)

        val removedSubs = mutableListOf<String>()
        val clearedQuotas = mutableListOf<String>()
        var orphanSubFilter: Set<String> = emptySet()
        var clearedSelected = false
        var clearedExplicit = false
        var clearedSession = false

        LicenseLogoutOrchestrator.wipeBoundLicenseData(
            object : LicenseLogoutStorage {
                override fun getActiveLicenseSubId(): String = "sub_license_1"
                override fun getAllSubscriptionIds(): List<String> = listOf("sub_license_1", "sub_other")
                override fun removeSubscription(subId: String) {
                    removedSubs += subId
                }
                override fun removeServersWithoutSubIfNeeded(boundSubIds: Set<String>) {
                    orphanSubFilter = boundSubIds
                }
                override fun clearQuota(subId: String) {
                    clearedQuotas += subId
                }
                override fun clearSelectedServer() {
                    clearedSelected = true
                }
                override fun clearExplicitSelection() {
                    clearedExplicit = true
                }
                override fun clearActiveLicenseSession() {
                    clearedSession = true
                }
            }
        )

        // Only the license-bound subscription was removed, leaving unrelated sub_other intact
        assertEquals(listOf("sub_license_1"), removedSubs)
        assertEquals(listOf("sub_license_1"), clearedQuotas)
        assertEquals(setOf("sub_license_1"), orphanSubFilter)
        assertTrue(clearedSelected)
        assertTrue(clearedExplicit)
        assertTrue(clearedSession)
    }

    @Test
    fun logout_abortsWithoutSuccessWhenVpnRefusesToStop() = runBlocking {
        var stopAttempts = 0
        val ok = LicenseLogoutOrchestrator.stopRuntimeAndConfirm(
            isCoreRunning = { true },
            cancelBackgroundWork = {},
            requestStopVpn = { stopAttempts += 1 },
            awaitStopEvent = { false },
            pollIntervalMs = 1L
        )
        assertFalse("Logout must fail cleanly if VPN remains running", ok)
        assertTrue("Expected stop retry before aborting", stopAttempts >= 2)
    }

    @Test
    fun supportLinks_usesConfiguredTelegramUsername() {
        assertEquals("Mo3i_Best", SupportLinks.TELEGRAM_USERNAME)
        assertEquals("tg://resolve?domain=Mo3i_Best", SupportLinks.telegramDeepLink)
        assertEquals("https://t.me/Mo3i_Best", SupportLinks.telegramWebUrl)
    }

    @Test
    fun refreshStatus_distinguishesDefinitiveRejectionFromTransientError() {
        val revokedClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("""{"message":"این دستگاه برای این لایسنس فعال نشده است."}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val revokedRes = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-AAAA-BBBB-CCCC",
            deviceId = "dev-123",
            backendBaseUrlOverride = "https://127.0.0.1:4150",
            client = revokedClient,
        )
        assertTrue(revokedRes is LicenseResolutionResult.Failure)
        assertTrue((revokedRes as LicenseResolutionResult.Failure).isDefinitiveRejection)

        val transientClient = OkHttpClient.Builder().addInterceptor { _ ->
            throw IOException("Network timeout")
        }.build()

        val transientRes = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-AAAA-BBBB-CCCC",
            deviceId = "dev-123",
            backendBaseUrlOverride = "https://127.0.0.1:4150",
            client = transientClient,
        )
        assertTrue(transientRes is LicenseResolutionResult.Failure)
        assertFalse((transientRes as LicenseResolutionResult.Failure).isDefinitiveRejection)
    }

    @Test
    fun refreshStatus_doesNotRevokeOn429Or5xx_butRevokesOn401SessionRevoked() {
        val rateLimitedClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .body("""{"status":"rate_limited","message":"Slow down"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val r429 = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-AAAA-BBBB-CCCC",
            deviceId = "dev-123",
            backendBaseUrlOverride = "https://127.0.0.1:4150",
            client = rateLimitedClient,
            sessionTokenOverride = "nps_test_tok",
        )
        assertTrue(r429 is LicenseResolutionResult.Failure)
        assertFalse((r429 as LicenseResolutionResult.Failure).isDefinitiveRejection)

        val revoked401Client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"status":"session_revoked","message":"Revoked by admin"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val r401 = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-AAAA-BBBB-CCCC",
            deviceId = "dev-123",
            backendBaseUrlOverride = "https://127.0.0.1:4150",
            client = revoked401Client,
            sessionTokenOverride = "nps_test_tok",
        )
        assertTrue(r401 is LicenseResolutionResult.Failure)
        assertTrue((r401 as LicenseResolutionResult.Failure).isDefinitiveRejection)
    }
}
