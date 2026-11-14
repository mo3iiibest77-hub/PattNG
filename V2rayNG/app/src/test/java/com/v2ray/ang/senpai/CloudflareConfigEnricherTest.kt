package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareConfigEnricherTest {

    @Test
    fun amazonClassifier_handlesBoxCharsAndCloudFrontDomainsEvenWithCloudflareIp() {
        val amazonWithBoxChars = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "🇸🇪 ╰a─m─a─z─o─n╯"
            server = "172.67.187.170"
            sni = "sw.mo3ibest.my.id"
            host = "sw.mo3ibest.my.id"
            network = "xhttp"
            security = "tls"
            serverPort = "443"
        }
        val cfDomainConfig = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Khodam"
            server = "d12345abc.cloudfront.net"
            sni = "d12345abc.cloudfront.net"
            network = "ws"
            security = "tls"
            serverPort = "443"
        }
        val cloudflareConfig = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "CF-Main"
            server = "cdn.example.com"
            sni = "cdn.example.com"
            host = "xhttp-host.example.com"
            network = "xhttp"
            security = "tls"
            serverPort = "443"
        }

        assertTrue(CloudflareConfigEnricher.isAmazonOrCloudFront(amazonWithBoxChars))
        assertFalse(CloudflareConfigEnricher.isCloudflareConfig(amazonWithBoxChars))

        assertTrue(CloudflareConfigEnricher.isAmazonOrCloudFront(cfDomainConfig))
        assertFalse(CloudflareConfigEnricher.isCloudflareConfig(cfDomainConfig))

        assertFalse(CloudflareConfigEnricher.isAmazonOrCloudFront(cloudflareConfig))
        assertTrue(CloudflareConfigEnricher.isCloudflareConfig(cloudflareConfig))
    }

    @Test
    fun hardening_appliesIdempotentlyOnlyToCloudflare_neverToAmazon() {
        val cfProfile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "CF-Edge"
            server = "cf.example.org"
            sni = "cf.example.org"
            network = "xhttp"
            security = "tls"
            serverPort = "443"
            cipherSuites = "TLS_AES_128_GCM_SHA256"
            fingerPrint = "chrome"
            finalMask = null
        }
        val amazonProfile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "╰a─m─a─z─o─n╯"
            server = "sw.mo3ibest.my.id"
            sni = "sw.mo3ibest.my.id"
            network = "xhttp"
            security = "tls"
            serverPort = "443"
            cipherSuites = null
            fingerPrint = "chrome"
            finalMask = null
        }

        assertTrue(CloudflareConfigEnricher.applyCloudflareHardeningIfEligible(cfProfile))
        val firstSuites = cfProfile.cipherSuites
        val firstMask = cfProfile.finalMask
        assertNotNull(firstSuites)
        assertTrue(firstSuites!!.contains("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"))
        assertEquals(CloudflareScanner.PRODUCTION_FINALMASK, firstMask)
        assertEquals("unsafe", cfProfile.fingerPrint)

        // Second application is completely idempotent (no duplicate cipher suites)
        assertTrue(CloudflareConfigEnricher.applyCloudflareHardeningIfEligible(cfProfile))
        assertEquals(firstSuites, cfProfile.cipherSuites)
        assertEquals(firstMask, cfProfile.finalMask)
        assertEquals("unsafe", cfProfile.fingerPrint)

        // Amazon must stay untouched
        assertFalse(CloudflareConfigEnricher.applyCloudflareHardeningIfEligible(amazonProfile))
        assertNull(amazonProfile.cipherSuites)
        assertNull(amazonProfile.finalMask)
        assertEquals("chrome", amazonProfile.fingerPrint)
    }

    @Test
    fun tlsSniExtraction_neverConfusesHostWithSni() {
        val onlyHost = ProfileItem.create(EConfigType.VLESS).apply {
            network = "xhttp"
            security = "tls"
            serverPort = "443"
            server = "104.16.10.5"
            host = "host-only.example.com"
            sni = ""
        }
        val withSniAndHost = ProfileItem.create(EConfigType.VLESS).apply {
            network = "xhttp"
            security = "tls"
            serverPort = "443"
            server = "104.16.10.5"
            host = "xhttp-host.example.com"
            sni = "cert-sni.example.com"
        }

        assertNull(CloudflareConfigEnricher.extractTlsSniForInitialConnect(onlyHost))
        assertEquals("cert-sni.example.com", CloudflareConfigEnricher.extractTlsSniForInitialConnect(withSniAndHost))
    }

    @Test
    fun autoConfigFilter_prioritizesCloudflareOverAmazonUnlessExplicitlySelected() {
        val amazon = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "╰a─m─a─z─o─n╯"
            server = "sw.mo3ibest.my.id"
            sni = "sw.mo3ibest.my.id"
            network = "xhttp"
            security = "tls"
            serverPort = "443"
        }
        val cloudflare = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Cloudflare-1"
            server = "cdn.example.com"
            sni = "cdn.example.com"
            network = "xhttp"
            security = "tls"
            serverPort = "443"
        }

        val ordered = listOf("g-amz" to amazon, "g-cf" to cloudflare)

        // Default auto-selection overrides Amazon selection with Cloudflare
        assertEquals(
            "g-cf",
            AutoConfigFilter.pickBestConnectGuid("g-amz", ordered, honorExplicitSelection = false)
        )
        // Explicit user choice in Configs screen respects Amazon
        assertEquals(
            "g-amz",
            AutoConfigFilter.pickBestConnectGuid("g-amz", ordered, honorExplicitSelection = true)
        )
    }

    @Test
    fun seedRanges_areAllValidNormalizedCidrs() {
        assertTrue(CloudflareSeedRanges.SEED_CIDRS.isNotEmpty())
        assertEquals(CloudflareSeedRanges.SEED_CIDRS.size, CloudflareSeedRanges.SEED_CIDRS.distinct().size)
        for (cidr in CloudflareSeedRanges.SEED_CIDRS) {
            assertNotNull("Invalid seed CIDR: $cidr", CloudflareSeedRanges.normalizeIpv4Cidr(cidr))
        }
    }

    @Test
    fun cdnCandidateOrder_prioritizesWsHostAndTlsSniBeforeServerIp_andMatchesPingIpToCdn() {
        val customCloudFront = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Sweden Edge"
            server = "104.16.12.5"
            sni = "sni-edge.custom.org"
            host = "ws-origin.custom.org"
            network = "ws"
            security = "tls"
            serverPort = "443"
        }

        val ordered = CdnPingClassifier.extractOrderedHostCandidates(customCloudFront, "saved.custom.org")
        assertEquals(
            listOf("ws-origin.custom.org", "sni-edge.custom.org", "saved.custom.org", "104.16.12.5"),
            ordered
        )

        val frontPingStdout = "PING ws-origin.custom.org (13.32.99.45): 56 data bytes\n64 bytes from 13.32.99.45: icmp_seq=0 ttl=244 time=58.2 ms"
        val (pingIp, pingRtt) = CdnPingClassifier.parsePingOutput(frontPingStdout)
        assertEquals("13.32.99.45", pingIp)
        assertEquals(58L, pingRtt)

        val verdict = CdnPingClassifier.classifyResolvedIp(
            domain = "ws-origin.custom.org",
            resolvedIp = pingIp,
            pingRttMs = pingRtt,
            cloudFrontCidrs = listOf("13.32.0.0/15", "99.84.0.0/16")
        )
        assertEquals(CdnType.CLOUDFRONT, verdict.cdnType)

        // Cached CloudFront verdict prevents CF hardening even if remark lacks "amazon"
        assertTrue(CloudflareConfigEnricher.isAmazonOrCloudFront(customCloudFront, cachedCdnType = verdict.cdnType))
        assertFalse(CloudflareConfigEnricher.isCloudflareConfig(customCloudFront, cachedCdnType = verdict.cdnType))
        assertFalse(CdnPingClassifier.isPublicRoutableIpv4("10.10.34.35"))
    }
}
