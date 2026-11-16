package com.v2ray.ang.senpai

import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import java.util.Base64 as JavaBase64

class DualPipelineSelectionAndFailoverTest {

    private lateinit var mockBase64: MockedStatic<Base64>

    @Before
    fun setUp() {
        mockBase64 = mockStatic(Base64::class.java)
        mockBase64.`when`<ByteArray> {
            Base64.decode(Mockito.anyString(), Mockito.anyInt())
        }.thenAnswer { inv ->
            val input = inv.arguments[0] as String
            val flags = inv.arguments[1] as Int
            val dec = if ((flags and Base64.URL_SAFE) != 0) JavaBase64.getUrlDecoder() else JavaBase64.getDecoder()
            dec.decode(input)
        }
        mockBase64.`when`<String> {
            Base64.encodeToString(Mockito.any(ByteArray::class.java), Mockito.anyInt())
        }.thenAnswer { inv ->
            val input = inv.arguments[0] as ByteArray
            val flags = inv.arguments[1] as Int
            var enc = if ((flags and Base64.URL_SAFE) != 0) JavaBase64.getUrlEncoder() else JavaBase64.getEncoder()
            if ((flags and Base64.NO_PADDING) != 0) enc = enc.withoutPadding()
            enc.encodeToString(input)
        }
    }

    @After
    fun tearDown() {
        mockBase64.close()
    }

    private fun makeCdnXhttpProfile(host: String = "sw.mo3ibest.my.id"): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "CF-XHTTP"
            server = host
            serverPort = "2053"
            password = "11111111-1111-1111-1111-111111111111"
            network = "xhttp"
            security = AppConfig.TLS
            sni = host
        }
    }

    private fun makeRealityXhttpProfile(host: String = "203.0.113.10"): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Reality-XHTTP"
            server = host
            serverPort = "8445"
            password = "22222222-2222-2222-2222-222222222222"
            network = "xhttp"
            security = AppConfig.REALITY
            sni = "www.nvidia.com"
            publicKey = "pbkSample"
        }
    }

    private fun makeHysteria2Profile(host: String = "203.0.113.10"): ProfileItem {
        return ProfileItem.create(EConfigType.HYSTERIA2).apply {
            remarks = "Hy2-Direct"
            server = host
            serverPort = "1377"
            password = "hy2-secret-pass"
            network = "hysteria"
            security = AppConfig.TLS
            sni = "realnd.example.com"
        }
    }

    @Test
    fun classifierStrictlySeparatesCdnAndStandardAndHidesSensitiveHostsInBadges() {
        val cdn = makeCdnXhttpProfile()
        val front = makeCdnXhttpProfile("d222222abcdef8.cloudfront.net")
        val reality = makeRealityXhttpProfile()
        val hy2 = makeHysteria2Profile()
        val ss = ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
            remarks = "SS-Std"
            server = "198.51.100.77"
            serverPort = "8388"
            password = "pw"
            method = "aes-256-gcm"
            network = "tcp"
        }
        val invalidPort = makeRealityXhttpProfile().apply { serverPort = "99999" }
        val missingCred = makeRealityXhttpProfile().apply { password = "" }

        assertEquals(ConfigPipeline.CDN, ConfigPipelineClassifier.classify(cdn))
        assertEquals(ConfigPipeline.CDN, ConfigPipelineClassifier.classify(front))
        assertEquals(ConfigPipeline.STANDARD, ConfigPipelineClassifier.classify(reality))
        assertEquals(ConfigPipeline.STANDARD, ConfigPipelineClassifier.classify(hy2))
        assertEquals(ConfigPipeline.STANDARD, ConfigPipelineClassifier.classify(ss))

        assertTrue(ConfigPipelineClassifier.isSupportedImport(cdn))
        assertTrue(ConfigPipelineClassifier.isSupportedImport(reality))
        assertTrue(ConfigPipelineClassifier.isSupportedImport(hy2))
        assertTrue(ConfigPipelineClassifier.isSupportedImport(ss))
        assertFalse(ConfigPipelineClassifier.isSupportedImport(invalidPort))
        assertFalse(ConfigPipelineClassifier.isSupportedImport(missingCred))

        val rBadge = ConfigPipelineClassifier.safeTransportBadge(reality)
        val hBadge = ConfigPipelineClassifier.safeTransportBadge(hy2)
        val cBadge = ConfigPipelineClassifier.safeTransportBadge(cdn)
        val fBadge = ConfigPipelineClassifier.safeTransportBadge(front)

        assertEquals("REALITY • XHTTP", rBadge)
        assertEquals("Hysteria2 • QUIC", hBadge)
        assertEquals("CDN • XHTTP", cBadge)
        assertEquals("CloudFront • XHTTP", fBadge)

        // Ensure zero host/SNI/UUID/port leaks in badges
        assertFalse(rBadge.contains("203.0.113.10"))
        assertFalse(rBadge.contains("nvidia"))
        assertFalse(fBadge.contains("cloudfront.net"))
        assertFalse(cBadge.contains("mo3ibest"))
    }

    @Test
    fun manualProxyExtractorAcceptsProxyLinksAndBase64WhileBlockingSubscriptionUrls() {
        val raw = """
            https://evil.example.com/sub/secret
            tg://resolve?domain=channel
            vless://uuid@203.0.113.10:8445?type=xhttp&security=reality#MyReality
            hy2://pass@203.0.113.10:1377?obfs=salamander#MyHy2
            random invalid line
        """.trimIndent()

        val extracted = ConfigPipelineClassifier.extractManualImportPayload(raw)
        assertNotNull(extracted)
        val lines = extracted!!.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("vless://"))
        assertTrue(lines[1].startsWith("hy2://"))

        // Subscription-only URL must be rejected cleanly
        assertNull(ConfigPipelineClassifier.extractManualImportPayload("https://panel.example.com/api/sub"))

        // Base64 batch containing proxy URIs works cleanly
        val b64Input = JavaBase64.getEncoder().encodeToString(
            "trojan://pw@1.2.3.4:443#T1\nss://YWVzLTEyOC1nY206cHc=@1.2.3.4:8388#S1".toByteArray()
        )
        val b64Extracted = ConfigPipelineClassifier.extractManualImportPayload(b64Input)
        assertNotNull(b64Extracted)
        assertEquals(2, b64Extracted!!.lines().size)
    }

    @Test
    fun selectBestStandardGuidPrioritizesMeasuredSpeedThenRealityAndHysteria() {
        val cdn = "cdn1" to makeCdnXhttpProfile()
        val trojan = "tr1" to ProfileItem.create(EConfigType.TROJAN).apply {
            server = "203.0.113.5"
            serverPort = "443"
            password = "pw"
            security = AppConfig.TLS
        }
        val hy2 = "hy2" to makeHysteria2Profile()
        val reality = "rel1" to makeRealityXhttpProfile()

        // Untested: prioritizes REALITY over Hysteria2 & Trojan, never selects CDN
        val untestedPick = ConfigPipelineClassifier.selectBestStandardGuid(listOf(cdn, trojan, hy2, reality))
        assertEquals("rel1", untestedPick)

        // With measured latencies: fastest positive Standard wins cleanly
        val measuredPick = ConfigPipelineClassifier.selectBestStandardGuid(
            profilesInOrder = listOf(cdn, trojan, hy2, reality),
            delaysByGuid = mapOf("cdn1" to 40L, "rel1" to 380L, "hy2" to 190L, "tr1" to -1L),
        )
        assertEquals("hy2", measuredPick)
    }

    @Test
    fun smartFailoverPolicyRequiresConsecutiveDegradationAppliesHysteresisAndEnforcesCooldown() {
        val s0 = SmartFailoverPolicy.TrackerState()

        // Healthy CDN resets strikes cleanly
        val (d1, s1) = SmartFailoverPolicy.evaluateDegradation(
            state = s0.copy(failureStrikes = 1),
            cdnDelayMs = 280L,
            nowMs = 10_000L,
            hasStandardCandidate = true,
            smartFailoverArmed = true,
        )
        assertEquals(FailoverDecision.Healthy, d1)
        assertEquals(0, s1.failureStrikes)

        // Single slow spike records strike 1 but does not prematurely switch
        val (d2, s2) = SmartFailoverPolicy.evaluateDegradation(
            state = s1,
            cdnDelayMs = 2_800L,
            nowMs = 30_000L,
            hasStandardCandidate = true,
            smartFailoverArmed = true,
        )
        assertEquals(FailoverDecision.StrikeRecorded(1), d2)

        // Second consecutive slow probe triggers SwitchToStandard
        val (d3, s3) = SmartFailoverPolicy.evaluateDegradation(
            state = s2,
            cdnDelayMs = 2_900L,
            nowMs = 50_000L,
            hasStandardCandidate = true,
            smartFailoverArmed = true,
        )
        assertEquals(FailoverDecision.SwitchToStandard(SmartSwitchReason.CDN_HIGH_LATENCY), d3)

        // Cooldown prevents flapping for 90s
        val (d4, _) = SmartFailoverPolicy.evaluateDegradation(
            state = s3,
            cdnDelayMs = -1L,
            nowMs = 80_000L,
            hasStandardCandidate = true,
            smartFailoverArmed = true,
        )
        assertEquals(FailoverDecision.CooldownActive, d4)

        // Hysteresis gate: on high latency (2900ms), Standard at 2650ms (<400ms margin) is rejected, 420ms passes
        assertFalse(SmartFailoverPolicy.shouldCommitSwitch(SmartSwitchReason.CDN_HIGH_LATENCY, 2_900L, 2_650L))
        assertTrue(SmartFailoverPolicy.shouldCommitSwitch(SmartSwitchReason.CDN_HIGH_LATENCY, 2_900L, 420L))
        assertFalse(SmartFailoverPolicy.shouldCommitSwitch(SmartSwitchReason.CDN_STALLED, -1L, -1L))
        assertTrue(SmartFailoverPolicy.shouldCommitSwitch(SmartSwitchReason.CDN_STALLED, -1L, 650L))
    }
}
