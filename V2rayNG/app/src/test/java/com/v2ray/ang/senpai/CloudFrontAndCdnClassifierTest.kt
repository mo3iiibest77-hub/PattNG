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
            serverPort = "443"
        }
        val vlessWs = ProfileItem.create(EConfigType.VLESS).apply {
            network = "ws"
            security = "tls"
            serverPort = "2083"
        }
        val vlessNonCdnPort = ProfileItem.create(EConfigType.VLESS).apply {
            network = "ws"
            security = "tls"
            serverPort = "12345"
        }
        val vlessReality = ProfileItem.create(EConfigType.VLESS).apply {
            network = "tcp"
            security = "reality"
            serverPort = "443"
        }
        val trojanWs = ProfileItem.create(EConfigType.TROJAN).apply {
            network = "ws"
            security = "tls"
            serverPort = "443"
        }

        assertTrue(AutoConfigFilter.isCdnScanEligible(vlessXhttp))
        assertTrue(AutoConfigFilter.isCdnScanEligible(vlessWs))
        assertFalse(AutoConfigFilter.isCdnScanEligible(vlessNonCdnPort))
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
        assertEquals(
            listOf(CdnType.CLOUDFLARE),
            SmartConnectPolicy.determineRequiredDiscoveryProviders(
                detectedProvidersInSubscription = listOf(CdnType.CLOUDFLARE),
                activeProvider = CdnType.CLOUDFLARE
            )
        )
        assertEquals(
            listOf(CdnType.CLOUDFLARE, CdnType.CLOUDFRONT),
            SmartConnectPolicy.determineRequiredDiscoveryProviders(
                detectedProvidersInSubscription = listOf(CdnType.CLOUDFLARE, CdnType.CLOUDFRONT),
                activeProvider = CdnType.CLOUDFLARE
            )
        )

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

        assertTrue(LicenseBackendClient.classifyInput("https://sub.example.com/abc") is ActivationInput.RejectedUrl)
        assertTrue(LicenseBackendClient.classifyInput("http://sub.example.com/abc") is ActivationInput.RejectedUrl)
        assertTrue(LicenseBackendClient.classifyInput("vless://uuid@host:443") is ActivationInput.RejectedUrl)
        assertTrue(LicenseBackendClient.classifyInput("NP-TRIAL-9988") is ActivationInput.LicenseKey)
        assertNull(LicenseBackendClient.parseRedeemJson("not json"))

        val redeemed = LicenseBackendClient.parseRedeemJson(
            """{"subscription_url":"https://panel.test/sub/1","plan_name":"Pro","total_bytes":53687091200}"""
        )
        assertEquals("https://panel.test/sub/1", redeemed?.resolvedSubscriptionUrl())
        assertEquals("Pro", redeemed?.planName)
        assertEquals(53687091200L, redeemed?.totalBytes)
    }

    @Test
    fun cdnPingClassifier_rejectsSinkholeAndParsesDohJson() {
        assertFalse(CdnPingClassifier.isPublicRoutableIpv4("10.10.34.35"))
        assertFalse(CdnPingClassifier.isPublicRoutableIpv4("192.168.1.1"))
        assertFalse(CdnPingClassifier.isPublicRoutableIpv4("127.0.0.1"))
        assertTrue(CdnPingClassifier.isPublicRoutableIpv4("104.16.12.5"))

        val dohJson = """
            {"Status":0,"Answer":[
              {"name":"example.com","type":5,"data":"cdn.example.com"},
              {"name":"cdn.example.com","type":1,"data":"10.10.34.35"},
              {"name":"cdn.example.com","type":1,"data":"104.16.12.5"}
            ]}
        """.trimIndent()
        assertEquals(listOf("104.16.12.5"), CdnPingClassifier.parseDohJsonIpv4s(dohJson))
    }
}
