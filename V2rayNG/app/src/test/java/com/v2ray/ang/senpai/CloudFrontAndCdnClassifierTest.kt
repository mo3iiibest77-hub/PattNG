package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFrontAndCdnClassifierTest {

    @Test
    fun parseAwsIpRangesJson_extractsOnlyCloudFrontIpv4Prefixes() {
        val json = """
            {
              "syncToken": "1700000000",
              "prefixes": [
                {"ip_prefix": "13.32.0.0/15", "region": "GLOBAL", "service": "CLOUDFRONT"},
                {"ip_prefix": "52.95.110.0/24", "region": "us-east-1", "service": "EC2"},
                {"ip_prefix": "99.84.0.0/16", "region": "GLOBAL", "service": "CLOUDFRONT"},
                {"ip_prefix": "13.32.0.0/15", "region": "GLOBAL", "service": "CLOUDFRONT"}
              ],
              "ipv6_prefixes": [
                {"ipv6_prefix": "2600:9000::/28", "service": "CLOUDFRONT"}
              ]
            }
        """.trimIndent()

        val parsed = CloudFrontIpRangesParser.parseAwsIpRangesJson(json)
        assertEquals(listOf("13.32.0.0/15", "99.84.0.0/16"), parsed)
    }

    @Test
    fun parsePingOutput_extractsResolvedIpv4AndRtt() {
        val sample = """
            PING d1234abcd.cloudfront.net (99.84.180.52) 56(84) bytes of data.
            64 bytes from server-99-84-180-52.fra60.r.cloudfront.net (99.84.180.52): icmp_seq=1 ttl=51 time=68.4 ms
        """.trimIndent()

        val (ip, rtt) = CdnPingClassifier.parsePingOutput(sample)
        assertEquals("99.84.180.52", ip)
        assertEquals(68L, rtt)
    }

    @Test
    fun classifyResolvedIp_distinguishesCloudFrontCloudflareAndUnknown() {
        val frontCidrs = listOf("13.32.0.0/15", "99.84.0.0/16")

        val frontResult = CdnPingClassifier.classifyResolvedIp(
            domain = "cdn.example.com",
            resolvedIp = "99.84.180.52",
            pingRttMs = 68L,
            cloudFrontCidrs = frontCidrs
        )
        assertEquals(CdnType.CLOUDFRONT, frontResult.cdnType)

        val cfResult = CdnPingClassifier.classifyResolvedIp(
            domain = "cf.example.com",
            resolvedIp = "104.16.12.5",
            pingRttMs = 45L,
            cloudFrontCidrs = frontCidrs
        )
        assertEquals(CdnType.CLOUDFLARE, cfResult.cdnType)

        val seedResult = CdnPingClassifier.classifyResolvedIp(
            domain = "seed.example.com",
            resolvedIp = "8.34.146.10",
            pingRttMs = 52L,
            cloudFrontCidrs = frontCidrs
        )
        assertEquals(CdnType.CLOUDFLARE, seedResult.cdnType)

        val unknownResult = CdnPingClassifier.classifyResolvedIp(
            domain = "direct.example.com",
            resolvedIp = "1.2.3.4",
            pingRttMs = 100L,
            cloudFrontCidrs = frontCidrs
        )
        assertEquals(CdnType.UNKNOWN, unknownResult.cdnType)
    }

    @Test
    fun subscriptionQuotaParser_parsesStandardUserinfoHeader() {
        val header = "upload=1073741824; download=2147483648; total=10737418240; expire=1800000000"
        val parsed = SubscriptionQuotaParser.parseHeader(header)
        assertNotNull(parsed)
        assertEquals(3221225472L, parsed!!.usedBytes)
        assertEquals(10737418240L, parsed.totalBytes)
        assertEquals(30, parsed.usagePercent)
        assertEquals(1800000000L, parsed.expireAtSeconds)
    }

    @Test
    fun autoConfigFilter_acceptsVlessXhttpAndWsTlsOnly() {
        val vlessXhttp = ProfileItem.create(EConfigType.VLESS).apply {
            network = "xhttp"
            security = "tls"
        }
        val vlessWs = ProfileItem.create(EConfigType.VLESS).apply {
            network = "ws"
            security = "tls"
        }
        val vlessReality = ProfileItem.create(EConfigType.VLESS).apply {
            network = "tcp"
            security = "reality"
        }
        val trojanWs = ProfileItem.create(EConfigType.TROJAN).apply {
            network = "ws"
            security = "tls"
        }

        assertTrue(AutoConfigFilter.isCdnScanEligible(vlessXhttp))
        assertTrue(AutoConfigFilter.isCdnScanEligible(vlessWs))
        assertFalse(AutoConfigFilter.isCdnScanEligible(vlessReality))
        assertFalse(AutoConfigFilter.isCdnScanEligible(trojanWs))

        val picked = AutoConfigFilter.pickBestConnectGuid(
            selectedGuid = "g1",
            profilesInOrder = listOf("g1" to vlessReality, "g2" to vlessXhttp)
        )
        assertEquals("g2", picked)
    }

    @Test
    fun smartConnectPolicy_and_licenseBackendContract() {
        assertEquals(listOf(CdnType.CLOUDFRONT, CdnType.CLOUDFLARE), SmartConnectPolicy.postConnectDiscoveryOrder())

        assertFalse(
            SmartConnectPolicy.shouldSwitchEdgeIp(
                currentBestIp = "104.16.0.1",
                currentUploadKBps = 200L,
                candidate = CandidateResult("104.16.0.2", latencyMs = 40L, uploadKBps = 210L)
            )
        )
        assertTrue(
            SmartConnectPolicy.shouldSwitchEdgeIp(
                currentBestIp = "104.16.0.1",
                currentUploadKBps = 200L,
                candidate = CandidateResult("104.16.0.2", latencyMs = 40L, uploadKBps = 250L)
            )
        )

        assertTrue(LicenseBackendClient.classifyInput("https://sub.example.com/abc") is ActivationInput.DirectSubscriptionUrl)
        assertTrue(LicenseBackendClient.classifyInput("QSN-TRIAL-9988") is ActivationInput.LicenseKey)
        assertNull(LicenseBackendClient.parseRedeemJson("not json"))

        val redeemed = LicenseBackendClient.parseRedeemJson(
            """{"subscription_url":"https://panel.test/sub/1","plan_name":"Pro","total_bytes":53687091200}"""
        )
        assertEquals("https://panel.test/sub/1", redeemed?.resolvedSubscriptionUrl())
        assertEquals("Pro", redeemed?.planName)
        assertEquals(53687091200L, redeemed?.totalBytes)
    }
}
